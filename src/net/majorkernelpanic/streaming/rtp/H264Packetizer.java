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

import android.annotation.SuppressLint;
import android.util.Log;

/**
 * 
 *   RFC 3984.
 *   
 *   H.264 streaming over RTP.
 *   
 *   Must be fed with an InputStream containing H.264 NAL units preceded by their length (4 bytes).
 *   The stream must start with mpeg4 or 3gpp header, it will be skipped.
 *   
 */
public class H264Packetizer extends AbstractPacketizer implements Runnable {

	public final static String TAG = "H264Packetizer";

	private Thread t = null;
	private int naluLength = 0;
	private long delay = 0, oldtime = 0;
	private Statistics stats = new Statistics();
	private byte[] sps = null, pps = null;
	byte[] header = new byte[5];	
	private int count = 0;
	private int streamType = 1;


	public H264Packetizer() {
		super();
		socket.setClockFrequency(90000);
	}

	public void start() {
		if (t == null) {
			t = new Thread(this); // H264Packetizer run
			t.start();
		}
	}

	public void stop() {
		if (t != null) {
			try {
				is.close();
			} catch (IOException e) {}
			t.interrupt(); // Thread.interrupted()
			try {
				t.join();
			} catch (InterruptedException e) {}
			t = null;
		}
	}

	public void setStreamParameters(byte[] pps, byte[] sps) {
		this.pps = pps;
		this.sps = sps;
	}	

	public void run() {// 在单独线程中
		long duration = 0, delta2 = 0;
		Log.d(TAG,"H264 packetizer started !");
		stats.reset();
		count = 0;

		if (is instanceof MediaCodecInputStream) {
			streamType = 1; // ????
			socket.setCacheSize(0); // 目前看来是MediaCodecInputStream From VideoStream.java
		} else {
			streamType = 0;	
			socket.setCacheSize(400);
		}

		try {
			while (!Thread.interrupted()) { // t.interrupt

				oldtime = System.nanoTime();
				// We read a NAL units from the input stream and we send them
				send(); // 在这里从MediaCodecInputStream.read --> MediaCodec.getOutputBuffers
						// 然后到RtpSocket.requestBuffer / markNextPacket / markNextPacket / send
				// We measure how long it took to receive NAL units from the phone
				duration = System.nanoTime() - oldtime;
				
				// Every 3 secondes, we send two packets containing NALU type 7 (sps) and 8 (pps)
				// Those should allow the H264 stream to be decoded even if no SDP was sent to the decoder.				
				delta2 += duration/1000000; // 每隔3秒钟 会发送 含有 sps pps 的NAL单元
											// 针对如果解码器没有获得SDP  在
				if (delta2>3000) { // duration是每次获取和发送的耗时  delta2是累计耗时 
					delta2 = 0;
					if (sps != null) { 
						/*
						 * see send() 
						 * 如果 NAL单元类型是 7/8 
						 * 那么NAL本来就有sps pps参数集 所以sps=null pps=null 这里就不会发送另外的NAL包
						 */
						buffer = socket.requestBuffer();
						socket.markNextPacket();
						socket.updateTimestamp(ts);
						// 	rtphl 是 rtp header length 头部的长度 12个字节
						//	也就是buffer的前面12个字节是放rtp header的
						//	header的后面就可以放sps/pps
						//	
						//	数组之间复制 
						//	Object src,int srcPos, Object dest,int destPos,int length
						System.arraycopy(sps, 0, buffer, rtphl, sps.length);
						super.send(rtphl+sps.length); 
						// 直接 socket.commitBuffer(len); // len=头部长度和sps长度
					}
					if (pps != null) {
						buffer = socket.requestBuffer();
						socket.updateTimestamp(ts);
						socket.markNextPacket();
						System.arraycopy(pps, 0, buffer, rtphl, pps.length);
						super.send(rtphl+pps.length);
						
					}					
				}

				stats.push(duration);
				// Computes the average duration of a NAL unit
				delay = stats.average();
				//Log.d(TAG,"duration: "+duration/1000000+" delay: "+delay/1000000);

			}
		} catch (IOException e) {
		} catch (InterruptedException e) {}

		Log.d(TAG,"H264 packetizer stopped !");

	}

