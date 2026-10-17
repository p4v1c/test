package fr.assistantvocal.app

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import fr.assistantvocal.core.assistant.BatteryInfo
import fr.assistantvocal.core.assistant.DeviceStatus
import java.time.ZonedDateTime
import kotlin.math.roundToInt

class AndroidDeviceStatus(private val context: Context) : DeviceStatus {

    override fun now(): ZonedDateTime = ZonedDateTime.now()

    override fun battery(): BatteryInfo? {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        if (level < 0 || scale <= 0) return null
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        return BatteryInfo((level * 100f / scale).roundToInt(), charging)
    }

    override fun isOnline(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    override fun adjustVolume(louder: Boolean): Int? {
        val am = context.getSystemService(AudioManager::class.java) ?: return null
        val direction = if (louder) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
        repeat(2) { am.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0) }
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).takeIf { it > 0 } ?: return null
        return (am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100f / max).roundToInt()
    }
}
