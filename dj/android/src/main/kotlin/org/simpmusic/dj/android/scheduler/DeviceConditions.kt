package org.simpmusic.dj.android.scheduler

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.os.BatteryManager
import android.os.PowerManager
import org.simpmusic.dj.model.AnalysisPriority

/** What the device is doing right now, as far as background work cares. */
interface DeviceConditions {
    val isBatterySaver: Boolean
    val isCharging: Boolean

    /** 0..100, or 100 when unknown. */
    val batteryPercent: Int
    val isMetered: Boolean

    /** PowerManager.THERMAL_STATUS_* (0 = none ... 6 = shutdown); 0 when unknown. */
    val thermalStatus: Int get() = 0
}

/**
 * Policy: when may analysis of [priority] run, and may it touch the network?
 *
 *  - BACKGROUND (warming the library): only while charging, never in battery saver, never once the phone is warm
 *    (thermal LIGHT or above). It used to run on battery from 30 %, and on top of the playing / next track's analysis
 *    it kept the phone hot for the whole session.
 *  - NEXT_UP / NOW_PLAYING (the DJ needs it for the very next transition): blocked only by battery saver
 *    (unless charging) or a nearly flat battery, because a missing analysis costs nothing worse than a plain
 *    crossfade. Also blocked once the phone is CRITICAL-ly hot: a plain crossfade is better than a throttled phone.
 *  - Network: NEXT_UP fetches a stream on any network unless the user switched "analyze on mobile data" off; BACKGROUND
 *    only on an unmetered one; where a fetch is forbidden they decode from the cache only (a fully played/precached
 *    track is entirely there). NOW_PLAYING is already streaming, so it is not held back.
 */
class AnalysisPolicy(
    private val conditions: DeviceConditions,
    private val analyzeOnMetered: () -> Boolean,
) {
    fun mayRun(priority: AnalysisPriority): Boolean = blockReason(priority) == null

    /** Why [priority] may not run right now, or null. */
    fun blockReason(priority: AnalysisPriority): BlockReason? {
        val c = conditions
        return when (priority) {
            AnalysisPriority.BACKGROUND ->
                when {
                    c.isBatterySaver -> BlockReason.BATTERY_SAVER
                    !c.isCharging -> BlockReason.NOT_CHARGING
                    c.thermalStatus >= THERMAL_LIGHT -> BlockReason.HOT
                    else -> null
                }
            AnalysisPriority.NEXT_UP, AnalysisPriority.NOW_PLAYING ->
                when {
                    c.thermalStatus >= THERMAL_CRITICAL -> BlockReason.HOT
                    c.isCharging -> null
                    c.isBatterySaver -> BlockReason.BATTERY_SAVER
                    c.batteryPercent < 10 -> BlockReason.LOW_BATTERY
                    else -> null
                }
        }
    }

    /**
     * May a stream be fetched for [priority]? NOW_PLAYING is already streaming. NEXT_UP follows the "analyze on mobile
     * data" switch. BACKGROUND (warming the library, possibly hundreds of songs) never fetches on a metered connection
     * whatever that switch says: it would silently spend the user's data plan.
     */
    fun mayUseNetwork(priority: AnalysisPriority): Boolean =
        when (priority) {
            AnalysisPriority.NOW_PLAYING -> true
            AnalysisPriority.NEXT_UP -> !conditions.isMetered || analyzeOnMetered()
            AnalysisPriority.BACKGROUND -> !conditions.isMetered
        }

    val isMetered: Boolean get() = conditions.isMetered

    private companion object {
        // PowerManager.THERMAL_STATUS_LIGHT / _SEVERE
        const val THERMAL_LIGHT = 1
        // Not SEVERE: a Pixel 10 Pro reported SEVERE while charging and playing, and the DJ stood still for the whole
        // session. The playing / next track is one analysis at a time; only CRITICAL (the system is about to act) stops it.
        const val THERMAL_CRITICAL = 4
    }

    /** One line for the log: every input the decisions above are made from. */
    fun describe(): String =
        "device[battery=${conditions.batteryPercent}% saver=${conditions.isBatterySaver} charging=${conditions.isCharging} thermal=${conditions.thermalStatus} " +
            "metered=${conditions.isMetered} analyzeOnMetered=${analyzeOnMetered()}]"
}

class AndroidDeviceConditions(context: Context) : DeviceConditions {
    private val app = context.applicationContext
    private val power = app.getSystemService(Context.POWER_SERVICE) as? PowerManager
    private val connectivity = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    override val isBatterySaver: Boolean get() = power?.isPowerSaveMode == true

    @Volatile private var cachedBattery: Intent? = null

    @Volatile private var cachedAt = 0L

    /** The sticky battery intent is a binder round trip; the scheduler and the engine ask several times a second. */
    private fun battery(): Intent? {
        val now = android.os.SystemClock.elapsedRealtime()
        if (cachedBattery == null || now - cachedAt > 15_000L) {
            cachedBattery = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            cachedAt = now
        }
        return cachedBattery
    }

    override val thermalStatus: Int
        get() = if (android.os.Build.VERSION.SDK_INT >= 29) power?.currentThermalStatus ?: 0 else 0

    override val isCharging: Boolean
        get() {
            val status = battery()?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: return false
            return status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        }

    override val batteryPercent: Int
        get() {
            val i = battery() ?: return 100
            val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            return if (level >= 0 && scale > 0) level * 100 / scale else 100
        }

    override val isMetered: Boolean get() = connectivity?.isActiveNetworkMetered == true
}
