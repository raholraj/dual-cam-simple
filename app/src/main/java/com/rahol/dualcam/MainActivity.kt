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
    private val uiHandler = Handler(android.os.Looper.getMainLooper())

    private var dX = 0f
    private var dY = 0f

    private var isPhotoMode = true
    private var isRecording = false
    private var dualOn = false
    private var gridOn = false
    private var mainIsBack = true
    private var flashMode = 0
    private var zoomLevel = 0f
    private var dualOpening = false

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
            photoViewer.visibility = View.GONE
            thumbPreview.setImageDrawable(null)
        }
        thumbPreview.setOnClickListener {
            if (thumbPreview.drawable != null) photoViewer.visibility = View.VISIBLE
        }

        pip.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    dX = v.x - e.rawX; dY = v.y - e.rawY; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val nx = (e.rawX + dX).coerceIn(0f, (rootWidth() - v.width).toFloat().coerceAtLeast(0f))
                    val ny = (e.rawY + dY).coerceIn(0f, (rootHeight() - v.height).toFloat().coerceAtLeast(0f))
                    v.x = nx; v.y = ny
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

    private fun rootWidth() = findViewById<View>(R.id.root).width
    private fun rootHeight() = findViewById<View>(R.id.root).height

    private fun setMode(photo: Boolean) {
        if (isRecording) return
        isPhotoMode = photo
        if (photo) {
            btnModePhoto.setTextColor(0xFFFFFFFF.toInt())
            btnModePhoto.setBackgroundColor(0x44FFFFFF)
            btnModeVideo.setTextColor(0x99FFFFFF.toInt())
            btnModeVideo.setBackgroundColor(0)
            shutterInner.setBackgroundResource(R.drawable.shutter_inner_photo)
        } else {
            btnModeVideo.setTextColor(0xFFFFFFFF.toInt())
            btnModeVideo.setBackgroundColor(0x44FFFFFF)
            btnModePhoto.setTextColor(0x99FFFFFF.toInt())
            btnModePhoto.setBackgroundColor(0)
            shutterInner.setBackgroundResource(R.drawable.shutter_inner_video)
        }
        updateStatus()
    }

    private fun toggleDual() {
        if (isRecording || dualOpening) return
        if (!dualOn) {
            dualOpening = true
            dualOn = true
            btnDual.text = "Dual ON"
            btnDual.setBackgroundColor(0xAAE53935.toInt())
            status.text = "Opening dual…"
            pip.alpha = 0f
            pip.visibility = View.VISIBLE
            pip.animate().alpha(1f).setDuration(250).start()
            uiHandler.postDelayed({ tryOpenPip() }, 350)
        } else {
            dualOn = false
            dualOpening = false
            btnDual.text = "Dual"
            btnDual.setBackgroundColor(0x33FFFFFF)
            pip.animate().alpha(0f).setDuration(200).withEndAction {
                pip.visibility = View.GONE
                closePipOnly()
            }.start()
            updateStatus()
        }
    }

    private fun tryOpenPip() {
        if (!dualOn) { dualOpening = false; return }
        if (pipPreview.isAvailable) {
            openPip()
        } else {
            uiHandler.postDelayed({
                if (dualOn) {
                    if (pipPreview.isAvailable) openPip()
                    else {
                        pipPreview.requestLayout()
                        uiHandler.postDelayed({
                            if (dualOn) openPip() else dualOpening = false
                        }, 300)
                    }
                } else dualOpening = false
            }, 200)
        }
    }

    private fun switchCameras() {
        if (isRecording || dualOpening) return
        mainIsBack = !mainIsBack
        if (dualOn) {
            dualOn = false
            closePipOnly()
            pip.visibility = View.GONE
            btnDual.text = "Dual"
            btnDual.setBackgroundColor(0x33FFFFFF)
        }
        closeMainOnly()
        uiHandler.postDelayed({
            openMain()
            updateStatus()
        }, 250)
    }

    private fun cycleFlash() {
        flashMode = (flashMode + 1) % 3
        btnFlash.alpha = when (flashMode) {
            1 -> 1f
            2 -> 0.7f
            else -> 0.45f
        }
        restartMainPreview()
    }

    private fun toggleGrid() {
        gridOn = !gridOn
        gridOverlay.visibility = if (gridOn) View.VISIBLE else View.GONE
    }

    private fun showSettings() {
        AlertDialog.Builder(this)
            .setTitle("Settings")
            .setItems(arrayOf("Flash: ${flashLabel()}", "Grid: ${if (gridOn) "ON" else "OFF"}", "About")) { _, which ->
                when (which) {
                    0 -> cycleFlash()
                    1 -> toggleGrid()
                    2 -> Toast.makeText(this, "Dual Cam v2 · Camera2", Toast.LENGTH_SHORT).show()
                }
            }.show()
    }

    private fun flashLabel() = when (flashMode) {
        1 -> "On"; 2 -> "Auto"; else -> "Off"
    }

    private fun onCaptureClick() {
        if (isPhotoMode) {
            shutterInner.animate().scaleX(0.85f).scaleY(0.85f).setDuration(80)
                .withEndAction {
                    shutterInner.animate().scaleX(1f).scaleY(1f).setDuration(80).start()
                }.start()
        } else {
            if (!isRecording) {
                isRecording = true
                shutterInner.setBackgroundResource(R.drawable.shutter_inner_recording)
                status.text = "Recording…"
            } else {
                isRecording = false
                shutterInner.setBackgroundResource(R.drawable.shutter_inner_video)
                updateStatus()
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
        if (bgThread != null) return
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
                if (dualOn && dualOpening && pipDev == null) openPip()
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
            Log.d(TAG, "cameras back=$backId front=$frontId")
        } catch (e: Exception) {
            status.text = "Camera list error"
        }
    }

    private fun mainCamId(): String? = if (mainIsBack) backId else frontId
    private fun pipCamId(): String? = if (mainIsBack) frontId else backId

    private fun openMain() {
        val id = mainCamId() ?: return
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        if (mainDev != null) return
        try {
            if (!lock.tryAcquire(4, TimeUnit.SECONDS)) {
                status.text = "Camera busy"
                return
            }
            mgr!!.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(c: CameraDevice) {
                    lock.release()
                    mainDev = c
                    startPreview(c, mainPreview, false)
                    runOnUiThread { updateStatus() }
                }
                override fun onDisconnected(c: CameraDevice) {
                    lock.release(); safeClose(c); mainDev = null
                }
                override fun onError(c: CameraDevice, e: Int) {
                    lock.release(); safeClose(c); mainDev = null
                    runOnUiThread { status.text = "Main error $e" }
                }
            }, bgHandler)
        } catch (e: Exception) {
            try { lock.release() } catch (_: Exception) {}
            status.text = "Main fail"
            Log.e(TAG, "openMain", e)
        }
    }

    private fun openPip() {
        if (!dualOn) { dualOpening = false; return }
        val id = pipCamId()
        if (id == null) {
            failDual("No second camera")
            return
        }
        if (pipDev != null) { dualOpening = false; return }
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            dualOpening = false
            return
        }
        val tex = pipPreview.surfaceTexture
        if (tex == null) {
            uiHandler.postDelayed({ if (dualOn && pipDev == null) openPip() else dualOpening = false }, 400)
            return
        }
        try {
            if (!lock.tryAcquire(4, TimeUnit.SECONDS)) {
                failDual("Camera lock timeout")
                return
            }
            mgr!!.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(c: CameraDevice) {
                    lock.release()
                    pipDev = c
                    dualOpening = false
                    startPreview(c, pipPreview, true)
                    runOnUiThread { status.text = "Dual ON · ready" }
                }
                override fun onDisconnected(c: CameraDevice) {
                    lock.release(); safeClose(c); pipDev = null
                }
                override fun onError(c: CameraDevice, e: Int) {
                    lock.release()
                    safeClose(c)
                    pipDev = null
                    Log.e(TAG, "PIP error code=$e")
                    runOnUiThread {
                        failDual(if (e == 2) "Device max cameras (err 2)" else "Dual fail err $e")
                    }
                }
            }, bgHandler)
        } catch (e: Exception) {
            try { lock.release() } catch (_: Exception) {}
            failDual("PIP open fail")
            Log.e(TAG, "openPip", e)
        }
    }

    private fun failDual(msg: String) {
        dualOn = false
        dualOpening = false
        btnDual.text = "Dual"
        btnDual.setBackgroundColor(0x33FFFFFF)
        pip.visibility = View.GONE
        closePipOnly()
        status.text = msg
    }

    private fun closePipOnly() {
        try { pipSess?.stopRepeating() } catch (_: Exception) {}
        try { pipSess?.close() } catch (_: Exception) {}
        try { pipDev?.close() } catch (_: Exception) {}
        pipSess = null
        pipDev = null
    }

    private fun closeMainOnly() {
        try { mainSess?.stopRepeating() } catch (_: Exception) {}
        try { mainSess?.close() } catch (_: Exception) {}
        try { mainDev?.close() } catch (_: Exception) {}
        mainSess = null
        mainDev = null
    }

    private fun safeClose(c: CameraDevice) {
        try { c.close() } catch (_: Exception) {}
    }

    private fun chooseSize(isPip: Boolean): Size {
        val id = if (isPip) pipCamId() else mainCamId()
        return try {
            val map = mgr!!.getCameraCharacteristics(id!!)
                .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val sizes = map!!.getOutputSizes(SurfaceTexture::class.java)
            if (isPip) {
                sizes.firstOrNull { it.width <= 640 && it.height <= 480 }
                    ?: sizes.minByOrNull { it.width * it.height }
                    ?: Size(640, 480)
            } else {
                sizes.firstOrNull { it.width <= 1280 && it.height <= 720 }
                    ?: sizes.minByOrNull { Math.abs(it.width * it.height - 1280 * 720) }
                    ?: Size(1280, 720)
            }
        } catch (_: Exception) {
            if (isPip) Size(640, 480) else Size(1280, 720)
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
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                if (!isPip && mainIsBack) {
                    try { set(CaptureRequest.FLASH_MODE, flashValue()) } catch (_: Exception) {}
                }
            }
            device.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    if (isPip) pipSess = s else mainSess = s
                    try {
                        s.setRepeatingRequest(req.build(), null, bgHandler)
                    } catch (e: Exception) {
                        Log.e(TAG, "repeat", e)
                    }
                }
                override fun onConfigureFailed(s: CameraCaptureSession) {
                    runOnUiThread {
                        status.text = if (isPip) "PIP config fail" else "Main config fail"
                    }
                }
            }, bgHandler)
        } catch (e: Exception) {
            Log.e(TAG, "preview", e)
        }
    }

    private fun restartMainPreview() {
        val dev = mainDev ?: return
        try { mainSess?.close() } catch (_: Exception) {}
        mainSess = null
        startPreview(dev, mainPreview, false)
    }

    private fun closeAll() {
        try {
            lock.tryAcquire(2, TimeUnit.SECONDS)
            closePipOnly()
            closeMainOnly()
        } catch (_: Exception) {
        } finally {
            try { lock.release() } catch (_: Exception) {}
        }
    }

    override fun onResume() {
        super.onResume()
        if (PERMS.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) {
            startBg()
            if (mainPreview.isAvailable) {
                findIds()
                if (mainDev == null) openMain()
            } else setupMain()
        }
    }

    override fun onPause() {
        dualOn = false
        dualOpening = false
        closeAll()
        bgThread?.quitSafely()
        bgThread = null
        bgHandler = null
        super.onPause()
    }
}
