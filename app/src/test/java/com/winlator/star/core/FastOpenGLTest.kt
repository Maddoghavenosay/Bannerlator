package com.winlator.star.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FastOpenGLTest {

    @Test fun containerDefaultsOn() {
        assertTrue(FastOpenGL.containerOn(null))
        assertTrue(FastOpenGL.containerOn(""))
        assertTrue(FastOpenGL.containerOn("1"))
        assertFalse(FastOpenGL.containerOn("0"))
        assertTrue(FastOpenGL.containerOn("junk"))
    }

    @Test fun shortcutOverridesContainer() {
        assertTrue(FastOpenGL.requested(null, null))
        assertFalse(FastOpenGL.requested("0", ""))
        assertTrue(FastOpenGL.requested("0", "1"))
        assertFalse(FastOpenGL.requested("1", "0"))
        assertFalse(FastOpenGL.requested("0", "junk"))
    }

    @Test fun firstReasonWins() {
        assertNull(FastOpenGL.unavailableReason(true, driverUsable = true, bundled = true))
        assertNull(FastOpenGL.unavailableReason(null, driverUsable = true, bundled = true))
        assertEquals(FastOpenGL.NEEDS_LAYER, FastOpenGL.unavailableReason(false, driverUsable = false, bundled = false))
        assertEquals(FastOpenGL.NEEDS_TURNIP, FastOpenGL.unavailableReason(true, driverUsable = false, bundled = false))
        assertEquals(FastOpenGL.NOT_BUNDLED, FastOpenGL.unavailableReason(true, driverUsable = true, bundled = false))
    }
}

class GlLaunchSwitchTest {
    @Test fun defaultsOffAndOverride() {
        val sw = FastOpenGL.FPS_HUD
        assertFalse(sw.containerOn(null))
        assertTrue(sw.containerOn("1"))
        assertTrue(sw.requested("0", "1"))
        assertFalse(sw.requested("1", "0"))
        assertTrue(sw.requested("1", ""))
        assertFalse(FastOpenGL.VSYNC_OFF.requested(null, null))
    }
}
