package com.kidsgarden.app.domain

/** Controllable clock for deterministic tests. Elapsed and wall time move independently. */
class FakeTimeProvider(
    var elapsed: Long = 1_000_000L,
    var epoch: Long = 1_760_000_000_000L,
) : TimeProvider {

    override fun elapsedRealtimeMillis(): Long = elapsed

    override fun epochMillis(): Long = epoch

    /** Advances both clocks, as real time passing does. */
    fun advance(millis: Long) {
        elapsed += millis
        epoch += millis
    }

    /** Moves only the wall clock, as a manual time or timezone change does. */
    fun shiftWallClock(millis: Long) {
        epoch += millis
    }
}
