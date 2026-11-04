package com.codex.phonebridge

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var hostInput: EditText
    private lateinit var codeInput: EditText
    private lateinit var status: TextView
    private lateinit var serviceStatus: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val refreshTask = object : Runnable {
        override fun run() {
            refreshStatus()
            handler.postDelayed(this, 750)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        val saved = getSharedPreferences(RelayService.PREFS, MODE_PRIVATE)
        hostInput.setText(saved.getString("host", ""))
        codeInput.setText(saved.getString("pairing_code", ""))
        askForNotificationsIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(refreshTask)
        handler.post(refreshTask)
    }

    override fun onPause() {
        handler.removeCallbacks(refreshTask)
        super.onPause()
    }

    private fun buildUi() {
        val pad = (24 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            gravity = Gravity.CENTER_VERTICAL
        }
        root.addView(TextView(this).apply {
            text = "PhoneBridge"
            textSize = 30f
        })
        root.addView(TextView(this).apply {
            text = "Relay a Bluetooth controller to your Windows PC over local Wi-Fi. You can lock the screen while relaying."
            textSize = 16f
            setPadding(0, pad / 2, 0, pad)
        })

        hostInput = EditText(this).apply {
            hint = "PC address (example: 192.168.1.20)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
        }
        codeInput = EditText(this).apply {
            hint = "Six-digit pairing code"
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
        }
        root.addView(hostInput, fieldParams())
        root.addView(codeInput, fieldParams())

        root.addView(Button(this).apply {
            text = "Enable input capture"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }, fieldParams())
        serviceStatus = TextView(this).apply {
            textSize = 14f
            setPadding(0, 4, 0, pad / 2)
        }
        root.addView(serviceStatus)

        root.addView(Button(this).apply {
            text = "Connect and start relay"
            setOnClickListener { startRelay() }
        }, fieldParams())
        root.addView(Button(this).apply {
            text = "Stop relay"
            setOnClickListener {
                startService(Intent(this@MainActivity, RelayService::class.java).setAction(RelayService.ACTION_STOP))
                status.text = "Relay stopped."
            }
        }, fieldParams())
        status = TextView(this).apply {
            textSize = 16f
            setPadding(0, pad / 2, 0, 0)
        }
        root.addView(status)
        root.addView(TextView(this).apply {
            text = "PhoneBridge only sends controller input to the PC you pair with. The persistent notification shows when relay is active."
            textSize = 13f
            setPadding(0, pad, 0, 0)
        })
        setContentView(root)
        refreshStatus()
    }

    private fun fieldParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = (8 * resources.displayMetrics.density).toInt()
        }

    private fun startRelay() {
        if (hostInput.text.isNullOrBlank() || codeInput.text.length != 6) {
            status.text = "Enter the PC address and six-digit pairing code from the receiver window."
            return
        }
        if (!isCaptureServiceEnabled()) {
            status.text = "Enable PhoneBridge input capture in Accessibility settings, then return here."
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        val intent = Intent(this, RelayService::class.java)
            .setAction(RelayService.ACTION_START)
            .putExtra(RelayService.EXTRA_HOST, hostInput.text.toString().trim())
            .putExtra(RelayService.EXTRA_CODE, codeInput.text.toString().trim())
        getSharedPreferences(RelayService.PREFS, MODE_PRIVATE).edit()
            .putString("host", hostInput.text.toString().trim())
            .putString("pairing_code", codeInput.text.toString().trim())
            .apply()
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
        status.text = "Starting relay…"
    }

    private fun askForNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 70)
        }
    }

    private fun isCaptureServiceEnabled(): Boolean {
        val expected = ComponentName(this, GamepadAccessibilityService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    private fun refreshStatus() {
        if (!::serviceStatus.isInitialized) return
        serviceStatus.text = if (isCaptureServiceEnabled()) "Input capture: enabled" else "Input capture: needs enabling in Accessibility settings"
        if (::status.isInitialized) {
            val prefs = getSharedPreferences(RelayService.PREFS, MODE_PRIVATE)
            status.text = prefs.getString("status", "Ready") ?: "Ready"
        }
    }
}
