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

package net.majorkernelpanic.streaming.rtp;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import net.majorkernelpanic.streaming.rtcp.SenderReport;
import android.os.SystemClock;
import android.util.Log;

/**
 * A basic implementation of an RTP socket.
 * It implements a buffering mechanism, relying on a FIFO of buffers and a Thread.
 * That way, if a packetizer tries to send many packets too quickly, the FIFO will
 * grow and packets will be sent one by one smoothly.
 */
public class RtpSocket implements Runnable {

	public static final String TAG = "RtpSocket";

	public static final int RTP_HEADER_LENGTH = 12;
	public static final int MTU = 1300;

	private MulticastSocket mSocket;
	private DatagramPacket[] mPackets;
	private byte[][] mBuffers;
	private long[] mTimestamps;

	private SenderReport mReport;
	
	private Semaphore mBufferRequested, mBufferCommitted;
	private Thread mThread;

	private long mCacheSize;
	private long mClock = 0;
	private long mOldTimestamp = 0;
	private int mSsrc, mSeq = 0, mPort = -1;
	private int mBufferCount, mBufferIn, mBufferOut;
	private int mCount = 0;
	
	private AverageBitrate mAverageBitrate;

	/**
	 * This RTP socket implements a buffering mechanism relying on a FIFO of buffers and a Thread.
	 * @throws IOException
	 */
	public RtpSocket() {
		
		//	组播地址的范围在224.0.0.0--- 239.255.255.255之间（都为D类地址 1110开头）
		//
		mCacheSize = 00;
		mBufferCount = 300; // TODO: reajust that when the FIFO is full 
		mBuffers = new byte[mBufferCount][];
		mPackets = new DatagramPacket[mBufferCount]; // package是用来 MulticastSocket::send的
		// rtcp 
		mReport = new SenderReport();	// 服务端rtcp的socket 也是一个MulticastSocket
		mAverageBitrate = new AverageBitrate();
		
		resetFifo(); 
		// 把两个信号量初始化为
		// mBufferRequested = mBufferCount , mBufferCommitted = 0

		for (int i=0; i<mBufferCount; i++) {
			
			//  组播和普通UDP socket之间的区别在于必须考虑TTL值 
			//	这时IP首部中取值 0- 255的一个字节。 它的含义为包被丢弃前通过的路由数目
			//	每通过一个路由器，其TTL减少1，有些路由器减少2或更多。当TTL值为0时包就被丢弃
			// 
			mBuffers[i] = new byte[MTU]; // 300个buffer x 每个buffer1300  个字节
			mPackets[i] = new DatagramPacket(mBuffers[i], 1);

			/*							     Version(2)  Padding(0)					 					*/
			/*									 ^		  ^			Extension(0)						*/
			/*									 |		  |				^								*/
			/*									 | --------				|								*/
			/*									 | |---------------------								*/
			/*									 | ||  -----------------------> Source Identifier(0)	*/
			/*									 | ||  |												*/
			mBuffers[i][0] = (byte) Integer.parseInt("10000000",2);

			/* Payload Type */
			mBuffers[i][1] = (byte) 96; // 每个buffer的开头两个字节 是  10000000 和 payload type=96

			/* Byte 2,3        ->  Sequence Number                   */
			/* Byte 4,5,6,7    ->  Timestamp                         */
			/* Byte 8,9,10,11  ->  Sync Source Identifier            */
			/* 上面就是固定的12个字节 rtp头部 */
		}

		try {
			mSocket = new MulticastSocket();
		} catch (Exception e) {
			throw new RuntimeException(e.getMessage());
		}
		
	}
	
	/* 	把两个信号量初始化为 acquire -1 release =1 
	 	mBufferRequested 初始计数 mBufferCount , mBufferCommitted 初始计数 0
					 
		mBufferRequested 空闲的buffer(requestBuffer)  
		mBufferCommitted 代表有数据的buffer(commitBuffer),可用来Multisocket::send发送 
		
		mBufferOut	记录即将/当前往socket所送的rtp包buffer索引 run() 
		mBufferIn	记录即将/当前处理的rtp包 buffer索引 commitBuffer() 
		*/
	private void resetFifo() {
		mCount = 0;
		mBufferIn = 0;
		mBufferOut = 0;
		mTimestamps = new long[mBufferCount];
		mBufferRequested = new Semaphore(mBufferCount);
		mBufferCommitted = new Semaphore(0);
		/*
		 *  
		 * */
		mReport.reset();
		mAverageBitrate.reset();
	}
	
