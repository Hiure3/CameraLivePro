package com.cameralivepro.app.camera

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager

data class DeviceStatus(val batteryPct: Int = -1, val tempC: Double = 0.0, val thermal: Int = 0, val charging: Boolean = false)

object DeviceMonitor {
    fun read(ctx: Context): DeviceStatus {
        val i: Intent? = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val temp = (i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0
        val st = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
        val thermal = if (Build.VERSION.SDK_INT >= 29)
            (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).currentThermalStatus else 0
        return DeviceStatus(if (level >= 0 && scale > 0) level * 100 / scale else -1, temp, thermal, charging)
    }
}
