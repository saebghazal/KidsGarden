package com.kidsgarden.app.vpn

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.kidsgarden.app.domain.BoundaryScheduler

/**
 * One-shot wake-up at the next grant boundary (T060, research R5). Runs in the process the
 * foreground VPN service keeps alive. No exact alarms, WorkManager, or wake locks.
 *
 * The delay is a wake opportunity, not the time authority: Handler delays pause in deep
 * sleep, so the reducer settles grants from elapsed time on every event, and the traffic
 * scheduler stops honoring a relaxed policy at its deadline on its own ([PolicyGuard]).
 */
class ServiceBoundaryScheduler(
    private val onBoundary: (generation: Long) -> Unit,
) : BoundaryScheduler {

    private val handler = Handler(Looper.getMainLooper())
    private var pending: Runnable? = null

    override fun schedule(atElapsedMillis: Long, generation: Long) {
        handler.post {
            pending?.let(handler::removeCallbacks)
            val task = Runnable {
                pending = null
                onBoundary(generation)
            }
            pending = task
            val delay = (atElapsedMillis - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
            handler.postDelayed(task, delay)
        }
    }

    override fun cancel() {
        handler.post {
            pending?.let(handler::removeCallbacks)
            pending = null
        }
    }
}
