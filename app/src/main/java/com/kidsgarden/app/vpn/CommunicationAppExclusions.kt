package com.kidsgarden.app.vpn

import android.content.Context
import android.content.pm.PackageManager
import android.net.VpnService
import android.provider.Telephony
import android.telecom.TelecomManager

/** The communication packages to exclude, resolved fresh each time. Never stored. */
interface CommunicationPackageResolver {
    fun defaultDialer(): String?
    fun systemDialer(): String?
    fun defaultSms(): String?
    val ownPackage: String
}

/**
 * Excludes one package from the VPN. Returns false if the package is not installed or not
 * visible. The only operation offered is exclusion: there is no allow-list, and no way to
 * bypass the VPN for the whole device.
 */
fun interface ExclusionTarget {
    fun disallow(packageName: String): Boolean
}

enum class ExclusionSkipReason {
    /** No default app is set for this role (for example, no default SMS app). */
    NOT_RESOLVED,

    /** The package is not installed or not visible to this app. */
    NOT_INSTALLED,

    /** The VPN builder rejected the package for another reason. */
    FAILED,
}

/** Coarse status for the parent; individual package names are not shown or kept. */
enum class ExclusionStatus { ALL_APPLIED, PARTIAL, NONE }

data class ExclusionResult(
    val applied: Set<String>,
    val skipped: Map<String, ExclusionSkipReason>,
    /** Roles with no default app, which are not failures. */
    val unresolvedRoles: Int,
) {
    val status: ExclusionStatus
        get() = when {
            applied.isEmpty() -> ExclusionStatus.NONE
            skipped.values.any { it == ExclusionSkipReason.FAILED } -> ExclusionStatus.PARTIAL
            else -> ExclusionStatus.ALL_APPLIED
        }
}

/**
 * Phone, messaging, and WhatsApp traffic is never slowed (spec FR-021, research R10,
 * T080). Before each `establish()` this resolves the default and system dialers, the
 * default SMS/RCS app, and `com.whatsapp`, and excludes each from the VPN. Each package is
 * handled on its own: one that is missing or fails never affects the others or dulling of
 * everything else. Results are returned, never persisted, so a changed default SMS app
 * takes effect at the next establish.
 */
class CommunicationAppExclusions(private val resolver: CommunicationPackageResolver) {

    fun apply(target: ExclusionTarget): ExclusionResult {
        var unresolved = 0
        val candidates = linkedSetOf<String>()
        for (resolved in listOf(resolver.defaultDialer(), resolver.systemDialer(), resolver.defaultSms())) {
            if (resolved.isNullOrBlank()) unresolved++ else candidates += resolved
        }
        candidates += WHATSAPP
        // Never exclude Kids Garden itself: its own traffic must stay inside its VPN.
        candidates -= resolver.ownPackage

        val applied = linkedSetOf<String>()
        val skipped = linkedMapOf<String, ExclusionSkipReason>()
        for (packageName in candidates) {
            val ok = try {
                target.disallow(packageName)
            } catch (_: RuntimeException) {
                skipped[packageName] = ExclusionSkipReason.FAILED
                continue
            }
            if (ok) applied += packageName else skipped[packageName] = ExclusionSkipReason.NOT_INSTALLED
        }
        return ExclusionResult(applied, skipped, unresolved)
    }

    companion object {
        const val WHATSAPP = "com.whatsapp"
    }
}

/** Android lookups for [CommunicationPackageResolver]; visibility comes from manifest `<queries>`. */
class AndroidCommunicationPackageResolver(private val context: Context) : CommunicationPackageResolver {
    private val telecom = context.getSystemService(TelecomManager::class.java)

    override fun defaultDialer(): String? = telecom?.defaultDialerPackage

    override fun systemDialer(): String? = telecom?.systemDialerPackage

    override fun defaultSms(): String? = Telephony.Sms.getDefaultSmsPackage(context)

    override val ownPackage: String = context.packageName
}

/** [ExclusionTarget] over `VpnService.Builder.addDisallowedApplication`. */
fun VpnService.Builder.asExclusionTarget(): ExclusionTarget = ExclusionTarget { packageName ->
    try {
        addDisallowedApplication(packageName)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }
}
