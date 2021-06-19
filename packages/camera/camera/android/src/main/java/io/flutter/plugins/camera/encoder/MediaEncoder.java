package io.flutter.plugins.camera.encoder;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.util.Log;
import com.libyuv.util.YuvUtil;

import androidx.annotation.MainThread;

import java.io.IOException;
import java.nio.ByteBuffer;



public abstract class MediaEncoder implements Runnable{
    private static final String TAG = MediaEncoder.class.getSimpleName();
    private String TAG_1;

    // 10[msec]
    protected static final int TIMEOUT_USEC = 10000;

    // 同步锁
    protected final Object mSync = new Object();

    // 是否正在进行录制
    protected volatile boolean mIsCapturing;
    // 结束录制的标识
    protected volatile boolean mRequestStop;

    // 可用数据帧数量（可以去muxer）
    private int mRequestDrainEncoderCount;

    // 是否写入EOS帧
    protected boolean mIsEndOfStream;
    // muxer是否正在运行
    protected boolean mMuxerStarted;
    // 轨道数量
    protected int mTrackIndex;

    // 视频，音频编码器
    protected MediaCodec mMediaCodec;
    // 输出buffer信息
    private MediaCodec.BufferInfo mBufferInfo;
    // 复用器
    protected MediaMuxerManager mMuxerManager;
    // 编码器回调
    protected MediaEncoderListener mMediaEncoderListener;


    /**
     * 构造方法
     *
     * @param mediaMuxerManager
     * @param mediaEncoderListener
     */
    public MediaEncoder(String tag,
                        final MediaMuxerManager mediaMuxerManager,
                        final MediaEncoderListener mediaEncoderListener) {
        TAG_1 = tag;
        Log.d(TAG, TAG_1 + "---MediaEncoder construtor ---");

        if (mediaEncoderListener == null) {
            throw new NullPointerException(TAG_1 +"MediaEncoderListener is null");
        }
        if (mediaMuxerManager == null) {
            throw new NullPointerException(TAG_1 +"MediaMuxerManager is null");
        }
        this.mMuxerManager = mediaMuxerManager;
        this.mMediaEncoderListener = mediaEncoderListener;
        // 添加解码器
        this.mMuxerManager.addEncoder(MediaEncoder.this);

        Log.d(TAG ,TAG_1 +"---MediaEncoder synchronized (mSync) before begin---");
        synchronized (mSync) {
            Log.d(TAG, TAG_1 +"---MediaEncoder synchronized (mSync) begin---");

            // 创建bufferInfo
            mBufferInfo = new MediaCodec.BufferInfo();
            // 开启 解码器线程
            new Thread(this, getClass().getSimpleName()).start();

            // 本解码器线程等待
            try {
                mSync.wait();
            } catch (final InterruptedException e) {
                e.printStackTrace();
            }
        }
        Log.d(TAG, TAG_1 +"---MediaEncoder synchronized (mSync) end---");
    }

    /**
     * 目前主线程调用
     */
    @MainThread
    public void startRecording() {

        Log.d(TAG,TAG_1 +"---startRecording synchronized (mSync) before begin---");
        synchronized (mSync) {
            Log.d(TAG,TAG_1 +"---startRecording synchronized (mSync) begin---");
            // 正在录制标识
            mIsCapturing = true;
            // 停止标识 置false
            mRequestStop = false;
            //
            mSync.notifyAll();
        }
        Log.d(TAG,TAG_1 +"---startRecording synchronized (mSync) end---");

    }

    /**
     * 停止录制(目前在主线程调用)
     */
    @MainThread
    public void stopRecording() {
        Log.d(TAG,TAG_1 + "---stopRecording synchronized (mSync) before begin---");
        synchronized (mSync) {
            Log.d(TAG,TAG_1 + "---stopRecording synchronized (mSync) begin---");
            if (!mIsCapturing || mRequestStop) {
                return;
            }
            mRequestStop = true;
            mSync.notifyAll();
        }
        Log.d(TAG,TAG_1 + "---stopRecording synchronized (mSync) end---");
    }

