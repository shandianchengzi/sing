package com.shandian.sing;

import android.content.Context;
import android.media.*;
import android.net.Uri;
import java.io.*;
import java.nio.*;

/** Decode a bounded audio range to a temporary PCM file using system codecs. */
final class PcmAudio {
  final File file;
  int rate, channels;
  long frames;

  PcmAudio(File file) {
    this.file = file;
  }

  static PcmAudio decode(Context context, Uri uri, long startUs, long lengthUs, File file)
      throws Exception {
    PcmAudio pcm = new PcmAudio(file);
    MediaExtractor extractor = new MediaExtractor();
    MediaCodec codec = null;
    try (OutputStream output = new BufferedOutputStream(new FileOutputStream(file), 65536)) {
      extractor.setDataSource(context, uri, null);
      MediaFormat input = null;
      for (int i = 0; i < extractor.getTrackCount(); i++) {
        MediaFormat format = extractor.getTrackFormat(i);
        String mime = format.getString(MediaFormat.KEY_MIME);
        if (mime != null && mime.startsWith("audio/")) {
          extractor.selectTrack(i);
          input = format;
          break;
        }
      }
      if (input == null) throw new IOException("伴奏没有音轨");
      extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
      input.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT);
      codec = MediaCodec.createDecoderByType(input.getString(MediaFormat.KEY_MIME));
      codec.configure(input, null, null, 0);
      codec.start();
      boolean inputEnd = false, outputEnd = false;
      int encoding = AudioFormat.ENCODING_PCM_16BIT;
      MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
      long deadline = System.nanoTime() + 30_000_000_000L;
      while (!outputEnd) {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("已取消导出");
        if (System.nanoTime() > deadline) throw new IOException("音频解码超时");
        if (!inputEnd) {
          int index = codec.dequeueInputBuffer(10000);
          if (index >= 0) {
            ByteBuffer buffer = codec.getInputBuffer(index);
            buffer.clear();
            int size = extractor.readSampleData(buffer, 0);
            if (size < 0) {
              codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
              inputEnd = true;
            } else {
              codec.queueInputBuffer(index, 0, size, extractor.getSampleTime(), 0);
              extractor.advance();
            }
          }
        }
        int index = codec.dequeueOutputBuffer(info, 10000);
        if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
          MediaFormat format = codec.getOutputFormat();
          int rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
          int channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
          if (pcm.frames > 0 && (pcm.rate != rate || pcm.channels != channels))
            throw new IOException("不支持中途改变音频格式");
          pcm.rate = rate;
          pcm.channels = channels;
          encoding =
              format.containsKey(MediaFormat.KEY_PCM_ENCODING)
                  ? format.getInteger(MediaFormat.KEY_PCM_ENCODING)
                  : AudioFormat.ENCODING_PCM_16BIT;
          if (channels < 1
              || channels > 2
              || (encoding != AudioFormat.ENCODING_PCM_16BIT
                  && encoding != AudioFormat.ENCODING_PCM_FLOAT))
            throw new IOException("混音仅支持单声道或双声道音频");
        } else if (index >= 0) {
          try {
            if (info.size > 0 && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
              deadline = System.nanoTime() + 30_000_000_000L;
              if (pcm.rate == 0) throw new IOException("解码器未返回音频格式");
              ByteBuffer buffer =
                  codec.getOutputBuffer(index).duplicate().order(ByteOrder.LITTLE_ENDIAN);
              buffer.position(info.offset);
              buffer.limit(info.offset + info.size);
              int bytesPerSample = encoding == AudioFormat.ENCODING_PCM_FLOAT ? 4 : 2;
              int count = info.size / (pcm.channels * bytesPerSample);
              int first =
                  (int)
                      Math.min(
                          count,
                          Math.max(0, (startUs - info.presentationTimeUs) * pcm.rate + 999999)
                              / 1000000);
              int last = count;
              if (lengthUs >= 0)
                last =
                    (int)
                        Math.max(
                            0,
                            Math.min(
                                count,
                                ((startUs + lengthUs - info.presentationTimeUs) * pcm.rate + 999999)
                                    / 1000000));
              buffer.position(info.offset + first * pcm.channels * bytesPerSample);
              for (int frame = first; frame < last; frame++) {
                for (int channel = 0; channel < pcm.channels; channel++) {
                  int sample =
                      encoding == AudioFormat.ENCODING_PCM_FLOAT
                          ? Math.max(-32768, Math.min(32767, Math.round(buffer.getFloat() * 32767)))
                          : buffer.getShort();
                  output.write(sample & 255);
                  output.write((sample >> 8) & 255);
                }
                pcm.frames++;
              }
              if (lengthUs >= 0
                  && info.presentationTimeUs + count * 1000000L / pcm.rate >= startUs + lengthUs)
                outputEnd = true;
            }
            if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputEnd = true;
          } finally {
            codec.releaseOutputBuffer(index, false);
          }
        }
      }
      return pcm;
    } finally {
      if (codec != null) codec.release();
      extractor.release();
    }
  }

  /** Bounded-memory linear resampling; no whole-song arrays. */
  static final class Reader implements AutoCloseable {
    final PcmAudio pcm;
    final InputStream input;
    final double[] left = new double[2], right = new double[2];
    long index;

    Reader(PcmAudio pcm) throws Exception {
      this(pcm, 0, 48000);
    }

    Reader(PcmAudio pcm, long outputFrame, int targetRate) throws Exception {
      this.pcm = pcm;
      FileInputStream file = new FileInputStream(pcm.file);
      index = outputFrame * pcm.rate / targetRate;
      file.getChannel().position(Math.min(pcm.frames, index) * pcm.channels * 2);
      input = new BufferedInputStream(file, 65536);
      frame(left);
      frame(right);
    }

    void frame(double[] values) throws IOException {
      for (int channel = 0; channel < pcm.channels; channel++) {
        int low = input.read(), high = input.read();
        values[channel] = low < 0 || high < 0 ? 0 : (short) (low | high << 8);
      }
      if (pcm.channels == 1) values[1] = values[0];
    }

    void sample(long outputFrame, int targetRate, double[] out) throws IOException {
      long numerator = outputFrame * pcm.rate;
      long wanted = numerator / targetRate;
      while (index < wanted) {
        left[0] = right[0];
        left[1] = right[1];
        frame(right);
        index++;
      }
      double fraction = (numerator % targetRate) / (double) targetRate;
      for (int c = 0; c < 2; c++) out[c] = left[c] + fraction * (right[c] - left[c]);
    }

    @Override
    public void close() throws IOException {
      input.close();
    }
  }
}
