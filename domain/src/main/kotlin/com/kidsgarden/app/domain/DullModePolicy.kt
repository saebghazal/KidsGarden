package com.kidsgarden.app.domain

/**
 * Network intent for a state (T070, plan "Design"). Pure; the reducer emits what this
 * returns and the VPN controller applies it only when capability allows.
 *
 * Precedence: unavailable VPN → Unavailable (never shown as Normal); Parent Mode and early
 * child play → Normal; Soft Landing → the staged ramp toward the grant's captured target;
 * DULL → the selected Dull profile.
 */
object DullModePolicy {

    fun policyFor(state: AuthorizationState, e: Long): NetworkPolicy {
        if (state.mode == AuthorizationMode.DEACTIVATED) return NetworkPolicy.Unavailable(StateMachine.REASON_DEACTIVATED)
        if (state.vpnCapability != VpnCapability.RUNNING) {
            return NetworkPolicy.Unavailable(StateMachine.REASON_VPN_UNAVAILABLE)
        }
        val grant = state.childGrant
        return when (state.mode) {
            AuthorizationMode.DULL -> NetworkPolicy.Dull(state.settings.dullIntensity)
            AuthorizationMode.PARENT_MODE -> NetworkPolicy.Normal
            AuthorizationMode.CHILD_PLAY_SESSION -> if (grant == null) dull(state) else NetworkPolicy.Normal
            AuthorizationMode.SOFT_LANDING ->
                if (grant == null) dull(state) else SoftLandingPolicy.policy(grant, e)
            AuthorizationMode.DEACTIVATED -> NetworkPolicy.Unavailable(StateMachine.REASON_DEACTIVATED)
        }
    }

    private fun dull(state: AuthorizationState): NetworkPolicy = NetworkPolicy.Dull(state.settings.dullIntensity)
}
