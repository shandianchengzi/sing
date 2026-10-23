#!/usr/bin/env python3
import os
os.environ.setdefault('TF_CPP_MIN_LOG_LEVEL','3')
os.environ.setdefault('TF_NUM_INTRAOP_THREADS','2')
os.environ.setdefault('TF_NUM_INTEROP_THREADS','1')
import subprocess,wave
from pathlib import Path
import numpy as np
import tensorflow as tf
root=Path(__file__).resolve().parents[2]; work=root/'build/mobile-model'
assets=root/'build/android-test-assets';assets.mkdir(exist_ok=True)
t=np.arange(88200)/44100
samples=np.stack([.18*np.sin(2*np.pi*220*t)+.07*np.sin(2*np.pi*900*t),.14*np.sin(2*np.pi*330*t)+.06*np.cos(2*np.pi*1700*t)],axis=1)
pcm=np.clip(np.round(samples*32768),-32768,32767).astype('<i2')
with wave.open(str(assets/'input.wav'),'wb') as out:
    out.setparams((2,2,44100,0,'NONE','not compressed'));out.writeframes(pcm.tobytes())
for ext,encoder in [('mp3','libmp3lame'),('aac','aac'),('m4a','aac')]:
    subprocess.run(['ffmpeg','-y','-v','error','-i',str(assets/'input.wav'),'-c:a',encoder,'-b:a','192k',str(assets/('input.'+ext))],check=True)
tf.compat.v1.disable_eager_execution()
with tf.Graph().as_default(),tf.compat.v1.Session() as session:
    saver=tf.compat.v1.train.import_meta_graph(str(work/'model.meta'),clear_devices=True)
    saver.restore(session,str(work/'model'))
    reference=session.run(tf.compat.v1.get_default_graph().get_tensor_by_name('strided_slice_23:0'),
        feed_dict={'waveform:0':np.pad(pcm.astype('float32')/32768,((4096,0),(0,0)))})[4096:4096+len(pcm)]
    # Java Math.round chooses floor(x+0.5), not NumPy's ties-to-even.
    np.clip(np.floor(reference*32768+.5),-32768,32767).astype('<i2').tofile(assets/'expected.pcm')
print('Prepared WAV/MP3/AAC/M4A fixtures and original-checkpoint reference')
