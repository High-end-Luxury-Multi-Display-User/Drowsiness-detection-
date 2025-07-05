package android.vendor.drwsiness_tf

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import android.view.TextureView
import android.widget.TextView
import androidx.annotation.RequiresPermission

class MainActivity : Activity() {

    private lateinit var textureView: TextureView
    private lateinit var statusText: TextView
    private lateinit var cameraDevice: CameraDevice
    private lateinit var captureSession: CameraCaptureSession
    private lateinit var imageReader: ImageReader
    private lateinit var eyeAnalyzer: EyeAnalyzerCamera2

    private lateinit var backgroundThread: HandlerThread
    private lateinit var backgroundHandler: Handler

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        textureView = findViewById(R.id.textureView)
        statusText = findViewById(R.id.result)

        // Start background thread for processing
        backgroundThread = HandlerThread("ImageProcessingThread")
        backgroundThread.start()
        backgroundHandler = Handler(backgroundThread.looper)

        // Initialize EyeAnalyzer
        eyeAnalyzer = EyeAnalyzerCamera2(
            this,
            onResult = { result ->
                runOnUiThread {
                    statusText.text = "Eye: ${result.status}\nConfidence: ${"%.2f".format(result.confidence)}"
                    statusText.setTextColor(result.color)
                }
            },
            onError = { error ->
                Log.e("MainActivity", "Error: $error")
            }
        )

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 100)
        } else {
            setupTextureListener()
        }
    }

    @RequiresPermission(Manifest.permission.CAMERA)
    private fun setupTextureListener() {
        if (textureView.isAvailable) {
            openCamera()
        } else {
            textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                @RequiresPermission(Manifest.permission.CAMERA)
                override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                    openCamera()
                }

                override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}
                override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true
                override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
            }
        }
    }

    @RequiresPermission(Manifest.permission.CAMERA)
    private fun openCamera() {
        val manager = getSystemService(CAMERA_SERVICE) as CameraManager
        val cameraId = manager.cameraIdList.firstOrNull { id ->
            val characteristics = manager.getCameraCharacteristics(id)
            val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
            facing == CameraCharacteristics.LENS_FACING_FRONT
        } ?: manager.cameraIdList.first()

//        val cameraId = manager.cameraIdList.first()

        manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) {
                cameraDevice = device
                startPreview()
            }

            override fun onDisconnected(device: CameraDevice) {
                device.close()
            }

            override fun onError(device: CameraDevice, error: Int) {
                device.close()
            }
        }, backgroundHandler)
    }

    private fun startPreview() {
        val texture = textureView.surfaceTexture!!
        texture.setDefaultBufferSize(320, 240)
        val previewSurface = Surface(texture)

        imageReader = ImageReader.newInstance(
            320, 240,
            android.graphics.ImageFormat.YUV_420_888, 2
        )

        var isProcessing = false

        imageReader.setOnImageAvailableListener({ reader ->
            if (isProcessing) {
                return@setOnImageAvailableListener
            }
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            isProcessing = true
            backgroundHandler.post {
                try {
                    eyeAnalyzer.analyze(image)
                } catch (e: Exception) {
                    Log.e("MainActivity", "Analysis failed: ${e.message}")
                } finally {
                    image.close()
                    isProcessing = false
                }
            }
        }, backgroundHandler)

        cameraDevice.createCaptureSession(listOf(previewSurface, imageReader.surface),
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    val request = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                        addTarget(previewSurface)
                        addTarget(imageReader.surface)
                    }
                    captureSession.setRepeatingRequest(request.build(), null, backgroundHandler)
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {}
            }, backgroundHandler)
    }

    @RequiresPermission(Manifest.permission.CAMERA)
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            setupTextureListener()
        } else {
            statusText.text = "Camera permission denied"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        eyeAnalyzer.close()
        cameraDevice.close()
        backgroundThread.quitSafely()
    }
}