	/** Closes the underlying socket. */
	public void close() {
		mSocket.close();
	}

	/** Sets the SSRC of the stream. */
	public void setSSRC(int ssrc) {
		this.mSsrc = ssrc;
		for (int i=0;i<mBufferCount;i++) {
			setLong(mBuffers[i], ssrc,8,12);
		}
		mReport.setSSRC(mSsrc);
	}

	/** Returns the SSRC of the stream. */
	public int getSSRC() {
		return mSsrc;
	}

	/** Sets the clock frquency of the stream in Hz. */
	public void setClockFrequency(long clock) {
		mClock = clock;
	}

	/** Sets the size of the FIFO in ms. */
	public void setCacheSize(long cacheSize) {
		mCacheSize = cacheSize;
	}
	
	/** Sets the Time To Live of the UDP packets. */
	public void setTimeToLive(int ttl) throws IOException {
		mSocket.setTimeToLive(ttl);
	}

	/** Sets the destination address and to which the packets will be sent. */
	public void setDestination(InetAddress dest, int dport, int rtcpPort) {
		mPort = dport;
		for (int i=0;i<mBufferCount;i++) {
			mPackets[i].setPort(dport);
			mPackets[i].setAddress(dest); // 设置每个包的目标地址 和 目标端口
		}									// UDP每次发包都要带地址和端口
		mReport.setDestination(dest, rtcpPort);
	}
	/*
	 * setTimeToLive		设置socket的ttl
	 * setDestination		设置每个数据报的目标地址和目标端口
	 * 
	 * getPort				客户端用于rtp的端口
	 * getLocalPort			服务端用于rtp的端口(还会分视频和音频的 )
	 * 
	 * getRtcpSocket		获得RtpSocket实例对应的SenderReport实例(用于rtcp)
	 * */
	public int getPort() {
		return mPort;
	}

	public int getLocalPort() {
		return mSocket.getLocalPort();
	}

	public SenderReport getRtcpSocket() {
		return mReport;
	}
	
	/** 
	 * Returns an available buffer from the FIFO, it can then be modified. 
	 * Call {@link #commitBuffer(int)} to send it over the network. 
	 * @throws InterruptedException 
	 **/
	public byte[] requestBuffer() throws InterruptedException {
		mBufferRequested.acquire(); // -1 
		mBuffers[mBufferIn][1] &= 0x7F;
		return mBuffers[mBufferIn];
	}

	/** Puts the buffer back into the FIFO without sending the packet. */
	public void commitBuffer() throws IOException {

		if (mThread == null) {
			mThread = new Thread(this);// 启动线程发送
			mThread.start();
		}
		
		if (++mBufferIn>=mBufferCount) mBufferIn = 0; 
		mBufferCommitted.release();// +1 

	}	
	
	/** Sends the RTP packet over the network. */
	public void commitBuffer(int length) throws IOException {
		updateSequence(); // 序号加1
		mPackets[mBufferIn].setLength(length); 	//	每个DatagramPacket的Buffer总长都是  new byte[MTU] MTU = 1300  个字节
												//	这里设置DatagramPacket实际发送的长度
		mAverageBitrate.push(length);

		if (++mBufferIn>=mBufferCount) mBufferIn = 0;
		mBufferCommitted.release();

		if (mThread == null) {
			mThread = new Thread(this);
			mThread.start();
		}		
	 
	}

	/** Returns an approximation of the bitrate of the RTP stream in bit per seconde. */
	public long getBitrate() {
		return mAverageBitrate.average();
	}

	/** Increments the sequence number. */
	private void updateSequence() {
		setLong(mBuffers[mBufferIn], ++mSeq, 2, 4);
	}

	/** 
	 * Overwrites the timestamp in the packet.
	 * @param timestamp The new timestamp in ns. 单位是ns 
	 * 
	 *        毫秒(ms)		微秒 (μs)		纳秒(ns)		皮秒(ps) 
		1 s = 10^3ms  		10^6us      10^9 ns   	10^12 ps 

	 **/
	public void updateTimestamp(long timestamp) {
		mTimestamps[mBufferIn] = timestamp;
		setLong(mBuffers[mBufferIn], (timestamp/100L)*(mClock/1000L)/10000L, 4, 8);
	}
	
