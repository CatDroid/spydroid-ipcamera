/*
 * Copyright (C) 2011-2014 GUIGUI Simon, fyhertz@gmail.com
 * 
 * This file is part of libstreaming (https://github.com/fyhertz/libstreaming)
 * 
 * Spydroid is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This source code is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this source code; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */

package net.majorkernelpanic.streaming.rtsp;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Locale;
import java.util.WeakHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.majorkernelpanic.streaming.Session;
import net.majorkernelpanic.streaming.SessionBuilder;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.os.Binder;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.util.Log;

/**
 * Implementation of a subset of the RTSP protocol (RFC 2326).
 * 
 * It allows remote control of an android device cameras & microphone.
 * For each connected client, a Session is instantiated.
 * The Session will start or stop streams according to what the client wants.
 * 
 */
public class RtspServer extends Service {

	public final static String TAG = "RtspServer";

	/** The server name that will appear in responses. */
	public static String SERVER_NAME = "MajorKernelPanic RTSP Server";

	/** Port used by default. */
	public static final int DEFAULT_RTSP_PORT = 8086; // 没有root权限 必须大于10000 e.g 10086 不能 xxxx

	/** Port already in use. */
	public final static int ERROR_BIND_FAILED = 0x00;

	/** A stream could not be started. */
	public final static int ERROR_START_FAILED = 0x01;

	/** Streaming started. */
	public final static int MESSAGE_STREAMING_STARTED = 0X00;
	
	/** Streaming stopped. */
	public final static int MESSAGE_STREAMING_STOPPED = 0X01;
	
	/** Key used in the SharedPreferences to store whether the RTSP server is enabled or not. */
	public final static String KEY_ENABLED = "rtsp_enabled";

	/** Key used in the SharedPreferences for the port used by the RTSP server. */
	public final static String KEY_PORT = "rtsp_port";

	protected SessionBuilder mSessionBuilder;
	protected SharedPreferences mSharedPreferences;
	protected boolean mEnabled = true;	
	protected int mPort = DEFAULT_RTSP_PORT;
	protected WeakHashMap<Session,Object> mSessions = new WeakHashMap<Session,Object>(2);
	
	private RequestListener mListenerThread;
	private final IBinder mBinder = new LocalBinder();
	private boolean mRestart = false;
	private final LinkedList<CallbackListener> mListeners = new LinkedList<CallbackListener>();
	

	public RtspServer() {
	}

	/** Be careful: those callbacks won't necessarily be called from the ui thread ! */
	public interface CallbackListener {

		/** Called when an error occurs. */
		void onError(RtspServer server, Exception e, int error);

		/** Called when streaming starts/stops. */
		void onMessage(RtspServer server, int message);
		
	}

	/**
	 * See {@link RtspServer.CallbackListener} to check out what events will be fired once you set up a listener.
	 * @param listener The listener
	 */
	public void addCallbackListener(CallbackListener listener) {
		synchronized (mListeners) {
			if (mListeners.size() > 0) {
				for (CallbackListener cl : mListeners) {
					if (cl == listener) return;
				}
			}
			mListeners.add(listener);			
		}
	}

	/**
	 * Removes the listener.
	 * @param listener The listener
	 */
	public void removeCallbackListener(CallbackListener listener) {
		synchronized (mListeners) {
			mListeners.remove(listener);				
		}
	}

	/** Returns the port used by the RTSP server. */	
	public int getPort() {
		return mPort;
	}

	/**
	 * Sets the port for the RTSP server to use.
	 * @param port The port
	 */
	public void setPort(int port) {
		Editor editor = mSharedPreferences.edit();
		editor.putString(KEY_PORT, String.valueOf(port));
		editor.commit();
	}	

	/** 
	 * Starts (or restart if needed, if for example the configuration 
	 * of the server has been modified) the RTSP server. 
	 */
	public void start() {
		Log.d(TAG,"start   mEnabled = " + mEnabled + " mRestart = " + mRestart);
		if (!mEnabled || mRestart) stop(); // 如果设置中没有打开rtsp服务的话 这里不会启动rtsp服务线程
		if (mEnabled && mListenerThread == null) {
			try {
				mListenerThread = new RequestListener();
			} catch (Exception e) {
				mListenerThread = null;
				e.printStackTrace();
				Log.e(TAG,"Excpetion ! e = " + e.getMessage());
			}
		}
		mRestart = false;
	}

