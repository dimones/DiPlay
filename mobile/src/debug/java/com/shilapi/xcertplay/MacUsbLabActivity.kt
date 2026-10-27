package com.shilapi.xcertplay

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView
import com.shilapi.xcertplay.iap2.session.Iap2Session
import com.shilapi.xcertplay.mfi.Iap2MfiAuthenticationClient
import com.shilapi.xcertplay.mfi.LocalMfiAuthenticationClient
import com.shilapi.xcertplay.transport.Iap2IdentificationClient
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import com.shilapi.xcertplay.transport.LoopbackDuplexByteStream
import java.io.File

/** Explicit shell-only diagnostic entry point. Excluded from release builds. */
class MacUsbLabActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var output: TextView
    @Volatile private var stream: LoopbackDuplexByteStream? = null
    @Volatile private var session: Iap2Session? = null
    @Volatile private var stopped = false
    private lateinit var report: File
    private val watchdog = Runnable {
        stopped = true
        stream?.close()
        session?.close()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        output = TextView(this).apply { textSize = 18f; setPadding(24, 24, 24, 24) }
        setContentView(ScrollView(this).apply { addView(output) })
        report = File(filesDir, "mac-usb-lab.log").apply { writeText("") }
        line("DiPlay Mac USB lab — control channel only")
        handler.postDelayed(watchdog, 100_000)
        Thread({ runProbe() }, "mac-usb-lab").start()
    }

    @Synchronized private fun line(message: String) {
        Log.i("DiPlayMacUsbLab", message)
        report.appendText(message + "\n")
        handler.post { if (!isDestroyed) output.append(message + "\n") }
    }

    private fun runProbe() {
        try {
            val directory = File(noBackupFilesDir, LocalMfiAuthenticationClient.DIRECTORY)
            directory.mkdirs()
            for (name in listOf("identity.pk8", "certificate.p7b")) {
                assets.open("offline-mfi/$name").use { source ->
                    File(directory, name).outputStream().use { source.copyTo(it) }
                }
            }
            val identity = LocalMfiAuthenticationClient.load(directory)
            line("Runtime identity loaded")
            val transport = LoopbackDuplexByteStream(19777)
            stream = transport
            if (stopped) { transport.close(); return }
            line("Mac relay connected")
            val control = Iap2Session.open(transport)
            session = control
            control.use {
                check(it.awaitReady(20_000)) { "iAP2 link readiness timeout" }
                line("iAP2 link ready")
                val config = Iap2IdentificationConfig(
                    name = "DiPlay Mac Lab", modelIdentifier = "DiPlay",
                    manufacturer = "DiPlay", serialNumber = "MAC-USB-LAB",
                    firmwareVersion = "0.2.21", hardwareVersion = "1",
                    carPlayUsbInterfaceNumber = 3,
                )
                Iap2IdentificationClient(it).identify(config, 20_000, ::line)
                line("iAP2 identification accepted")
                Iap2MfiAuthenticationClient(identity).run(it, 30_000, ::line)
                line("PASS: iPhone accepted iAP2 accessory authentication")
                line("Media not started; ending bounded diagnostic session")
            }
        } catch (error: Exception) {
            line("FAILED: ${error.javaClass.simpleName}: ${error.message}")
        } finally {
            stream?.close()
            handler.removeCallbacks(watchdog)
        }
    }

    override fun onDestroy() {
        stopped = true
        stream?.close()
        session?.close()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
