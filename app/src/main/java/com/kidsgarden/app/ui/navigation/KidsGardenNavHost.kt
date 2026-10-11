package com.kidsgarden.app.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.kidsgarden.app.R
import com.kidsgarden.app.domain.ParentFacingSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Route names. Protected routes register themselves through [guardedComposable]. */
object Routes {
    const val ENTRY = "entry"
    const val PIN = "pin"

    /** Every route registered behind the parent-access guard. */
    internal val protected: MutableSet<String> = mutableSetOf()
}

/**
 * App navigation (contracts/parent-ui.md). Unauthenticated entry lands on setup or the
 * PIN screen; protected routes render only with active parent access. No navigation
 * argument, saved state, or intent extra carries a PIN or asserts verification.
 *
 * Story tasks register their routes here: setup (T038, T062), PIN and parent actions
 * (T053–T055), child duration (T063), dashboard and settings (T073–T076).
 */
@Composable
fun KidsGardenNavHost(
    snapshot: StateFlow<ParentFacingSnapshot>,
    parentUiAccessCleared: Flow<Unit>,
    onLeftProtectedGraph: () -> Unit,
    navController: NavHostController = rememberNavController(),
) {
    val state by snapshot.collectAsStateWithLifecycle()

    // When parent access ends anywhere (lock, child start, PIN change), drop every
    // protected screen from the back stack so none can be restored.
    LaunchedEffect(navController) {
        parentUiAccessCleared.collect { navController.closeProtectedRoutes() }
    }

    // Leaving the protected graph by navigation also ends parent access.
    DisposableEffect(navController) {
        var wasProtected = false
        val listener = NavController.OnDestinationChangedListener { _, destination, _ ->
            val isProtected = destination.route in Routes.protected
            if (wasProtected && !isProtected) onLeftProtectedGraph()
            wasProtected = isProtected
        }
        navController.addOnDestinationChangedListener(listener)
        onDispose { navController.removeOnDestinationChangedListener(listener) }
    }

    NavHost(navController = navController, startDestination = Routes.ENTRY) {
        // Temporary until the setup flow (T038) and PIN screen (T053) replace them.
        composable(Routes.ENTRY) { EntryPlaceholder() }
        composable(Routes.PIN) { EntryPlaceholder() }
        // Story routes gate on live state, e.g.:
        // guardedComposable(route, { state.parentAccessActive }, onDenied = { navController.closeProtectedRoutes() }) { ... }
    }
}

/** Pops all protected destinations, leaving the neutral PIN entry. */
fun NavController.closeProtectedRoutes() {
    val current = currentDestination?.route ?: return
    if (current in Routes.protected) {
        navigate(Routes.PIN) {
            popUpTo(graph.id) { inclusive = true }
            launchSingleTop = true
        }
    }
}

@Composable
private fun EntryPlaceholder() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text = stringResource(R.string.entry_placeholder))
    }
}
