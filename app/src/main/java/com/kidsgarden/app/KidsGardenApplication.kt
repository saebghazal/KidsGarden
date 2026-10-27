package com.kidsgarden.app

import android.app.Application

class KidsGardenApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // The container reports ProcessStarted once settings have loaded, so a saved
        // Deactivated choice is honoured (FR-031). Until then the state is DULL with no grant.
        container = AppContainer(this)
    }
}
