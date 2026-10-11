package com.kidsgarden.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kidsgarden.app.security.ParentPinManager
import com.kidsgarden.app.security.PinOutcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * PIN change (T075). Success is reported only after the new credential is durably saved;
 * any failure leaves the old PIN working. Holds outcomes only, never PIN text.
 * [onChanged] revokes every grant and all parent access.
 */
class ChangePinViewModel(
    private val pinManager: ParentPinManager,
    private val onChanged: () -> Unit,
) : ViewModel() {

    sealed interface State {
        data object Idle : State
        data object Saving : State
        data object CurrentMismatch : State
        data class RetryDelayed(val untilElapsedMillis: Long) : State
        data object InvalidNewPin : State
        data object NewPinMismatch : State
        data object StorageFailed : State
        data object Changed : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    fun submit(current: CharArray, newPin: CharArray, confirmation: CharArray) {
        if (_state.value == State.Saving || _state.value == State.Changed) {
            listOf(current, newPin, confirmation).forEach { it.fill('\u0000') }
            return
        }
        if (!ParentPinManager.isValidPin(newPin) || !newPin.contentEquals(confirmation)) {
            _state.value = if (ParentPinManager.isValidPin(newPin)) State.NewPinMismatch else State.InvalidNewPin
            listOf(current, newPin, confirmation).forEach { it.fill('\u0000') }
            return
        }
        _state.value = State.Saving
        viewModelScope.launch {
            val next = when (val outcome = pinManager.changePin(current, newPin, confirmation)) {
                PinOutcome.Accepted -> State.Changed
                PinOutcome.Mismatch -> State.CurrentMismatch
                is PinOutcome.RetryDelayed -> State.RetryDelayed(outcome.untilElapsedMillis)
                PinOutcome.InvalidFormat -> State.InvalidNewPin
                else -> State.StorageFailed
            }
            _state.value = next
            if (next == State.Changed) onChanged()
        }
    }
}
