package android.vendor.drawsiness_tf

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.vendor.drawsiness_tf.R
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : Activity(), EyeDetectionService.DetectionCallback {

    companion object {
        private const val TAG = "MainActivity"
        private const val PERMISSIONS_REQUEST_CAMERA = 100
    }

    private lateinit var statusText: TextView
    private lateinit var toggleButton: Button
    private var eyeDetectionService: EyeDetectionService? = null
    private var isBound = false
    private var isEyeDetectionEnabled = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.d(TAG, "Service connected")
            val binder = service as EyeDetectionService.LocalBinder
            eyeDetectionService = binder.getService().apply {
                setDetectionCallback(this@MainActivity)
            }
            isBound = true
            updateToggleButton()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.d(TAG, "Service disconnected")
            isBound = false
            eyeDetectionService = null
            isEyeDetectionEnabled = false
            updateToggleButton()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "Activity created")
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.result)
        toggleButton = findViewById(R.id.toggleButton)

        // Start service
        startServiceOnlyOnce()

        // Request camera permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "Requesting camera permission")
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), PERMISSIONS_REQUEST_CAMERA)
        } else {
            Log.d(TAG, "Camera permission already granted")
            bindService()
        }

        // Set up toggle button
        toggleButton.setOnClickListener {
            Log.d(TAG, "Toggle button clicked, isBound=$isBound, isEnabled=$isEyeDetectionEnabled")
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "Camera permission not granted")
                Toast.makeText(this, "Camera permission required", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (isBound) {
                toggleService()
            } else {
                Log.w(TAG, "Service not bound")
                Toast.makeText(this, "Service not bound", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startServiceOnlyOnce() {
        Log.d(TAG, "Starting service")
        startService(Intent(this, EyeDetectionService::class.java))
    }

    private fun bindService() {
        Log.d(TAG, "Binding to service")
        bindService(Intent(this, EyeDetectionService::class.java), serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun toggleService() {
        Intent(this, EyeDetectionService::class.java).apply {
            action = if (isEyeDetectionEnabled) {
                EyeDetectionService.ACTION_STOP_DETECTION
            } else {
                EyeDetectionService.ACTION_START_DETECTION
            }
            startService(this)
        }
        isEyeDetectionEnabled = !isEyeDetectionEnabled
        updateToggleButton()
    }

    private fun updateToggleButton() {
        Log.d(TAG, "Updating toggle button: isEnabled=$isEyeDetectionEnabled")
        toggleButton.text = if (isEyeDetectionEnabled) "Disable Eye Detection" else "Enable Eye Detection"
        toggleButton.setBackgroundColor(if (isEyeDetectionEnabled) Color.RED else Color.GREEN)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        Log.d(TAG, "Permission result: requestCode=$requestCode, grantResults=$grantResults")
        if (requestCode == PERMISSIONS_REQUEST_CAMERA && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "Camera permission granted")
            bindService()
        } else {
            Log.w(TAG, "Camera permission denied")
            statusText.text = "Camera permission denied"
            statusText.setTextColor(Color.RED)
            toggleButton.isEnabled = false
            Toast.makeText(this, "Camera permission denied", Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            bindService()
        }
    }

    override fun onPause() {
        super.onPause()
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Activity destroyed")
    }

    override fun onResultReceived(status: String) {
        Log.d(TAG, "Result received: $status")
        runOnUiThread {
            statusText.text = status
        }
    }

    override fun onErrorReceived(error: String) {
        Log.e(TAG, "Error received: $error")
        runOnUiThread {
            Toast.makeText(this, "Error: $error", Toast.LENGTH_LONG).show()
            statusText.text = "Error: $error"
            statusText.setTextColor(Color.RED)
        }
    }

    override fun onStatusReceived(isEnabled: Boolean) {
        Log.d(TAG, "Status received: isEnabled=$isEnabled")
        isEyeDetectionEnabled = isEnabled
        runOnUiThread { updateToggleButton() }
    }
}