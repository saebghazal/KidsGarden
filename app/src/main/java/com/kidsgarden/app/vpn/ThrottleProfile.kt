package com.kidsgarden.app.vpn

import com.kidsgarden.app.data.ThrottleSettings
import kotlin.math.roundToLong

/**
 * What the traffic scheduler enforces, per direction. Rates are aggregate across all flows.
 * [rateBytesPerSecond] null means no intentional cap.
 */
data class ThrottleProfile(
    val rateBytesPerSecond: Long?,
    val addedDelayPerDirectionMillis: Long,
) {
    init {
        require(rateBytesPerSecond == null || rateBytesPerSecond > 0) { "rate must be positive" }
        require(addedDelayPerDirectionMillis >= 0) { "delay must be nonnegative" }
    }

    val unshaped: Boolean get() = rateBytesPerSecond == null && addedDelayPerDirectionMillis == 0L

    companion object {
        /** Normal: no cap and zero artificial delay. */
        val UNSHAPED = ThrottleProfile(rateBytesPerSecond = null, addedDelayPerDirectionMillis = 0)

        /** rateBytesPerSecond = kbps×1000/8; added RTT is split half per direction. */
        fun from(settings: ThrottleSettings): ThrottleProfile = ThrottleProfile(
            rateBytesPerSecond = kbpsToBytesPerSecond(settings.bandwidthKbps.toDouble()),
            addedDelayPerDirectionMillis = settings.addedRttLatencyMs / 2L,
        )

        fun kbpsToBytesPerSecond(kbps: Double): Long = (kbps * 1000.0 / 8.0).roundToLong().coerceAtLeast(1L)
    }
}