	/**
	 * Reads a NAL unit in the FIFO and sends it.
	 * If it is too big, we split it in FU-A units (RFC 3984).
	 */
	@SuppressLint("NewApi")
	private void send() throws IOException, InterruptedException {
		int sum = 1, len = 0, type;

		if (streamType == 0) {
			// NAL units are preceeded by their length, we parse the length
			fill(header,0,5);
			ts += delay;
			naluLength = header[3]&0xFF | (header[2]&0xFF)<<8 | (header[1]&0xFF)<<16 | (header[0]&0xFF)<<24;
			if (naluLength>100000 || naluLength<0) resync();
		} else if (streamType == 1) {
			// NAL units are preceeded with 0x00000001
			
			// 这里可能阻塞
			// 这里进行MediaCodecInputStream::read 获得一个编码后的帧 并把 0~5的内容 拷贝到header
			fill(header,0,5); 
			
			// 从MediaCodec::dequeueOutputBuffer获得的BufferInfo中的时间戳
			ts = ((MediaCodecInputStream)is).getLastBufferInfo().presentationTimeUs*1000L; // 单位 ns  1s = 10^3ms = 10^6us = 10^9ns
			//ts += delay;
			naluLength = is.available()+1; // 这一个NAL单元的长度  编码后的帧还有多少可读(除去0～5)
			if (!(header[0]==0 && header[1]==0 && header[2]==0)) {
				// Turns out, the NAL units are not preceeded with 0x00000001
				Log.e(TAG, "NAL units are not preceeded by 0x00000001");
				streamType = 2; 
				return;
			}
			// 每一个NAL开头(编码后的帧)必须是以 00 00 00 01 开头 
			// 起始码不属于NALU，但在NALU header之前，用来隔开每个NALU 
			// header[4] 是  NALU Header  包含了NALU的类型 
		} else {
			// Nothing preceededs the NAL units
			fill(header,0,1);
			header[4] = header[0];
			ts = ((MediaCodecInputStream)is).getLastBufferInfo().presentationTimeUs*1000L;
			//ts += delay;
			naluLength = is.available()+1;
		}

		// Parses the NAL unit type
		type = header[4]&0x1F;     // 一个NALU  NAL单元 NAL单元类型

		// 如果NAL的类型是7或者8 那么NAL本来就含有sps pps 所有不用"每隔3s发一个带sps pps的NAL包" 
		// see run()
		// The stream already contains NAL unit type 7 or 8, we don't need 
		// to add them to the stream ourselves
		if (type == 7 || type == 8) {
			Log.v(TAG,"SPS or PPS present in the stream.");
			count++;
			if (count>4) {
				sps = null;
				pps = null;
			}
		}

		//Log.d(TAG,"- Nal unit length: " + naluLength + " delay: "+delay/1000000+" type: "+type);

		
		// Small NAL unit => Single NAL unit 
		if (naluLength<=MAXPACKETSIZE-rtphl-2) {
			buffer = socket.requestBuffer(); // MTU = 1300 
			buffer[rtphl] = header[4]; 
			len = fill(buffer, rtphl+1,  naluLength-1); 
			// 相当于 读取完 整个MediaCodec::dequeueOutputBuffer取出来的数据
			// 
			socket.updateTimestamp(ts);
			socket.markNextPacket(); // rtphl[1] != 0x80 
			super.send(naluLength+rtphl);
			//Log.d(TAG,"----- Single NAL unit - len:"+len+" delay: "+delay);
		}
		/* 
		 * 每次传输 包含 rtph 和 FU-A 和 NALU[5...] 不能超过 MAXPACKETSIZE = 1272 
		 * 
		 * MediaCodec::dequeueOutputBuffer 返回的NALU格式如下
		 * 						byte4        byte5...
		 * 	00	00	00	01	      xx	     ......
		 *  |  固定          |   NALU type |  数据 
		 * 
		 * 1.当一个NALU小于1272-12-2=1258字节的时候，采用一个单RTP包发送
		 * 
		 * 		rtphl + header[4] + MediaCodec::dequeueOutputBuffer获得buffer的 buffer[5~...]
		 * 
		 * 2.大的NAL单元 要分开发送
		 * 
		 * 		rtph1(12字节 M=0) + FU-A indicator(NRI | 28 ) + FU-A header( NALU type | 0x80 )  + NALU分片(大小是MAXPACKETSIZE-rtphl-2)
		 * 		rtph1(12字节 M=0) + FU-A indicator(NRI | 28)  + FU-A header( NALU type        )  + NALU分片(大小是MAXPACKETSIZE-rtphl-2)
		 * 		rtph1(12字节 M=1) + FU-A indicator(NRI | 28)  + FU-A header( NALU type | 0x40 )  + 剩下的NALU分片
		 * 
		 * 
		 * FU_INDICATOR 
		 * 		                6 5    4....0      
		 * 		F	 			NRI    
		 * 		forbidden_bit   优先级  28
		 * FU_HEADER
		 * 		bit7      6       5    4 ... 0 
		 * 		S         E       R    TYPE(NALU type)
		 * 		开始分片   最后分片       NALU类型
		 * */
		// Large NAL unit => Split nal unit 
		else {   

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
			// Set FU-A header
			header[1] = (byte) (header[4] & 0x1F);  // FU header type
			header[1] += 0x80; // Start bit
			// Set FU-A indicator  指示该RTP包是 一个NAL分离单元
			header[0] = (byte) ((header[4] & 0x60) & 0xFF); // FU indicator NRI
			header[0] += 28;								
			// 分离之后  每个RTP包的 NAL单元(NAL分片) 前面还需要加上 FU-A indicator/header
			
			// header[4] & 0x60 NRI：nal_ref_idc.2 位，用来指示该NALU 的重要性等级。值越大，表示当前NALU越重要 具体大于0 时取何值，没有具体规定
			// header[4] & 0x1F Type: NALU 的类型 

			while (sum < naluLength) {
				buffer = socket.requestBuffer();
				buffer[rtphl] = header[0];
				buffer[rtphl+1] = header[1];
				socket.updateTimestamp(ts); 
				/* 如果NAL单元分离的话 
				 * 
				 * rtp时间戳还是一样的
				 * rtp包序号还是会自增 (RtpSocket::commitBuffer RtpSocket::updateSequence)
				 */
				
				// naluLength-sum 一帧/NAL单元 剩下的数据 是否大于 MAXPACKETSIZE-rtphl-2 (2 就是 rtphl rtphl+1 两个单元 FU-A indicator/header )
				if ((len = fill(buffer, rtphl+2,  naluLength-sum > MAXPACKETSIZE-rtphl-2 ? MAXPACKETSIZE-rtphl-2 : naluLength-sum  ))<0) return; sum += len;
				// Last packet before next NAL
				if (sum >= naluLength) {
					// End bit on
					buffer[rtphl+1] += 0x40; // 设置 NALU分片头 End bit [bit6] 0x40   把NAL单元 分开传输 最后一个 分离单元的 FU-A header 要+40 
					socket.markNextPacket();			 
				}
				super.send(len+rtphl+2); 
				// Switch start bit
				header[1] = (byte) (header[1] & 0x7F);    // 设置 NALU分片头FU-A indicator Start bit[bit7] 0x80
				//Log.d(TAG,"----- FU-A unit, sum:"+sum);
			}
		}
	}

