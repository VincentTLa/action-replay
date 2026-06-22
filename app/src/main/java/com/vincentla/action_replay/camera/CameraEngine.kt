package com.vincentla.action_replay.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.WindowManager
import androidx.core.content.ContextCompat
import java.io.File
import java.nio.ByteBuffer

private const val TAG = "CameraEngine"

// Fallback config; the real values are chosen per-device in selectCaptureConfig().
private const val VIDEO_W = 1280
private const val VIDEO_H = 720
private const val VIDEO_FPS = 30
private const val VIDEO_BITRATE = 5_000_000
private const val VIDEO_GOP_SEC = 1
// Cap for the device-best size: 4K is skipped to keep the encoder + ~8s rolling buffer sane.
private const val VIDEO_MAX_W = 1920
private const val VIDEO_MAX_H = 1080
// Tuned so 720p30 ≈ 5 Mbps; scales the bitrate with the chosen resolution.
private const val VIDEO_BITS_PER_PIXEL = 0.18
private const val VIDEO_MIME = MediaFormat.MIMETYPE_VIDEO_AVC

private const val AUDIO_MIME = MediaFormat.MIMETYPE_AUDIO_AAC
private const val AUDIO_SAMPLE_RATE = 44_100
private const val AUDIO_BITRATE = 128_000
private const val AUDIO_CHANNEL_COUNT = 1

private const val RING_BUFFER_SEC = 8L
private const val RING_BUFFER_US = RING_BUFFER_SEC * 1_000_000L

/**
 * Camera2 + MediaCodec pipeline. Continuously buffers ~8s of encoded video+audio,
 * optionally streams the same samples to a live MediaMuxer (Play→Stop session),
 * and can dump the trailing N seconds out to a separate MP4 (rewind clips).
 *
 * Single-threaded ownership: public methods are called from the main thread.
 * Internals use dedicated HandlerThreads for camera and codec callbacks.
 */
