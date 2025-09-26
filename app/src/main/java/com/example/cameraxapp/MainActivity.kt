package com.example.cameraxapp

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.*
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity() {
    private val TAG = "OpenVine"

    private enum class CamState {
        IDLE,
        RECORDING_SEGMENT,
        WAITING_FOR_NEXT_RECORDING,
        ALL_SEGMENTS_RECORDED
    }

    private var camState: CamState = CamState.IDLE

    private lateinit var textureView: TextureView
    private lateinit var recordButton: View
    private lateinit var switchButton: Button
    // Camera2
    private var cameraDevice: android.hardware.camera2.CameraDevice? = null
    private var captureSession: android.hardware.camera2.CameraCaptureSession? = null
    private var currentCameraId: String = ""
    private var rearCameraId: String = ""
    private var frontCameraId: String = ""

    // Encoder / muxer
    @Volatile private var encoder: MediaCodec? = null
    private var encoderSurface: Surface? = null
    private val encoderExecutor = Executors.newSingleThreadExecutor()
    @Volatile private var encoderOutputFormat: MediaFormat? = null

    // Muxer related (per-segment)
    @Volatile private var muxer: MediaMuxer? = null
    private var muxerTrackIndex = -1
    @Volatile private var muxerStarted = false
    private var segmentStartPtsUs: Long = 0L
    private val writingSegment = AtomicBoolean(false)
    private val segmentFiles = mutableListOf<String>()
    private var allRecordedTime: Long = 0L
    private var maxDuration: Long = 6000L
    
    // Encoding loop control
    private val encoderLoopRunning = AtomicBoolean(false)

    // Desired video params
    private val WIDTH = 1280
    private val HEIGHT = 720
    private val FPS = 30
    private val BITRATE = 4_000_000

    // Permissions
    private val permissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        val ok = perms.entries.all { it.value }
        if (ok) startEverything()
        else Toast.makeText(this, "Permissions required", Toast.LENGTH_SHORT).show()
    }

    @SuppressLint("ServiceCast")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        textureView = findViewById(R.id.viewFinder)
        recordButton = findViewById(R.id.video_capture_button)
        switchButton = findViewById(R.id.switch_camera_button)

        var cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager

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

        if (!allPermissionsGranted()) {
            permissionsLauncher.launch(arrayOf(Manifest.permission.CAMERA))
        } else {
            startEverything()
        }
    }

    private fun allPermissionsGranted(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }

    private fun startEverything() {
        // Prepare encoder (pre-warm) and camera when texture is available
        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                openCameraAndStartEncoder()
            }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean { return true }
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
        if (textureView.isAvailable) openCameraAndStartEncoder()
    }

    private fun openCameraAndStartEncoder() {
        prepareEncoder() // create encoder + input surface + start encoder output loop
        openCamera()
    }

    // -----------------------
    // Encoder setup & loop
    // -----------------------
    private fun prepareEncoder() {
        if (encoder != null) return

        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, WIDTH, HEIGHT).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
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
        encoderLoopRunning.set(true)
        encoderExecutor.execute { encoderOutputLoop() }
    }

    private fun encoderOutputLoop() {
        val enc = encoder ?: return
        val bufferInfo = MediaCodec.BufferInfo()
        try {
            while (encoderLoopRunning.get()) {
                val outIndex = enc.dequeueOutputBuffer(bufferInfo, 10_000)
                when {
                    outIndex >= 0 -> {
                        val encoded = enc.getOutputBuffer(outIndex)
                        encoded?.let { buf ->
                            if (bufferInfo.size > 0) {
                                buf.position(bufferInfo.offset)
                                buf.limit(bufferInfo.offset + bufferInfo.size)

                                // If a segment is active, write to the current muxer.
                                if (writingSegment.get() && muxerStarted) {
                                    // For each segment we subtract the first PTS so segment starts at 0.
                                    val adjustedPts = bufferInfo.presentationTimeUs - segmentStartPtsUs
                                    val newInfo = MediaCodec.BufferInfo().apply {
                                        offset = 0
                                        size = bufferInfo.size
                                        flags = bufferInfo.flags
                                        presentationTimeUs = adjustedPts
                                    }
                                    muxer?.writeSampleData(muxerTrackIndex, buf, newInfo)
                                }
                            }
                        }
                        enc.releaseOutputBuffer(outIndex, false)

                        // handle EOS written to muxer: when buffer has EOS flag while writingSegment was true,
                        // we will stop muxer on stopSegment() instead of here.
                    }
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val newFormat = enc.outputFormat
                        encoderOutputFormat = newFormat // cache so we can create muxer when segment starts
                        Log.i(TAG, "Encoder output format changed: $newFormat")
                    }
                    outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        // no output yet
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Encoder output loop error", e)
        }
    }

    // -----------------------
    // Camera2 handling
    // -----------------------
    private fun openCamera() {
        val manager = getSystemService(android.content.Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
        try {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
            manager.openCamera(currentCameraId, object : android.hardware.camera2.CameraDevice.StateCallback() {
                override fun onOpened(device: android.hardware.camera2.CameraDevice) {
                    cameraDevice = device
                    createCaptureSession()
                }
                override fun onDisconnected(device: android.hardware.camera2.CameraDevice) {
                    device.close()
                    cameraDevice = null
                }
                override fun onError(device: android.hardware.camera2.CameraDevice, error: Int) {
                    device.close()
                    cameraDevice = null
                }
            }, null)
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

        // encoderSurface must already exist (prepareEncoder called earlier)
        val encSurface = encoderSurface ?: return

        try {
            device.createCaptureSession(listOf(previewSurface, encSurface),
                object : android.hardware.camera2.CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: android.hardware.camera2.CameraCaptureSession) {
                        captureSession = session
                        try {
                            val request = device.createCaptureRequest(android.hardware.camera2.CameraDevice.TEMPLATE_PREVIEW)
                            request.addTarget(previewSurface)
                            request.addTarget(encSurface) // feed encoder continuously
                            request.set(android.hardware.camera2.CaptureRequest.CONTROL_MODE, android.hardware.camera2.CameraMetadata.CONTROL_MODE_AUTO)
                            session.setRepeatingRequest(request.build(), null, null)
                        } catch (e: Exception) {
                            Log.e(TAG, "createCaptureSession -> setRepeatingRequest", e)
                        }
                    }
                    override fun onConfigureFailed(session: android.hardware.camera2.CameraCaptureSession) {
                        Log.e(TAG, "capture session config failed")
                    }
                }, null)
        } catch (e: Exception) {
            Log.e(TAG, "createCaptureSession", e)
        }
    }

    // -----------------------
    // Segment control
    // -----------------------
    private fun startSegment() {
        // Wait for encoder format to be available
        val format = encoderOutputFormat
        if (format == null) {
            Toast.makeText(this, "Encoder not ready yet", Toast.LENGTH_SHORT).show()
            return
        }

        // create file
        val outDir = externalMediaDirs.first()
        val outFile = File(outDir, "segment_${System.currentTimeMillis()}.mp4")

        // setup muxer for this segment
        try {
            muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxerTrackIndex = muxer!!.addTrack(format)
            muxer!!.start()
            muxerStarted = true

            segmentFiles.add(outFile.absolutePath)

            // mark segment start PTS using last-seen buffer PTS idea:
            // We use current system of cached encoder output timestamps. We'll capture the next
            // encoder buffer's presentationTimeUs as segmentStartPtsUs.
            // To simplify: set it to 0 initially, and the encoder loop will subtract the first written buffer timestamp.
            segmentStartPtsUs = Long.MAX_VALUE // sentinel; will be set at first write
            writingSegment.set(true)

            // Now set a short listener to capture the first buffer timestamp as soon as it appears.
            // The encoderOutputLoop will use segmentStartPtsUs when writing; we need to set it when we see the first buffer.
            // To capture it, we will spin until encoderOutputFormat exists and then wait for first write call to set segmentStartPtsUs.
            // As a practical approach here: set segmentStartPtsUs to the encoder internal timestamp at next available buffer.
            // The encoder loop sets adjustedPts = bufferInfo.presentationTimeUs - segmentStartPtsUs
            // So we must set segmentStartPtsUs at the moment of the first buffer we intend to write.
            // We implement this by watching for the first write in encoderOutputLoop; to do that, we use the sentinel value
            // (Long.MAX_VALUE) and in encoderOutputLoop, when writingSegment && segmentStartPtsUs == Long.MAX_VALUE, we set it.
            Log.i(TAG, "Segment started, writing to ${outFile.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start muxer", e)
            writingSegment.set(false)
            muxerStarted = false
            muxer?.release(); muxer = null
        }
    }

    private fun stopSegment() {
        if (!camState ==  writingSegment.get()) return

        // stop writing and finalize muxer
        writingSegment.set(false)

        camState = CamState.WAITING_FOR_NEXT_RECORDING
        // small delay to ensure the encoder loop wrote last buffers (you can tune/remove)
        encoderExecutor.execute {
            try {
                // wait briefly for encoder to flush current buffers
                Thread.sleep(100)
            } catch (e: InterruptedException) { /* ignore */ }

            try {
                if (muxerStarted) {
                    try {
                        muxer?.stop()
                    } catch (e: Exception) {
                        Log.e(TAG, "muxer.stop() error", e)
                    }
                })
                muxer?.release()
            } catch (e: Exception) {
                Log.e(TAG, "Error finalizing muxer", e)
            } finally {
                muxer = null
                muxerStarted = false
                muxerTrackIndex = -1
                segmentStartPtsUs = 0L
                Log.i(TAG, "Segment finalized")
            }
        }
    }

    // -----------------------
    // Lifecycle cleanup
    // -----------------------
    override fun onDestroy() {
        super.onDestroy()
        encoderLoopRunning.set(false)
        encoderExecutor.shutdownNow()

        try {
            captureSession?.close()
            cameraDevice?.close()
        } catch (e: Exception) { /* ignore */ }

        encoder?.stop()
        encoder?.release()
        encoder = null
        encoderSurface?.release()
        encoderSurface = null
    }
}
