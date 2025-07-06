package android.vendor.drwsiness_tf

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.util.UUID

class EyeDetectionService : Service() {

    companion object {
        private const val TAG = "EyeDetectionService"
        private const val NOTIFICATION_ID = 5678
        private const val CHANNEL_ID = "eye_detection_channel"
        const val ACTION_START_DETECTION = "android.vendor.drwsiness_tf.ACTION_START"
        const val ACTION_STOP_DETECTION = "android.vendor.drwsiness_tf.ACTION_STOP"
    }

    private val binder = LocalBinder()
    private var eyeAnalyzer: EyeAnalyzerCamera2? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private var isEyeDetectionEnabled = false
    private var callback: DetectionCallback? = null

    inner class LocalBinder : Binder() {
        fun getService(): EyeDetectionService = this@EyeDetectionService
    }

    fun setDetectionCallback(callback: DetectionCallback?) {
        this.callback = callback
    }

    override fun onBind(intent: Intent?): IBinder {
        Log.d(TAG, "Service bound")
        return binder
    }

    @SuppressLint("ForegroundServiceType")
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
        createNotificationChannel()
        startForeground(
            NOTIFICATION_ID,
            createNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        )
        try {
            backgroundThread = HandlerThread("EyeDetectionThread-${UUID.randomUUID()}")
            backgroundThread?.start()
            backgroundHandler = backgroundThread?.looper?.let { Handler(it) }
                ?: throw IllegalStateException("Failed to create background handler")

            eyeAnalyzer = EyeAnalyzerCamera2(
                this,
                onResult = { result ->
                    Log.d(TAG, "Result: ${result.status}, Confidence: ${result.confidence}")
                    callback?.onResultReceived(result.status)
                },
                onError = { error ->
                    Log.e(TAG, "Error: $error")
                    callback?.onErrorReceived(error)
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Initialization failed: ${e.message}")
            callback?.onErrorReceived("Service initialization failed: ${e.message}")
            stopSelf()
        }
    }


    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: action=${intent?.action}")
        when (intent?.action) {
            ACTION_START_DETECTION -> startEyeDetection()
            ACTION_STOP_DETECTION -> stopEyeDetection()
        }
        return START_STICKY
    }

