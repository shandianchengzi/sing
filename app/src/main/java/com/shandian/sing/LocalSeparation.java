package com.shandian.sing;

import android.content.Context;
import android.net.Uri;
import java.io.*;
import java.net.HttpURLConnection;
import org.tensorflow.lite.Interpreter;

/** Original Spleeter 2stems inference, on-device only. */
final class LocalSeparation {
  static final class Task {
    volatile boolean cancelled;
    volatile HttpURLConnection connection;
    volatile Thread thread;
    void cancel() {
      cancelled = true;
      if (connection != null) connection.disconnect();
      if (thread != null) thread.interrupt();
    }
    void check() throws InterruptedIOException {
      if (cancelled || Thread.currentThread().isInterrupted()) throw new InterruptedIOException("已取消本地分离");
    }
  }

  static File separate(Context context, Uri source, String title, Task task, SeparationClient.Progress progress)
      throws Exception {
    SpleeterModel.ensure(context, task, progress);
    File pcmFile = File.createTempFile("local-separation-", ".pcm", context.getCacheDir());
    File directory = new File(context.getFilesDir(), "accompaniments");
    if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("无法创建伴奏目录");
    File partial = File.createTempFile("spleeter-", ".part", directory);
    File songDirectory = new File(directory, partial.getName().replace(".part", ""));
    if (!LibraryNames.validTitle(title) || !songDirectory.mkdir()) {
      pcmFile.delete(); partial.delete(); throw new IOException("无法创建歌曲目录");
    }
    File result = new File(songDirectory, title + "-伴奏.wav");
    boolean success = false;
    try {
      progress.update("正在读取歌曲音轨…"); task.check();
      PcmAudio pcm = PcmAudio.decode(context, source, 0, -1, pcmFile);
      if (pcm.rate <= 0 || pcm.frames <= 0) throw new IOException("歌曲没有可处理的声音");
      long samples = (pcm.frames * SpleeterDsp.RATE + pcm.rate - 1) / pcm.rate;
      long outputBytes = samples * 4 + 44;
      if (outputBytes > 0xffffffffL || directory.getUsableSpace() < outputBytes + 32L * 1024 * 1024)
        throw new IOException("剩余存储空间不足，请清理后重试");
      task.check(); progress.update("正在加载原两轨模型…");
      Interpreter.Options options = new Interpreter.Options().setNumThreads(4).setUseXNNPACK(true);
      // Keep CPU FP32 baseline; do not silently enable FP16/GPU delegates.
      try (Interpreter model = new Interpreter(SpleeterModel.map(context), options);
           ResampledSource reader = new ResampledSource(pcm);
           OutputStream output = new BufferedOutputStream(new FileOutputStream(partial), 65536)) {
        int[] shape = model.getInputTensor(0).shape();
        if (!java.util.Arrays.equals(shape, new int[]{1,512,1024,2}) || model.getOutputTensorCount() != 1)
          throw new IOException("模型输入输出不兼容");
        WaveFile.header(output, samples, SpleeterDsp.RATE);
        SpleeterDsp.separate(samples, reader::read,
          (l,r) -> { write(output,l); write(output,r); },
          (in,out) -> { task.check(); model.run(in,out); task.check(); },
          (done,total) -> { task.check(); progress.update("手机本地提取伴奏 " + (done * 100 / total) + "%\n原 Spleeter 两轨模型 · 歌曲无需上传"); });
      }
      task.check();
      if (partial.length() != outputBytes || !partial.renameTo(result)) throw new IOException("伴奏保存校验失败");
      success = true; return result;
    } finally {
      pcmFile.delete(); partial.delete(); if (!success) { result.delete(); songDirectory.delete(); }
    }
  }
  static void write(OutputStream out, float value) throws IOException {
    if (!Float.isFinite(value)) throw new IOException("伴奏出现无效采样");
    int sample = Math.max(-32768, Math.min(32767, Math.round(value * 32768)));
    out.write(sample & 255); out.write((sample >> 8) & 255);
  }
  static final class ResampledSource implements AutoCloseable {
    final PcmAudio pcm; PcmAudio.Reader reader; long last = -1;
    final double[] stereo = new double[2];
    ResampledSource(PcmAudio pcm) { this.pcm=pcm; }
    void read(long frame, float[] out) throws Exception {
      if (reader == null || frame < last) {
        if (reader != null) reader.close();
        reader = new PcmAudio.Reader(pcm, frame, SpleeterDsp.RATE);
      }
      reader.sample(frame, SpleeterDsp.RATE, stereo); last=frame;
      out[0]=(float)(stereo[0]/32768); out[1]=(float)(stereo[1]/32768);
    }
    public void close() throws IOException { if (reader != null) reader.close(); }
  }
}