class CameraEngine(
    private val context: Context,
    private val listener: Listener,
) {

    interface Listener {
        fun onClipSaved(uri: Uri?, label: String)
        fun onSessionSaved(uri: Uri?)
        fun onError(message: String, cause: Throwable? = null)
        fun onBufferProgress(seconds: Float) {}
        fun onCaptureConfig(width: Int, height: Int, fps: Int) {}
    }

    private val ringBuffer = SampleRingBuffer(RING_BUFFER_US)

    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var videoCodecThread: HandlerThread? = null
    private var videoCodecHandler: Handler? = null

    private var cameraDevice: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var videoEncoder: MediaCodec? = null
    private var videoEncoderSurface: Surface? = null
    private var audioEncoder: MediaCodec? = null
    private var audioRecord: AudioRecord? = null
    @Volatile private var audioReading = false
    private var audioWorkerThread: Thread? = null

    @Volatile private var videoFormat: MediaFormat? = null
    @Volatile private var audioFormat: MediaFormat? = null
    @Volatile private var videoStartPts = Long.MIN_VALUE
    @Volatile private var audioStartPts = Long.MIN_VALUE

    private val sessionLock = Any()
    private var liveMuxer: MediaMuxer? = null
    private var liveMuxerFile: File? = null
    private var liveVideoTrack = -1
    private var liveAudioTrack = -1
    private var liveStarted = false
    private var liveWaitingForKeyframe = false
    private var pendingSessionStart = false

    private var sensorOrientation = 0
    private var cameraId: String? = null
    private var videoW = VIDEO_W
    private var videoH = VIDEO_H
    private var videoFps = VIDEO_FPS
    private var videoBitrate = VIDEO_BITRATE
    private var running = false

    // ---------------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------------

    fun start(previewSurface: Surface) {
        if (running) return
        if (!hasPermissions()) {
            listener.onError("Camera/audio permission missing")
            return
        }
        running = true
        ringBuffer.clear()
        videoStartPts = Long.MIN_VALUE
        audioStartPts = Long.MIN_VALUE
        try {
            startThreads()
            selectCaptureConfig()
            setupVideoEncoder()
            setupAudioEncoder()
            openCamera(previewSurface)
            setupAudioRecord()
            videoEncoder?.start()
            audioEncoder?.start()
            startAudioWorker()
        } catch (t: Throwable) {
            listener.onError("start failed: ${t.message}", t)
            stop()
        }
    }

    fun stop() {
        running = false
        val sessionWasActive: Boolean
        synchronized(sessionLock) {
            sessionWasActive = liveStarted || pendingSessionStart
            if (sessionWasActive) {
                finalizeLiveMuxerLocked(publish = false)
            }
        }
        audioReading = false
        try { session?.close() } catch (_: Throwable) {}
        try { cameraDevice?.close() } catch (_: Throwable) {}
        try { audioRecord?.stop() } catch (_: Throwable) {}
        audioWorkerThread?.join(500)
        audioWorkerThread = null
        try { audioRecord?.release() } catch (_: Throwable) {}
        try { videoEncoder?.stop() } catch (_: Throwable) {}
        try { videoEncoder?.release() } catch (_: Throwable) {}
        try { videoEncoderSurface?.release() } catch (_: Throwable) {}
        try { audioEncoder?.stop() } catch (_: Throwable) {}
        try { audioEncoder?.release() } catch (_: Throwable) {}

        session = null; cameraDevice = null; videoEncoder = null
        audioEncoder = null; audioRecord = null; videoEncoderSurface = null

        stopThreads()
        if (sessionWasActive) listener.onSessionSaved(null)
    }

    fun beginSession() {
        synchronized(sessionLock) {
            if (pendingSessionStart || liveStarted) return
            // Discard pre-Play footage so rewind only ever sees this session's frames.
            // ringBuffer has its own lock and the drain thread never holds sessionLock
            // while touching it, so this sessionLock→ringBuffer order can't deadlock.
            ringBuffer.clear()
            val outFile = File(context.cacheDir, "session_${System.currentTimeMillis()}.mp4")
            liveMuxerFile = outFile
            liveMuxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            liveMuxer?.setOrientationHint(orientationHint())
            liveVideoTrack = -1
            liveAudioTrack = -1
            liveStarted = false
            liveWaitingForKeyframe = true
            pendingSessionStart = true
        }
        requestKeyframe()
        maybeStartLiveMuxer()
    }

    fun endSession() {
        var publishedFile: File? = null
        synchronized(sessionLock) {
            if (!pendingSessionStart && !liveStarted) return
            publishedFile = liveMuxerFile
            finalizeLiveMuxerLocked(publish = true)
        }
        publishedFile?.let { file ->
            Thread {
                val uri = ClipSaver.publish(context, file, "session")
                listener.onSessionSaved(uri)
            }.start()
        }
    }

    fun rewindAndSave(seconds: Int) {
        val label = "rewind${seconds}s"
        val rewindUs = seconds * 1_000_000L
        Thread {
            try {
                val samples = ringBuffer.snapshotLast(rewindUs)
                if (samples.isNullOrEmpty()) {
                    listener.onClipSaved(null, label)
                    return@Thread
                }
                val file = writeClip(samples) ?: run {
                    listener.onClipSaved(null, label)
                    return@Thread
                }
                val uri = ClipSaver.publish(context, file, label)
                listener.onClipSaved(uri, label)
            } catch (t: Throwable) {
                Log.e(TAG, "rewindAndSave failed", t)
                listener.onError("Rewind failed: ${t.message}", t)
            }
        }.start()
    }

    fun bufferedSeconds(): Float = ringBuffer.bufferedUs() / 1_000_000f

    // ---------------------------------------------------------------------
    // Setup
    // ---------------------------------------------------------------------

    private fun hasPermissions(): Boolean {
        val cam = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
        val mic = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
        return cam == PackageManager.PERMISSION_GRANTED && mic == PackageManager.PERMISSION_GRANTED
    }

    private fun startThreads() {
        cameraThread = HandlerThread("cam").also { it.start() }
        cameraHandler = Handler(cameraThread!!.looper)
        videoCodecThread = HandlerThread("vcodec").also { it.start() }
        videoCodecHandler = Handler(videoCodecThread!!.looper)
    }

    private fun stopThreads() {
        cameraThread?.quitSafely(); cameraThread = null; cameraHandler = null
        videoCodecThread?.quitSafely(); videoCodecThread = null; videoCodecHandler = null
    }

    // Reads the back camera's capabilities and picks the largest 16:9 encoder size within the cap,
    // scaling bitrate to match. Must run BEFORE setupVideoEncoder() (encoder needs the chosen size)
    // and before openCamera() (which reuses the resolved cameraId). Throws if there's no back camera.
    private fun selectCaptureConfig() {
        val mgr = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val backId = mgr.cameraIdList.firstOrNull { id ->
            mgr.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: throw IllegalStateException("No back camera")
        cameraId = backId

        val chars = mgr.getCameraCharacteristics(backId)
        sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90

        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val best = map?.getOutputSizes(MediaCodec::class.java)
            ?.filter { it.width <= VIDEO_MAX_W && it.height <= VIDEO_MAX_H && it.width * 9 == it.height * 16 }
            ?.maxByOrNull { it.width.toLong() * it.height }
            ?: Size(VIDEO_W, VIDEO_H)
        videoW = best.width
        videoH = best.height
        videoFps = VIDEO_FPS   // ponytail: 30 only — >30 needs a constrained high-speed session (separate effort)
        videoBitrate = (videoW.toLong() * videoH * videoFps * VIDEO_BITS_PER_PIXEL).toInt().coerceAtLeast(2_000_000)
        listener.onCaptureConfig(videoW, videoH, videoFps)
    }

    private fun setupVideoEncoder() {
        val format = MediaFormat.createVideoFormat(VIDEO_MIME, videoW, videoH).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, videoBitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, videoFps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, VIDEO_GOP_SEC)
        }
        val codec = MediaCodec.createEncoderByType(VIDEO_MIME)
        codec.setCallback(VideoCodecCallback(), videoCodecHandler)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        videoEncoderSurface = codec.createInputSurface()
        videoEncoder = codec
    }

    private fun setupAudioEncoder() {
        val format = MediaFormat.createAudioFormat(AUDIO_MIME, AUDIO_SAMPLE_RATE, AUDIO_CHANNEL_COUNT).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BITRATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
        }
        // Sync mode — feed input AND drain output from the audio worker thread.
        // ponytail: mixing async setCallback with sync dequeue* throws ISE, hence sync here.
        val codec = MediaCodec.createEncoderByType(AUDIO_MIME)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        audioEncoder = codec
    }

    @SuppressLint("MissingPermission")
    private fun setupAudioRecord() {
        val minBuf = AudioRecord.getMinBufferSize(
            AUDIO_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(8192)
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.CAMCORDER,
            AUDIO_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBuf * 2
        )
        audioRecord?.startRecording()
    }

    private fun startAudioWorker() {
        val recorder = audioRecord ?: return
        val codec = audioEncoder ?: return
        audioReading = true
        Thread {
            val pcm = ByteArray(4096)
            val info = MediaCodec.BufferInfo()
            while (audioReading) {
                // ---- input: read PCM and feed the encoder ----
                val read = recorder.read(pcm, 0, pcm.size)
                if (read > 0) {
                    val inputIdx = try { codec.dequeueInputBuffer(10_000) }
                    catch (_: IllegalStateException) { -1 }
                    if (inputIdx >= 0) {
                        val inputBuf = try { codec.getInputBuffer(inputIdx) }
                        catch (_: IllegalStateException) { null }
                        if (inputBuf != null) {
                            inputBuf.clear()
                            inputBuf.put(pcm, 0, read)
                            val ptsUs = SystemClock.elapsedRealtimeNanos() / 1000
                            try {
                                codec.queueInputBuffer(inputIdx, 0, read, ptsUs, 0)
                            } catch (_: IllegalStateException) { /* shutting down */ }
                        }
                    }
                }
                // ---- output: drain any ready encoded buffers ----
                while (audioReading) {
                    val outIdx = try { codec.dequeueOutputBuffer(info, 0) }
                    catch (_: IllegalStateException) { break }
                    when {
                        outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            audioFormat = codec.outputFormat
                            maybeStartLiveMuxer()
                        }
                        outIdx < 0 -> break
                        else -> handleEncodedOutput(codec, outIdx, info, Track.AUDIO)
                    }
                }
            }
        }.also { it.name = "audio-worker"; audioWorkerThread = it }.start()
    }

    @SuppressLint("MissingPermission")
    private fun openCamera(previewSurface: Surface) {
        val mgr = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val backId = cameraId ?: throw IllegalStateException("No back camera")

        mgr.openCamera(backId, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) {
                cameraDevice = device
                createSession(device, previewSurface)
            }

            override fun onDisconnected(device: CameraDevice) {
                device.close(); cameraDevice = null
            }

            override fun onError(device: CameraDevice, error: Int) {
                device.close(); cameraDevice = null
                listener.onError("Camera onError: $error")
            }
        }, cameraHandler)
    }

    private fun createSession(device: CameraDevice, previewSurface: Surface) {
        val encSurface = videoEncoderSurface ?: return
        val targets = listOf(previewSurface, encSurface)
        device.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) {
                session = s
                val req = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                    addTarget(previewSurface)
                    addTarget(encSurface)
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                    set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, android.util.Range(videoFps, videoFps))
                }
                s.setRepeatingRequest(req.build(), null, cameraHandler)
            }

            override fun onConfigureFailed(s: CameraCaptureSession) {
                listener.onError("CameraCaptureSession configure failed")
            }
        }, cameraHandler)
    }

    private fun deviceRotationDegrees(): Int {
        val rotation = if (Build.VERSION.SDK_INT >= 30) {
            context.display?.rotation
        } else {
            @Suppress("DEPRECATION")
            (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation
        }
        return when (rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
    }

    private fun orientationHint(): Int {
        // Back camera. Generalised from the original landscape-locked case (rotation 90 → sensor-90):
        // rotate the saved file upright for whichever landscape the device was held in at capture.
        // Orientation is locked while recording, so this rotation stays stable for the whole file.
        return ((sensorOrientation - deviceRotationDegrees()) + 360) % 360
    }

    // ---------------------------------------------------------------------
    // Codec callbacks
    // ---------------------------------------------------------------------

    private inner class VideoCodecCallback : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
            // Surface input — never called.
        }

        override fun onOutputBufferAvailable(
            codec: MediaCodec,
            index: Int,
            info: MediaCodec.BufferInfo
        ) {
            handleEncodedOutput(codec, index, info, Track.VIDEO)
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            videoFormat = format
            maybeStartLiveMuxer()
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            listener.onError("video codec error: ${e.message}", e)
        }
    }

    private fun handleEncodedOutput(
        codec: MediaCodec,
        index: Int,
        info: MediaCodec.BufferInfo,
        track: Track,
    ) {
        try {
            val outBuf: ByteBuffer = codec.getOutputBuffer(index) ?: run {
                codec.releaseOutputBuffer(index, false); return
            }
            // CSD chunks are part of the MediaFormat already; skip them here.
            if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0 || info.size <= 0) {
                codec.releaseOutputBuffer(index, false); return
            }
            outBuf.position(info.offset)
            outBuf.limit(info.offset + info.size)
            val data = ByteArray(info.size)
            outBuf.get(data)

            val rawPts = info.presentationTimeUs

            // Track first PTS of each track to compute the cross-clock offset.
            if (track == Track.VIDEO && videoStartPts == Long.MIN_VALUE) videoStartPts = rawPts
            if (track == Track.AUDIO && audioStartPts == Long.MIN_VALUE) audioStartPts = rawPts

            // Normalize audio into the video time domain. Camera sensor timestamps and
            // SystemClock.elapsedRealtimeNanos() can differ by the full device uptime (hours).
            // Shifting audio by the constant epoch difference keeps the ring buffer and muxer
            // on a single monotonic clock so trim(), snapshotLast(), and writeClip() all agree.
            val ptsUs = if (track == Track.AUDIO) {
                val vs = videoStartPts
                val as_ = audioStartPts
                if (vs == Long.MIN_VALUE || as_ == Long.MIN_VALUE) {
                    // Offset not yet established; drop this early audio sample.
                    codec.releaseOutputBuffer(index, false)
                    return
                }
                rawPts - (as_ - vs)
            } else {
                rawPts
            }

            val sample = Sample(track, data, ptsUs, info.flags)
            ringBuffer.add(sample)
            if (track == Track.VIDEO) {
                listener.onBufferProgress(ringBuffer.bufferedUs() / 1_000_000f)
            }
            writeToLiveMuxer(sample)
            codec.releaseOutputBuffer(index, false)
        } catch (t: Throwable) {
            Log.w(TAG, "drain failed", t)
            try { codec.releaseOutputBuffer(index, false) } catch (_: Throwable) {}
        }
    }

    // ---------------------------------------------------------------------
    // Live muxer (Play → Stop session)
    // ---------------------------------------------------------------------

    private fun maybeStartLiveMuxer() {
        synchronized(sessionLock) {
            val vf = videoFormat ?: return
            val af = audioFormat ?: return
            val muxer = liveMuxer ?: return
            if (!pendingSessionStart || liveStarted) return
            liveVideoTrack = muxer.addTrack(vf)
            liveAudioTrack = muxer.addTrack(af)
            muxer.start()
            liveStarted = true
            pendingSessionStart = false
        }
    }

    private fun writeToLiveMuxer(sample: Sample) {
        synchronized(sessionLock) {
            val muxer = liveMuxer ?: return
            if (!liveStarted) return
            if (liveWaitingForKeyframe) {
                if (sample.track != Track.VIDEO || !sample.isKeyframe) return
                liveWaitingForKeyframe = false
            }
            val trackIdx = if (sample.track == Track.VIDEO) liveVideoTrack else liveAudioTrack
            val info = MediaCodec.BufferInfo().apply {
                set(0, sample.data.size, sample.ptsUs, sample.flags)
            }
            val buf = ByteBuffer.wrap(sample.data)
            try {
                muxer.writeSampleData(trackIdx, buf, info)
            } catch (t: Throwable) {
                Log.w(TAG, "muxer write failed", t)
            }
        }
    }

    private fun finalizeLiveMuxerLocked(publish: Boolean) {
        val muxer = liveMuxer
        val file = liveMuxerFile
        liveMuxer = null
        liveMuxerFile = null
        liveStarted = false
        pendingSessionStart = false
        liveWaitingForKeyframe = false
        if (muxer != null) {
            try { muxer.stop() } catch (_: Throwable) {}
            try { muxer.release() } catch (_: Throwable) {}
        }
        if (!publish) {
            file?.delete()
        }
    }

    private fun requestKeyframe() {
        try {
            videoEncoder?.setParameters(Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            })
        } catch (_: Throwable) {}
    }

    // ---------------------------------------------------------------------
    // Clip writer (rewind 3s / 5s)
    // ---------------------------------------------------------------------

    private fun writeClip(samples: List<Sample>): File? {
        val vf = videoFormat ?: return null
        val af = audioFormat ?: return null
        val file = File(context.cacheDir, "clip_${System.currentTimeMillis()}.mp4")
        val muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        muxer.setOrientationHint(orientationHint())
        val vTrack = muxer.addTrack(vf)
        val aTrack = muxer.addTrack(af)
        muxer.start()
        val basePts = samples.first { it.track == Track.VIDEO }.ptsUs
        for (s in samples) {
            val rebased = s.ptsUs - basePts
            if (rebased < 0) continue   // ponytail: audio clock skew vs video PTS; skip pre-anchor samples rather than clamping to 0 (avoids non-monotonic muxer writes)
            val info = MediaCodec.BufferInfo().apply {
                set(0, s.data.size, rebased, s.flags)
            }
            val buf = ByteBuffer.wrap(s.data)
            try {
                muxer.writeSampleData(if (s.track == Track.VIDEO) vTrack else aTrack, buf, info)
            } catch (t: Throwable) {
                Log.w(TAG, "clip muxer write failed", t)
            }
        }
        var muxerStopped = false
        try { muxer.stop(); muxerStopped = true } catch (t: Throwable) { Log.w(TAG, "clip muxer stop failed", t) }
        try { muxer.release() } catch (_: Throwable) {}
        if (!muxerStopped) { file.delete(); return null }
        return file
    }
}
