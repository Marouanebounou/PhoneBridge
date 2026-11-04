package com.codex.phonebridge

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.hardware.input.InputManager
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityEvent

class GamepadAccessibilityService : AccessibilityService(), InputManager.InputDeviceListener {
    private var lastDeviceId: Int? = null
    private val inputManager by lazy { getSystemService(INPUT_SERVICE) as InputManager }

    override fun onServiceConnected() {
        super.onServiceConnected()
        inputManager.registerInputDeviceListener(this, Handler(Looper.getMainLooper()))
        serviceInfo = serviceInfo.apply {
            // Declaring canRequestFilterKeyEvents in the service XML grants this capability;
            // request the runtime flag too, so button keys reach onKeyEvent and can be consumed.
            flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
            if (Build.VERSION.SDK_INT >= 34) {
                setMotionEventSources(InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_JOYSTICK or InputDevice.SOURCE_DPAD)
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    override fun onInputDeviceAdded(deviceId: Int) = Unit

    override fun onInputDeviceChanged(deviceId: Int) = Unit

    override fun onInputDeviceRemoved(deviceId: Int) {
        if (lastDeviceId == deviceId) {
            RelaySession.clear()
            lastDeviceId = null
        }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val device = InputDevice.getDevice(event.deviceId) ?: return false
        if (!device.supportsSource(InputDevice.SOURCE_GAMEPAD) && !device.supportsSource(InputDevice.SOURCE_DPAD)) return false
        val name = keyName(event.keyCode) ?: return false
        lastDeviceId = event.deviceId
        if (event.action == KeyEvent.ACTION_DOWN) RelaySession.setButton(name, true)
        if (event.action == KeyEvent.ACTION_UP) RelaySession.setButton(name, false)
        return true // consume gamepad buttons so they control the PC instead of navigating Android
    }

    override fun onMotionEvent(event: MotionEvent) {
        if (Build.VERSION.SDK_INT < 34) return
        val device = InputDevice.getDevice(event.deviceId) ?: return
        if (!device.supportsSource(InputDevice.SOURCE_JOYSTICK) && !device.supportsSource(InputDevice.SOURCE_GAMEPAD)) return
        lastDeviceId = event.deviceId
        val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
        RelaySession.setButton("DPAD_LEFT", hatX < -0.5f)
        RelaySession.setButton("DPAD_RIGHT", hatX > 0.5f)
        RelaySession.setButton("DPAD_UP", hatY < -0.5f)
        RelaySession.setButton("DPAD_DOWN", hatY > 0.5f)
        RelaySession.setAxes(
            mapOf(
                "lx" to event.getAxisValue(MotionEvent.AXIS_X),
                "ly" to event.getAxisValue(MotionEvent.AXIS_Y),
                "rx" to (event.getAxisValue(MotionEvent.AXIS_Z).takeIf { it != 0f } ?: event.getAxisValue(MotionEvent.AXIS_RX)),
                "ry" to (event.getAxisValue(MotionEvent.AXIS_RZ).takeIf { it != 0f } ?: event.getAxisValue(MotionEvent.AXIS_RY)),
                "lt" to maxOf(event.getAxisValue(MotionEvent.AXIS_LTRIGGER), event.getAxisValue(MotionEvent.AXIS_BRAKE)),
                "rt" to maxOf(event.getAxisValue(MotionEvent.AXIS_RTRIGGER), event.getAxisValue(MotionEvent.AXIS_GAS))
            )
        )
    }

    private fun keyName(code: Int): String? = when (code) {
        KeyEvent.KEYCODE_BUTTON_A -> "A"
        KeyEvent.KEYCODE_BUTTON_B -> "B"
        KeyEvent.KEYCODE_BUTTON_X -> "X"
        KeyEvent.KEYCODE_BUTTON_Y -> "Y"
        KeyEvent.KEYCODE_BUTTON_L1 -> "LB"
        KeyEvent.KEYCODE_BUTTON_R1 -> "RB"
        KeyEvent.KEYCODE_BUTTON_L2 -> "LT_BUTTON"
        KeyEvent.KEYCODE_BUTTON_R2 -> "RT_BUTTON"
        KeyEvent.KEYCODE_BUTTON_THUMBL -> "LS"
        KeyEvent.KEYCODE_BUTTON_THUMBR -> "RS"
        KeyEvent.KEYCODE_BUTTON_SELECT -> "BACK"
        KeyEvent.KEYCODE_BUTTON_START -> "START"
        KeyEvent.KEYCODE_BUTTON_MODE -> "GUIDE"
        KeyEvent.KEYCODE_DPAD_UP -> "DPAD_UP"
        KeyEvent.KEYCODE_DPAD_DOWN -> "DPAD_DOWN"
        KeyEvent.KEYCODE_DPAD_LEFT -> "DPAD_LEFT"
        KeyEvent.KEYCODE_DPAD_RIGHT -> "DPAD_RIGHT"
        else -> null
    }
}
