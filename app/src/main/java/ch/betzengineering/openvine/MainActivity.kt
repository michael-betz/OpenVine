package ch.betzengineering.openvine

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.display.DisplayManager
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaScannerConnection
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.util.Range
import android.view.Display
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.ImageButton
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import ch.betzengineering.openvine.ui.ProgressRingView
import java.io.File
import java.nio.ByteBuffer
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

class MainActivity : AppCompatActivity() {
    companion object {
        // Desired video params
        private const val WIDTH = 1280
        private const val HEIGHT = 720
        private const val FPS = 60
        private const val BITRATE = 16_000_000
        private const val AUDIO_BITRATE = 128_000

        // Samples of silence the AAC encoder puts in front of the audio.
        // Video is delayed by the same amount to keep audio and video in sync.
        private const val AAC_ENCODER_DELAY_SAMPLES = 2048

        private const val PREF_MIC_ASKED = "mic_permission_asked"
    }

    private val TAG = "OpenVine"

    private enum class CamState {
        STITCH, // All segments recorded, stitch them together
        IDLE,
        RECORDING,
        FINISHING, // User has requested stop, but we must wait for the last frame to be encoded
    }

    @Volatile
    private var camState: CamState = CamState.IDLE

    /** Detects, characterizes, and connects to a CameraDevice (used for all camera operations) */
    private val cameraManager: CameraManager by lazy {
        applicationContext.getSystemService(CAMERA_SERVICE) as CameraManager
    }

    /** [android.hardware.display.DisplayManager] to listen to display changes */
    private val displayManager: DisplayManager by lazy {
        applicationContext.getSystemService(DISPLAY_SERVICE) as DisplayManager
    }

    private lateinit var textureView: TextureView
    private lateinit var recordButton: View
    private lateinit var switchButton: ImageButton
    private lateinit var progressRing: ProgressRingView
    private lateinit var galleryButton: ImageButton
    @Volatile
    private var lastStitchedFile: File? = null

    // Camera2
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var currentCameraOrientation: Int = 0
    private var currentCameraId: String = ""
    private var allCameraIds: MutableList<String> = mutableListOf()
    private var currentCameraIndex: Int = 0

    // Encoder / muxer
    @Volatile
    private var encoder: MediaCodec? = null
    private var encoderSurface: Surface? = null
    private val encoderExecutor = Executors.newSingleThreadExecutor()
    private val stitchingExecutor = Executors.newSingleThreadExecutor()

    @Volatile
    private var encoderOutputFormat: MediaFormat? = null

    // Muxer related (per-segment)
    @Volatile
    private var muxer: MediaMuxer? = null
    private var muxerTrackIndex = -1
    @Volatile
    private var segmentStartPtsUs: Long = 0L

    // Frames written to the current segment, used to measure the actual frame duration
    private var segmentFrameCount = 0

    // Nominal frame duration of the currently selected FPS range
    @Volatile
    private var frameDurationUs: Long = 1_000_000L / FPS
    private var fpsRange: Range<Int>? = null

    // Offset to convert video PTS to the boottime clock used by AudioCapture
    private var ptsToBoottimeUs: Long = 0L
    private var lastSegmentLengthUs: Long = 0L
    @Volatile
    private var segmentLengthUs: Long = 0L
    @Volatile
    private var segmentFile: String? = null
    private val segments = mutableListOf<Segment>()
    @Volatile
    private var allRecordedTimeUs: Long = 0L
    private var maxDurationUs: Long = 6000000L

    /** A recorded piece of video plus the audio belonging to it */
    private class Segment(val path: String, val durationUs: Long, val audio: ShortArray?)

    // Audio
    private val audioCapture = AudioCapture()

    // Encoding loop control
    private val encoderLoopRunning = AtomicBoolean(false)



