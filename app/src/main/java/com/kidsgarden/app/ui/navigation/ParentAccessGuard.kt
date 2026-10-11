package com.kidsgarden.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable

/**
 * Renders [content] only while parent access is active (FR-004). Otherwise nothing is
 * shown and [onDenied] routes to the PIN screen. Checked on every composition, so Back,
 * recents restore, notification taps, and deep links all re-evaluate the gate; hiding a
 * button is not the protection.
 */
@Composable
fun ParentAccessGuard(
    parentAccessActive: Boolean,
    onDenied: () -> Unit,
    content: @Composable () -> Unit,
) {
    if (parentAccessActive) {
        content()
    } else {
        LaunchedEffect(Unit) { onDenied() }
    }
}

/** Registers a destination that only renders behind [ParentAccessGuard]. */
fun NavGraphBuilder.guardedComposable(
    route: String,
    parentAccessActive: () -> Boolean,
    onDenied: () -> Unit,
    content: @Composable (NavBackStackEntry) -> Unit,
) {
    Routes.protected += route
    composable(route) { entry ->
        ParentAccessGuard(parentAccessActive = parentAccessActive(), onDenied = onDenied) {
            content(entry)
        }
    }
}
