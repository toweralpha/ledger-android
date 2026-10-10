package com.xiaozhangben.ledger.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Brand detection behind the per-manufacturer keep-alive guidance.
 *
 * This is the part of "works on other Android phones" that can actually be wrong: the
 * APK installs anywhere, but showing a Xiaomi user the Huawei instructions is worse than
 * showing nothing, because they will follow the steps and conclude the app is broken.
 */
class OemGuidanceTest {

    @Test
    fun `xiaomi sub-brands are recognised`() {
        // A Redmi reports manufacturer "Xiaomi" and brand "Redmi"; checking only one
        // field would miss half the line-up.
        assertEquals(Oem.XIAOMI, OemGuidance.detect("Xiaomi", "Redmi"))
        assertEquals(Oem.XIAOMI, OemGuidance.detect("Xiaomi", "POCO"))
        assertEquals(Oem.XIAOMI, OemGuidance.detect("Xiaomi", "Xiaomi"))
        assertEquals(Oem.XIAOMI, OemGuidance.detect("Blackshark", "blackshark"))
    }

    @Test
    fun `huawei and honor are told apart`() {
        assertEquals(Oem.HUAWEI, OemGuidance.detect("HUAWEI", "HUAWEI"))
        assertEquals(Oem.HONOR, OemGuidance.detect("HONOR", "HONOR"))
        assertEquals(Oem.HONOR, OemGuidance.detect("HONOR", "hihonor"))
        // Older Honor phones report manufacturer HUAWEI with brand HONOR; the brand must
        // win, because MagicOS and EMUI have different settings screens.
        assertEquals(Oem.HONOR, OemGuidance.detect("HUAWEI", "HONOR"))
    }

    @Test
    fun `oppo family is recognised and oneplus is separate`() {
        assertEquals(Oem.OPPO, OemGuidance.detect("OPPO", "OPPO"))
        assertEquals(Oem.OPPO, OemGuidance.detect("realme", "RMX3363"))
        assertEquals(Oem.ONEPLUS, OemGuidance.detect("OnePlus", "OnePlus"))
    }

    @Test
    fun `vivo and iqoo map to the same guidance`() {
        assertEquals(Oem.VIVO, OemGuidance.detect("vivo", "vivo"))
        assertEquals(Oem.VIVO, OemGuidance.detect("vivo", "iQOO"))
    }

    @Test
    fun `samsung and meizu are recognised`() {
        assertEquals(Oem.SAMSUNG, OemGuidance.detect("samsung", "SM-S9210"))
        assertEquals(Oem.MEIZU, OemGuidance.detect("Meizu", "meizu"))
    }

    @Test
    fun `an unknown brand falls back to generic instead of guessing`() {
        assertEquals(Oem.GENERIC, OemGuidance.detect("Google", "Pixel 8"))
        assertEquals(Oem.GENERIC, OemGuidance.detect(null, null))
        assertEquals(Oem.GENERIC, OemGuidance.detect("", ""))
    }

    @Test
    fun `detection is case-insensitive`() {
        assertEquals(Oem.XIAOMI, OemGuidance.detect("XIAOMI", "REDMI"))
        assertEquals(Oem.VIVO, OemGuidance.detect("VIVO", "VIVO"))
    }

    @Test
    fun `every brand has written instructions`() {
        // A brand with no steps would leave the user with two buttons that may both fail
        // on their ROM, and nothing to fall back on.
        Oem.entries.forEach { oem ->
            val profile = OemGuidance.profile(oem)
            assertTrue("${oem.name} has no setup steps", profile.setupSteps.isNotEmpty())
            assertEquals(oem, profile.oem)
            assertTrue("${oem.name} has a blank step", profile.setupSteps.none { it.isBlank() })
        }
    }

    @Test
    fun `every deep link candidate is a well-formed component name`() {
        Oem.entries.forEach { oem ->
            val profile = OemGuidance.profile(oem)
            (profile.autoStartActivities + profile.batteryActivities).forEach { entry ->
                val parts = entry.split('/')
                assertEquals("malformed component: $entry", 2, parts.size)
                assertTrue("empty package in: $entry", parts[0].isNotBlank())
                assertTrue("empty class in: $entry", parts[1].isNotBlank())
            }
        }
    }

    @Test
    fun `the generic profile claims no deep links it cannot honour`() {
        // There is no portable auto-start screen, so offering a button that goes nowhere
        // would be misleading; the steps are the whole answer for unknown brands.
        val generic = OemGuidance.profile(Oem.GENERIC)
        assertTrue(generic.autoStartActivities.isEmpty())
        assertTrue(generic.batteryActivities.isEmpty())
    }

    @Test
    fun `guidance is manufacturer specific rather than one shared blob`() {
        val xiaomi = OemGuidance.profile(Oem.XIAOMI).setupSteps.joinToString()
        val huawei = OemGuidance.profile(Oem.HUAWEI).setupSteps.joinToString()
        val samsung = OemGuidance.profile(Oem.SAMSUNG).setupSteps.joinToString()
        assertFalse(xiaomi == huawei)
        assertFalse(huawei == samsung)
        assertTrue(xiaomi.contains("省电策略"))
        assertTrue(huawei.contains("应用启动管理"))
        assertTrue(samsung.contains("从不休眠"))
    }
}
