#!/usr/bin/env python3
"""Compare original checkpoint waveform output with actual Java DSP + TFLite.

Includes silence and >512 STFT frames to verify continuous partition stitching.
The synthetic mixture is a numerical regression fixture, not an audio quality score.
"""
import os
os.environ.setdefault('TF_CPP_MIN_LOG_LEVEL','3')
os.environ.setdefault('TF_NUM_INTRAOP_THREADS','2')
os.environ.setdefault('TF_NUM_INTEROP_THREADS','1')
import argparse, json, subprocess, tempfile, time
from pathlib import Path
import numpy as np
import tensorflow as tf

def verify(checkpoint, model, output):
    root=Path(__file__).resolve().parents[2]
    subprocess.run(['javac','-d',str(root/'build/model-tests'),str(root/'app/src/main/java/com/shandian/sing/SpleeterDsp.java'),str(root/'tests/SpleeterDspBridge.java')],check=True)
    tf.compat.v1.disable_eager_execution()
    graph=tf.Graph()
    with graph.as_default():
        saver=tf.compat.v1.train.import_meta_graph(str(checkpoint)+'.meta',clear_devices=True)
    interpreter=tf.lite.Interpreter(model_path=str(model),num_threads=2,
        experimental_op_resolver_type=tf.lite.experimental.OpResolverType.BUILTIN_WITHOUT_DEFAULT_DELEGATES)
    interpreter.allocate_tensors()
    inp=interpreter.get_input_details()[0]; out=interpreter.get_output_details()[0]
    assert list(inp['shape'])==[1,512,1024,2] and inp['dtype']==np.float32
    reports=[]
    with tempfile.TemporaryDirectory() as temp, tf.compat.v1.Session(graph=graph) as session:
        saver.restore(session,str(checkpoint))
        work=Path(temp)
        # Crosses the 512-frame network partition boundary; stereo, finite tail.
        for name,duration in [('mixture',13.1),('silence',.3)]:
            n=int(44100*duration); t=np.arange(n)/44100
            audio=np.stack([.12*np.sin(2*np.pi*220*t)+.08*np.sin(2*np.pi*(700*t+40*t*t)),
                            .09*np.sin(2*np.pi*330*t)+.04*np.cos(2*np.pi*1900*t)],axis=1).astype('float32')
            if name=='silence': audio[:]=0
            source=work/'audio.bin'; source.write_bytes(audio.astype('<f4').tobytes())
            def java(mask):
                subprocess.run(['java','-Xmx512m','-cp',str(root/'build/model-tests'),'com.shandian.sing.SpleeterDspBridge',str(source),str(work/'magnitudes.bin'),mask,str(work/'wave.bin')],check=True)
            java('unity')
            magnitudes=np.fromfile(work/'magnitudes.bin',dtype='<f4').reshape(-1,1,512,1024,2)
            tick=time.monotonic()
            for i,mag in enumerate(magnitudes):
                interpreter.set_tensor(inp['index'],mag); interpreter.invoke()
                result=interpreter.get_tensor(out['index'])
                assert np.isfinite(result).all() and result.min()>=0 and result.max()<=1.001
                result.astype('<f4').tofile(str(work/'mask')+f'-{i}.bin')
            seconds=time.monotonic()-tick
            java(str(work/'mask'))
            actual=np.fromfile(work/'wave.bin',dtype='<f4').reshape(-1,2)
            # The shipped .meta predates Spleeter 2.4's 4096-sample boundary padding.
            # Apply the same padding/crop as the desktop 2.4 pipeline.
            padded=np.pad(audio,((4096,0),(0,0)))
            expected=session.run(graph.get_tensor_by_name('strided_slice_23:0'),
                feed_dict={graph.get_tensor_by_name('waveform:0'):padded})[4096:4096+n]
            assert actual.shape==expected.shape==audio.shape,(actual.shape,expected.shape)
            diff=actual-expected
            max_error=float(np.max(np.abs(diff))); rmse=float(np.sqrt(np.mean(diff*diff)))
            assert max_error<2e-4 and rmse<2e-5,(max_error,rmse)
            reports.append({'fixture':name,'samples':n,'partitions':len(magnitudes),'max_absolute_error':max_error,'rmse':rmse,'host_cpu_inference_seconds':seconds})
    output.write_text(json.dumps({'model_sha256':__import__('hashlib').sha256(model.read_bytes()).hexdigest(),'checks':reports},indent=2)+'\n')
    print(output.read_text())

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('checkpoint',type=Path);p.add_argument('model',type=Path);p.add_argument('output',type=Path)
    a=p.parse_args();verify(a.checkpoint,a.model,a.output)
