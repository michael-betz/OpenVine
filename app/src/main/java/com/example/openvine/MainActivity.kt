package com.example.openvine

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.*
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
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
import com.example.openvine.ui.ProgressRingView
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import android.provider.MediaStore // Make sure this import is added at the top
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class MainActivity : AppCompatActivity() {
    private val TAG = "OpenVine"

    private enum class CamState {
        STITCH, // All segments recorded, stitch them together
        IDLE,
        RECORDING,
        FINISHING, // User has requested stop, but we must wait for the last frame to be encoded

    }

    private var camState: CamState = CamState.IDLE

    private lateinit var textureView: TextureView
    private lateinit var recordButton: View
    private lateinit var switchButton: ImageButton
    private lateinit var progressRing: ProgressRingView
    private lateinit var galleryButton: ImageButton
    private var lastStitchedFile: File? = null

    // Camera2
    private var cameraDevice: android.hardware.camera2.CameraDevice? = null
    private var captureSession: android.hardware.camera2.CameraCaptureSession? = null
    private var currentCameraOrientation: Int = 0
    private var currentCameraId: String = ""
    private var rearCameraId: String = ""
    private var frontCameraId: String = ""

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
    private var segmentStartPtsUs: Long = 0L
    private var lastSegmentLengthUs: Long = 0L
    private var segmentLengthUs: Long = 0L
    private val segmentFiles = mutableListOf<String>()
    private var allRecordedTimeUs: Long = 0L
    private var maxDurationUs: Long = 6000000L

    // Encoding loop control
    private val encoderLoopRunning = AtomicBoolean(false)

    // Desired video params
    private val WIDTH = 1280
    private val HEIGHT = 720
    private val FPS = 60
    private val BITRATE = 16_000_000

    // Permissions
    private val permissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        val ok = perms.entries.all { it.value }
        if (ok) startEverything()
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

        val cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager

        for (id in cameraManager.cameraIdList) {
            val characteristics = cameraManager.getCameraCharacteristics(id)
            val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
            when (facing) {
                CameraCharacteristics.LENS_FACING_BACK -> rearCameraId = id
                CameraCharacteristics.LENS_FACING_FRONT -> frontCameraId = id
            }
        }

        // Rear default
        currentCameraId = rearCameraId

        // --- SWITCH BUTTON ---
        switchButton.setOnClickListener {
            currentCameraId = if (currentCameraId == rearCameraId) frontCameraId else rearCameraId
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
                // Verify that there is an app that can handle this intent
                Log.i(TAG, "intent: $intent")
                if (intent.resolveActivity(packageManager) != null) {
                    startActivity(intent)
                } else {
                    Toast.makeText(this, "No gallery app found to view videos", Toast.LENGTH_SHORT)
                        .show()
                }
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

        if (!allPermissionsGranted()) {
            permissionsLauncher.launch(arrayOf(Manifest.permission.CAMERA))
        }
    }

    override fun onResume() {
        super.onResume()
        // If the texture is already available, the listener won't be called again.
        // We need to restart the camera preview manually.
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
    }


    private fun allPermissionsGranted(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
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
        camState = CamState.IDLE
        Log.i(TAG, "camState: $camState. Starting encoderOutputLoop()")
        encoderLoopRunning.set(true)
        encoderExecutor.execute { encoderOutputLoop() }
    }

    private fun releaseEncoder() {
        // Stop the encoder loop and release all encoder-related resources.
        encoderLoopRunning.set(false)
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
                                        Log.d(TAG, "Segment first PTS: $segmentStartPtsUs")
                                    }
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

            Log.i(
                TAG,
                "Final length: ${segmentLengthUs / 1000} ms. Total recorded: ${allRecordedTimeUs / 1000} ms"
            )

            if (allRecordedTimeUs >= maxDurationUs) {
                camState = CamState.STITCH
                Log.i(TAG, "!!! Recording finished. Segments: $segmentFiles")
                val filesToStitch = ArrayList(segmentFiles)
                stitchingExecutor.execute {
                    val stitchedFile = stitchVideos(filesToStitch)
                    stitchedFile?.let {
                        lastStitchedFile = it
                        runOnUiThread {
                            galleryButton.visibility = View.VISIBLE
                            Toast.makeText(this, "${it.name}", Toast.LENGTH_SHORT).show()
                        }
                    }
                    segmentFiles.clear()
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

        try {
            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.CAMERA
                ) != PackageManager.PERMISSION_GRANTED
            ) return
            manager.openCamera(
                currentCameraId,
                object : android.hardware.camera2.CameraDevice.StateCallback() {
                    override fun onOpened(device: android.hardware.camera2.CameraDevice) {
                        cameraDevice = device
                        createCaptureSession()
                        Log.i(
                            TAG,
                            "Opened the cam $currentCameraId. Orientation: $currentCameraOrientation"
                        )
                    }

                    override fun onDisconnected(device: android.hardware.camera2.CameraDevice) {
                        device.close()
                        cameraDevice = null
                    }

                    override fun onError(
                        device: android.hardware.camera2.CameraDevice,
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
        val previewSurface = Surface(st)

        val encSurface = encoderSurface ?: return

        try {
            device.createCaptureSession(
                listOf(previewSurface, encSurface),
                object : android.hardware.camera2.CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: android.hardware.camera2.CameraCaptureSession) {
                        captureSession = session
                        try {
                            val request =
                                device.createCaptureRequest(android.hardware.camera2.CameraDevice.TEMPLATE_PREVIEW)
                            request.addTarget(previewSurface)
                            request.addTarget(encSurface)
                            request.set(
                                android.hardware.camera2.CaptureRequest.CONTROL_MODE,
                                android.hardware.camera2.CameraMetadata.CONTROL_MODE_AUTO
                            )
                            session.setRepeatingRequest(request.build(), null, null)
                        } catch (e: Exception) {
                            Log.e(TAG, "createCaptureSession -> setRepeatingRequest", e)
                        }
                    }

                    override fun onConfigureFailed(session: android.hardware.camera2.CameraCaptureSession) {
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
            segmentFiles.add(outFile.absolutePath)
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
    private fun stitchVideos(files: List<String>): File? {
        if (files.isEmpty()) {
            Log.w(TAG, "stitchVideos: No files to stitch")
            return null
        }

        // Output file name is the timestamp
        val fName =
            LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy_MM_dd_HH_mm_ss"))
        val outputFile = File(externalMediaDirs.first(), "$fName.mp4")
        var muxer: MediaMuxer? = null
        var totalDurationUs: Long = 0

        try {
            muxer =
                MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer.setOrientationHint(90)
            var videoTrackIndex = -1
            var firstFileFormat: MediaFormat? = null

            for (filePath in files) {
                val extractor = MediaExtractor()
                extractor.setDataSource(filePath)

                var trackIndex = -1
                for (i in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(i)
                    val mime = format.getString(MediaFormat.KEY_MIME)
                    if (mime?.startsWith("video/") == true) {
                        trackIndex = i
                        if (firstFileFormat == null) {
                            firstFileFormat = format
                            videoTrackIndex = muxer.addTrack(firstFileFormat)
                            muxer.start()
                        }
                        break
                    }
                }

                if (trackIndex == -1) {
                    Log.w(TAG, "No video track found in $filePath")
                    extractor.release()
                    continue
                }

                extractor.selectTrack(trackIndex)

                val buffer = java.nio.ByteBuffer.allocate(1024 * 1024)
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

                totalDurationUs += extractor.getTrackFormat(trackIndex)
                    .getLong(MediaFormat.KEY_DURATION)
                extractor.release()
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error during stitching", e)
            return null
        } finally {
            muxer?.stop()
            muxer?.release()
        }

        // --- Cleanup ---
        files.forEach {
            try {
                File(it).delete()
            } catch (e: Exception) {
                Log.e(TAG, "$e, could not delete $it")
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
