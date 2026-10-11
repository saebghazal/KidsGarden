package com.kidsgarden.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import com.kidsgarden.app.domain.DomainEvent
import com.kidsgarden.app.ui.navigation.KidsGardenNavHost

/**
 * Single activity. No intent extra is trusted: nothing passed to this activity can
 * assert PIN verification or grant authorization (contracts/parent-ui.md).
 */
class MainActivity : ComponentActivity() {

    private val container: AppContainer
        get() = (application as KidsGardenApplication).container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                KidsGardenNavHost(
                    snapshot = container.sessionManager.state,
                    parentUiAccessCleared = container.sessionManager.parentUiAccessCleared,
                    onLeftProtectedGraph = { container.sessionManager.dispatch(DomainEvent.ParentUiLeft) },
                )
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Leaving the parent interface clears parent access (FR-004). Rotation and
        // other configuration changes are not "leaving".
        if (!isChangingConfigurations) {
            container.sessionManager.dispatch(DomainEvent.ParentUiLeft)
        }
    }
}