	/** 
	 * Stops the RTSP server but not the Android Service. 
	 * To stop the Android Service you need to call {@link android.content.Context#stopService(Intent)}; 
	 */
	public void stop() {
		if (mListenerThread != null) {
			try {
				mListenerThread.kill();
				for ( Session session : mSessions.keySet() ) {
				    if ( session != null ) {
				    	if (session.isStreaming()) session.stop();
				    } 
				}
			} catch (Exception e) {
			} finally {
				mListenerThread = null;
			}
		}
	}

	/** Returns whether or not the RTSP server is streaming to some client(s). */
	public boolean isStreaming() {
		for ( Session session : mSessions.keySet() ) {
		    if ( session != null ) {
		    	if (session.isStreaming()) return true;
		    } 
		}
		return false;
	}
	
	public boolean isEnabled() {
		return mEnabled;
	}

	/** Returns the bandwidth consumed by the RTSP server in bits per second. */
	public long getBitrate() {
		long bitrate = 0;
		for ( Session session : mSessions.keySet() ) {
		    if ( session != null ) {
		    	if (session.isStreaming()) bitrate += session.getBitrate();
		    } 
		}
		return bitrate;
	}
	
	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		return START_STICKY;
	}

	@Override
	public void onCreate() {

		// Let's restore the state of the service 
		mSharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
		mPort = Integer.parseInt(mSharedPreferences.getString(KEY_PORT, String.valueOf(mPort)));
		mEnabled = mSharedPreferences.getBoolean(KEY_ENABLED, mEnabled);
		// 在设置中 设置 是否打开 rtsp服务和端口号
		// sharedPreferences中如果属性改变的话 调用 mOnSharedPreferenceChangeListener 

		// If the configuration is modified, the server will adjust rtsp的设置改变了(启用/关闭 端口号)
		mSharedPreferences.registerOnSharedPreferenceChangeListener(mOnSharedPreferenceChangeListener);

		start();
	}

	@Override
	public void onDestroy() {
		stop();
		mSharedPreferences.unregisterOnSharedPreferenceChangeListener(mOnSharedPreferenceChangeListener);
	}

	private OnSharedPreferenceChangeListener mOnSharedPreferenceChangeListener = new OnSharedPreferenceChangeListener() {
		@Override
		public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {

			if (key.equals(KEY_PORT)) {
				int port = Integer.parseInt(sharedPreferences.getString(KEY_PORT, String.valueOf(mPort)));
				if (port != mPort) {
					mPort = port;
					mRestart = true;
					start();
				}
			}		
			else if (key.equals(KEY_ENABLED)) {
				mEnabled = sharedPreferences.getBoolean(KEY_ENABLED, mEnabled);
				start();
			}
		}
	};

	/** The Binder you obtain when a connection with the Service is established. */
	public class LocalBinder extends Binder {
		public RtspServer getService() {
			return RtspServer.this;
		}
	}

	@Override
	public IBinder onBind(Intent intent) {
		return mBinder;
	}

	protected void postMessage(int id) { // 发给RtspServer::addCallbackListener
		synchronized (mListeners) {
			if (mListeners.size() > 0) {
				for (CallbackListener cl : mListeners) {
					cl.onMessage(this, id);
				}
			}			
		}
	}	
	
	protected void postError(Exception exception, int id) {
		synchronized (mListeners) {
			if (mListeners.size() > 0) {
				for (CallbackListener cl : mListeners) {
					cl.onError(this, exception, id);
				}
			}			
		}
	}

	/** 
	 * By default the RTSP uses {@link UriParser} to parse the URI requested by the client
	 * but you can change that behavior by override this method.
	 * @param uri The uri that the client has requested
	 * @param client The socket associated to the client
	 * @return A proper session
	 */
	protected Session handleRequest(String uri, Socket client) throws IllegalStateException, IOException {
		Session session = UriParser.parse(uri); // 通过SessionBuilder创建Session
		session.setOrigin(client.getLocalAddress().getHostAddress());
		if (session.getDestination()==null) {
			session.setDestination(client.getInetAddress().getHostAddress());
		} // DESCRIBE rtsp://192.168.1.60:8086/ RTSP/1.0
		return session;
	}
	
	class RequestListener extends Thread implements Runnable {

		private final ServerSocket mServer;

		public RequestListener() throws IOException {
			this.setName("RequSer" + mPort );
			
			try {
				Log.d(TAG,"mPort = " + mPort);
				mServer = new ServerSocket();
				Log.d(TAG,"try Start");
				start(); // 自己启动线程run
				Log.d(TAG,"rtsp start = " + mServer);
			} catch (BindException e) {
				Log.e(TAG,"Port already in use !");
				postError(e, ERROR_BIND_FAILED);
				throw e;
			} 
			
			
//			catch (SocketException e){
//				e.printStackTrace();
//				Log.e(TAG,"SocketException ! e = " + e.getMessage());
//				throw e;
//			} 
			// 问题1:
			// 小米5 禁止应用访问 数据网络  而当前如果用 数据网络的话 就会出现 SocketException Access Permisson Denied
			
			// 问题2:
			// android.os.StrictMode$AndroidBlockGuardPolicy.onNetwork
			// Android3.0不允许在主线程访问网络
			// 把绑定的动作放在 run 线程中
		}

		public void run() {
			
			try {
				//mServer.setReuseAddress(true);
				mServer.bind(new InetSocketAddress(mPort) );
				Log.d(TAG,"rtsp run = " + mServer);
			} catch (IOException e1) {
				Log.e(TAG,"ListenerThread Bind Error 1");
				try {
					mServer.close();
					mListenerThread = null;
				} catch (IOException e) {
					e.printStackTrace();
				}
				Log.e(TAG,"ListenerThread Bind Error 2");
				e1.printStackTrace();
				return ;
				
			}
			
			Log.d(TAG,"RTSP server listening on port "+mServer.getLocalPort());
			while (!Thread.interrupted()) {
				try {
					new WorkerThread(mServer.accept()).start();// 每个client连接对应一个WorkerThread
				} catch (SocketException e) {
					break;
				} catch (IOException e) {
					Log.e(TAG,e.getMessage());
					continue;
				}
				Log.d(TAG,"RTSP server continue listening  " );
			}
			Log.d(TAG,"RTSP server stopped !");
		}

		public void kill() {
			try {
				mServer.close();
			} catch (IOException e) {}
			try {
				this.join();
			} catch (InterruptedException ignore) {}
		}

	}

	// One thread per client
	class WorkerThread extends Thread implements Runnable {

		private final Socket mClient;
		private final OutputStream mOutput;
		private final BufferedReader mInput;

		// Each client has an associated session
		private Session mSession;

		public WorkerThread(final Socket client) throws IOException {
			mInput = new BufferedReader(new InputStreamReader(client.getInputStream()));
			mOutput = client.getOutputStream();
			mClient = client;
			mSession = new Session(); 	
			// 每个客户端对应一个WorkerThread Session mSession Socket mClient
			// 在processRequest处理 DESCRIBE的时候   handleRequest 根据客户端URL重新生成

		}

		public void run() {
			Request request;
			Response response;

			Log.i(TAG, "Connection from "+mClient.getInetAddress().getHostAddress());

			while (!Thread.interrupted()) {

				request = null;
				response = null;

				// Parse the request
				try {
					request = Request.parseRequest(mInput);
				} catch (SocketException e) {
					// Client has left
					break;
				} catch (Exception e) {
					// We don't understand the request :/
					response = new Response();
					response.status = Response.STATUS_BAD_REQUEST;
				}

				// Do something accordingly like starting the streams, sending a session description
				if (request != null) {
					try {
						response = processRequest(request);
					}
					catch (Exception e) {
						// This alerts the main thread that something has gone wrong in this thread
						postError(e, ERROR_START_FAILED);
						Log.e(TAG,e.getMessage()!=null?e.getMessage():"An error occurred");
						e.printStackTrace();
						response = new Response(request);
						/*
						 * 	可能有如下错误 到值 客户端VLC 出现
						 * 
						 *  live555 error: SETUP of'video/H264' failed 500 Internal Server Error
						 *  
						 *  
						 * 	D/RtspServer( 2744): a=control:trackID=1
							E/RtspServer( 2744): SETUP 192.168.43.1:8086/trackID=1
							E/RtspServer( 2744): An error occurred
							D/RtspServer( 2744): RTSP/1.0 500 Internal Server Error
							D/RtspServer( 2744): Server: MajorKernelPanic RTSP Server
							D/RtspServer( 2744): Cseq: 4
							D/RtspServer( 2744): Content-Length: 0
						 * */
					}
				}

				// We always send a response
				// The client will receive an "INTERNAL SERVER ERROR" if an exception has been thrown at some point
				try {
					response.send(mOutput);
				} catch (IOException e) {
					Log.e(TAG,"Response was not sent properly");
					break;
				}

			}

			// Streaming stops when client disconnects
			boolean streaming = isStreaming();
			mSession.syncStop();
			if (streaming && !isStreaming()) {
				postMessage(MESSAGE_STREAMING_STOPPED);
			}
			mSession.release();

			try {
				mClient.close();
			} catch (IOException ignore) {}

			Log.i(TAG, "Client disconnected");

		}

		public Response processRequest(Request request) throws IllegalStateException, IOException {
			Response response = new Response(request);
			
 

			/* ********************************************************************************** */
			/* ********************************* Method DESCRIBE ******************************** */
			/* ********************************************************************************** */
			if (request.method.equalsIgnoreCase("DESCRIBE")) {
				/*
				 *  C-S：DESCRIBE rtsp://192.168.1.60:8086/ RTSP/1.0
				 
				 	UriParser.parse 的时候 解析URL的参数
				 	
				 	根据 url参数 multicast h264/h263 acc/armnb camera      来 确定
				 				组播地址   是否传输音频或视频流    视频流的摄像头 
				 */
				// Parse the requested URI and configure the session
				mSession = handleRequest(request.uri, mClient);
				mSessions.put(mSession, null);
				mSession.syncConfigure();
				
				String requestContent = mSession.getSessionDescription(); 
				// 在handleRequest-->UrlParser.parse 创建sesson和stream 这里获得各个stream的描述
				// mSession::mAudioStream::getSessionDescription
				// mSession::mVideoStream::getSessionDescription
				//
				String requestAttributes = 
						"Content-Base: "+mClient.getLocalAddress().getHostAddress()+":"+mClient.getLocalPort()+"/\r\n" +
								"Content-Type: application/sdp\r\n";

			 
				response.attributes = requestAttributes;
				response.content = requestContent; // application/sdp 协议描述媒体信息

				// If no exception has been thrown, we reply with OK
				response.status = Response.STATUS_OK;

			}

			/* ********************************************************************************** */
			/* ********************************* Method OPTIONS ********************************* */
			/* ********************************************************************************** */
			else if (request.method.equalsIgnoreCase("OPTIONS")) {
				response.status = Response.STATUS_OK;
				response.attributes = "Public: DESCRIBE,SETUP,TEARDOWN,PLAY,PAUSE\r\n";
				response.status = Response.STATUS_OK;
				
				/*	OPTIONS请求时，发送可用的方法
				 * 
				 *  C-S：OPTIONS rtsp://192.168.1.60:8086/ RTSP/1.0		//可用选项
				 *
				 *	S-C: Public: DESCRIBE,SETUP,TEARDOWN,PLAY,PAUSE		//描述信息、建立连接、关闭、播放、暂停
				 */
			}

			/* ********************************************************************************** */
			/* ********************************** Method SETUP ********************************** */
			/* ********************************************************************************** */
			else if (request.method.equalsIgnoreCase("SETUP")) {
				Pattern p; Matcher m;
				int p2, p1, ssrc, trackId, src[];
				String destination;

				// SETUP 192.168.1.60:8086/trackID=0 RTSP/1.0
				
				p = Pattern.compile("trackID=(\\w+)",Pattern.CASE_INSENSITIVE);
				m = p.matcher(request.uri);

				if (!m.find()) {
					response.status = Response.STATUS_BAD_REQUEST;
					return response;
				} 

				trackId = Integer.parseInt(m.group(1));

				if (!mSession.trackExists(trackId)) { // DESCRIBE 会创建对应stream AudioStream/VideoStream
					response.status = Response.STATUS_NOT_FOUND;
					return response;
				}

	
				p = Pattern.compile("client_port=(\\d+)-(\\d+)",Pattern.CASE_INSENSITIVE);
				m = p.matcher(request.headers.get("transport"));
				/*
				 *  SETUP     rtsp://192.168.1.109/1.mpg/track1    RTSP/1.0    //  URL多了track1 
				    CSeq: 3  
				    Transport: RTP/AVP;    unicast;    client_port=1112-1113   //  URL headers
				    User-Agent: VLC media player(LIVE555 Streaming Media v2007.02.20)  
				
					// client_port=5006-5007;server_port=49749-49750;
					// 5006 作为客户端rtp的端口  5007 作为客户端rtcp的端口
				 * */

				if (!m.find()) {
					int[] ports = mSession.getTrack(trackId).getDestinationPorts();
					p1 = ports[0];
					p2 = ports[1];
				}
				else {
					p1 = Integer.parseInt(m.group(1)); 
					p2 = Integer.parseInt(m.group(2));
				}

				ssrc = mSession.getTrack(trackId).getSSRC();
				/*
				 *	制定基于udp协议的rtp传输，目标地址，客户端端口、服务器端口，以及ssrc的数值
				 *
				 *	这里ssrc的数值很重要，它是同步源标识,synchronization source (SSRC) identifier
				 *
				 *	rtp header中包含这个 (随机生成)
				 * */
				src = mSession.getTrack(trackId).getLocalPorts();// 服务端给出对应这个客户端的rtp/rtcp端口
				destination = mSession.getDestination();

				// 获得对应的AudioStream 或者  VideoStream 设置其 "客户端"的 rtp端口 和  rtcp端口
				mSession.getTrack(trackId).setDestinationPorts(p1, p2);
				
				boolean streaming = isStreaming();
				mSession.syncStart(trackId); 
				if (!streaming && isStreaming()) { 
					postMessage(MESSAGE_STREAMING_STARTED); // 只是通知 回调 RtspServer::addCallbackListener
				}
					
				//	Transport: RTP/AVP/UDP;unicast;destination=192.168.1.26;client_port=5006-5007;server_port=49749-49750;ssrc=431567f7;mode=play
				// 	Session: 1185d20035702ca   
				//	Cache-Control: no-cache
				//	SETUP是针对某个track/stream 确定好 rtp rtcp端口号 
				response.attributes = "Transport: RTP/AVP/UDP;"+(InetAddress.getByName(destination).isMulticastAddress()?"multicast":"unicast")+
						";destination="+mSession.getDestination()+ // DECRIBE时商定 rtp rtcp 的 组播地址 (UriParser.java )
						";client_port="+p1+"-"+p2+			//  C->S 客户端的端口(rtp rtcp)  由于用MutliCast/UDP 服务端和客户端每次发送都要指定对端的ip:port
						";server_port="+src[0]+"-"+src[1]+ 	//	S->C 服务器的端口(rtp rtcp) 
						";ssrc="+Integer.toHexString(ssrc)+ //	S->C 该track/stream的ssrc
						";mode=play\r\n" +
						"Session: "+ "1185d20035702ca" + "\r\n" +
						"Cache-Control: no-cache\r\n";
				response.status = Response.STATUS_OK;

				// If no exception has been thrown, we reply with OK
				response.status = Response.STATUS_OK;

			}

			/* ********************************************************************************** */
			/* ********************************** Method PLAY *********************************** */
			/* ********************************************************************************** */
			else if (request.method.equalsIgnoreCase("PLAY")) {
				
				/*  SETUP的时候已经启动   PLAY方法只是返回一些启动音频和视频的信息
				 *  Session: 3  
    				RTP-Info:  url=rtsp://192.168.1.109/1.mpg/track1; seq=9200; rtptime=214793785,
 								url=rtsp://192.168.1.109/1.mpg/track2;  seq=12770;  rtptime=31721  
				 * */
				String requestAttributes = "RTP-Info: ";
				if (mSession.trackExists(0)) requestAttributes += "url=rtsp://"+mClient.getLocalAddress().getHostAddress()+":"+mClient.getLocalPort()+"/trackID="+0+";seq=0,";
				if (mSession.trackExists(1)) requestAttributes += "url=rtsp://"+mClient.getLocalAddress().getHostAddress()+":"+mClient.getLocalPort()+"/trackID="+1+";seq=0,";
				requestAttributes = requestAttributes.substring(0, requestAttributes.length()-1) + "\r\nSession: 1185d20035702ca\r\n";

				response.attributes = requestAttributes;

				// If no exception has been thrown, we reply with OK
				response.status = Response.STATUS_OK;

			}

			/* ********************************************************************************** */
			/* ********************************** Method PAUSE ********************************** */
			/* ********************************************************************************** */
			else if (request.method.equalsIgnoreCase("PAUSE")) {
				response.status = Response.STATUS_OK;
			}

			/* ********************************************************************************** */
			/* ********************************* Method TEARDOWN ******************************** */
			/* ********************************************************************************** */
			else if (request.method.equalsIgnoreCase("TEARDOWN")) {
				response.status = Response.STATUS_OK;
			}

			/* ********************************************************************************** */
			/* ********************************* Unknown method ? ******************************* */
			/* ********************************************************************************** */
			else {
				Log.e(TAG,"Command unknown: "+request);
				response.status = Response.STATUS_BAD_REQUEST;
			}

			return response;

		}

	}

	static class Request {

		// Parse method & uri
		public static final Pattern regexMethod = Pattern.compile("(\\w+) (\\S+) RTSP",Pattern.CASE_INSENSITIVE);
		// Parse a request header
		public static final Pattern rexegHeader = Pattern.compile("(\\S+):(.+)",Pattern.CASE_INSENSITIVE);

		public String method;
		public String uri;
		public HashMap<String,String> headers = new HashMap<String,String>();

		/** Parse the method, uri & headers of a RTSP request */
		public static Request parseRequest(BufferedReader input) throws IOException, IllegalStateException, SocketException {
			Request request = new Request();
			String line;
			Matcher matcher;

			// Parsing request method & uri
			if ((line = input.readLine())==null) throw new SocketException("Client disconnected");
			matcher = regexMethod.matcher(line);
			matcher.find();
			request.method = matcher.group(1);
			request.uri = matcher.group(2);

			// Parsing headers of the request
			while ( (line = input.readLine()) != null && line.length()>3 ) {
				matcher = rexegHeader.matcher(line);
				matcher.find();
				request.headers.put(matcher.group(1).toLowerCase(Locale.US),matcher.group(2));
			}
			if (line==null) throw new SocketException("Client disconnected");

			// It's not an error, it's just easier to follow what's happening in logcat with the request in red
			Log.e(TAG,request.method+" "+request.uri);

			return request;
		}
	}

	static class Response {

		// Status code definitions
		public static final String STATUS_OK = "200 OK";
		public static final String STATUS_BAD_REQUEST = "400 Bad Request";
		public static final String STATUS_NOT_FOUND = "404 Not Found";
		public static final String STATUS_INTERNAL_SERVER_ERROR = "500 Internal Server Error";

		public String status = STATUS_INTERNAL_SERVER_ERROR;
		public String content = "";
		public String attributes = "";

		private final Request mRequest;

		public Response(Request request) {
			this.mRequest = request;
		}

		public Response() {
			// Be carefull if you modify the send() method because request might be null !
			mRequest = null;
		}

		public void send(OutputStream output) throws IOException {
			int seqid = -1;

			try {
				seqid = Integer.parseInt(mRequest.headers.get("cseq").replace(" ",""));
			} catch (Exception e) {
				Log.e(TAG,"Error parsing CSeq: "+(e.getMessage()!=null?e.getMessage():""));
			}

			String response = 	"RTSP/1.0 "+status+"\r\n" +
					"Server: "+SERVER_NAME+"\r\n" +
					(seqid>=0?("Cseq: " + seqid + "\r\n"):"") +
					"Content-Length: " + content.length() + "\r\n" +
					attributes +
					"\r\n" + 
					content;

			Log.d(TAG,response.replace("\r", ""));

			output.write(response.getBytes());
		}
	}

}
