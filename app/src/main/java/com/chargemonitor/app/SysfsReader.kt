package com.chargemonitor.app

import java.io.File

/**
 * Reads kernel power-supply nodes under /sys/class/power_supply.
 * On many phones (especially Android 10+) these are blocked by SELinux,
 * so everything here is best-effort: whatever is readable gets used.
 */
object SysfsReader {

    private const val ROOT = "/sys/class/power_supply"

    private val knownSupplies = listOf(
        "usb", "ac", "battery", "wireless", "main", "pc_port", "dc",
        "charger", "bms", "usb_pd", "usbpd", "otg", "mtk-master-charger",
        "mtk_charger_type", "max77705-charger", "s2mu106-charger"
    )

    private val fields = listOf(
        // generic
        "type", "real_type", "usb_type", "charger_type", "online", "present",
        "voltage_now", "voltage_max", "current_now", "current_max",
        "input_current_now", "input_current_limit", "input_voltage_now",
        "power_now", "charge_type", "status",
        // Qualcomm / USB-PD
        "pd_active", "pd_allowed", "typec_mode", "hvdcp_opti_allowed",
        // Xiaomi
        "quick_charge_type",
        // Oppo / OnePlus / Realme (VOOC / Warp / SuperVOOC)
        "voocchg_ing", "fastchg_status", "fast_chg_type", "chargerid_volt",
        // Samsung
        "hv_charger_status", "charge_power", "afc_charger"
    )

    private var readablePaths: List<String>? = null
    private var reads = 0

    private fun scan(): List<String> {
        val dirs = try {
            File(ROOT).listFiles()?.map { it.name }
        } catch (_: Throwable) {
            null
        }
        val supplies = (dirs ?: emptyList()) + knownSupplies
        val out = ArrayList<String>()
        for (s in supplies.distinct()) {
            for (f in fields) {
                val p = "$ROOT/$s/$f"
                try {
                    val file = File(p)
                    if (file.exists() && file.canRead()) out.add(p)
                } catch (_: Throwable) {
                }
            }
        }
        return out
    }

    /** Returns map of "supply/field" -> value for every readable node. */
    fun read(): Map<String, String> {
        // Re-scan occasionally (charger plugged in can create new nodes)
        if (readablePaths == null || reads % 15 == 0) readablePaths = scan()
        reads++
        val result = LinkedHashMap<String, String>()
        for (p in readablePaths.orEmpty()) {
            val v = try {
                File(p).readText().trim().take(200)
            } catch (_: Throwable) {
                null
            }
            if (!v.isNullOrEmpty()) result[p.removePrefix("$ROOT/")] = v
        }
        return result
    }
}
