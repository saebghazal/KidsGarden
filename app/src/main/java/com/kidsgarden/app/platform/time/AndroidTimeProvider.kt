package com.kidsgarden.app.platform.time

import android.os.SystemClock
import com.kidsgarden.app.domain.TimeProvider

/** Android clocks behind the pure domain interface. elapsedRealtime includes deep sleep. */
class AndroidTimeProvider : TimeProvider {
    override fun elapsedRealtimeMillis(): Long = SystemClock.elapsedRealtime()

    override fun epochMillis(): Long = System.currentTimeMillis()
}
