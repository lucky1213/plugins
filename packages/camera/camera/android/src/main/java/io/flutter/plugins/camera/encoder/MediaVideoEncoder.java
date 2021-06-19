package io.flutter.plugins.camera.encoder;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.util.Log;
import android.view.Surface;

import java.io.IOException;



/**
 * 功能：
 * </p>
 * <p>Copyright corp.xxx.com 2018 All right reserved </p>
 *
 * @author tuke 时间 2019/8/11
 * @email
 * <p>
 * 最后修改人：无
 * <p>
 */
public class MediaVideoEncoder extends MediaEncoder {

    private static final String TAG = MediaVideoEncoder.class.getSimpleName();

    private static final String MIME_TYPE = "video/avc";
    // FPS 帧率
    private static final int FRAME_RATE = 30;
    private static final float BPP = 0.50f;

    private final int mVideoWidth;
    private final int mVideoHeight;
    
    // 由MediaCodec创建的输入surface
    private Surface mMediaCodecIntputSurface;


    /**
     * 构造方法
     *
     * @param mediaMuxerManager
     * @param mediaEncoderListener
     */
    public MediaVideoEncoder(MediaMuxerManager mediaMuxerManager,
                             MediaEncoderListener mediaEncoderListener,
                             int videoWidth,
                             int videoHeight) {

        super(TAG,mediaMuxerManager, mediaEncoderListener);

        Log.i(TAG, "MediaVideoEncoder constructor： ");
        mVideoWidth = videoWidth;
        mVideoHeight = videoHeight;
    }


    /**
     * 初始化编码器
     * @throws IOException
     */
    @Override
    public void prepare() throws IOException {
        Log.d(TAG, "---prepare---");

        mTrackIndex = -1;
        mMuxerStarted = mIsEndOfStream = false;

        //-----------------MediaFormat-----------------------
        // mediaCodeC采用的是H.264编码
        final MediaFormat format = MediaFormat.createVideoFormat(MIME_TYPE, mVideoWidth, mVideoHeight);
        // 数据来源自surface
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        // 视频码率
        format.setInteger(MediaFormat.KEY_BIT_RATE, calcBitRate());
        // fps帧率
        format.setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE);
        // 设置关键帧的时间
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 5);

        //-----------------Encoder编码器-----------------------
        mMediaCodec = MediaCodec.createEncoderByType(MIME_TYPE);

        mMediaCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);

        // 获取编码器输入Surface，只能在#configure and #start 之间调用，相机帧写入此Surface
        mMediaCodecIntputSurface = mMediaCodec.createInputSurface();

        // 开始
        mMediaCodec.start();

        Log.i(TAG, "prepare finishing");
        if (mMediaEncoderListener != null) {
            try {
                mMediaEncoderListener.onPrepared(this);
            } catch (final Exception e) {
                Log.e(TAG, "prepare:", e);
            }
        }

    }


    @Override
    public void release() {
        Log.i(TAG, "MediaVideoEncoder release:");
        if (mMediaCodecIntputSurface != null) {
            mMediaCodecIntputSurface.release();
            mMediaCodecIntputSurface = null;
        }
        super.release();
    }

    /**
     * @return
     */
    public Surface getIntputSurface() {
        return mMediaCodecIntputSurface;
    }

    public void start() {
        // 开始
        mMediaCodec.start();
    }


    @Override
    public void signalEndOfInputStream() {
        Log.d(TAG, "sending EOS to encoder");

        // video 向编码器写入EOS帧
        mMediaCodec.signalEndOfInputStream();
        mIsEndOfStream = true;
    }

    /**
     * 码率
     * @return
     */
    private int calcBitRate() {
        final int bitrate = (int) (BPP * FRAME_RATE * mVideoWidth * mVideoHeight);
//        final int bitrate = 800000;
        Log.i(TAG, String.format("bitrate=%5.2f[Mbps]", bitrate / 1024f / 1024f));
        return bitrate;
    }
}