	/* 方法					设置rtph
	 * updateTimestamp		rtph[4~8) = timstamp 这个rtp包 打包的时间
	 * markNextPacket		rtph[1] |= 0x80  如果当前 NALU为一个接入单元最后的那个NALU，那么将M位置 1;
	 * 										 或者当前RTP 数据包为一个NALU 的最后的那个分片时 ，M位置 1;
	 * 										 目前SpyAndroid实现 run()中 NALU分片时候 最后一个RTP数据包 M=1 中间的RTP包M=0
	 * 											不分片的情况 每个RTP包 都 M = 1 
	 * updateSequence		rtph[2~4) = ++mSeq   自增序号
	 * setSSRC				rtph[8~12) = ssrc	 mSsrc	所有rtp包都一样( AbstractPacketizer()随机值 )
	 * RtpSocket创建时候		rtph[0] 	10000000 所有rtp包都一样
	 * RtpSocket			rtph[1]		96'D 60'H payload type 所有rtp包都一样
	 * 
	 * 
	 * rtph[0] = 10 00 00 00 B  rtph[1] = 96  rtph[2~3] = 序号  rtph[4~7] = 时间戳
	 * rtph[8~11] SSRC 同步 源 标识符
	 * */

	/** Sets the marker in the RTP packet. */
	public void markNextPacket() {
		mBuffers[mBufferIn][1] |= 0x80;
	}

