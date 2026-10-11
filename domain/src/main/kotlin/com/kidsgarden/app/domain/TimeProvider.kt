package com.kidsgarden.app.domain

/**
 * Clock abstraction (Constitution IV). Elapsed time including deep sleep is the only
 * authority for durations; epoch time is used only for parent-facing display projections.
 */
interface TimeProvider {
    /** Monotonic milliseconds since boot, including sleep. */
    fun elapsedRealtimeMillis(): Long

    /** Adjustable wall-clock milliseconds. Never used to grant or extend access. */
    fun epochMillis(): Long
}

/** One consistent reading of both clocks, passed into the reducer with each event. */
data class TimeSnapshot(
    val elapsedMillis: Long,
    val epochMillis: Long,
)

fun TimeProvider.snapshot(): TimeSnapshot =
    TimeSnapshot(elapsedMillis = elapsedRealtimeMillis(), epochMillis = epochMillis())