    /**
     * 目前在主线程被调用，子类实现
     *
     * @throws IOException
     */
    @MainThread
    public abstract void prepare() throws IOException;

    /**
     * 表明帧数据 已经可用
     *
     * @return true 如果编码器可以编码
     */
    public boolean frameAvailableSoon() {
        Log.d(TAG, TAG_1 + "---frameAvailableSoon---");

        Log.d(TAG, TAG_1 + "---mSync before begin---");
        synchronized (mSync) {
            Log.d(TAG, TAG_1 + "---mSync begin---");

            if (!mIsCapturing || mRequestStop) {
                Log.d(TAG, TAG_1 + "mIsCapturing: " + mIsCapturing);
                Log.d(TAG, TAG_1 + "mRequestStop: " + mRequestStop);
                Log.d(TAG, TAG_1 + "return false");
                return false;
            }
            mRequestDrainEncoderCount++;
            Log.d(TAG, TAG_1 + "mRequestDrainEncoderCount: "+mRequestDrainEncoderCount);
            mSync.notifyAll();
        }
        Log.d(TAG, TAG_1 + "---mSync end---");
        Log.d(TAG, TAG_1 + "return true");
        return true;
    }

    @Override
    public void run() {
        Log.d(TAG,TAG_1 + "---run---");

        Log.d(TAG,TAG_1 + "---run synchronized (mSync) before begin---");
        // 线程开启
        synchronized (mSync) {
            Log.d(TAG,TAG_1 + "---run synchronized (mSync) begin---");

            mRequestStop = false;
            mRequestDrainEncoderCount = 0;

            // 唤醒等待的线程
            mSync.notify();
        }
        Log.d(TAG,TAG_1 + "---run synchronized (mSync) end---");

        // 线程开启
        final boolean isRunning = true;
        // 接受的停止请求
        boolean localRequestStop;
        // 可以muxer编码器输出数据
        boolean localRequestDrainEncoderFlag;

        // 死循环
        while (isRunning) {

            // 检查循环条件 是否成立
            Log.d(TAG,TAG_1 + "---run2 synchronized (mSync) before begin---");
            synchronized (mSync) {
                Log.d(TAG,TAG_1 + "---run2 synchronized (mSync) begin---");

                localRequestStop = mRequestStop;
                localRequestDrainEncoderFlag = (mRequestDrainEncoderCount > 0);
                if (localRequestDrainEncoderFlag) {
                    mRequestDrainEncoderCount--;
                }
            }
            Log.d(TAG,TAG_1 + "---run2 synchronized (mSync) end---");

            // 停止录制时，调用
            if (localRequestStop) {

                // 编码器输出数据，写入Muxer
                drainEncoder();
                // 写EOS帧
                signalEndOfInputStream();
                // 对EOS帧 处理输出数据
                drainEncoder();
                // 释放所有对象
                release();

                break;
            }

            // 需要Muxer
            if (localRequestDrainEncoderFlag) {
                drainEncoder();

            } else {

                // ------不需要录制时，线程进入等待状态---------
                Log.d(TAG,TAG_1 + "---run3 synchronized (mSync) before begin---");
                synchronized (mSync) {
                    Log.d(TAG,TAG_1 + "---run3 synchronized (mSync) begin---");
                    try {
                        mSync.wait();
                    } catch (final InterruptedException e) {
                        e.printStackTrace();
                        break;
                    }
                }
                Log.d(TAG,TAG_1 + "---run3 synchronized (mSync) end---");
            }
        }

        synchronized (mSync) {
            mRequestStop = true;
            mIsCapturing = false;
        }
    }

