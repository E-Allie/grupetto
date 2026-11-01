package com.spop.poverlay.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ChartSignatureTest {
    @Test
    fun `identical data and bounds share a signature`() {
        assertEquals(chartSignature(listOf(1f, 2f, 3f), 250f), chartSignature(listOf(1f, 2f, 3f), 250f))
    }

    @Test
    fun `a new sample changes the signature`() {
        assertNotEquals(chartSignature(listOf(1f, 2f, 3f), 250f), chartSignature(listOf(2f, 3f, 4f), 250f))
        assertNotEquals(chartSignature(listOf(1f, 2f), 250f), chartSignature(listOf(1f, 2f, 2f), 250f))
    }

    @Test
    fun `a new max value changes the signature`() {
        assertNotEquals(chartSignature(listOf(1f, 2f, 3f), 250f), chartSignature(listOf(1f, 2f, 3f), 300f))
    }
}
