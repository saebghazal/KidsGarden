package com.kidsgarden.app.ui.pin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kidsgarden.app.security.ParentPinManager
import com.kidsgarden.app.security.PinOutcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * PIN verification for the parent interface. Holds only the outcome; the PIN goes straight
 * to [ParentPinManager.verify], which clears it. A correct PIN opens the parent interface
 * only; it never changes DULL by itself.
 */
class ParentPinViewModel(private val pinManager: ParentPinManager) : ViewModel() {

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data object Mismatch : State
        data class RetryDelayed(val untilElapsedMillis: Long) : State
        data object NotEnrolled : State
        data object StorageFailed : State
        data object Accepted : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    fun submit(pin: CharArray) {
        if (_state.value == State.Checking || _state.value == State.Accepted) {
            pin.fill('\u0000')
            return
        }
        _state.value = State.Checking
        viewModelScope.launch {
            _state.value = when (val outcome = pinManager.verify(pin)) {
                PinOutcome.Accepted -> State.Accepted
                is PinOutcome.RetryDelayed -> State.RetryDelayed(outcome.untilElapsedMillis)
                PinOutcome.NotEnrolled -> State.NotEnrolled
                PinOutcome.StorageFailed -> State.StorageFailed
                else -> State.Mismatch
            }
        }
    }
}
