package com.kidsgarden.app

import android.app.Application
import com.kidsgarden.app.domain.DomainEvent

class KidsGardenApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // A new process always starts at DULL with no grant (FR-027); this event
        // makes that explicit to the reducer and applies the matching policy.
        container.sessionManager.dispatch(DomainEvent.ProcessStarted)
    }
}
