package com.stagemic.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Bundle
import android.os.PowerManager
import android.text.format.Formatter
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.stagemic.app.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var streamer: AudioStreamer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var nsdManager: NsdManager? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var resolving = false

    // State machine
    private enum class AppState { IDLE, CONNECTED, TALKING }
    private var state = AppState.IDLE

    companion object {
        const val PREFS_NAME = "stagemic_prefs"
        const val PREF_LAST_URL = "last_url"
    }

    // ─── Permission handling ─────────────────────────────────────────────────

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            connectAndStream()
        } else {
            showToast("Microphone permission is required to stream audio")
        }
    }

    // ─── Lifecycle ───────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        restoreLastIp()
        showPhoneIp()
        setupUI()
        startReceiverDiscovery()
    }

    override fun onDestroy() {
        super.onDestroy()
        disconnectAndStop()
        stopReceiverDiscovery()
    }

    // ─── UI setup ────────────────────────────────────────────────────────────

    private fun setupUI() {
        // Connect button
        binding.btnConnect.setOnClickListener {
            when (state) {
                AppState.IDLE -> handleConnectPressed()
                AppState.CONNECTED, AppState.TALKING -> handleDisconnectPressed()
            }
            binding.btnDiscover.setOnClickListener { startReceiverDiscovery() }
        }

        // Push-to-talk button — hold to speak
        binding.btnTalk.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startTalking()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    stopTalking()
                    true
                }
                else -> false
            }
        }

        updateUI()
    }

    private fun updateUI() {
        when (state) {
            AppState.IDLE -> {
                binding.btnConnect.text = "CONNECT"
                binding.btnConnect.isEnabled = true
                binding.btnTalk.visibility = View.GONE
                binding.tvStatus.text = "Enter laptop IP and connect"
                binding.statusDot.setBackgroundResource(R.drawable.dot_disconnected)
                binding.levelBar.progress = 0
                binding.tvLatency.text = ""
            }
            AppState.CONNECTED -> {
                binding.btnConnect.text = "DISCONNECT"
                binding.btnConnect.isEnabled = true
                binding.btnTalk.visibility = View.VISIBLE
                binding.btnTalk.text = "HOLD TO TALK"
                binding.btnTalk.alpha = 1.0f
                binding.tvStatus.text = "Connected · Hold button to speak"
                binding.statusDot.setBackgroundResource(R.drawable.dot_connected)
                binding.levelBar.progress = 0
            }
            AppState.TALKING -> {
                binding.btnConnect.text = "DISCONNECT"
                binding.btnConnect.isEnabled = true
                binding.btnTalk.visibility = View.VISIBLE
                binding.btnTalk.text = "🎤 LIVE"
                binding.btnTalk.alpha = 0.7f
                binding.tvStatus.text = "Streaming audio…"
                binding.statusDot.setBackgroundResource(R.drawable.dot_talking)
            }
        }
    }

    // ─── Connection logic ────────────────────────────────────────────────────

    private fun handleConnectPressed() {
        val url = binding.etIpAddress.text.toString().trim()
        if (url.isEmpty()) {
            showToast("Enter the HTTPS receiver link")
            return
        }

        if (!isValidReceiverUrl(url)) {
            showToast("Enter a valid HTTPS link ending in trycloudflare.com")
            return
        }

        saveUrl(url)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            connectAndStream()
        }
    }

    private fun startReceiverDiscovery() {
        stopReceiverDiscovery()
        nsdManager = getSystemService(Context.NSD_SERVICE) as NsdManager
        binding.btnDiscover.text = "SEARCHING FOR RECEIVERS…"
        binding.btnDiscover.isEnabled = false
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceType == "_stagemic._tcp." && !resolving) {
                    resolving = true
                    nsdManager?.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                            resolving = false
                        }
                        override fun onServiceResolved(info: NsdServiceInfo) {
                            resolving = false
                            val host = info.host?.hostAddress ?: return
                            val url = "ws://$host:${info.port}/ws/audio"
                            runOnUiThread {
                                binding.etIpAddress.setText(url)
                                saveUrl(url)
                                binding.tvStatus.text = "Found ${info.serviceName}"
                                showToast("Receiver found on Wi-Fi")
                            }
                            stopReceiverDiscovery()
                        }
                    })
                }
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                runOnUiThread { showToast("Wi-Fi receiver discovery failed") }
                stopReceiverDiscovery()
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                resolving = false
            }
        }
        discoveryListener = listener
        nsdManager?.discoverServices("_stagemic._tcp.", NsdManager.PROTOCOL_DNS_SD, listener)
    }

    private fun stopReceiverDiscovery() {
        discoveryListener?.let { listener ->
            try {
                nsdManager?.stopServiceDiscovery(listener)
            } catch (_: IllegalArgumentException) {
                // Discovery may already have stopped after a resolution.
            }
        }
        discoveryListener = null
        resolving = false
        if (::binding.isInitialized) {
            binding.btnDiscover.text = "FIND RECEIVER ON WI-FI"
            binding.btnDiscover.isEnabled = true
        }
    }

    private fun connectAndStream() {
        val url = binding.etIpAddress.text.toString().trim()

        // Acquire wake lock so CPU doesn't sleep while streaming
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "StageMic::StreamingLock")
        wakeLock?.acquire(60 * 60 * 1000L) // max 1 hour

        streamer = AudioStreamer(
            receiverUrl = url,
            onLevelUpdate = { level ->
                runOnUiThread {
                    binding.levelBar.progress = level
                }
            },
            onError = { message ->
                runOnUiThread {
                    showToast("Error: $message")
                    disconnectAndStop()
                }
            }
        )

        state = AppState.CONNECTED
        updateUI()

    }

    private fun handleDisconnectPressed() {
        disconnectAndStop()
    }

    private fun disconnectAndStop() {
        stopTalking()
        streamer?.stop()
        streamer = null
        wakeLock?.release()
        wakeLock = null
        state = AppState.IDLE
        updateUI()
    }

    // ─── Push-to-talk ────────────────────────────────────────────────────────

    private fun startTalking() {
        if (state != AppState.CONNECTED) return
        state = AppState.TALKING
        updateUI()
        streamer?.start(lifecycleScope)
    }

    private fun stopTalking() {
        if (state != AppState.TALKING) return
        streamer?.stop()
        // Re-create streamer with same config so it's ready for next press
        val url = binding.etIpAddress.text.toString().trim()
        streamer = AudioStreamer(
            receiverUrl = url,
            onLevelUpdate = { level ->
                runOnUiThread { binding.levelBar.progress = level }
            },
            onError = { message ->
                runOnUiThread {
                    showToast("Error: $message")
                    disconnectAndStop()
                }
            }
        )
        state = AppState.CONNECTED
        updateUI()
        binding.levelBar.progress = 0
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private fun showPhoneIp() {
        try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val ip = Formatter.formatIpAddress(wifiManager.connectionInfo.ipAddress)
            binding.tvPhoneIp.text = "This phone's IP: $ip"
        } catch (e: Exception) {
            binding.tvPhoneIp.text = "Connect to Wi-Fi first"
        }
    }

    private fun restoreLastIp() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastUrl = prefs.getString(PREF_LAST_URL, "") ?: ""
        if (lastUrl.isNotEmpty()) {
            binding.etIpAddress.setText(lastUrl)
        }
    }

    private fun saveUrl(url: String) {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_LAST_URL, url)
            .apply()
    }

    private fun isValidReceiverUrl(url: String): Boolean {
        return (url.startsWith("https://") || url.startsWith("http://") ||
            url.startsWith("wss://") || url.startsWith("ws://")) &&
            url.length > 10
    }

    private fun showToast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
