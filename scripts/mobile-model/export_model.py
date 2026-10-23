#!/usr/bin/env python3
"""Freeze the original Spleeter 2stems weights as an FP32 ratio-mask model.

Only the STFT/iSTFT boundary moves to Android; no retraining or quantization.
Run with tensorflow-cpu==2.16.2. The v1.4.0 checkpoint is the one distributed
by vocal-separate v0.0.4. Output uses T=512, F=1024, stereo, 44,100 Hz.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
os.environ.setdefault('TF_CPP_MIN_LOG_LEVEL', '2')
os.environ.setdefault('TF_NUM_INTRAOP_THREADS', '2')
os.environ.setdefault('TF_NUM_INTEROP_THREADS', '1')
import tensorflow as tf


def export(checkpoint, output):
    tf.compat.v1.disable_eager_execution()
    graph = tf.Graph()
    with graph.as_default():
        magnitude = tf.compat.v1.placeholder(tf.float32, [1, 512, 1024, 2], name='magnitude')
        saver = tf.compat.v1.train.import_meta_graph(
            str(checkpoint) + '.meta', input_map={'strided_slice_3:0': magnitude}, clear_devices=True)
        vocals = graph.get_tensor_by_name('vocals_spectrogram/mul:0')
        backing = graph.get_tensor_by_name('accompaniment_spectrogram/mul:0')
        # Exact original Spleeter separation_exponent=2 ratio, epsilon=1e-10.
        mask = tf.identity((tf.square(backing) + 5e-11) /
                           (tf.square(vocals) + tf.square(backing) + 1e-10), name='accompaniment_mask')
        with tf.compat.v1.Session(graph=graph) as session:
            saver.restore(session, str(checkpoint))
            converter = tf.compat.v1.lite.TFLiteConverter.from_session(session, [magnitude], [mask])
            converter.target_spec.supported_ops = [tf.lite.OpsSet.TFLITE_BUILTINS]
            converter.optimizations = []
            model = converter.convert()
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_bytes(model)
    metadata = {'id': 'spleeter-2stems-fp32-v1', 'sha256': hashlib.sha256(model).hexdigest(),
                'bytes': len(model), 'sample_rate': 44100, 'frame_length': 4096,
                'frame_step': 1024, 'time_frames': 512, 'frequency_bins': 1024,
                'input_shape': [1, 512, 1024, 2], 'output': 'accompaniment_ratio_mask',
                'precision': 'float32', 'quantized': False,
                'checkpoint_sha256': {Path(str(checkpoint)+suffix).name:
                    hashlib.sha256(Path(str(checkpoint)+suffix).read_bytes()).hexdigest()
                    for suffix in ('.meta', '.index', '.data-00000-of-00001')}}
    output.with_suffix('.json').write_text(json.dumps(metadata, indent=2)+'\n')
    print(json.dumps(metadata, indent=2))

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('checkpoint', type=Path)
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    export(args.checkpoint, args.output)
