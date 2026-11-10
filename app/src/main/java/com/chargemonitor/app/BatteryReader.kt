package com.chargemonitor.app

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import kotlin.math.abs

data class BatterySnapshot(
    val levelPct: Int,
    val status: Int,
    val plugged: Int,
    val health: Int,
    val voltageMv: Int,
    val tempC: Float,
    val technology: String,
    /** Positive = flowing into battery, negative = out of battery. */
    val currentMa: Int?,
    val chargeCounterMah: Int?,
    val maxChargerCurrentMa: Int?,
    val maxChargerVoltageMv: Int?,
    val chargeTimeRemainingMs: Long?,
    val cycleCount: Int?,
    val designCapacityMah: Int?,
    val inputVoltageMv: Int?,
    val inputCurrentMa: Int?,
    val sysfs: Map<String, String>,
    val charger: ChargerInfo,
) {
    val isPlugged: Boolean get() = plugged != 0
    val isCharging: Boolean get() = status == BatteryManager.BATTERY_STATUS_CHARGING
    val isFull: Boolean get() = status == BatteryManager.BATTERY_STATUS_FULL

    /** Power going into (or out of) the battery, in watts. */
    val powerW: Float? get() = currentMa?.let { voltageMv / 1000f * it / 1000f }

    val chargerRatedW: Float?
        get() = if (maxChargerCurrentMa != null && maxChargerVoltageMv != null)
            maxChargerCurrentMa / 1000f * maxChargerVoltageMv / 1000f else null

    val inputPowerW: Float?
        get() = if (inputVoltageMv != null && inputCurrentMa != null)
            inputVoltageMv / 1000f * inputCurrentMa / 1000f else null

    val estimatedFullMah: Int?
        get() = if (chargeCounterMah != null && levelPct in 10..100)
            chargeCounterMah * 100 / levelPct else null
}

object BatteryReader {

    private var currentInMicroAmps: Boolean? = null
    private var designCapacity: Int? = null
    private var designCapacityTried = false

    fun read(context: Context): BatterySnapshot? {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return null
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager

        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val pct = if (level >= 0 && scale > 0) level * 100 / scale
        else bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        val health = intent.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN)
        val rawV = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)
        val voltageMv = when {
            rawV > 100_000 -> rawV / 1000 // reported in µV
            rawV in 1..20 -> rawV * 1000   // reported in V
            else -> rawV
        }
        val tempC = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f
        val tech = intent.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY) ?: "Unknown"

        val chargingDirection = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                (plugged != 0 && status == BatteryManager.BATTERY_STATUS_FULL)
        val currentMa = normalizeCurrent(
            bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW), chargingDirection
        )

        val counter = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        val counterMah = when {
            counter == Int.MIN_VALUE || counter <= 0 -> null
            counter < 30_000 -> counter        // already mAh
            else -> counter / 1000             // µAh -> mAh
        }

        // Hidden extras present on most devices: charger's negotiated max current/voltage
        val maxI = intent.getIntExtra("max_charging_current", -1)
        val maxV = intent.getIntExtra("max_charging_voltage", -1)
        val maxImA = if (plugged != 0 && maxI > 0) (if (maxI > 20_000) maxI / 1000 else maxI) else null
        val maxVmV = if (plugged != 0 && maxV > 0) (if (maxV > 100_000) maxV / 1000 else maxV) else null

        val timeLeft = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            bm.computeChargeTimeRemaining().takeIf { it > 0 }
        } else null

        val cycles = intent.getIntExtra("android.os.extra.CYCLE_COUNT", -1).takeIf { it >= 0 }

        val sysfs = SysfsReader.read()
        val inV = if (plugged != 0) parseMicro(sysfs["usb/voltage_now"] ?: sysfs["ac/voltage_now"], isVoltage = true)
            ?.takeIf { it in 3000..30000 } else null
        val inI = if (plugged != 0) parseMicro(
            sysfs["usb/input_current_now"] ?: sysfs["usb/current_now"] ?: sysfs["ac/current_now"],
            isVoltage = false
        )?.let { abs(it) }?.takeIf { it in 1..15000 } else null

        val powerW = currentMa?.let { voltageMv / 1000f * it / 1000f }
        val charger = ProtocolDetector.detect(plugged, sysfs, maxVmV, powerW)

        return BatterySnapshot(
            levelPct = pct,
            status = status,
            plugged = plugged,
            health = health,
            voltageMv = voltageMv,
            tempC = tempC,
            technology = tech,
            currentMa = currentMa,
            chargeCounterMah = counterMah,
            maxChargerCurrentMa = maxImA,
            maxChargerVoltageMv = maxVmV,
            chargeTimeRemainingMs = timeLeft,
            cycleCount = cycles,
            designCapacityMah = loadDesignCapacity(context),
            inputVoltageMv = inV,
            inputCurrentMa = inI,
            sysfs = sysfs,
            charger = charger,
        )
    }

    /** Different OEMs report µA or mA, positive or negative. Normalise to signed mA. */
    private fun normalizeCurrent(raw: Int, charging: Boolean): Int? {
        if (raw == Int.MIN_VALUE || raw == Int.MAX_VALUE || raw == 0) return null
        if (abs(raw) > 15_000) currentInMicroAmps = true
        val ma = if (currentInMicroAmps == true) raw / 1000 else raw
        val mag = abs(ma)
        return if (charging) mag else -mag
    }

    /** Parses a sysfs value in µV / µA (or mV / mA) and returns mV / mA. */
    private fun parseMicro(v: String?, isVoltage: Boolean): Int? {
        val n = v?.trim()?.toLongOrNull() ?: return null
        if (n == 0L) return null
        val limit = if (isVoltage) 100_000L else 20_000L
        return (if (abs(n) > limit) n / 1000 else n).toInt()
    }

    /** Design capacity from the hidden PowerProfile class (works on many devices). */
    private fun loadDesignCapacity(context: Context): Int? {
        if (designCapacityTried) return designCapacity
        designCapacityTried = true
        designCapacity = try {
            val cls = Class.forName("com.android.internal.os.PowerProfile")
            val obj = cls.getConstructor(Context::class.java).newInstance(context)
            val cap = cls.getMethod("getBatteryCapacity").invoke(obj) as Double
            cap.toInt().takeIf { it > 500 }
        } catch (_: Throwable) {
            null
        }
        return designCapacity
    }
}
