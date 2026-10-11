package com.kidsgarden.app.ui.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kidsgarden.app.security.ParentPinManager
import com.kidsgarden.app.security.PinOutcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Enrollment (T035). Holds only the outcome, never PIN text: the PIN is passed straight
 * to [ParentPinManager.enroll], which clears the arrays.
 */
class CreatePinViewModel(private val pinManager: ParentPinManager) : ViewModel() {

    sealed interface State {
        data object Idle : State
        data object Saving : State
        data object InvalidFormat : State
        data object ConfirmationMismatch : State
        data object StorageFailed : State
        data object AlreadyCreated : State
        data object Created : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    fun submit(pin: CharArray, confirmation: CharArray) {
        if (_state.value == State.Saving || _state.value == State.Created) return
        if (!ParentPinManager.isValidPin(pin)) {
            clear(pin, confirmation)
            _state.value = State.InvalidFormat
            return
        }
        if (!pin.contentEquals(confirmation)) {
            clear(pin, confirmation)
            _state.value = State.ConfirmationMismatch
            return
        }
        _state.value = State.Saving
        viewModelScope.launch {
            _state.value = when (pinManager.enroll(pin, confirmation)) {
                PinOutcome.Accepted -> State.Created
                PinOutcome.AlreadyEnrolled -> State.AlreadyCreated
                PinOutcome.StorageFailed -> State.StorageFailed
                else -> State.InvalidFormat
            }
        }
    }

    private fun clear(vararg arrays: CharArray) = arrays.forEach { it.fill('\u0000') }
}
