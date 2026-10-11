package com.kidsgarden.app

import android.content.Context
import com.kidsgarden.app.data.DataStoreProvider
import com.kidsgarden.app.data.SettingsRepository
import com.kidsgarden.app.domain.BoundaryScheduler
import com.kidsgarden.app.domain.DomainEvent
import com.kidsgarden.app.domain.NetworkPolicy
import com.kidsgarden.app.domain.NetworkPolicySink
import com.kidsgarden.app.domain.SessionManager
import com.kidsgarden.app.domain.TimeProvider
import com.kidsgarden.app.platform.time.AndroidTimeProvider
import com.kidsgarden.app.security.ParentPinManager
import com.kidsgarden.app.security.PinHasher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Manual dependency container: exactly one instance of each component per process,
 * shared by UI and (later) the VPN service. No DI framework (plan Technical Context).
 */
class AppContainer(context: Context) {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val time: TimeProvider = AndroidTimeProvider()

    val settingsRepository = SettingsRepository(DataStoreProvider.create(context.applicationContext, ioScope))

    val pinManager = ParentPinManager(settingsRepository, PinHasher(), time)

    val sessionManager = SessionManager(
        time = time,
        policySink = NoShapingYet,
        boundaryScheduler = NoBoundariesYet,
        scope = appScope,
    )

    init {
        appScope.launch {
            var first = true
            settingsRepository.settings.collect { settings ->
                if (first && DataStoreProvider.repairedSinceStart) {
                    sessionManager.dispatch(DomainEvent.SettingsInvalid)
                }
                first = false
                sessionManager.dispatch(DomainEvent.SettingsChanged(settings))
            }
        }
    }

    /**
     * Placeholder until VpnDullingController is wired (T046). VPN capability stays
     * UNAVAILABLE, so the parent sees SETUP_REQUIRED rather than false protection.
     */
    private object NoShapingYet : NetworkPolicySink {
        override suspend fun apply(policy: NetworkPolicy, generation: Long) = Unit
    }

    /** Placeholder until ServiceBoundaryScheduler exists (T060); no grants exist before then. */
    private object NoBoundariesYet : BoundaryScheduler {
        override fun schedule(atElapsedMillis: Long, generation: Long) = Unit
        override fun cancel() = Unit
    }
}
