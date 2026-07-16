package com.tracktosearch.ui.animation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnimationsTest {
    @Test
    fun stagger_delay_per_row() {
        assertEquals(0, enterStaggerDelayMs(0))
        assertEquals(30, enterStaggerDelayMs(1))
        assertEquals(60, enterStaggerDelayMs(2))
        assertEquals(0, enterStaggerDelayMs(3))
        assertEquals(60, enterStaggerDelayMs(11))
    }

    @Test
    fun should_play_default_only_once() {
        val played = mutableSetOf<Long>()
        assertTrue(shouldPlayDefault(1L, played))
        played.add(1L)
        assertFalse(shouldPlayDefault(1L, played))
    }
}
