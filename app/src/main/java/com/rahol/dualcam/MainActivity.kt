package com.rahol.dualcam

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "DualCam"
        private const val REQ = 1001
        private val PERMS = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
    }

    private lateinit var backPreview: TextureView
    private lateinit var frontPreview: TextureView
    private lateinit var pip: FrameLayout
    private lateinit var status: TextView
    private lateinit var btnModePhoto: TextView
    private lateinit var btnModeVideo: TextView
    private lateinit var btnCapture: FrameLayout
    private lateinit var shutterOuter: View
    private lateinit var shutterInner: View
    private lateinit var btnDual: TextView
    private lateinit var btnFlash: TextView
    private lateinit var btnGrid: TextView
    private lateinit var gridOverlay: View

    private var mgr: CameraManager? = null
    private var backDev: CameraDevice? = null
    private var frontDev: CameraDevice? = null
    private var backSess: CameraCaptureSession? = null
    private var frontSess: CameraCaptureSession? = null
    private var backId: String? = null
    private var frontId: String? = null
    private var bgThread: HandlerThread? = null
    private var bgHandler: Handler? = null
    private val lock = Semaphore(1)

    private var dX = 0f
    private var dY = 0f

    private var isPhotoMode = true
    private var isRecording = false
    private var dualOn = false
    private var gridOn = false
    /** 0=off 1=on 2=auto */
    private var flashMode = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        backPreview = findViewById(R.id.backPreview)
        frontPreview = findViewById(R.id.frontPreview)
        pip = findViewById(R.id.pipContainer)
        status = findViewById(R.id.statusText)
        btnModePhoto = findViewById(R.id.btnModePhoto)
        btnModeVideo = findViewById(R.id.btnModeVideo)
        btnCapture = findViewById(R.id.btnCapture)
        shutterOuter = findViewById(R.id.shutterOuter)
        shutterInner = findViewById(R.id.shutterInner)
        btnDual = findViewById(R.id.btnDual)
        btnFlash = findViewById(R.id.btnFlash)
        btnGrid = findViewById(R.id.btnGrid)
        gridOverlay = findViewById(R.id.gridOverlay)

        mgr = getSystemService(CAMERA_SERVICE) as CameraManager

        shutterOuter.setBackgroundResource(R.drawable.shutter_outer)
        shutterInner.setBackgroundResource(R.drawable.shutter_inner_photo)

        btnModePhoto.setOnClickListener { setMode(true) }
        btnModeVideo.setOnClickListener { setMode(false) }
        btnCapture.setOnClickListener { onCaptureClick() }
        btnDual.setOnClickListener { toggleDual() }
        btnFlash.setOnClickListener { cycleFlash() }
        btnGrid.setOnClickListener { toggleGrid() }

        pip.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    dX = v.x - e.rawX; dY = v.y - e.rawY; true
                }
                MotionEvent.ACTION_MOVE -> {
                    v.animate().x(e.rawX + dX).y(e.rawY + dY).setDuration(0).start()
                    true
                }
                else -> false
            }
        }

        if (PERMS.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) {
            startBg(); setupBackOnly()
        } else {
            ActivityCompat.requestPermissions(this, PERMS, REQ)
        }
    }

    private fun setMode(photo: Boolean) {
        if (isRecording) {
            Toast.makeText(this, "Stop recording first", Toast.LENGTH_SHORT).show()
            return
        }
        isPhotoMode = photo
        if (photo) {
            btnModePhoto.setTextColor(0xFFFFFFFF.toInt())
            btnModePhoto.setBackgroundColor(0x33FFFFFF)
            btnModeVideo.setTextColor(0xAAFFFFFF.toInt())
            btnModeVideo.setBackgroundColor(0)
            shutterInner.setBackgroundResource(R.drawable.shutter_inner_photo)
        } else {
            btnModeVideo.setTextColor(0xFFFFFFFF.toInt())
            btnModeVideo.setBackgroundColor(0x33FFFFFF)
            btnModePhoto.setTextColor(0xAAFFFFFF.toInt())
            btnModePhoto.setBackgroundColor(0)
            shutterInner.setBackgroundResource(R.drawable.shutter_inner_video)
        }
        updateStatus()
    }

    private fun toggleDual() {
        if (isRecording) {
            Toast.makeText(this, "Stop recording first", Toast.LENGTH_SHORT).show()
            return
        }
        dualOn = !dualOn
        if (dualOn) {
            btnDual.text = "Dual ON"
            btnDual.setBackgroundColor(0x66E53935)
            pip.visibility = View.VISIBLE
            openFront()
            status.text = "Opening front camera…"
        } else {
            btnDual.text = "Dual OFF"
            btnDual.setBackgroundColor(0x33FFFFFF)
            pip.visibility = View.GONE
            closeFrontOnly()
            updateStatus()
        }
    }

    private fun cycleFlash() {
        flashMode = (flashMode + 1) % 3
        btnFlash.text = when (flashMode) {
            1 -> "Flash On"
            2 -> "Flash Auto"
            else -> "Flash Off"
        }
        // Re-apply preview request with new flash if session exists
        restartBackPreview()
    }

    private fun toggleGrid() {
        gridOn = !gridOn
        gridOverlay.visibility = if (gridOn) View.VISIBLE else View.GONE
        btnGrid.setTextColor(if (gridOn) 0xFFFFFFFF.toInt() else 0xAAFFFFFF.toInt())
    }

    private fun onCaptureClick() {
        if (isPhotoMode) {
            Toast.makeText(this, "Photo captured (preview)", Toast.LENGTH_SHORT).show()
        } else {
            if (!isRecording) {
                isRecording = true
                shutterInner.setBackgroundResource(R.drawable.shutter_inner_recording)
                status.text = "Recording…"
            } else {
                isRecording = false
                shutterInner.setBackgroundResource(R.drawable.shutter_inner_video)
                updateStatus()
                Toast.makeText(this, "Stopped", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateStatus() {
        val mode = if (isPhotoMode) "PHOTO" else "VIDEO"
        val dual = if (dualOn) " · Dual" else ""
        status.text = "$mode$dual"
    }

    override fun onRequestPermissionsResult(code: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(code, p, r)
        if (code == REQ && r.all { it == PackageManager.PERMISSION_GRANTED }) {
            startBg(); setupBackOnly()
        } else status.text = "Permission required"
    }

    private fun startBg() {
        bgThread = HandlerThread("cam").also { it.start() }
        bgHandler = Handler(bgThread!!.looper)
    }

    private fun setupBackOnly() {
        backPreview.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(s: SurfaceTexture, w: Int, h: Int) {
                findIds(); openBack()
            }
            override fun onSurfaceTextureSizeChanged(s: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(s: SurfaceTexture) = true
            override fun onSurfaceTextureUpdated(s: SurfaceTexture) {}
        }
        frontPreview.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(s: SurfaceTexture, w: Int, h: Int) {
                if (dualOn && backDev != null) openFront()
            }
            override fun onSurfaceTextureSizeChanged(s: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(s: SurfaceTexture) = true
            override fun onSurfaceTextureUpdated(s: SurfaceTexture) {}
        }
        if (backPreview.isAvailable) { findIds(); openBack() }
    }

    private fun findIds() {
        try {
            for (id in mgr!!.cameraIdList) {
                val f = mgr!!.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING)
                if (f == CameraCharacteristics.LENS_FACING_BACK && backId == null) backId = id
                if (f == CameraCharacteristics.LENS_FACING_FRONT && frontId == null) frontId = id
            }
        } catch (e: Exception) {
            status.text = "Camera list error"
        }
    }

    private fun openBack() {
        val id = backId ?: return
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        try {
            if (!lock.tryAcquire(3, TimeUnit.SECONDS)) return
            mgr!!.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(c: CameraDevice) {
                    lock.release(); backDev = c; startPreview(c, backPreview, false)
                    updateStatus()
                }
                override fun onDisconnected(c: CameraDevice) { lock.release(); c.close(); backDev = null }
                override fun onError(c: CameraDevice, e: Int) {
                    lock.release(); c.close(); backDev = null
                    status.text = "Back error $e"
                }
            }, bgHandler)
        } catch (e: Exception) {
            lock.release(); status.text = "Back fail"
        }
    }

    private fun openFront() {
        val id = frontId ?: run {
            status.text = "No front camera"
            dualOn = false
            btnDual.text = "Dual OFF"
            pip.visibility = View.GONE
            return
        }
        if (frontDev != null) return
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        try {
            if (!lock.tryAcquire(3, TimeUnit.SECONDS)) {
                status.text = "Front timeout"
                return
            }
            mgr!!.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(c: CameraDevice) {
                    lock.release()
                    frontDev = c
                    startPreview(c, frontPreview, true)
                    status.text = "Dual ON · ready"
                }
                override fun onDisconnected(c: CameraDevice) {
                    lock.release(); c.close(); frontDev = null
                }
                override fun onError(c: CameraDevice, e: Int) {
                    lock.release(); c.close(); frontDev = null
                    dualOn = false
                    runOnUiThread {
                        btnDual.text = "Dual OFF"
                        btnDual.setBackgroundColor(0x33FFFFFF)
                        pip.visibility = View.GONE
                        status.text = "Dual failed (error $e)"
                    }
                    Log.e(TAG, "Front error $e")
                }
            }, bgHandler)
        } catch (e: Exception) {
            lock.release()
            status.text = "Front fail"
        }
    }

    private fun closeFrontOnly() {
        try {
            frontSess?.close()
            frontDev?.close()
        } catch (_: Exception) {}
        frontSess = null
        frontDev = null
    }

    private fun chooseSize(isFront: Boolean): Size {
        val id = if (isFront) frontId else backId
        return try {
            val map = mgr!!.getCameraCharacteristics(id!!)
                .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val sizes = map!!.getOutputSizes(SurfaceTexture::class.java)
            sizes.firstOrNull { it.width <= 1280 && it.height <= 720 }
                ?: sizes.minByOrNull { it.width * it.height } ?: Size(640, 480)
        } catch (_: Exception) {
            Size(640, 480)
        }
    }

    private fun flashValue(): Int = when (flashMode) {
        1 -> CaptureRequest.FLASH_MODE_TORCH
        2 -> CaptureRequest.FLASH_MODE_SINGLE
        else -> CaptureRequest.FLASH_MODE_OFF
    }

    private fun startPreview(device: CameraDevice, view: TextureView, isFront: Boolean) {
        val tex = view.surfaceTexture ?: return
        val size = chooseSize(isFront)
        tex.setDefaultBufferSize(size.width, size.height)
        val surface = Surface(tex)
        try {
            val req = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface)
                set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                if (!isFront) {
                    try {
                        set(CaptureRequest.FLASH_MODE, flashValue())
                    } catch (_: Exception) {}
                }
            }
            device.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    if (isFront) frontSess = s else backSess = s
                    try {
                        s.setRepeatingRequest(req.build(), null, bgHandler)
                    } catch (e: Exception) {
                        Log.e(TAG, "repeat", e)
                    }
                }
                override fun onConfigureFailed(s: CameraCaptureSession) {
                    status.text = if (isFront) "Front config fail" else "Back config fail"
                }
            }, bgHandler)
        } catch (e: Exception) {
            Log.e(TAG, "preview", e)
        }
    }

    private fun restartBackPreview() {
        val dev = backDev ?: return
        try {
            backSess?.close()
        } catch (_: Exception) {}
        startPreview(dev, backPreview, false)
    }

    private fun closeAll() {
        try {
            lock.acquire()
            backSess?.close(); frontSess?.close()
            backDev?.close(); frontDev?.close()
            backSess = null; frontSess = null
            backDev = null; frontDev = null
        } catch (_: Exception) {
        } finally {
            lock.release()
        }
    }

    override fun onResume() {
        super.onResume()
        if (PERMS.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) {
            startBg()
            if (backPreview.isAvailable) {
                findIds(); openBack()
                if (dualOn) openFront()
            } else setupBackOnly()
        }
    }

    override fun onPause() {
        closeAll()
        bgThread?.quitSafely()
        bgThread = null
        bgHandler = null
        super.onPause()
    }
}
