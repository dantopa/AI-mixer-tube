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
}

/**
 * Policy: when may analysis of [priority] run, and may it touch the network?
 *
 *  - BACKGROUND (warming the library): never in battery saver, never below 30% unless charging.
 *  - NEXT_UP / NOW_PLAYING (the DJ needs it for the very next transition): blocked only by battery saver
 *    (unless charging) or a nearly flat battery, because a missing analysis costs nothing worse than a plain
 *    crossfade.
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
                    !c.isCharging && c.batteryPercent < 30 -> BlockReason.LOW_BATTERY
                    else -> null
                }
            AnalysisPriority.NEXT_UP, AnalysisPriority.NOW_PLAYING ->
                when {
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

    /** One line for the log: every input the decisions above are made from. */
    fun describe(): String =
        "device[battery=${conditions.batteryPercent}% saver=${conditions.isBatterySaver} charging=${conditions.isCharging} " +
            "metered=${conditions.isMetered} analyzeOnMetered=${analyzeOnMetered()}]"
}

class AndroidDeviceConditions(context: Context) : DeviceConditions {
    private val app = context.applicationContext
    private val power = app.getSystemService(Context.POWER_SERVICE) as? PowerManager
    private val connectivity = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    override val isBatterySaver: Boolean get() = power?.isPowerSaveMode == true

    private fun battery(): Intent? = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

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
