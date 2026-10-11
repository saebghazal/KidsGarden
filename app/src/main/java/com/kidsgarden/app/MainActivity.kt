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
                KidsGardenNavHost(container = container)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Permissions can change in system settings while the app is away.
        container.setupStatus.refresh()
        container.deviceEvents.sample()
        container.callMonitor.refresh()
        container.sessionManager.dispatch(DomainEvent.UiOpened)
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
