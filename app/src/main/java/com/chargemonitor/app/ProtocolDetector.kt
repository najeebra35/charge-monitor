package com.chargemonitor.app

import android.os.BatteryManager

data class ChargerInfo(val protocol: String, val source: String)

/**
 * Android has no public API that says "this is PD / QC / VOOC".
 * We combine vendor kernel flags (when readable) with the hidden
 * max_charging_voltage extra and a power-based fallback.
 */
object ProtocolDetector {

    private val bracket = Regex("\\[([^\\]]+)]")

    fun detect(plugged: Int, s: Map<String, String>, maxVmv: Int?, powerW: Float?): ChargerInfo {
        if (plugged == 0) return ChargerInfo("Not connected", "—")
        if (plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS) {
            return ChargerInfo("Wireless charging (Qi)", "Android API")
        }

        fun value(field: String): Pair<String, String>? =
            s.entries.firstOrNull { it.key.endsWith("/$field") }?.let { it.key to it.value }

        // --- Oppo / OnePlus / Realme ---
        listOf("voocchg_ing", "fastchg_status").forEach { f ->
            value(f)?.let { (k, v) ->
                if (v == "1") return ChargerInfo("VOOC / SuperVOOC / Warp charging", k)
            }
        }
        value("fast_chg_type")?.let { (k, v) ->
            if ((v.toIntOrNull() ?: 0) > 0) return ChargerInfo("VOOC / SuperVOOC (type $v)", k)
        }

        // --- Xiaomi / Redmi / Poco ---
        value("quick_charge_type")?.let { (k, v) ->
            val name = when (v.toIntOrNull()) {
                1 -> "Fast charge"
                2 -> "Flash charge"
                3 -> "Turbo charge"
                4 -> "Super / HyperCharge"
                else -> null
            }
            if (name != null) return ChargerInfo("Xiaomi $name", k)
        }

        // --- Samsung ---
        value("hv_charger_status")?.let { (k, v) ->
            when (v.toIntOrNull()) {
                1 -> return ChargerInfo("Samsung Adaptive Fast Charging (AFC)", k)
                2 -> return ChargerInfo("Samsung Super Fast Charging (PD PPS)", k)
                3 -> return ChargerInfo("Samsung Super Fast Charging 2.0 (PD PPS)", k)
                else -> {}
            }
        }

        // --- Qualcomm PD flag ---
        value("pd_active")?.let { (k, v) ->
            when (v.toIntOrNull()) {
                1 -> return ChargerInfo("USB Power Delivery (PD)", k)
                2 -> return ChargerInfo("USB PD PPS (Super Fast)", k)
                else -> {}
            }
        }

        // --- Charger type strings ---
        s["usb/usb_type"]?.let { v ->
            val active = bracket.find(v)?.groupValues?.get(1)
            if (active != null) mapType(active)?.let { return ChargerInfo(it, "usb/usb_type") }
        }
        for (key in listOf("usb/real_type", "usb/type", "ac/type", "usb/charger_type")) {
            s[key]?.let { v -> mapType(v)?.let { return ChargerInfo(it, key) } }
        }
        s.entries.firstOrNull { it.key.endsWith("/charger_type") }?.let { e ->
            mapType(e.value)?.let { return ChargerInfo(it, e.key) }
        }

        // --- Heuristics ---
        if (maxVmv != null && maxVmv > 5500) {
            return ChargerInfo(
                "Fast charger (~%.0f V high-voltage: PD / QC)".format(maxVmv / 1000f),
                "max_charging_voltage"
            )
        }
        if (powerW != null && powerW >= 15f) {
            return ChargerInfo("Fast charging (protocol hidden by phone)", "Power estimate")
        }
        return when (plugged) {
            BatteryManager.BATTERY_PLUGGED_AC -> ChargerInfo("Standard wall charger", "Android API")
            BatteryManager.BATTERY_PLUGGED_USB -> ChargerInfo("USB port (PC / Laptop)", "Android API")
            8 -> ChargerInfo("Dock", "Android API")
            else -> ChargerInfo("Unknown", "Android API")
        }
    }

    private fun mapType(t: String): String? {
        val u = t.uppercase()
        return when {
            "PPS" in u -> "USB PD PPS (Super Fast)"
            "PD" in u -> "USB Power Delivery (PD)"
            "HVDCP_3P5" in u || "HVDCP_3.5" in u -> "Qualcomm Quick Charge 3.5"
            "HVDCP_3" in u -> "Qualcomm Quick Charge 3.0"
            "HVDCP" in u -> "Qualcomm Quick Charge 2.0"
            "DCP" in u -> "Standard wall charger (DCP)"
            "CDP" in u -> "USB charging port (CDP)"
            "SDP" in u -> "PC / Laptop USB (SDP)"
            "FLOAT" in u -> "Non-standard charger / cable (Float)"
            "WIRELESS" in u -> "Wireless"
            else -> null
        }
    }
}