    /**
     * Every time the size of the TextureSize changes,
     * we calculate the scale and create a new session
     */
    private val surfaceTextureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
            createCaptureSession()
        }

        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
            createCaptureSession()
        }

        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = false
    }

    // Permissions
    private val permissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        val ok = cameraPermissionGranted()
        if (perms[Manifest.permission.RECORD_AUDIO] == false)
            Toast.makeText(this, "No microphone permission, recording without sound", Toast.LENGTH_SHORT).show()
        if (ok) {
            startAudio()
            startEverything()
        }
        else Toast.makeText(this, "Permissions required", Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        textureView = findViewById(R.id.viewFinder)
        recordButton = findViewById(R.id.video_capture_button)
        switchButton = findViewById(R.id.switch_camera_button)
        progressRing = findViewById(R.id.progressRing)
        galleryButton = findViewById(R.id.gallery_button)

        // Collect all cameras: rear cameras first, then front camera
        for (id in cameraManager.cameraIdList) {
            val characteristics = cameraManager.getCameraCharacteristics(id)
            val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
            if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                allCameraIds.add(id)
            }
        }
        for (id in cameraManager.cameraIdList) {
            val characteristics = cameraManager.getCameraCharacteristics(id)
            val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
            if (facing == CameraCharacteristics.LENS_FACING_FRONT) {
                allCameraIds.add(id)
            }
        }

        // Default to first camera (first rear camera)
        currentCameraIndex = 0
        currentCameraId = allCameraIds.firstOrNull() ?: ""

        // --- SWITCH CAMERA BUTTON ---
        // Cycles through all rear cameras, then front camera, then back to first rear
        switchButton.setOnClickListener {
            currentCameraIndex = (currentCameraIndex + 1) % allCameraIds.size
            currentCameraId = allCameraIds[currentCameraIndex]
            closeCamera()
            openCamera()
        }

        recordButton.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    startSegment()
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    stopSegment()
                    true
                }

                else -> false
            }
        }

        galleryButton.setOnClickListener {
            val intent: Intent
            if (lastStitchedFile == null) {
                Log.i(TAG, "Show all media files")
                intent = Intent(Intent.ACTION_VIEW, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            } else {
                val fileUri = FileProvider.getUriForFile(
                    this,
                    applicationContext.packageName + ".provider",
                    lastStitchedFile!!
                )
                Log.i(TAG, "Show $fileUri")
                // Show last recorded video directly
                intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(fileUri, "video/mp4")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
            // Verify that there is an app that can handle this intent
            Log.i(TAG, "intent: $intent")
            if (intent.resolveActivity(packageManager) != null) {
                startActivity(intent)
            } else {
                Toast.makeText(this, "No gallery app found to view videos", Toast.LENGTH_SHORT)
                    .show()
            }
        }

        // update progress ring continuously
        val handler = Handler(Looper.getMainLooper())
        handler.post(object : Runnable {
            override fun run() {
                progressRing.progress = allRecordedTimeUs.toFloat() / maxDurationUs
                handler.postDelayed(this, 16) // ~60fps
            }
        })

        // The camera is required, ask every time. The microphone is optional, ask only once
        // so users who prefer silent videos are not nagged.
        val prefs = getPreferences(MODE_PRIVATE)
        val toRequest = mutableListOf<String>()
        if (!cameraPermissionGranted()) toRequest.add(Manifest.permission.CAMERA)
        if (!micPermissionGranted() && !prefs.getBoolean(PREF_MIC_ASKED, false)) {
            toRequest.add(Manifest.permission.RECORD_AUDIO)
            prefs.edit().putBoolean(PREF_MIC_ASKED, true).apply()
        }
        if (toRequest.isNotEmpty()) permissionsLauncher.launch(toRequest.toTypedArray())
    }

    override fun onResume() {
        super.onResume()
        // If the texture is already available, the listener won't be called again.
        // We need to restart the camera preview manually.
        startAudio()
        if (textureView.isAvailable) {
            prepareEncoder()
            openCamera()
        } else {
            startEverything()
        }
    }

    override fun onPause() {
        super.onPause()
        // Release resources when the app is paused to allow other apps to use the camera
        // and to prevent resource leaks.
        closeCamera()
        releaseEncoder()
        audioCapture.stop()
    }


    private fun cameraPermissionGranted(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED

    private fun micPermissionGranted(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED

    private fun startAudio() {
        if (micPermissionGranted()) audioCapture.start()
    }

    private fun startEverything() {
        // Prepare encoder (pre-warm) and camera when texture is available
        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                prepareEncoder() // create encoder + input surface + start encoder output loop
                openCamera()
            }

            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                return true
            }

            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
    }

    // -----------------------
    // Encoder setup & loop
    // -----------------------
    private fun prepareEncoder() {
        if (encoder != null) return

        val format =
            MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, WIDTH, HEIGHT).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                )
                setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
                setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 0) // crucial: keyframe every frame
            }

        val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        enc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoderSurface = enc.createInputSurface()
        enc.start()
        encoder = enc

        // Start loop that drains encoder output continuously.
        // A stitch still running in the background sets IDLE itself when done.
        if (camState != CamState.STITCH) camState = CamState.IDLE
        Log.i(TAG, "camState: $camState. Starting encoderOutputLoop()")
        encoderLoopRunning.set(true)
        encoderExecutor.execute { encoderOutputLoop() }
    }

    private fun releaseEncoder() {
        // Stop the encoder loop, then finish a segment that is still being recorded.
        // Runs on the encoder thread, so it happens after the loop has exited.
        encoderLoopRunning.set(false)
        try {
            encoderExecutor.submit {
                if (muxer != null) {
                    Log.i(TAG, "Paused while recording, finalizing segment")
                    stopMuxerAndFinalizeSegment()
                }
            }.get(2, TimeUnit.SECONDS)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to finalize segment", e)
        }
        encoder?.let {
            try {
                it.stop()
                it.release()
            } catch (e: IllegalStateException) {
                Log.e(TAG, "Failed to stop/release encoder", e)
            }
        }
        encoder = null
        encoderSurface?.release()
        encoderSurface = null
    }

    private fun encoderOutputLoop() {
        val enc = encoder ?: return
        val bufferInfo = MediaCodec.BufferInfo()
        try {
            while (encoderLoopRunning.get()) {
                val timeout = if (camState == CamState.FINISHING) 500_000L else 10_000L
                val outIndex = enc.dequeueOutputBuffer(bufferInfo, timeout)
                when {
                    outIndex >= 0 -> {
                        val encoded = enc.getOutputBuffer(outIndex)
                        encoded?.let { buf ->
                            if (bufferInfo.size > 0) {
                                buf.position(bufferInfo.offset)
                                buf.limit(bufferInfo.offset + bufferInfo.size)

                                if (camState == CamState.RECORDING || camState == CamState.FINISHING) {
                                    if (segmentStartPtsUs == Long.MAX_VALUE) {
                                        segmentStartPtsUs = bufferInfo.presentationTimeUs
                                        allRecordedTimeUs += frameDurationUs
                                        ptsToBoottimeUs = ptsClockOffsetUs(segmentStartPtsUs)
                                        Log.d(TAG, "Segment first PTS: $segmentStartPtsUs")
                                    }
                                    segmentFrameCount++
                                    segmentLengthUs =
                                        bufferInfo.presentationTimeUs - segmentStartPtsUs
                                    allRecordedTimeUs += segmentLengthUs - lastSegmentLengthUs
                                    lastSegmentLengthUs = segmentLengthUs
                                    val newInfo = MediaCodec.BufferInfo().apply {
                                        offset = bufferInfo.offset
                                        size = bufferInfo.size
                                        flags = bufferInfo.flags
                                        presentationTimeUs = segmentLengthUs
                                    }
                                    muxer?.writeSampleData(muxerTrackIndex, buf, newInfo)
                                    Log.d(
                                        TAG,
                                        "Writing to muxer: size=${newInfo.size}, pts=${newInfo.presentationTimeUs}"
                                    )

                                    if (camState == CamState.FINISHING || allRecordedTimeUs >= maxDurationUs) {
                                        stopMuxerAndFinalizeSegment()
                                    }
                                }
                            }
                        }
                        enc.releaseOutputBuffer(outIndex, false)
                    }

                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        encoderOutputFormat = enc.outputFormat
                        Log.i(TAG, "Encoder output format changed: $encoderOutputFormat")
                    }

                    outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (camState == CamState.FINISHING) {
                            Log.w(
                                TAG,
                                "Timeout waiting for last frame. Finalizing segment anyway."
                            )
                            stopMuxerAndFinalizeSegment()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Encoder output loop error", e)
        }
    }

    /**
     * Video PTS should use the boottime clock (SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME), but some
     * devices deliver monotonic timestamps anyway, which differ by the time spent in deep sleep.
     * Detect the clock by checking which one the (fresh) frame timestamp is closer to.
     */
    private fun ptsClockOffsetUs(ptsUs: Long): Long {
        val bootUs = SystemClock.elapsedRealtimeNanos() / 1000
        val monoUs = System.nanoTime() / 1000
        val offset = if (abs(ptsUs - monoUs) < abs(ptsUs - bootUs)) bootUs - monoUs else 0L
        Log.d(TAG, "PTS clock: ${if (offset == 0L) "boottime" else "monotonic"}, offset=$offset us")
        return offset
    }

    private fun stopMuxerAndFinalizeSegment() {
        try {
            muxer?.stop()
            muxer?.release()
            Log.i(TAG, "Muxer stopped and released.")
        } catch (e: Exception) {
            Log.e(TAG, "Error finalizing muxer", e)
        } finally {
            muxer = null
            muxerTrackIndex = -1

            // Cut the matching audio. Duration includes the display time of the last frame.
            segmentFile?.let { path ->
                if (segmentStartPtsUs != Long.MAX_VALUE) {
                    val lastFrameUs =
                        if (segmentFrameCount > 1) segmentLengthUs / (segmentFrameCount - 1)
                        else frameDurationUs
                    val durationUs = segmentLengthUs + lastFrameUs
                    val audio =
                        if (audioCapture.isRunning) audioCapture.extract(segmentStartPtsUs + ptsToBoottimeUs, durationUs)
                        else null
                    segments.add(Segment(path, durationUs, audio))
                } else {
                    File(path).delete() // no frame was written
                }
            }
            segmentFile = null

            Log.i(
                TAG,
                "Final length: ${segmentLengthUs / 1000} ms. Total recorded: ${allRecordedTimeUs / 1000} ms"
            )

            if (allRecordedTimeUs >= maxDurationUs) {
                camState = CamState.STITCH
                Log.i(TAG, "!!! Recording finished. Segments: ${segments.size}")
                val toStitch = ArrayList(segments)
                segments.clear()
                stitchingExecutor.execute {
                    val stitchedFile = stitchVideos(toStitch)
                    stitchedFile?.let {
                        lastStitchedFile = it
                        runOnUiThread {
                            galleryButton.visibility = View.VISIBLE
                            Toast.makeText(this, "${it.name}", Toast.LENGTH_SHORT).show()
                        }
                    }
                    allRecordedTimeUs = 0L
                    camState = CamState.IDLE
                }
            } else {
                camState = CamState.IDLE
            }

            segmentStartPtsUs = 0L
            segmentLengthUs = 0L
            lastSegmentLengthUs = 0L
        }
    }

    // -----------------------
    // Camera2 handling
    // -----------------------
    private fun openCamera() {
        val manager = getSystemService(CAMERA_SERVICE) as CameraManager

        // Get orientaion
        val characteristics = manager.getCameraCharacteristics(currentCameraId)
        currentCameraOrientation =
            characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        currentCameraOrientation -= 90

        fpsRange = chooseFpsRange(characteristics)
        fpsRange?.let { frameDurationUs = 1_000_000L / it.upper }
        val sizeOk = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(MediaCodec::class.java)
            ?.any { it.width == WIDTH && it.height == HEIGHT } ?: false
        if (!sizeOk) Log.w(TAG, "Camera $currentCameraId does not support ${WIDTH}x$HEIGHT")
        Log.i(TAG, "Camera $currentCameraId FPS range: $fpsRange")

        try {
            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.CAMERA
                ) != PackageManager.PERMISSION_GRANTED
            ) return
            manager.openCamera(
                currentCameraId,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(device: CameraDevice) {
                        cameraDevice = device
                        createCaptureSession()
                        textureView.surfaceTextureListener = surfaceTextureListener
                        Log.i(
                            TAG,
                            "Opened the cam $currentCameraId. Orientation: $currentCameraOrientation"
                        )
                    }

                    override fun onDisconnected(device: CameraDevice) {
                        device.close()
                        cameraDevice = null
                    }

                    override fun onError(
                        device: CameraDevice,
                        error: Int
                    ) {
                        device.close()
                        cameraDevice = null
                    }
                },
                null
            )
        } catch (e: Exception) {
            Log.e(TAG, "openCamera", e)
        }
    }

    /** Prefers a fixed [FPS] range, then the range reaching closest to [FPS] */
    private fun chooseFpsRange(characteristics: CameraCharacteristics): Range<Int>? {
        val ranges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?: return null
        return ranges.filter { it.upper <= FPS }
            .maxWithOrNull(compareBy<Range<Int>> { it.upper }.thenBy { it.lower })
    }

    private fun closeCamera() {
        captureSession?.close()
        captureSession = null
        cameraDevice?.close()
        cameraDevice = null
    }


    private fun createCaptureSession() {
        val device = cameraDevice ?: return
        val st = textureView.surfaceTexture ?: return
        st.setDefaultBufferSize(WIDTH, HEIGHT)

        val transformedTexture = CameraUtils.buildTargetTexture(
            textureView, cameraManager.getCameraCharacteristics(currentCameraId),
            displayManager.getDisplay(Display.DEFAULT_DISPLAY).rotation
        )
        val previewSurface = Surface(transformedTexture)
        val encSurface = encoderSurface ?: return

        try {
            device.createCaptureSession(
                listOf(previewSurface, encSurface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        try {
                            val request =
                                device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                            request.addTarget(previewSurface)
                            request.addTarget(encSurface)
                            request.set(
                                CaptureRequest.CONTROL_MODE,
                                CameraMetadata.CONTROL_MODE_AUTO
                            )
                            fpsRange?.let {
                                request.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it)
                            }
                            session.setRepeatingRequest(request.build(), null, null)
                        } catch (e: Exception) {
                            Log.e(TAG, "createCaptureSession -> setRepeatingRequest", e)
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "capture session config failed")
                    }
                }, null
            )
        } catch (e: Exception) {
            Log.e(TAG, "createCaptureSession", e)
        }
    }

    // -----------------------
    // Segment control
    // -----------------------
    private fun startSegment() {
        if (camState != CamState.IDLE) return
        val format = encoderOutputFormat ?: run {
            Toast.makeText(this, "Encoder not ready", Toast.LENGTH_SHORT).show()
            return
        }

        val outFile =
            File(externalMediaDirs.first(), "segment_${System.currentTimeMillis()}.mp4")
        try {
            muxer =
                MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxerTrackIndex = muxer!!.addTrack(format)
            muxer!!.setOrientationHint(currentCameraOrientation)
            muxer!!.start()
            segmentFile = outFile.absolutePath
            segmentFrameCount = 0
            segmentStartPtsUs = Long.MAX_VALUE
            camState = CamState.RECORDING
            Log.i(TAG, "Segment started, writing to ${outFile.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start muxer", e)
            camState = CamState.IDLE
            muxer?.release(); muxer = null
        }
    }

    private fun stopSegment() {
        if (camState != CamState.RECORDING) return
        camState = CamState.FINISHING
    }


    @SuppressLint("WrongConstant")
    private fun stitchVideos(segs: List<Segment>): File? {
        if (segs.isEmpty()) {
            Log.w(TAG, "stitchVideos: No segments to stitch")
            return null
        }

        // Output file name is the timestamp
        val fName =
            LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy_MM_dd_HH_mm_ss"))
        val outputFile = File(externalMediaDirs.first(), "$fName.mp4")
        var muxer: MediaMuxer? = null
        var muxerStarted = false

        try {
            // Encode the audio of all segments in one go: no encoder priming at every cut
            val aac = if (segs.all { it.audio != null }) encodeAac(concatAudio(segs)) else null
            val videoOffsetUs =
                if (aac != null) AAC_ENCODER_DELAY_SAMPLES * 1_000_000L / AudioCapture.SAMPLE_RATE
                else 0L

            muxer =
                MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer.setOrientationHint(90)
            var videoTrackIndex = -1
            var audioTrackIndex = -1
            var totalDurationUs = videoOffsetUs

            for (seg in segs) {
                val extractor = MediaExtractor()
                extractor.setDataSource(seg.path)

                var trackIndex = -1
                for (i in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(i)
                    val mime = format.getString(MediaFormat.KEY_MIME)
                    if (mime?.startsWith("video/") == true) {
                        trackIndex = i
                        if (!muxerStarted) {
                            videoTrackIndex = muxer.addTrack(format)
                            if (aac != null) audioTrackIndex = muxer.addTrack(aac.first)
                            muxer.start()
                            muxerStarted = true
                        }
                        break
                    }
                }

                if (trackIndex == -1) {
                    Log.w(TAG, "No video track found in ${seg.path}")
                    extractor.release()
                    continue
                }

                extractor.selectTrack(trackIndex)

                val buffer = ByteBuffer.allocate(1024 * 1024)
                val bufferInfo = MediaCodec.BufferInfo()

                while (true) {
                    val sampleSize = extractor.readSampleData(buffer, 0)
                    if (sampleSize < 0) {
                        break
                    }

                    bufferInfo.size = sampleSize
                    bufferInfo.offset = 0
                    bufferInfo.presentationTimeUs = extractor.sampleTime + totalDurationUs
                    bufferInfo.flags = extractor.sampleFlags

                    muxer.writeSampleData(videoTrackIndex, buffer, bufferInfo)

                    extractor.advance()
                }

                // Same duration as used for cutting the audio, keeps both tracks in sync
                totalDurationUs += seg.durationUs
                extractor.release()
            }

            if (aac != null && audioTrackIndex >= 0) {
                val info = MediaCodec.BufferInfo()
                for ((ptsUs, data) in aac.second) {
                    info.set(0, data.size, ptsUs, MediaCodec.BUFFER_FLAG_KEY_FRAME)
                    muxer.writeSampleData(audioTrackIndex, ByteBuffer.wrap(data), info)
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error during stitching", e)
            return null
        } finally {
            if (muxerStarted) muxer?.stop()
            muxer?.release()
        }

        // --- Cleanup ---
        segs.forEach {
            try {
                File(it.path).delete()
            } catch (e: Exception) {
                Log.e(TAG, "$e, could not delete ${it.path}")
            }
        }

        // Notify the MediaStore that a new video has been created.
        // This makes it appear in the gallery immediately.
        MediaScannerConnection.scanFile(
            applicationContext,
            arrayOf(outputFile.absolutePath),
            arrayOf("video/mp4"),
            null
        )

        Log.i(TAG, "Stitching complete. Output: ${outputFile.absolutePath}")
        return outputFile
    }

    /** Joins the audio of all segments, with a short fade at every cut to avoid clicks */
    private fun concatAudio(segs: List<Segment>): ShortArray {
        val fadeLen = AudioCapture.SAMPLE_RATE * 3 / 1000 // 3 ms
        val out = ShortArray(segs.sumOf { it.audio!!.size })
        var pos = 0
        for ((index, seg) in segs.withIndex()) {
            val a = seg.audio!!
            val n = a.size
            val fade = minOf(fadeLen, n / 2)
            for (i in 0 until n) {
                var gain = 1f
                if (index > 0 && i < fade) gain = i.toFloat() / fade
                if (index < segs.size - 1 && i >= n - fade) gain = (n - 1 - i).toFloat() / fade
                out[pos + i] = (a[i] * gain).toInt().toShort()
            }
            pos += n
        }
        return out
    }

    /** Encodes mono 16-bit PCM to AAC-LC. Returns the track format and (PTS, data) packets */
    private fun encodeAac(pcm: ShortArray): Pair<MediaFormat, List<Pair<Long, ByteArray>>>? {
        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC, AudioCapture.SAMPLE_RATE, 1
        ).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BITRATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val packets = mutableListOf<Pair<Long, ByteArray>>()
        var outFormat: MediaFormat? = null
        try {
            enc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            enc.start()
            val info = MediaCodec.BufferInfo()
            var inPos = 0
            var inputDone = false
            while (true) {
                if (!inputDone) {
                    val inIndex = enc.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val buf = enc.getInputBuffer(inIndex)!!
                        buf.clear()
                        val n = minOf(pcm.size - inPos, buf.remaining() / 2)
                        val ptsUs = inPos * 1_000_000L / AudioCapture.SAMPLE_RATE
                        if (n <= 0) {
                            enc.queueInputBuffer(inIndex, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            buf.order(java.nio.ByteOrder.nativeOrder()).asShortBuffer().put(pcm, inPos, n)
                            enc.queueInputBuffer(inIndex, 0, n * 2, ptsUs, 0)
                            inPos += n
                        }
                    }
                }
                val outIndex = enc.dequeueOutputBuffer(info, 10_000)
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    outFormat = enc.outputFormat
                } else if (outIndex >= 0) {
                    if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        val buf = enc.getOutputBuffer(outIndex)!!
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        val data = ByteArray(info.size)
                        buf.get(data)
                        packets.add(info.presentationTimeUs to data)
                    }
                    enc.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "AAC encoding failed", e)
            return null
        } finally {
            runCatching { enc.stop() }
            enc.release()
        }
        Log.i(TAG, "AAC encoded ${pcm.size} samples into ${packets.size} packets")
        return outFormat?.let { it to packets }
    }

    // -----------------------
    // Lifecycle cleanup
    // -----------------------
    override fun onDestroy() {
        super.onDestroy()
        closeCamera()
        releaseEncoder()
        encoderExecutor.shutdownNow()
        stitchingExecutor.shutdownNow()
    }
}
