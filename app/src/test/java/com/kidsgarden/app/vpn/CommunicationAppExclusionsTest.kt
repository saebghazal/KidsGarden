package com.kidsgarden.app.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Communication-app exclusions (T080, T084). */
class CommunicationAppExclusionsTest {

    private class FakeResolver(
        var dialer: String? = "com.android.dialer",
        var system: String? = "com.android.dialer",
        var sms: String? = "com.google.android.apps.messaging",
        override val ownPackage: String = "com.kidsgarden.app",
    ) : CommunicationPackageResolver {
        override fun defaultDialer() = dialer
        override fun systemDialer() = system
        override fun defaultSms() = sms
    }

    /** Records exclusions; packages in [missing] are not installed, [failing] throw. */
    private class FakeTarget(
        val missing: Set<String> = emptySet(),
        val failing: Set<String> = emptySet(),
    ) : ExclusionTarget {
        val excluded = mutableListOf<String>()
        override fun disallow(packageName: String): Boolean {
            if (packageName in failing) throw IllegalStateException("rejected")
            if (packageName in missing) return false
            excluded += packageName
            return true
        }
    }

    private val resolver = FakeResolver()
    private val exclusions = CommunicationAppExclusions(resolver)

    @Test
    fun allPresentAreExcluded() {
        val target = FakeTarget()
        val result = exclusions.apply(target)
        assertEquals(
            setOf("com.android.dialer", "com.google.android.apps.messaging", CommunicationAppExclusions.WHATSAPP),
            result.applied,
        )
        assertEquals(ExclusionStatus.ALL_APPLIED, result.status)
        // The same dialer resolved twice is excluded once.
        assertEquals(target.excluded.distinct(), target.excluded)
    }

    @Test
    fun missingWhatsAppIsSkippedWithoutAffectingOthers() {
        val result = exclusions.apply(FakeTarget(missing = setOf(CommunicationAppExclusions.WHATSAPP)))
        assertEquals(ExclusionSkipReason.NOT_INSTALLED, result.skipped[CommunicationAppExclusions.WHATSAPP])
        assertTrue("com.google.android.apps.messaging" in result.applied)
        assertEquals(ExclusionStatus.ALL_APPLIED, result.status)
    }

    @Test
    fun nullDefaultSmsIsUnresolvedNotAFailure() {
        resolver.sms = null
        val result = exclusions.apply(FakeTarget())
        assertEquals(1, result.unresolvedRoles)
        assertTrue(CommunicationAppExclusions.WHATSAPP in result.applied)
        assertEquals(ExclusionStatus.ALL_APPLIED, result.status)
    }

    @Test
    fun oneFailingPackageDoesNotStopTheRest() {
        val result = exclusions.apply(FakeTarget(failing = setOf("com.android.dialer")))
        assertEquals(ExclusionSkipReason.FAILED, result.skipped["com.android.dialer"])
        assertTrue(CommunicationAppExclusions.WHATSAPP in result.applied)
        assertTrue("com.google.android.apps.messaging" in result.applied)
        assertEquals(ExclusionStatus.PARTIAL, result.status)
    }

    @Test
    fun neverABroadBypass() {
        resolver.sms = "com.kidsgarden.app" // even if misreported, Kids Garden itself stays inside
        val target = FakeTarget()
        val result = exclusions.apply(target)
        assertFalse("com.kidsgarden.app" in target.excluded)
        assertTrue(result.applied.size <= 4)
        assertTrue(target.excluded.none { it.isBlank() })
    }

    @Test
    fun changedDefaultSmsAppliesOnNextEstablish() {
        val first = exclusions.apply(FakeTarget())
        resolver.sms = "com.samsung.android.messaging"
        val second = exclusions.apply(FakeTarget())
        assertTrue("com.google.android.apps.messaging" in first.applied)
        assertTrue("com.samsung.android.messaging" in second.applied)
        assertFalse("com.google.android.apps.messaging" in second.applied)
    }

    @Test
    fun nothingAppliedIsReportedAsNone() {
        resolver.dialer = null
        resolver.system = null
        resolver.sms = null
        val result = exclusions.apply(FakeTarget(missing = setOf(CommunicationAppExclusions.WHATSAPP)))
        assertEquals(ExclusionStatus.NONE, result.status)
        assertEquals(3, result.unresolvedRoles)
    }

    @Test
    fun parentChosenAppsAreExcludedToo() {
        val target = FakeTarget(missing = setOf("org.gone.app"))
        val result = exclusions.apply(target, listOf("com.example.homework", "org.gone.app", "com.kidsgarden.app"))
        assertTrue("com.example.homework" in result.applied)
        assertEquals(ExclusionSkipReason.NOT_INSTALLED, result.skipped["org.gone.app"])
        // Kids Garden itself can never skip its own VPN, even if listed.
        assertFalse("com.kidsgarden.app" in target.excluded)
        // The fixed communication apps are still excluded.
        assertTrue(CommunicationAppExclusions.WHATSAPP in result.applied)
    }
}
