package com.codex.phonebridge

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** Process-local input snapshot shared by the accessibility and network services. */
object RelaySession {
    @Volatile var host: String = ""
    @Volatile var pairingCode: String = ""
    @Volatile var token: String = ""
    @Volatile var connected: Boolean = false
    private val buttons = ConcurrentHashMap.newKeySet<String>()
    private val axes = ConcurrentHashMap<String, Float>()

    init { resetAxes() }

    @Synchronized fun clear() {
        buttons.clear()
        axes.clear()
        resetAxes()
    }

    private fun resetAxes() {
        for (name in listOf("lx", "ly", "rx", "ry", "lt", "rt")) axes[name] = 0f
    }

    @Synchronized fun setButton(name: String, down: Boolean) {
        if (down) buttons.add(name) else buttons.remove(name)
    }

    @Synchronized fun setAxes(values: Map<String, Float>) {
        values.forEach { (key, value) -> axes[key] = value.coerceIn(-1f, 1f) }
    }

    @Synchronized fun inputPacket(): JSONObject {
        val buttonArray = JSONArray()
        buttons.sorted().forEach(buttonArray::put)
        val axisObject = JSONObject()
        axes.forEach { (key, value) -> axisObject.put(key, value.toDouble()) }
        return JSONObject().put("type", "input").put("buttons", buttonArray).put("axes", axisObject)
    }
}
