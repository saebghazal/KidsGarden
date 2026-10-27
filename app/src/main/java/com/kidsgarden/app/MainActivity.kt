package com.kidsgarden.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
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
                CardWindow {
                    KidsGardenNavHost(container = container)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Permissions can change in system settings while the app is away.
        container.setupStatus.refresh()
        container.deviceEvents.sample()
        container.callMonitor.refresh()
        container.effectCapability.refresh()
        container.sessionManager.dispatch(DomainEvent.UiOpened)
    }
}

/**
 * The app as a rounded card over the dimmed home screen, not full screen (owner change
 * 2026-10-25). The window itself is see-through (Theme.KidsGarden.Card).
 */
@Composable
private fun CardWindow(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = SCRIM_ALPHA))
            .safeDrawingPadding(),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            shadowElevation = 8.dp,
            modifier = Modifier
                .fillMaxWidth(CARD_WIDTH)
                .widthIn(max = 560.dp)
                .fillMaxHeight(CARD_HEIGHT),
        ) {
            content()
        }
    }
}

private const val SCRIM_ALPHA = 0.45f
private const val CARD_WIDTH = 0.92f
private const val CARD_HEIGHT = 0.85f