    public byte[] rotateYUV420SP(byte[] data, int imageWidth, int imageHeight) {
        byte[] yuv = new byte[imageWidth * imageHeight * 3 / 2];
        int i = 0;
        for (int x = 0; x < imageWidth; x++) {
            for (int y = imageHeight - 1; y >= 0; y--) {
                yuv[i] = data[y * imageWidth + x];
                i++;
            }
        }
        i = imageWidth * imageHeight * 3 / 2 - 1;
        for (int x = imageWidth - 1; x > 0; x = x - 2) {
            for (int y = 0; y < imageHeight / 2; y++) {
                yuv[i] = data[(imageWidth * imageHeight) + (y * imageWidth) + x];
                i--;
                yuv[i] = data[(imageWidth * imageHeight) + (y * imageWidth)
                        + (x - 1)];
                i--;
            }
        }
        return yuv;
    }


    /**
     * mEncoder从缓冲区取数据，然后交给mMuxer复用
     */
    protected void drainEncoder() {
        if (mMediaCodec == null || mMuxerManager == null) {
            return;
        }
        int count = 0;

        // 拿到输出缓冲区,用于取到编码后的数据
        // ByteBuffer[] encoderOutputBuffers = mMediaCodec.getOutputBuffers();

        LOOP:
        while (mIsCapturing) {
            // 拿到输出缓冲区的索引
            int encoderStatus = mMediaCodec.dequeueOutputBuffer(mBufferInfo, TIMEOUT_USEC);
            if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                Log.d(TAG,TAG_1 + "---drainEncoder encoderStatus == INFO_TRY_AGAIN_LATER---");

                // 还没有可用的输出
                if (!mIsEndOfStream) {
                    // 大于5次没有可用的输出，就退出
                    if (++count > 5) {
                        // 结束循环
                        break LOOP;
                    }
                }
            } else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                Log.d(TAG,TAG_1 + "---drainEncoder encoderStatus == INFO_OUTPUT_FORMAT_CHANGED---");
                // 输出帧格式改变，应该在接受buffer之前返回，只会发生一次

                if (mMuxerStarted) {
                    throw new RuntimeException("format changed twice");
                }
                // 得到解码器的输出格式，传给复用器muxer
                final MediaFormat format = mMediaCodec.getOutputFormat();
                // muxer添加轨道
                mTrackIndex = mMuxerManager.addTrack(format);
                //
                mMuxerStarted = true;
                //
                if (!mMuxerManager.start()) {
                    // 循环等待muxer开始
                    synchronized (mMuxerManager) {
                        while (!mMuxerManager.isStarted())
                            try {
                                mMuxerManager.wait(100);
                            } catch (final InterruptedException e) {
                                break LOOP;
                            }
                    }
                }
            } else if (encoderStatus < 0) {
                // unexpected status

            } else {
                Log.d(TAG,TAG_1 + "---drainEncoder encoderStatus == 编码器输出位置" + encoderStatus);

                // 获取解码后的数据
                final ByteBuffer data = mMediaCodec.getOutputBuffer(encoderStatus);
                ByteBuffer encodedData = data;
                if (this instanceof MediaVideoEncoder) {
                    byte[] inputData = new byte[data.remaining()];
                    data.get(inputData);
                    final byte[] outputData = new byte[480 * 720 * 3 / 2];
                    final byte[] outputData2 = new byte[480 * 720 * 3 / 2];

//                    YuvUtil.yuvCompress(inputData, 720, 480, outputData, 720, 480, 0, 90, false);
//                    YuvUtil.yuvI420ToNV21(outputData, 480, 720, outputData2);
                    YuvUtil.yuvRotateI420(inputData, 480, 720, outputData, 90);

                    encodedData = ByteBuffer.wrap(outputData);
                }


                if (encodedData == null) {
                    throw new RuntimeException("encoderOutputBuffer " + encoderStatus + " was null");
                }

                if ((mBufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    mBufferInfo.size = 0;
                }

                if (mBufferInfo.size != 0) {
                    count = 0;
                    if (!mMuxerStarted) {
                        throw new RuntimeException(TAG_1 + "drain:muxer hasn't started");
                    }
                    // 写编码数据到muxer，显示时间需要调整
                    mBufferInfo.presentationTimeUs = getPTSUs();
                    // 写muxer
                    mMuxerManager.writeSampleData(mTrackIndex, encodedData, mBufferInfo);
                    // 上一个bufferInfo显示时间
                    prevOutputPTSUs = mBufferInfo.presentationTimeUs;
                }
                // 释放buffer给编码器
                mMediaCodec.releaseOutputBuffer(encoderStatus, false);
                // 视频流结束帧
                if ((mBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    mIsCapturing = false;
                    break;
                }
            }
        }
    }

