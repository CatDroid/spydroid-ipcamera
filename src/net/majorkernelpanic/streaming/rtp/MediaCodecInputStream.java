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
import java.io.InputStream;
import java.nio.ByteBuffer;

import android.annotation.SuppressLint;
import android.media.MediaCodec;
import android.media.MediaCodec.BufferInfo;
import android.media.MediaFormat;
import android.util.Log;

/**
 * An InputStream that uses data from a MediaCodec.
 * The purpose of this class is to interface existing RTP packetizers of
 * libstreaming with the new MediaCodec API. This class is not thread safe !  
 */
@SuppressLint("NewApi")
public class MediaCodecInputStream extends InputStream {

	public final String TAG = "MediaCodecInputStream"; 

	private MediaCodec mMediaCodec = null;
	private BufferInfo mBufferInfo = new BufferInfo();
	private ByteBuffer[] mBuffers = null;
	private ByteBuffer mBuffer = null;
	private int mIndex = -1;
	private boolean mClosed = false;
	
	public MediaFormat mMediaFormat;

	public MediaCodecInputStream(MediaCodec mediaCodec) {
		mMediaCodec = mediaCodec;
		mBuffers = mMediaCodec.getOutputBuffers();// 对应编码时候 mMediaCodec::getInputBuffers @  VideoStream.java
	}

	@Override
	public void close() {
		mClosed = true;
	}

	@Override
	public int read() throws IOException {
		return 0;
	}

	@Override
	public int read(byte[] buffer, int offset, int length) throws IOException {
		int min = 0;

		try {
			if (mBuffer==null) { 
				// mBuffer不等于null 代表上次MediaCodec::dequeueOutputBuffer的数据还没有read完
				// 所以就不会从MediaCodec取出解码后的数据
				while (!Thread.interrupted() && !mClosed) {
					mIndex = mMediaCodec.dequeueOutputBuffer(mBufferInfo, 500000);
					long now = System.nanoTime()/1000 ; 
					Log.d("TOM", "[" + mIndex + "] Out = " + mBufferInfo.presentationTimeUs + " now = " + now 
								+ " diff(ms) = " + (now - mBufferInfo.presentationTimeUs)/1000
								+ " size = " +  mBufferInfo.size );
					
					if (mIndex>=0 ){
						//Log.d(TAG,"Index: "+mIndex+" Time: "+mBufferInfo.presentationTimeUs+" size: "+mBufferInfo.size);
						mBuffer = mBuffers[mIndex];
						mBuffer.position(0);
						break;
						
					// 其他负数 状态
					} else if (mIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
						mBuffers = mMediaCodec.getOutputBuffers();
						// 重新获取buffer堆
					} else if (mIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
						mMediaFormat = mMediaCodec.getOutputFormat();
						Log.i(TAG,mMediaFormat.toString());
					} else if (mIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
						Log.v(TAG,"No buffer available...");
						//return 0;
					} else {
						Log.e(TAG,"Message: "+mIndex);
						//return 0;
					}
				}			
			}
			
			if (mClosed) throw new IOException("This InputStream was closed");
			
			// 如果 buffer的大小大于 read指定的length 那么将会 截取到length
			// mBufferInfo.size  整帧的大小
			// mBuffer.position  还没读取的开始位置
			min = length < mBufferInfo.size - mBuffer.position() ? length : mBufferInfo.size - mBuffer.position(); 
			mBuffer.get(buffer, offset, min); // 保存数据到buffer  (返回给read)
			if (mBuffer.position()>=mBufferInfo.size) { // get会导致mBuffer.position移动
				mMediaCodec.releaseOutputBuffer(mIndex, false); // 返还一个buffer
				mBuffer = null; 
			}// 没有读完的话 下次read mBuffer!=null 不会从MediaCodec获取下一帧
			
			// 只要read返回 MediaCodecInputStream就会保留一个
			// mBufferInfo 表示当前 MediaCodec.dequeueOutputBuffer 取出buffer的信息
		} catch (RuntimeException e) {
			e.printStackTrace();
		}

		return min;
	}
	
	/*	表示在上次read返回之后， 还有多少数据没有读取
	
		如果mBuffer != null 代表上次 dequeueBuffer后的mBuffer 还有数据没有read完 
		
		mBufferInfo.size = 
	 		每一个NAL开头(编码后的帧)必须是以 00 00 00 01 开头  (起始码不属于NALU，但在NALU header之前，用来隔开每个NALU) 
			header[4] 是  NALU Header  包含了NALU的类型 
			NALU border
	*/	
	public int available() { 
		if (mBuffer != null) // 当前取出来 MediaCodec.getOutputBuffers dequeueBuffer
			return mBufferInfo.size - mBuffer.position();
		else 
			return 0;
	}

	public BufferInfo getLastBufferInfo() {
		return mBufferInfo;
	}

}
