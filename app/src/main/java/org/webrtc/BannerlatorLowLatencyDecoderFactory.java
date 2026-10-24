package org.webrtc;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaCrypto;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Bundle;
import android.view.Surface;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Bannerlator's hardware video decoder factory for the native Xbox Cloud Gaming player. It builds WebRTC's own
 * AndroidVideoDecoder (hence this package) around a MediaCodec wrapper that can:
 *  - ask the decoder for low-latency output (Android's KEY_LOW_LATENCY and Qualcomm's low-latency / picture-order
 *    vendor keys; unknown keys are ignored, and a decoder that refuses them is configured without them);
 *  - for zero-copy, decode straight onto the player's SurfaceView instead of WebRTC's texture, rendering every
 *    output buffer there (no GL copy, no compositor; WebRTC then gets no decoded frames to draw).
 * Without either option it behaves like WebRTC's HardwareVideoDecoderFactory.
 */
public class BannerlatorLowLatencyDecoderFactory implements VideoDecoderFactory {
    /** Counts frames sent to the display in zero-copy mode (WebRTC's own stats see none). */
    public interface FrameCounter { void onFrameShown(); }

    private final HardwareVideoDecoderFactory hardware;
    private final EglBase.Context sharedContext;
    private final boolean lowLatency;
    private final Surface zeroCopySurface;
    private final FrameCounter counter;

    public BannerlatorLowLatencyDecoderFactory(EglBase.Context sharedContext, boolean lowLatency, Surface zeroCopySurface, FrameCounter counter) {
        this.hardware = new HardwareVideoDecoderFactory(sharedContext);
        this.sharedContext = sharedContext;
        this.lowLatency = lowLatency;
        this.zeroCopySurface = zeroCopySurface;
        this.counter = counter;
    }

    @Override
    public VideoCodecInfo[] getSupportedCodecs() {
        return hardware.getSupportedCodecs();
    }

    @Override
    public VideoDecoder createDecoder(VideoCodecInfo info) {
        if (!lowLatency && zeroCopySurface == null) return hardware.createDecoder(info);
        VideoCodecMimeType type;
        try { type = VideoCodecMimeType.valueOf(info.getName()); } catch (IllegalArgumentException e) { return hardware.createDecoder(info); }
        MediaCodecInfo codec = findHardwareDecoder(type);
        if (codec == null) return hardware.createDecoder(info);
        Integer color = MediaCodecUtils.selectColorFormat(MediaCodecUtils.DECODER_COLOR_FORMATS, codec.getCapabilitiesForType(type.mimeType()));
        if (color == null) return hardware.createDecoder(info);
        return new AndroidVideoDecoder(new WrapperFactory(), codec.getName(), type, color, sharedContext);
    }

    private static MediaCodecInfo findHardwareDecoder(VideoCodecMimeType type) {
        for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
            if (info.isEncoder()) continue;
            if (MediaCodecUtils.codecSupportsType(info, type) && MediaCodecUtils.isHardwareAccelerated(info)) return info;
        }
        return null;
    }

    private class WrapperFactory implements MediaCodecWrapperFactory {
        @Override
        public MediaCodecWrapper createByCodecName(String name) throws IOException {
            return new Wrapper(new MediaCodecWrapperFactoryImpl().createByCodecName(name));
        }
    }

    private class Wrapper implements MediaCodecWrapper {
        private final MediaCodecWrapper codec;
        Wrapper(MediaCodecWrapper codec) { this.codec = codec; }

        @Override
        public void configure(MediaFormat format, Surface surface, MediaCrypto crypto, int flags) {
            Surface target = zeroCopySurface != null ? zeroCopySurface : surface;
            if (lowLatency) {
                MediaFormat fast = MediaFormat.createVideoFormat(format.getString(MediaFormat.KEY_MIME),
                    format.getInteger(MediaFormat.KEY_WIDTH), format.getInteger(MediaFormat.KEY_HEIGHT));
                copyInt(format, fast, MediaFormat.KEY_COLOR_FORMAT);
                if (Build.VERSION.SDK_INT >= 30) fast.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
                fast.setInteger(MediaFormat.KEY_PRIORITY, 0);
                fast.setInteger("vendor.qti-ext-dec-low-latency.enable", 1);
                fast.setInteger("vendor.qti-ext-dec-picture-order.enable", 1);
                try {
                    codec.configure(fast, target, crypto, flags);
                    Logging.d("BannerlatorDecoder", "low-latency decoder configured" + (zeroCopySurface != null ? " (zero-copy)" : ""));
                    return;
                } catch (RuntimeException e) {
                    Logging.w("BannerlatorDecoder", "decoder refused low-latency keys: " + e);
                }
            }
            codec.configure(format, target, crypto, flags);
        }

        private void copyInt(MediaFormat from, MediaFormat to, String key) {
            if (from.containsKey(key)) to.setInteger(key, from.getInteger(key));
        }

        // Zero-copy: AndroidVideoDecoder renders one buffer to its texture and drops the rest until the texture
        // hands the frame back, which never happens with the display surface; show every buffer instead.
        @Override public void releaseOutputBuffer(int index, boolean render) {
            if (zeroCopySurface != null) { codec.releaseOutputBuffer(index, true); if (counter != null) counter.onFrameShown(); }
            else codec.releaseOutputBuffer(index, render);
        }

        @Override public void start() { codec.start(); }
        @Override public void flush() { codec.flush(); }
        @Override public void stop() { codec.stop(); }
        @Override public void release() { codec.release(); }
        @Override public int dequeueInputBuffer(long timeoutUs) { return codec.dequeueInputBuffer(timeoutUs); }
        @Override public void queueInputBuffer(int index, int offset, int size, long presentationTimeUs, int flags) { codec.queueInputBuffer(index, offset, size, presentationTimeUs, flags); }
        @Override public int dequeueOutputBuffer(MediaCodec.BufferInfo info, long timeoutUs) { return codec.dequeueOutputBuffer(info, timeoutUs); }
        @Override public MediaFormat getInputFormat() { return codec.getInputFormat(); }
        @Override public MediaFormat getOutputFormat() { return codec.getOutputFormat(); }
        @Override public MediaFormat getOutputFormat(int index) { return codec.getOutputFormat(index); }
        @Override public ByteBuffer getInputBuffer(int index) { return codec.getInputBuffer(index); }
        @Override public ByteBuffer getOutputBuffer(int index) { return codec.getOutputBuffer(index); }
        @Override public Surface createInputSurface() { return codec.createInputSurface(); }
        @Override public void setParameters(Bundle params) { codec.setParameters(params); }
        @Override public MediaCodecInfo getCodecInfo() { return codec.getCodecInfo(); }
    }
}