    /**
     * 向编码器写入 视频流结束帧
     */
    public void signalEndOfInputStream() {
        encode(null, 0, getPTSUs());
    }

    /**
     * 向编码器写入buffer数据，开始H264编码
     * 此处主要是Audio的pcm编码，video通过surface传给编码器
     *
     * @param buffer
     * @param length             buffer长度，eos帧是0
     * @param presentationTimeUs buffer显示时间
     */
    protected void encode(final ByteBuffer buffer, final int length, final long presentationTimeUs) {
        if (!mIsCapturing) {
            return;
        }
        // 编码器输入buffers
        // final ByteBuffer[] inputBuffers = mMediaCodec.getInputBuffers();

        // 循环写入
        while (mIsCapturing) {
            // 获取一个输出Buffer
            final int inputBufferIndex = mMediaCodec.dequeueInputBuffer(TIMEOUT_USEC);
            if (inputBufferIndex >= 0) {
                final ByteBuffer inputBuffer = mMediaCodec.getInputBuffer(inputBufferIndex);
                inputBuffer.clear();
                // 放入数据
                if (buffer != null) {
                    inputBuffer.put(buffer);
                }

                if (length <= 0) {
                    // 向编码器 写入EOS帧
                    mIsEndOfStream = true;
                    mMediaCodec.queueInputBuffer(
                            inputBufferIndex,
                            0,
                            0,
                            presentationTimeUs,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                    break;
                } else {
                    // 将buffer传递给编码器
                    mMediaCodec.queueInputBuffer(
                            inputBufferIndex,
                            0,
                            length,
                            presentationTimeUs,
                            0);
                }
                break;
            } else if (inputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {

            }
        }
    }


    /**
     * // 释放所有对象
     */
    public void release() {

        // 回调停止
        try {
            if (mMediaEncoderListener != null) {
                mMediaEncoderListener.onStopped(MediaEncoder.this);
            }
        } catch (final Exception e) {
            e.printStackTrace();
        }

        // 设置标识 停止录制
        mIsCapturing = false;

        // ------释放mediacodec--------
        if (mMediaCodec != null) {
            try {
                mMediaCodec.stop();
                mMediaCodec.release();
                mMediaCodec = null;
            } catch (final Exception e) {
                e.printStackTrace();
            }
        }

        // ----------释放muxer-----------
        if (mMuxerStarted) {
            if (mMuxerManager != null) {
                try {
                    mMuxerManager.stop();
                } catch (final Exception e) {
                    e.printStackTrace();
                }
            }
        }

        // mBufferInfo置空
        mBufferInfo = null;
    }


    /**
     * previous presentationTimeUs for writing
     */
    private long prevOutputPTSUs = 0;

    /**
     * get next encoding presentationTimeUs
     *
     * @return
     */
    protected long getPTSUs() {
        long result = System.nanoTime() / 1000L;
        if (result < prevOutputPTSUs)
            result = (prevOutputPTSUs - result) + result;
        return result;
    }


    /**
     * 编码器回调
     */
    public interface MediaEncoderListener {
        /**
         * @param encoder
         */
        void onPrepared(MediaEncoder encoder);

        /**
         * @param encoder
         */
        void onStopped(MediaEncoder encoder);
    }
}