    fun startEyeDetection() {
        if (isEyeDetectionEnabled) {
            Log.d(TAG, "Eye detection already enabled")
            return
        }
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Camera permission not granted")
            callback?.onErrorReceived("Camera permission not granted")
            return
        }
        Log.d(TAG, "Starting eye detection")
        isEyeDetectionEnabled = true
        openCamera()
        updateNotification()
    }

    fun stopEyeDetection() {
        if (!isEyeDetectionEnabled) {
            Log.d(TAG, "Eye detection already stopped")
            return
        }
        Log.d(TAG, "Stopping eye detection")
        isEyeDetectionEnabled = false
        try {
            captureSession?.close()
            cameraDevice?.close()
            imageReader?.close()
            captureSession = null
            cameraDevice = null
            imageReader = null
        } catch (e: Exception) {
            Log.e(TAG, "Error closing camera: ${e.message}")
            callback?.onErrorReceived("Error closing camera: ${e.message}")
        }
        updateNotification()
        callback?.onStatusReceived(isEyeDetectionEnabled)
    }

    @RequiresPermission(Manifest.permission.CAMERA)
    private fun openCamera() {
        val manager = getSystemService(CAMERA_SERVICE) as CameraManager
        val cameraId = try {
            manager.cameraIdList.firstOrNull { id ->
                val characteristics = manager.getCameraCharacteristics(id)
                val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                facing == CameraCharacteristics.LENS_FACING_FRONT
            } ?: manager.cameraIdList.firstOrNull() ?: run {
                Log.e(TAG, "No camera available")
                callback?.onErrorReceived("No camera available")
                isEyeDetectionEnabled = false
                callback?.onStatusReceived(isEyeDetectionEnabled)
                return
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get camera ID: ${e.message}")
            callback?.onErrorReceived("Failed to get camera ID: ${e.message}")
            isEyeDetectionEnabled = false
            callback?.onStatusReceived(isEyeDetectionEnabled)
            return
        }

        Log.d(TAG, "Opening camera: $cameraId")
        try {
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    Log.d(TAG, "Camera opened")
                    cameraDevice = device
                    startPreview()
                }

                override fun onDisconnected(device: CameraDevice) {
                    Log.w(TAG, "Camera disconnected")
                    device.close()
                    cameraDevice = null
                    isEyeDetectionEnabled = false
                    callback?.onStatusReceived(isEyeDetectionEnabled)
                }

                override fun onError(device: CameraDevice, error: Int) {
                    Log.e(TAG, "Camera error: $error")
                    device.close()
                    cameraDevice = null
                    isEyeDetectionEnabled = false
                    callback?.onStatusReceived(isEyeDetectionEnabled)
                    callback?.onErrorReceived("Camera error: $error")
                }
            }, backgroundHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open camera: ${e.message}")
            callback?.onErrorReceived("Failed to open camera: ${e.message}")
            isEyeDetectionEnabled = false
            callback?.onStatusReceived(isEyeDetectionEnabled)
        }
    }

    private fun startPreview() {
        imageReader = ImageReader.newInstance(320, 240, android.graphics.ImageFormat.YUV_420_888, 2)
        var isProcessing = false

        imageReader?.setOnImageAvailableListener({ reader ->
            if (!isEyeDetectionEnabled || isProcessing) {
                Log.d(TAG, "Skipping frame: enabled=$isEyeDetectionEnabled, processing=$isProcessing")
                return@setOnImageAvailableListener
            }
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            isProcessing = true
            backgroundHandler?.post {
                try {
                    Log.d(TAG, "Processing frame")
                    eyeAnalyzer?.analyze(image)
                } catch (e: Exception) {
                    Log.e(TAG, "Analysis failed: ${e.message}")
                    callback?.onErrorReceived("Analysis failed: ${e.message}")
                } finally {
                    image.close()
                    isProcessing = false
                }
            }
        }, backgroundHandler)

        val device = cameraDevice ?: run {
            Log.e(TAG, "Camera device is null")
            callback?.onErrorReceived("Camera device is null")
            isEyeDetectionEnabled = false
            callback?.onStatusReceived(isEyeDetectionEnabled)
            return
        }

        try {
            device.createCaptureSession(listOf(imageReader?.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    Log.d(TAG, "Capture session configured")
                    captureSession = session
                    try {
                        val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                            addTarget(imageReader?.surface!!)
                        }
                        session.setRepeatingRequest(request.build(), null, backgroundHandler)
                        Log.d(TAG, "Capture session started")
                        callback?.onStatusReceived(isEyeDetectionEnabled)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to start capture session: ${e.message}")
                        callback?.onErrorReceived("Failed to start capture session: ${e.message}")
                        isEyeDetectionEnabled = false
                        callback?.onStatusReceived(isEyeDetectionEnabled)
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e(TAG, "Capture session configuration failed")
                    callback?.onErrorReceived("Capture session configuration failed")
                    isEyeDetectionEnabled = false
                    callback?.onStatusReceived(isEyeDetectionEnabled)
                }
            }, backgroundHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create capture session: ${e.message}")
            callback?.onErrorReceived("Failed to create capture session: ${e.message}")
            isEyeDetectionEnabled = false
            callback?.onStatusReceived(isEyeDetectionEnabled)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Eye Detection Service")
            .setContentText(if (isEyeDetectionEnabled) "Detecting eyes..." else "Idle")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Eye Detection",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, createNotification())
    }

    override fun onDestroy() {
        Log.d(TAG, "Service destroyed")
        stopEyeDetection()
        eyeAnalyzer?.close()
        backgroundThread?.quitSafely()
        backgroundThread = null
        backgroundHandler = null
        eyeAnalyzer = null
        callback = null
        super.onDestroy()
    }

    interface DetectionCallback {
        fun onResultReceived(status: String)
        fun onErrorReceived(error: String)
        fun onStatusReceived(isEnabled: Boolean)
    }
}