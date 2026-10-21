# Validation

`ANDROID_HOME=/path/to/sdk bash tests/run.sh` tests LRC parsing plus absolute-to-voice timeline mapping, clamping at recorded boundaries, PCM amplitude mixing, mono/stereo handling, 44.1→48 kHz interpolation, EOF silence, and WAV lengths/header fields.

Platform MediaRecorder / MediaPlayer / MediaCodec paths require an Android device. Before a stable release, check:

1. Scrub an MP3/OGG/MP4 accompaniment to 00:15, record ~5 s. Confirm its JSON sidecar starts near 15000 ms and links the correct source.
2. Replay; scrub to 00:17. Confirm voice seeks near 2 s while backing seeks near 17 s. Pause, scrub and continue; scrub repeatedly while seeking. Current replay is bounded to the recorded segment.
3. Select a recording made with another backing track and verify the original track is reopened. Move/delete the source and confirm a readable error with no recording deletion.
4. Export only vocals (M4A), mixed WAV and ZIP. WAV duration should equal the recorded segment; ZIP includes the original full source and alignment.txt. Try OGG/Opus/Vorbis and 44.1/48 kHz, mono/stereo.
5. Open a pre-sidecar recording and confirm it is not deleted; mixed export prompts to use the current backing at zero.
6. Cancel a long export and check controls recover and temporary PCM files are removed. Destination may contain a partial file; the UI reports this.
7. Cover-install a fixed-signature APK and confirm recordings, settings and sidecars remain.

JVM tests do not prove device decoder compatibility, recording latency or synchronization accuracy. Built-in players are synchronized after seeking, but this is not sample-accurate studio synchronization; wired headphones are recommended.

## In-app updater

UpdateTest checks upgrade/equal/downgrade comparisons, rejects non-HTTPS/wrong-repo/mismatched-version or hash URLs, malformed checksums and unreasonable sizes. release_metadata.py reads the actual APK package/version and SHA-256; publication must upload its version/hash-named APK before update.json.

Device checks still required: current-version check; older-version download with visible progress; cancellation; a corrupted APK/hash/signature rejection; denial and granting of per-source installation permission; installer cancellation and retry from the cached package; successful cover-install retaining recordings and settings. The provider must allow only read access to /update.apk through a temporary URI grant.
