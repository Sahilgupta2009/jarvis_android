package com.jarvis.assistant

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    companion object {
        val RUNTIME_PERMS: List<String> = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            add(Manifest.permission.READ_CONTACTS)
            add(Manifest.permission.READ_SMS)
            add(Manifest.permission.SEND_SMS)
            add(Manifest.permission.CALL_PHONE)
            add(Manifest.permission.READ_CALL_LOG)
            add(Manifest.permission.CAMERA)
        }
    }

    private var startWakeAfterPerms = false

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (startWakeAfterPerms && Voice.hasMic()) {
            startWakeAfterPerms = false
            JarvisService.start(this)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)

        Jarvis.cameraHook = { front, cb -> captureCamera(front, cb) }

        // keep the screen awake while the orb is up
        lifecycleScope.launch {
            Jarvis.orbVisible.collect { visible ->
                if (visible) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }

        setContent {
            JarvisTheme {
                JarvisRoot(this)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        Jarvis.appVisible = true
    }

    override fun onStop() {
        Jarvis.appVisible = false
        super.onStop()
    }

    override fun onDestroy() {
        if (isFinishing) Jarvis.cameraHook = null
        super.onDestroy()
    }

    private fun handleIntent(i: Intent?) {
        if (i == null) return
        if (i.getBooleanExtra("wake", false)) {
            // woken by "Jarvis": show over the lock screen and light the display
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        if (i.getBooleanExtra("startWake", false)) {
            i.removeExtra("startWake")
            toggleWake(forceOn = true)
        }
    }

    /** Ask for every runtime permission Jarvis can use that isn't granted yet. */
    fun grantAll() {
        val missing = RUNTIME_PERMS.filter { !Device.hasPerm(it) }
        if (missing.isNotEmpty()) permLauncher.launch(missing.toTypedArray())
    }

    fun toggleWake(forceOn: Boolean = false) {
        if (Jarvis.wakeOn.value && !forceOn) {
            Store.wakeEnabled = false
            JarvisService.stop(this)
            return
        }
        if (!Voice.hasMic()) {
            startWakeAfterPerms = true
            val need = mutableListOf(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33 && !Device.hasPerm(Manifest.permission.POST_NOTIFICATIONS)) {
                need.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            permLauncher.launch(need.toTypedArray())
            return
        }
        JarvisService.start(this)
    }

    /** Take one photo without showing a preview. Calls back with JPEG bytes and the rotation to apply. */
    private fun captureCamera(front: Boolean, cb: (ByteArray?, Int) -> Unit) {
        if (!Device.hasPerm(Manifest.permission.CAMERA)) { cb(null, 0); return }
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                val capture = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
                provider.unbindAll()
                provider.bindToLifecycle(
                    this, if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA, capture
                )
                // give auto-exposure a moment to settle
                Handler(Looper.getMainLooper()).postDelayed({
                    capture.takePicture(ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageCapturedCallback() {
                        override fun onCaptureSuccess(image: ImageProxy) {
                            val buf = image.planes[0].buffer
                            val bytes = ByteArray(buf.remaining())
                            buf.get(bytes)
                            val rot = image.imageInfo.rotationDegrees
                            image.close()
                            provider.unbindAll()
                            cb(bytes, rot)
                        }

                        override fun onError(exception: ImageCaptureException) {
                            provider.unbindAll()
                            cb(null, 0)
                        }
                    })
                }, 900)
            } catch (e: Exception) {
                cb(null, 0)
            }
        }, ContextCompat.getMainExecutor(this))
    }
}
