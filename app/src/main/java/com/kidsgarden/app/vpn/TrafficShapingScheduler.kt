package com.kidsgarden.app.vpn

import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.max
import kotlin.math.min

enum class Direction { UPSTREAM, DOWNSTREAM }

/**
 * Generic, content-blind traffic shaping (T042, contracts/android-vpn.md).
 *
 * - Aggregate per-direction token buckets enforce the cap across all flows. Waiters queue
 *   on a fair lock, so flows take turns slice by slice.
 * - Added latency: each chunk is released no earlier than its read time plus the current
 *   per-direction delay. Release times are monotonic per flow.
 * - Bounded memory: chunks in flight hold a share of [maxQueuedBytes]. TCP readers block
 *   when the budget is exhausted (backpressure); datagrams over budget are dropped.
 * - At most [maxFlows] flows; further flows are refused.
 * - Policy changes re-read the profile while waiting, so switching to Normal drains
 *   artificial delay immediately without resetting any flow.
 *
 * Only byte counts and times are seen here; payloads are opaque and never inspected.
 */
class TrafficShapingScheduler(
    initialProfile: ThrottleProfile,
    val maxQueuedBytes: Int = DEFAULT_MAX_QUEUED_BYTES,
    val maxFlows: Int = DEFAULT_MAX_FLOWS,
) {
    @Volatile
    var profile: ThrottleProfile = initialProfile
        private set

    @Volatile
    private var generation: Long = Long.MIN_VALUE

    private val applyLock = Any()
    private val buffer = Semaphore(maxQueuedBytes, true)
    private val activeFlows = AtomicInteger(0)
    private val buckets = Direction.entries.associateWith { TokenBucket(it) }

    /** Applies [newProfile] unless [newGeneration] is older than the last applied one. */
    fun apply(newProfile: ThrottleProfile, newGeneration: Long): Boolean = synchronized(applyLock) {
        if (newGeneration < generation) return false
        generation = newGeneration
        profile = newProfile
        true
    }

    /** Registers a flow, or returns null when [maxFlows] are active. */
    fun openFlow(): FlowHandle? {
        while (true) {
            val current = activeFlows.get()
            if (current >= maxFlows) return null
            if (activeFlows.compareAndSet(current, current + 1)) return FlowHandle()
        }
    }

    val activeFlowCount: Int get() = activeFlows.get()

    /** Blocks until [bytes] of in-flight budget are available (TCP backpressure). */
    fun reserveStream(bytes: Int): Boolean = try {
        buffer.acquire(bytes)
        true
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        false
    }

    /** Non-blocking budget for one datagram; false means drop it. */
    fun reserveDatagram(bytes: Int): Boolean = buffer.tryAcquire(bytes)

    fun release(bytes: Int) {
        if (bytes > 0) buffer.release(bytes)
    }

    inner class FlowHandle internal constructor() : AutoCloseable {
        private val lastRelease = LongArray(Direction.entries.size) { Long.MIN_VALUE }
        private val closed = java.util.concurrent.atomic.AtomicBoolean(false)

        /**
         * Waits for the chunk's release time and the direction's rate budget, then hands
         * it to [write] in fair slices. Returns false if interrupted.
         */
        fun send(
            direction: Direction,
            data: ByteArray,
            offset: Int,
            length: Int,
            readAtNanos: Long,
            write: (ByteArray, Int, Int) -> Unit,
        ): Boolean {
            if (!awaitRelease(direction, readAtNanos)) return false
            var sent = 0
            while (sent < length) {
                val slice = buckets.getValue(direction).take(length - sent) ?: return false
                write(data, offset + sent, slice)
                sent += slice
            }
            return true
        }

        private fun awaitRelease(direction: Direction, readAtNanos: Long): Boolean {
            val index = direction.ordinal
            while (true) {
                val delayNanos = TimeUnit.MILLISECONDS.toNanos(profile.addedDelayPerDirectionMillis)
                // Without added delay, release now (this is how Normal drains queued delay).
                val target = if (delayNanos == 0L) readAtNanos else max(readAtNanos + delayNanos, lastRelease[index])
                val remaining = target - System.nanoTime()
                if (remaining <= 0) {
                    lastRelease[index] = target
                    return true
                }
                // Sleep in short steps so a policy change (for example to Normal) applies promptly.
                if (!sleepNanos(min(remaining, POLICY_RECHECK_NANOS))) return false
            }
        }

        override fun close() {
            if (closed.compareAndSet(false, true)) activeFlows.decrementAndGet()
        }
    }

    private inner class TokenBucket(private val direction: Direction) {
        private val lock = ReentrantLock(true)
        private var tokens = 0.0
        private var lastRefillNanos = System.nanoTime()

        /** Returns the slice size granted (≤ [wanted]), or null if interrupted. */
        fun take(wanted: Int): Int? = lock.withLock {
            while (true) {
                val rate = profile.rateBytesPerSecond ?: run {
                    tokens = 0.0
                    lastRefillNanos = System.nanoTime()
                    return wanted
                }
                val burst = max(rate / BURST_DIVISOR, MIN_BURST_BYTES).toDouble()
                val now = System.nanoTime()
                tokens = min(burst, tokens + (now - lastRefillNanos) * rate / NANOS_PER_SECOND)
                lastRefillNanos = now
                val slice = min(wanted.toDouble(), burst).toInt().coerceAtLeast(1)
                if (tokens >= slice) {
                    tokens -= slice
                    return slice
                }
                val waitNanos = ((slice - tokens) * NANOS_PER_SECOND / rate).toLong().coerceAtLeast(1L)
                if (!sleepNanos(min(waitNanos, POLICY_RECHECK_NANOS))) return null
            }
            @Suppress("UNREACHABLE_CODE")
            wanted
        }

        override fun toString(): String = "TokenBucket($direction)"
    }

    private fun sleepNanos(nanos: Long): Boolean = try {
        TimeUnit.NANOSECONDS.sleep(nanos)
        true
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        false
    }

    companion object {
        const val DEFAULT_MAX_QUEUED_BYTES = 16 * 1024 * 1024
        const val DEFAULT_MAX_FLOWS = 1024
        private const val NANOS_PER_SECOND = 1_000_000_000.0
        private val POLICY_RECHECK_NANOS = TimeUnit.MILLISECONDS.toNanos(50)
        private const val BURST_DIVISOR = 5L
        private const val MIN_BURST_BYTES = 1500L
    }
}
