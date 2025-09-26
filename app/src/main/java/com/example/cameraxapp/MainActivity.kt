package com.example.cameraxapp

import androidx.appcompat.app.AppCompatActivity
import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.core.content.ContextCompat
import com.example.cameraxapp.databinding.ActivityMainBinding
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.core.Preview
import androidx.camera.core.CameraSelector
import android.util.Log
import android.view.MotionEvent
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.PendingRecording
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.PermissionChecker
import java.text.SimpleDateFormat
import java.util.Locale

typealias LumaListener = (luma: Double) -> Unit

class MainActivity : AppCompatActivity() {
    private lateinit var viewBinding: ActivityMainBinding

    private var videoCapture: VideoCapture<Recorder>? = null
    private var pendingRecording: PendingRecording? = null;
    private var recording: Recording? = null
    private val segmentUris = mutableListOf<Uri>()
    private var segmentStartTime: Long = 0L
    private var allRecordedTime: Long = 0L
    private var maxDuration: Long = 6000L
    private var is_recording = false

    private lateinit var cameraExecutor: ExecutorService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(viewBinding.root)

        // Request camera permissions
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            requestPermissions()
        }

        // Set up the listeners for take photo and video capture buttons
        viewBinding.videoCaptureButton.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> onPush()
                MotionEvent.ACTION_UP -> onRelease()
                MotionEvent.ACTION_CANCEL -> onRelease()
            }
            true
        }

        cameraExecutor = Executors.newSingleThreadExecutor()

        // update progress ring continuously
        val handler = Handler(Looper.getMainLooper())
        handler.post(object : Runnable {
            override fun run() {
                val totalElapsed = if (is_recording) {
                    allRecordedTime + (System.currentTimeMillis() - segmentStartTime)
                } else {
                    allRecordedTime
                }
                viewBinding.progressRing.progress = totalElapsed.toFloat() / maxDuration
                handler.postDelayed(this, 16) // ~60fps
            }
        })
    }

    private val stopRunnable = Runnable {
        if (is_recording) {
            Log.i(TAG, "Stopping recording $recording")
            recording?.stop()
            allRecordedTime += System.currentTimeMillis() - segmentStartTime;
            is_recording = false;
//            viewBinding.videoCaptureButton.apply {text = "Stopped"}
        }
    }

    private fun onPush() {
        Log.i(TAG, "onPush() $recording")
//        if (recording == null) {
//            Log.i(TAG, "Starting a new recording")
//            captureVideo()
//        } else if (!is_recording) {
//            Log.i(TAG, "Resuming recording $recording")
//            recording?.resume()
//        }
        captureVideoSegment();
    }

    private fun onRelease() {
        Log.i(TAG, "Pausing recording $recording")
//        if (is_recording)
//            recording?.pause()
        stopRunnable.run()
    }

    private fun buildRecording() {
        val vc = videoCapture
        val name = "segment_${System.currentTimeMillis()}"
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
        }
        val outputOptions = MediaStoreOutputOptions.Builder(
            contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ).setContentValues(contentValues).build()

        pendingRecording = vc?.output
            ?.prepareRecording(this, outputOptions)
//            ?.apply {
//                if (PermissionChecker.checkSelfPermission(this@MainActivity,
//                        Manifest.permission.RECORD_AUDIO) ==
//                    PermissionChecker.PERMISSION_GRANTED)
//                {
//                    withAudioEnabled()
//                }
//            }
    }

    //    Different approach, we capture into multiple video files, one for each segment
    private fun captureVideoSegment() {
        recording = pendingRecording?.start(ContextCompat.getMainExecutor(this)) { event ->
                if (event is VideoRecordEvent.Start) {
                    is_recording = true;

                    segmentStartTime = System.currentTimeMillis()
                    viewBinding.root.removeCallbacks(stopRunnable)
//                    viewBinding.root.postDelayed(stopRunnable, maxDuration)
                } else if (event is VideoRecordEvent.Finalize) {
                    if (!event.hasError()) {
                        val msg = "Video capture succeeded: ${event.outputResults.outputUri}"
                        Toast.makeText(baseContext, msg, Toast.LENGTH_SHORT)
                            .show()
                        Log.i(TAG, msg)
//                            viewBinding.videoCaptureButton.apply {text = "Succeeded"}
                    } else {
                        Log.e(TAG, "Video capture ends with error: " +
                                "${event.error}")
//                            viewBinding.videoCaptureButton.apply {text = "Failed"}
                    }
                    segmentUris.add(event.outputResults.outputUri)
                    buildRecording()
                }
            }
    }


    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            // Used to bind the lifecycle of cameras to the lifecycle owner
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            // Preview
            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(viewBinding.viewFinder.surfaceProvider)
                }

            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.LOWEST))
                .build()
            videoCapture = VideoCapture.withOutput(recorder)

            // Select back camera as a default
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                // Unbind use cases before rebinding
                cameraProvider.unbindAll()

                // Bind use cases to camera
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, videoCapture)
            } catch(exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }

//            Setup the next recording segment (with a new filenmae)
            buildRecording()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun requestPermissions() {
        activityResultLauncher.launch(REQUIRED_PERMISSIONS)
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(
            baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    companion object {
        private const val TAG = "CameraXApp"
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
        private val REQUIRED_PERMISSIONS =
            mutableListOf (
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO
            ).apply {
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                    add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            }.toTypedArray()
    }

    private val activityResultLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions())
        { permissions ->
            // Handle Permission granted/rejected
            var permissionGranted = true
            permissions.entries.forEach {
                if (it.key in REQUIRED_PERMISSIONS && it.value == false)
                    permissionGranted = false
            }
            if (!permissionGranted) {
                Toast.makeText(baseContext,
                    "Permission request denied",
                    Toast.LENGTH_SHORT).show()
            } else {
                startCamera()
            }
        }

}
