package com.kidsgarden.app.ui.session

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kidsgarden.app.domain.StateMachine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Starts a child session (T063). [startChild] dispatches the request and reports whether
 * the reducer accepted it; the reducer alone decides. Picking nothing starts nothing.
 */
class ChildSessionViewModel(
    private val startChild: suspend (minutes: Int) -> Boolean,
) : ViewModel() {

    sealed interface State {
        data object Choosing : State
        data object Starting : State
        data object Started : State
        data object Failed : State
    }

    private val _state = MutableStateFlow<State>(State.Choosing)
    val state: StateFlow<State> = _state.asStateFlow()

    fun start(minutes: Int) {
        if (minutes !in StateMachine.CHILD_DURATIONS_MINUTES) return
        if (_state.value == State.Starting || _state.value == State.Started) return
        _state.value = State.Starting
        viewModelScope.launch {
            _state.value = if (startChild(minutes)) State.Started else State.Failed
        }
    }
}
