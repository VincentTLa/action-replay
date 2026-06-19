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
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import androidx.core.content.ContextCompat
import java.io.File
import java.nio.ByteBuffer

private const val TAG = "CameraEngine"

private const val VIDEO_W = 1280
private const val VIDEO_H = 720
private const val VIDEO_FPS = 30
private const val VIDEO_BITRATE = 5_000_000
private const val VIDEO_GOP_SEC = 1
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
    }

    private val ringBuffer = SampleRingBuffer(RING_BUFFER_US)

    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var videoCodecThread: HandlerThread? = null
    private var videoCodecHandler: Handler? = null
    private var audioCodecThread: HandlerThread? = null
    private var audioCodecHandler: Handler? = null
    private var audioReaderThread: HandlerThread? = null

    private var cameraDevice: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var videoEncoder: MediaCodec? = null
    private var videoEncoderSurface: Surface? = null
    private var audioEncoder: MediaCodec? = null
    private var audioRecord: AudioRecord? = null
    private var audioReading = false

    private var videoFormat: MediaFormat? = null
    private var audioFormat: MediaFormat? = null

    private val sessionLock = Any()
    private var liveMuxer: MediaMuxer? = null
    private var liveMuxerFile: File? = null
    private var liveVideoTrack = -1
    private var liveAudioTrack = -1
    private var liveStarted = false
    private var liveWaitingForKeyframe = false
    private var pendingSessionStart = false

    private var sensorOrientation = 0
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
        try {
            startThreads()
            setupVideoEncoder()
            setupAudioEncoder()
            openCamera(previewSurface)
            setupAudioRecord()
            videoEncoder?.start()
            audioEncoder?.start()
            startAudioReader()
        } catch (t: Throwable) {
            listener.onError("start failed: ${t.message}", t)
            stop()
        }
    }

    fun stop() {
        running = false
        // Finalize a live session if active.
        synchronized(sessionLock) {
            if (liveStarted || pendingSessionStart) {
                finalizeLiveMuxerLocked(publish = false)
            }
        }
        audioReading = false
        try { session?.close() } catch (_: Throwable) {}
        try { cameraDevice?.close() } catch (_: Throwable) {}
        try { audioRecord?.stop() } catch (_: Throwable) {}
        try { audioRecord?.release() } catch (_: Throwable) {}
        try { videoEncoder?.stop() } catch (_: Throwable) {}
        try { videoEncoder?.release() } catch (_: Throwable) {}
        try { videoEncoderSurface?.release() } catch (_: Throwable) {}
        try { audioEncoder?.stop() } catch (_: Throwable) {}
        try { audioEncoder?.release() } catch (_: Throwable) {}

        session = null; cameraDevice = null; videoEncoder = null
        audioEncoder = null; audioRecord = null; videoEncoderSurface = null

        stopThreads()
    }

    fun beginSession() {
        synchronized(sessionLock) {
            if (pendingSessionStart || liveStarted) return
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
        audioCodecThread = HandlerThread("acodec").also { it.start() }
        audioCodecHandler = Handler(audioCodecThread!!.looper)
        audioReaderThread = HandlerThread("aread").also { it.start() }
    }

    private fun stopThreads() {
        cameraThread?.quitSafely(); cameraThread = null; cameraHandler = null
        videoCodecThread?.quitSafely(); videoCodecThread = null; videoCodecHandler = null
        audioCodecThread?.quitSafely(); audioCodecThread = null; audioCodecHandler = null
        audioReaderThread?.quitSafely(); audioReaderThread = null
    }

    private fun setupVideoEncoder() {
        val format = MediaFormat.createVideoFormat(VIDEO_MIME, VIDEO_W, VIDEO_H).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, VIDEO_BITRATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, VIDEO_FPS)
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
        val codec = MediaCodec.createEncoderByType(AUDIO_MIME)
        codec.setCallback(AudioCodecCallback(), audioCodecHandler)
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

    private fun startAudioReader() {
        val recorder = audioRecord ?: return
        val codec = audioEncoder ?: return
        audioReading = true
        Thread {
            val buf = ByteArray(4096)
            while (audioReading) {
                val read = recorder.read(buf, 0, buf.size)
                if (read <= 0) continue
                // Feed the codec until it accepts our bytes; ponytail: dropping is fine on overflow.
                val inputIdx = try {
                    codec.dequeueInputBuffer(10_000)
                } catch (_: IllegalStateException) {
                    break
                }
                if (inputIdx < 0) continue
                val inputBuf = try {
                    codec.getInputBuffer(inputIdx)
                } catch (_: IllegalStateException) {
                    break
                } ?: continue
                inputBuf.clear()
                inputBuf.put(buf, 0, read)
                val ptsUs = SystemClock.elapsedRealtimeNanos() / 1000
                try {
                    codec.queueInputBuffer(inputIdx, 0, read, ptsUs, 0)
                } catch (_: IllegalStateException) {
                    break
                }
            }
        }.also { it.name = "audio-reader" }.start()
    }

    @SuppressLint("MissingPermission")
    private fun openCamera(previewSurface: Surface) {
        val mgr = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val backId = mgr.cameraIdList.firstOrNull { id ->
            mgr.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: throw IllegalStateException("No back camera")
        sensorOrientation = mgr.getCameraCharacteristics(backId)
            .get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90

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
                    set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, android.util.Range(VIDEO_FPS, VIDEO_FPS))
                }
                s.setRepeatingRequest(req.build(), null, cameraHandler)
            }

            override fun onConfigureFailed(s: CameraCaptureSession) {
                listener.onError("CameraCaptureSession configure failed")
            }
        }, cameraHandler)
    }

    private fun orientationHint(): Int {
        // Landscape-locked activity + back camera. Sensor is usually 90° on most
        // devices, giving an upright frame in landscape — hint 0. For an inverted
        // landscape device, the user can rotate in post.
        return ((sensorOrientation - 90) + 360) % 360
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

    private inner class AudioCodecCallback : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
            // Input is hand-fed by the audio-reader thread via dequeueInputBuffer.
        }

        override fun onOutputBufferAvailable(
            codec: MediaCodec,
            index: Int,
            info: MediaCodec.BufferInfo
        ) {
            handleEncodedOutput(codec, index, info, Track.AUDIO)
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            audioFormat = format
            maybeStartLiveMuxer()
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            listener.onError("audio codec error: ${e.message}", e)
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

            val sample = Sample(track, data, info.presentationTimeUs, info.flags)
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
            val rebased = (s.ptsUs - basePts).coerceAtLeast(0L)
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
        try { muxer.stop() } catch (_: Throwable) {}
        try { muxer.release() } catch (_: Throwable) {}
        return file
    }
}
