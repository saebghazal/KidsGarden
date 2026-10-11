package com.kidsgarden.app.security

import com.kidsgarden.app.data.SettingsRepository
import com.kidsgarden.app.domain.TimeProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Result of a PIN operation. Carries no PIN material. */
sealed interface PinOutcome {
    data object Accepted : PinOutcome
    data object Mismatch : PinOutcome
    data class RetryDelayed(val untilElapsedMillis: Long) : PinOutcome
    data object InvalidFormat : PinOutcome
    data object NotEnrolled : PinOutcome
    data object AlreadyEnrolled : PinOutcome
    data object StorageFailed : PinOutcome
}

/**
 * Enrollment, verification, and change of the parent PIN (contracts/parent-pin-manager.md).
 *
 * - Six ASCII digits only (FR-006).
 * - Five consecutive failures impose a 30-second delay measured in elapsed time. The
 *   counter lives in memory only: process death clears it, and nothing is persisted.
 * - Every PIN char array passed in is cleared before returning.
 *
 * A successful [changePin] does not itself revoke grants; the caller dispatches the
 * revocation to SessionManager once this returns [PinOutcome.Accepted] (T077).
 */
class ParentPinManager(
    private val repository: SettingsRepository,
    private val hasher: PinHasher,
    private val time: TimeProvider,
    private val hashDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val mutex = Mutex()
    private var consecutiveFailures = 0
    private var retryNotBefore: Long? = null

    suspend fun enroll(pin: CharArray, confirmation: CharArray): PinOutcome = mutex.withLock {
        try {
            if (!isValidPin(pin) || !pin.contentEquals(confirmation)) return@withLock PinOutcome.InvalidFormat
            val stored = repository.load().getOrElse { return@withLock PinOutcome.StorageFailed }
            if (stored.pinCredential != null) return@withLock PinOutcome.AlreadyEnrolled
            val credential = withContext(hashDispatcher) { hasher.create(pin) }
            repository.createParentCredential(credential)
                .fold(onSuccess = { PinOutcome.Accepted }, onFailure = { PinOutcome.StorageFailed })
        } finally {
            pin.fill('\u0000')
            confirmation.fill('\u0000')
        }
    }

    suspend fun verify(pin: CharArray): PinOutcome = mutex.withLock {
        try {
            checkCurrent(pin)
        } finally {
            pin.fill('\u0000')
        }
    }

    suspend fun changePin(current: CharArray, newPin: CharArray, confirmation: CharArray): PinOutcome =
        mutex.withLock {
            try {
                val check = checkCurrent(current)
                if (check != PinOutcome.Accepted) return@withLock check
                if (!isValidPin(newPin) || !newPin.contentEquals(confirmation)) return@withLock PinOutcome.InvalidFormat
                val stored = repository.load().getOrNull()?.pinCredential ?: return@withLock PinOutcome.NotEnrolled
                val replacement = withContext(hashDispatcher) { hasher.create(newPin) }
                repository.changeCredential(expected = stored, newCredential = replacement)
                    .fold(onSuccess = { PinOutcome.Accepted }, onFailure = { PinOutcome.StorageFailed })
            } finally {
                current.fill('\u0000')
                newPin.fill('\u0000')
                confirmation.fill('\u0000')
            }
        }

    /** Must be called with [mutex] held. Applies the retry gate and counts failures. */
    private suspend fun checkCurrent(pin: CharArray): PinOutcome {
        val now = time.elapsedRealtimeMillis()
        retryNotBefore?.let { until ->
            if (now < until) return PinOutcome.RetryDelayed(until)
            retryNotBefore = null
        }
        if (!isValidPin(pin)) return recordFailure(now)
        val credential = repository.load().getOrElse { return PinOutcome.StorageFailed }.pinCredential
            ?: return PinOutcome.NotEnrolled
        val matches = withContext(hashDispatcher) { hasher.verify(pin, credential) }
        return if (matches) {
            consecutiveFailures = 0
            PinOutcome.Accepted
        } else {
            recordFailure(now)
        }
    }

    private fun recordFailure(now: Long): PinOutcome {
        consecutiveFailures++
        if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
            consecutiveFailures = 0
            retryNotBefore = now + RETRY_DELAY_MILLIS
        }
        return PinOutcome.Mismatch
    }

    companion object {
        const val PIN_LENGTH = 6
        const val MAX_CONSECUTIVE_FAILURES = 5
        const val RETRY_DELAY_MILLIS = 30_000L

        fun isValidPin(pin: CharArray): Boolean = pin.size == PIN_LENGTH && pin.all { it in '0'..'9' }
    }
}
