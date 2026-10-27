package com.shilapi.xcertplay

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.net.VpnService
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.iap2.session.Iap2Session
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.mfi.Iap2MfiAuthenticationClient
import com.shilapi.xcertplay.mfi.LocalMfiAuthenticationClient
import com.shilapi.xcertplay.network.CarPlayVpnService
import com.shilapi.xcertplay.transport.*
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Three-minute USB media experiment; launch explicitly through adb, never auto-start. */
class MacUsbCarPlayActivity : Activity(), SurfaceHolder.Callback {
    private val main = Handler(Looper.getMainLooper())
    private val surfaceReady = CountDownLatch(1)
    private val serviceReady = CountDownLatch(1)
    private val touches = Executors.newSingleThreadExecutor()
    private lateinit var video: SurfaceView
    private lateinit var status: TextView
    private lateinit var report: File
    @Volatile private var vpn: CarPlayVpnService? = null
    @Volatile private var control: Iap2Session? = null
    @Volatile private var transport: LoopbackDuplexByteStream? = null
    @Volatile private var ethernet: LoopbackEthernetTransport? = null
    @Volatile private var sink: AndroidMediaSink? = null
    @Volatile private var activeSession: AirPlaySession? = null
    @Volatile private var stopped = false
    private var bound = false
    private val cleanupLock = Any()
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            vpn = (binder as CarPlayVpnService.LocalBinder).service
            serviceReady.countDown()
        }
        override fun onServiceDisconnected(name: ComponentName?) { vpn = null; stopExperiment() }
    }
    private val watchdog = Runnable { line("Three-minute lab limit reached"); stopExperiment() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_FULLSCREEN)
        report = File(filesDir, "mac-usb-media.log").apply { writeText("") }
        video = SurfaceView(this).apply { holder.addCallback(this@MacUsbCarPlayActivity) }
        status = TextView(this).apply {
            textSize = 16f; setTextColor(android.graphics.Color.WHITE)
            setBackgroundColor(0xb0000000.toInt()); setPadding(16, 8, 16, 8)
        }
        setContentView(FrameLayout(this).apply {
            addView(video, FrameLayout.LayoutParams(-1, -1))
            addView(status, FrameLayout.LayoutParams(-1, -2))
        })
        video.setOnTouchListener { view, event ->
            val released = event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL
            val contacts = if (released) emptyList() else (0 until event.pointerCount)
                .filter { !(event.actionMasked == MotionEvent.ACTION_POINTER_UP && it == event.actionIndex) }
                .map { AirPlayContact(event.getPointerId(it),
                    (event.getX(it) / view.width.coerceAtLeast(1)).toDouble().coerceIn(0.0, 1.0),
                    (event.getY(it) / view.height.coerceAtLeast(1)).toDouble().coerceIn(0.0, 1.0), true) }
            if (!stopped) touches.execute { activeSession?.sendTouch(contacts) }
            if (released) view.performClick()
            true
        }
        line("Mac USB CarPlay lab — waiting for local VPN permission")
        val permission = VpnService.prepare(this)
        if (permission != null) startActivityForResult(permission, 7) else begin()
    }

    @Deprecated("Lab uses the platform Activity permission callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 7 && resultCode == RESULT_OK) begin()
        else line("Local VPN permission was not granted")
    }

    private fun begin() {
        bound = bindService(Intent(this, CarPlayVpnService::class.java), connection, BIND_AUTO_CREATE)
        if (!bound) { line("FAILED: could not bind local VPN service"); return }
        main.postDelayed(watchdog, 180_000)
        Thread(::runExperiment, "mac-usb-media").start()
    }

    @Synchronized private fun line(message: String) {
        val safe = message.replace(Regex("usbTransport=\\S+"), "usbTransport=<redacted>")
        Log.i("DiPlayMacUsbMedia", safe)
        report.appendText(safe + "\n")
        main.post { if (!isDestroyed) status.text = safe }
    }

    private fun runExperiment() {
        try {
            check(surfaceReady.await(10, TimeUnit.SECONDS) && serviceReady.await(10, TimeUnit.SECONDS)) {
                "Surface or VPN service did not become ready"
            }
            check(!stopped)
            val directory = File(noBackupFilesDir, LocalMfiAuthenticationClient.DIRECTORY).apply { mkdirs() }
            for (name in listOf("identity.pk8", "certificate.p7b")) {
                assets.open("offline-mfi/$name").use { source ->
                    File(directory, name).outputStream().use { source.copyTo(it) }
                }
            }
            val mfi = LocalMfiAuthenticationClient.load(directory)
            val frames = LoopbackEthernetTransport().also { ethernet = it }
            if (stopped) { frames.close(); return }
            val mac = frames.hostMac.joinToString(":") { "%02x".format(it.toInt() and 255) }
            line("NCM relay connected")
            val identity = AirPlayPersistence.loadIdentity(this)
            val pairings = AirPlayPersistence.loadPairings(this) { id, key -> AirPlayPersistence.savePairing(this, id, key) }
            val displayWidth = video.width.coerceAtLeast(640)
            val displayHeight = video.height.coerceAtLeast(360)
            val config = AirPlayConfig("DiPlay Mac Lab", mac, mac, "950.7.1",
                AirPlayDisplayConfig(displayWidth, displayHeight, fps = 30), microphone = false)
            val mediaSink = AndroidMediaSink(video.holder.surface, videoWidth = displayWidth, videoHeight = displayHeight,
                context = this, onAudioDiagnostic = { line("Audio: $it") }).also { sink = it }
            val media = CarPlayMediaEngine(mediaSink, microphoneEnabled = false)
            val listener = object : AirPlaySessionListener {
                override fun onTcpAccepted(event: AirPlayTcpAccepted) { line("AirPlay TCP accepted") }
                override fun onSessionActive(session: AirPlaySession) {
                    activeSession = session; line("AirPlay session active — waiting for first video frame")
                }
                override fun onVideoFrameRendered(session: AirPlaySession) { line("PASS: first CarPlay video frame rendered") }
                override fun onSessionEnded(session: AirPlaySession) { line("AirPlay session ended") }
                override fun onTransportError(message: String) { line("AirPlay transport error: $message") }
            }
            val service = checkNotNull(vpn)
            val result = service.attach(frames, "fe80::d1:1", frames.hostMac, config, identity, pairings, mfi, listener, media)
            check(result is CarPlayVpnService.AttachResult.Started) { "VPN attach failed: $result ${service.lastAttachFailure}" }
            line("NCM/VPN and AirPlay listener ready")
            val stream = LoopbackDuplexByteStream(19777).also { transport = it }
            if (stopped) { stream.close(); return }
            val csm = Iap2Session.open(stream).also { control = it }
            val endpoint = Iap2WiredCarPlayEndpoint(listOf("fe80::d1:1"), service.boundPort() ?: 7000,
                identity.publicKeyHex, config.sourceVersion, mac)
            val identification = Iap2IdentificationConfig("DiPlay Mac Lab", "DiPlay", "DiPlay", "MAC-USB-LAB",
                "0.2.21", "1", carPlayUsbInterfaceNumber = 3)
            csm.use {
                Iap2WiredControlClient(it, Iap2MfiAuthenticationClient(mfi)).run(
                    identification, endpoint, 500, timeoutMillis = 150_000, onProgress = ::line)
            }
        } catch (error: Exception) {
            if (!stopped) line("FAILED: ${error.javaClass.simpleName}: ${error.message}")
        } finally {
            main.removeCallbacks(watchdog)
            stopExperiment()
        }
    }

    private fun stopExperiment() = synchronized(cleanupLock) {
        stopped = true
        runCatching { transport?.close() }
        runCatching { control?.close() }
        runCatching { ethernet?.close() }
        runCatching { vpn?.detach() }
        runCatching { sink?.close() }
        Unit
    }

    override fun surfaceCreated(holder: SurfaceHolder) { surfaceReady.countDown() }
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
    override fun surfaceDestroyed(holder: SurfaceHolder) { stopExperiment() }
    override fun onDestroy() {
        stopExperiment()
        main.removeCallbacksAndMessages(null)
        touches.shutdownNow()
        if (bound) unbindService(connection)
        super.onDestroy()
    }
}