	/** The Thread sends the packets in the FIFO one by one at a constant rate. */
	@Override
	public void run() { // 来自 commitBuffer 
		Statistics stats = new Statistics(50,3000);
		try {
			// Caches mCacheSize milliseconds of the stream in the FIFO.
			Thread.sleep(mCacheSize);
			long delta = 0;
			// 等待 超时4s (long timeout, TimeUnit unit)
			while (mBufferCommitted.tryAcquire(4,TimeUnit.SECONDS)) {
				if (mOldTimestamp != 0) {
					// We use our knowledge of the clock rate of the stream and the difference between two timestamps to
					// compute the time lapse that the packet represents.
					if ((mTimestamps[mBufferOut]-mOldTimestamp)>0) {
						// 即将要发的rtp包 一定要比 上一个 要晚
						
						stats.push(mTimestamps[mBufferOut]-mOldTimestamp);
						long d = stats.average()/1000000;
						//Log.d(TAG,"delay: "+d+" d: "+(mTimestamps[mBufferOut]-mOldTimestamp)/1000000);
						// We ensure that packets are sent at a constant and suitable rate no matter how the RtpSocket is used.
						// 我们按照一个固定和稳定的速率发送 不管怎么使用rtpsocket
						if (mCacheSize>0){ 
							/// ???  实际没有看到 这个 ???
							Log.d("LILI", " mCacheSize = " + mCacheSize + " sleep = " + d );
							Thread.sleep(d);
						}
					} else if ((mTimestamps[mBufferOut]-mOldTimestamp)<0) {
						
						// 错误 即将要发的rtp包  要比 上一个 要早 ～～～
						Log.e(TAG, "TS: "+mTimestamps[mBufferOut]+" OLD: "+mOldTimestamp);
					}
					delta += mTimestamps[mBufferOut]-mOldTimestamp;
					if (delta>500000000 || delta<0) { // delta  只是为了统计 无用
						//Log.d(TAG,"permits: "+mBufferCommitted.availablePermits());
						delta = 0;
					}
				}
				
				
				
				/*
				 	D/TOM     ( 4689): [9] In = 3161193910
					D/TOM     ( 4689): [6] Out = 3161193910 now = 3161198130 diff = 4220 size = 542  <= 可以一个rtp包发送完  00 00 00 01 + NALU header(1 byte) + NALU body = 542 
					D/TOM     ( 4689): now = 3161198539034 mTimestamps[mBufferOut] = 3161193910000iff = 4629034 Seq = 7e9
	
				   	D/TOM     ( 4689): [2] In = 3161360951
					D/TOM     ( 4689): [3] Out = 3161360951 now = 3161365020 diff = 4069 size = 3068 <= 导致了DALU分片发送  用多个rtp包发送
					D/TOM     ( 4689): now = 3161365279111 mTimestamps[mBufferOut] = 3161360951000 diff = 4328111 Seq = 7f2
					D/TOM     ( 4689): now = 3161365841418 mTimestamps[mBufferOut] = 3161360951000 diff = 4890418 Seq = 7f3
					D/TOM     ( 4689): now = 3161366063034 mTimestamps[mBufferOut] = 3161360951000 diff = 5112034 Seq = 7f4
				 * 
				 * */
				
				// rtcp socket 流控 1.包的大小 2.发送时候的时间戳  3.对应rtp包的时间戳 
				long thisisnow = System.nanoTime() ;
				byte[] test = mPackets[mBufferOut].getData();
				Log.d("TOM", "now = " +   thisisnow   + " mTimestamps[mBufferOut] = " + mTimestamps[mBufferOut] 
								+ " diff = " +  (  thisisnow   - mTimestamps[mBufferOut])  
								+ " Seq = " + Integer.toHexString( 0xFF&test[2])  + Integer.toHexString( 0xFF&test[3])
								
								);
				mReport.update(mPackets[mBufferOut].getLength(), thisisnow ,(mTimestamps[mBufferOut]/100L)*(mClock/1000L)/10000L);
				
				// 上一个rtp包的时间
				// 类似MediaCodec::dequeueOutputBuffer编码后H264帧/NALU时间戳 
				// 但如果NALU过大的话 那么就会分离 rtp包的时间戳就会一样(rtp包序号不一样) 
				mOldTimestamp = mTimestamps[mBufferOut]; 
	
				// 发送rtp包 仍掉开头的30fp ??
				// 如果mCount<30 也当成已经覆盖了 mBufferOut往前走
				if (mCount++>30) mSocket.send(mPackets[mBufferOut]);  
				
				if (++mBufferOut>=mBufferCount) mBufferOut = 0;
				mBufferRequested.release(); // +1 这样 requestBuffer 就有buffer可用了
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
		mThread = null;
		resetFifo();
	}

	private void setLong(byte[] buffer, long n, int begin, int end) {
		for (end--; end >= begin; end--) {
			buffer[end] = (byte) (n % 256);
			n >>= 8;
		}
	}

	/** 
	 * Computes an average bit rate. 
	 **/
	protected static class AverageBitrate {

		private final static long RESOLUTION = 200;
		
		private long mOldNow, mNow, mDelta;
		private long[] mElapsed, mSum;
		private int mCount, mIndex, mTotal;
		private int mSize;
		
		public AverageBitrate() {
			mSize = 5000/((int)RESOLUTION);
			reset();
		}
		
		public AverageBitrate(int delay) {
			mSize = delay/((int)RESOLUTION);
			reset();
		}
		
		public void reset() {
			mSum = new long[mSize];
			mElapsed = new long[mSize];
			mNow = SystemClock.elapsedRealtime();
			mOldNow = mNow;
			mCount = 0;
			mDelta = 0;
			mTotal = 0;
			mIndex = 0;
		}
		
		public void push(int length) {
			mNow = SystemClock.elapsedRealtime();
			if (mCount>0) {
				mDelta += mNow - mOldNow;
				mTotal += length;
				if (mDelta>RESOLUTION) {
					mSum[mIndex] = mTotal;
					mTotal = 0;
					mElapsed[mIndex] = mDelta;
					mDelta = 0;
					mIndex++;
					if (mIndex>=mSize) mIndex = 0;
				}
			}
			mOldNow = mNow;
			mCount++;
		}
		
		public int average() {
			long delta = 0, sum = 0;
			for (int i=0;i<mSize;i++) {
				sum += mSum[i];
				delta += mElapsed[i];
			}
			//Log.d(TAG, "Time elapsed: "+delta);
			return (int) (delta>0?8000*sum/delta:0);
		}
		
	}
	
	/** Computes the proper rate at which packets are sent. */
	protected static class Statistics {

		public final static String TAG = "Statistics";
		
		private int count=500, c = 0;
		private float m = 0, q = 0;
		private long elapsed = 0;
		private long start = 0;
		private long duration = 0;
		private long period = 6000000000L;
		private boolean initoffset = false;

		public Statistics(int count, long period) {
			this.count = count;
			this.period = period*1000000L; 
		}
		
		public void push(long value) {
			duration += value;
			elapsed += value;
			if (elapsed>period) {
				elapsed = 0;
				long now = System.nanoTime();
				if (!initoffset || (now - start < 0)) {
					start = now;
					duration = 0;
					initoffset = true;
				}
				value -= (now - start) - duration;
				//Log.d(TAG, "sum1: "+duration/1000000+" sum2: "+(now-start)/1000000+" drift: "+((now-start)-duration)/1000000+" v: "+value/1000000);
			}
			if (c<40) {
				// We ignore the first 40 measured values because they may not be accurate
				c++;
				m = value;
			} else {
				m = (m*q+value)/(q+1);
				if (q<count) q++;
			}
		}
		
		public long average() {
			long l = (long)m-2000000;
			return l>0 ? l : 0;
		}

	}

}