	private int fill(byte[] buffer, int offset,int length) throws IOException {
		int sum = 0, len;
		while (sum<length) { // 如果少于给定length那么继续read
			len = is.read(buffer, offset+sum, length-sum);
			if (len<0) {
				throw new IOException("End of stream");
			}
			else sum+=len;
		}
		return sum;
	}

	private void resync() throws IOException {
		int type;

		Log.e(TAG,"Packetizer out of sync ! Let's try to fix that...(NAL length: "+naluLength+")");

		while (true) {

			header[0] = header[1];
			header[1] = header[2];
			header[2] = header[3];
			header[3] = header[4];
			header[4] = (byte) is.read();

			type = header[4]&0x1F;

			if (type == 5 || type == 1) {
				naluLength = header[3]&0xFF | (header[2]&0xFF)<<8 | (header[1]&0xFF)<<16 | (header[0]&0xFF)<<24;
				if (naluLength>0 && naluLength<100000) {
					oldtime = System.nanoTime();
					Log.e(TAG,"A NAL unit may have been found in the bit stream !");
					break;
				}
				if (naluLength==0) {
					Log.e(TAG,"NAL unit with NULL size found...");
				} else if (header[3]==0xFF && header[2]==0xFF && header[1]==0xFF && header[0]==0xFF) {
					Log.e(TAG,"NAL unit with 0xFFFFFFFF size found...");
				}
			}

		}

	}

}