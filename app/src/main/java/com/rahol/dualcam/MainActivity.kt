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
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
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

    private lateinit var mainPreview: TextureView
    private lateinit var pipPreview: TextureView
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
    private lateinit var btnSwitch: TextView
    private lateinit var btnSettings: TextView
    private lateinit var gridOverlay: View
    private lateinit var zoomBar: SeekBar
    private lateinit var thumbPreview: ImageView
    private lateinit var photoViewer: FrameLayout
    private lateinit var photoFull: ImageView
    private lateinit var btnDeletePhoto: TextView
    private lateinit var btnClosePhoto: TextView

    private var mgr: CameraManager? = null
    private var mainDev: CameraDevice? = null
    private var pipDev: CameraDevice? = null
    private var mainSess: CameraCaptureSession? = null
    private var pipSess: CameraCaptureSession? = null
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
    private var mainIsBack = true
    private var flashMode = 0
    private var zoomLevel = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        mainPreview = findViewById(R.id.mainPreview)
        pipPreview = findViewById(R.id.pipPreview)
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
        btnSwitch = findViewById(R.id.btnSwitch)
        btnSettings = findViewById(R.id.btnSettings)
        gridOverlay = findViewById(R.id.gridOverlay)
        zoomBar = findViewById(R.id.zoomBar)
        thumbPreview = findViewById(R.id.thumbPreview)
        photoViewer = findViewById(R.id.photoViewer)
        photoFull = findViewById(R.id.photoFull)
        btnDeletePhoto = findViewById(R.id.btnDeletePhoto)
        btnClosePhoto = findViewById(R.id.btnClosePhoto)

        mgr = getSystemService(CAMERA_SERVICE) as CameraManager

        shutterOuter.setBackgroundResource(R.drawable.shutter_outer)
        shutterInner.setBackgroundResource(R.drawable.shutter_inner_photo)

        btnModePhoto.setOnClickListener { setMode(true) }
        btnModeVideo.setOnClickListener { setMode(false) }
        btnCapture.setOnClickListener { onCaptureClick() }
        btnDual.setOnClickListener { toggleDual() }
        btnFlash.setOnClickListener { cycleFlash() }
        btnGrid.setOnClickListener { toggleGrid() }
        btnSwitch.setOnClickListener { switchCameras() }
        btnSettings.setOnClickListener { showSettings() }
        btnClosePhoto.setOnClickListener { photoViewer.visibility = View.GONE }
        btnDeletePhoto.setOnClickListener {
            Toast.makeText(this, "Deleted (preview)", Toast.LENGTH_SHORT).show()
            photoViewer.visibility = View.GONE
            thumbPreview.setImageDrawable(null)
        }
        thumbPreview.setOnClickListener {
            if (thumbPreview.drawable != null) photoViewer.visibility = View.VISIBLE
        }

        zoomBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                zoomLevel = progress / 100f
                applyZoom()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

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
            startBg(); setupMain()
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
            pip.alpha = 0f
            pip.visibility = View.VISIBLE
            pip.animate().alpha(1f).setDuration(300).start()
            openPip()
            status.text = "Opening second camera…"
        } else {
            btnDual.text = "Dual OFF"
            btnDual.setBackgroundColor(0x33FFFFFF)
            pip.animate().alpha(0f).setDuration(200).withEndAction {
                pip.visibility = View.GONE
                closePipOnly()
            }.start()
            updateStatus()
        }
    }

    private fun switchCameras() {
        if (isRecording) {
            Toast.makeText(this, "Stop recording first", Toast.LENGTH_SHORT).show()
            return
        }
        mainIsBack = !mainIsBack
        closeAll()
        Handler(mainLooper).postDelayed({
            openMain()
            if (dualOn) openPip()
        }, 200)
    }

    private fun cycleFlash() {
        flashMode = (flashMode + 1) % 3
        btnFlash.text = when (flashMode) {
            1 -> "Flash On"
            2 -> "Flash Auto"
            else -> "Flash Off"
        }
        restartMainPreview()
    }

    private fun toggleGrid() {
        gridOn = !gridOn
        gridOverlay.visibility = if (gridOn) View.VISIBLE else View.GONE
        btnGrid.setTextColor(if (gridOn) 0xFFFFFFFF.toInt() else 0xAAFFFFFF.toInt())
    }

    private fun showSettings() {
        val items = arrayOf("Shutter sound: ON", "About Dual Cam")
        AlertDialog.Builder(this)
            .setTitle("Settings")
            .setItems(items) { _, which ->
                if (which == 0) Toast.makeText(this, "Sound toggle (coming)", Toast.LENGTH_SHORT).show()
                else Toast.makeText(this, "Dual Cam v1 - Camera2 PIP", Toast.LENGTH_SHORT).show()
            }
            .show()
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
        val cam = if (mainIsBack) "Back" else "Front"
        status.text = "$mode$dual · $cam"
    }

    override fun onRequestPermissionsResult(code: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(code, p, r)
        if (code == REQ && r.all { it == PackageManager.PERMISSION_GRANTED }) {
            startBg(); setupMain()
        } else status.text = "Permission required"
    }

    private fun startBg() {
        bgThread = HandlerThread("cam").also { it.start() }
        bgHandler = Handler(bgThread!!.looper)
    }

    private fun setupMain() {
        mainPreview.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(s: SurfaceTexture, w: Int, h: Int) {
                findIds(); openMain()
            }
            override fun onSurfaceTextureSizeChanged(s: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(s: SurfaceTexture) = true
            override fun onSurfaceTextureUpdated(s: SurfaceTexture) {}
        }
        pipPreview.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(s: SurfaceTexture, w: Int, h: Int) {
                if (dualOn && mainDev != null) openPip()
            }
            override fun onSurfaceTextureSizeChanged(s: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(s: SurfaceTexture) = true
            override fun onSurfaceTextureUpdated(s: SurfaceTexture) {}
        }
        if (mainPreview.isAvailable) { findIds(); openMain() }
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

    private fun mainCamId(): String? = if (mainIsBack) backId else frontId
    private fun pipCamId(): String? = if (mainIsBack) frontId else backId

    private fun openMain() {
        val id = mainCamId() ?: return
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        try {
            if (!lock.tryAcquire(3, TimeUnit.SECONDS)) return
            mgr!!.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(c: CameraDevice) {
                    lock.release(); mainDev = c; startPreview(c, mainPreview, false)
                    updateStatus()
                }
                override fun onDisconnected(c: CameraDevice) { lock.release(); c.close(); mainDev = null }
                override fun onError(c: CameraDevice, e: Int) {
                    lock.release(); c.close(); mainDev = null
                    status.text = "Main error $e"
                }
            }, bgHandler)
        } catch (e: Exception) {
            lock.release(); status.text = "Main fail"
        }
    }

    private fun openPip() {
        val id = pipCamId() ?: run {
            status.text = "No second camera"
            dualOn = false
            btnDual.text = "Dual OFF"
            pip.visibility = View.GONE
            return
        }
        if (pipDev != null) return
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        try {
            if (!lock.tryAcquire(3, TimeUnit.SECONDS)) {
                status.text = "PIP timeout"
                return
            }
            mgr!!.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(c: CameraDevice) {
                    lock.release()
                    pipDev = c
                    startPreview(c, pipPreview, true)
                    status.text = "Dual ON · ready"
                }
                override fun onDisconnected(c: CameraDevice) {
                    lock.release(); c.close(); pipDev = null
                }
                override fun onError(c: CameraDevice, e: Int) {
                    lock.release(); c.close(); pipDev = null
                    dualOn = false
                    runOnUiThread {
                        btnDual.text = "Dual OFF"
                        btnDual.setBackgroundColor(0x33FFFFFF)
                        pip.visibility = View.GONE
                        status.text = "Dual failed (error $e)"
                    }
                    Log.e(TAG, "PIP error $e")
                }
            }, bgHandler)
        } catch (e: Exception) {
            lock.release()
            status.text = "PIP fail"
        }
    }

    private fun closePipOnly() {
        try {
            pipSess?.close()
            pipDev?.close()
        } catch (_: Exception) {}
        pipSess = null
        pipDev = null
    }

    private fun chooseSize(isPip: Boolean): Size {
        val id = if (isPip) pipCamId() else mainCamId()
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

    private fun startPreview(device: CameraDevice, view: TextureView, isPip: Boolean) {
        val tex = view.surfaceTexture ?: return
        val size = chooseSize(isPip)
        tex.setDefaultBufferSize(size.width, size.height)
        val surface = Surface(tex)
        try {
            val req = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface)
                set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                if (!isPip && mainIsBack) {
                    try {
                        set(CaptureRequest.FLASH_MODE, flashValue())
                    } catch (_: Exception) {}
                }
            }
            device.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    if (isPip) pipSess = s else mainSess = s
                    try {
                        s.setRepeatingRequest(req.build(), null, bgHandler)
                        if (!isPip) applyZoom()
                    } catch (e: Exception) {
                        Log.e(TAG, "repeat", e)
                    }
                }
                override fun onConfigureFailed(s: CameraCaptureSession) {
                    status.text = if (isPip) "PIP config fail" else "Main config fail"
                }
            }, bgHandler)
        } catch (e: Exception) {
            Log.e(TAG, "preview", e)
        }
    }

    private fun applyZoom() {
        val sess = mainSess ?: return
        val dev = mainDev ?: return
        try {
            val id = mainCamId() ?: return
            val chars = mgr!!.getCameraCharacteristics(id)
            val maxZoom = chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
            val active = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return
            val centerX = active.width() / 2
            val centerY = active.height() / 2
            val cropW = (active.width() / (1f + zoomLevel * (maxZoom - 1f))).toInt()
            val cropH = (active.height() / (1f + zoomLevel * (maxZoom - 1f))).toInt()
            val left = centerX - cropW / 2
            val top = centerY - cropH / 2
            val crop = android.graphics.Rect(left, top, left + cropW, top + cropH)
            val tex = mainPreview.surfaceTexture ?: return
            val surface = Surface(tex)
            val req = dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface)
                set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                set(CaptureRequest.SCALER_CROP_REGION, crop)
                if (mainIsBack) {
                    try { set(CaptureRequest.FLASH_MODE, flashValue()) } catch (_: Exception) {}
                }
            }
            sess.setRepeatingRequest(req.build(), null, bgHandler)
        } catch (e: Exception) {
            Log.e(TAG, "zoom", e)
        }
    }

    private fun restartMainPreview() {
        val dev = mainDev ?: return
        try { mainSess?.close() } catch (_: Exception) {}
        startPreview(dev, mainPreview, false)
    }

    private fun closeAll() {
        try {
            lock.acquire()
            mainSess?.close(); pipSess?.close()
            mainDev?.close(); pipDev?.close()
            mainSess = null; pipSess = null
            mainDev = null; pipDev = null
        } catch (_: Exception) {
        } finally {
            lock.release()
        }
    }

    override fun onResume() {
        super.onResume()
        if (PERMS.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) {
            startBg()
            if (mainPreview.isAvailable) {
                findIds(); openMain()
                if (dualOn) openPip()
            } else setupMain()
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
