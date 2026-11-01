package com.spop.poverlay.erg

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResistanceCommandTrackerTest {
    @Test fun `uninitialized tracker does not report an override`() {
        assertFalse(ResistanceCommandTracker().isUnexpected(40, 1000))
    }

    @Test fun `prior target echoes are accepted for exactly fifteen hundred milliseconds`() {
        val tracker = ResistanceCommandTracker()
        tracker.reset(40, 1000)
        tracker.commanded(50, 1100)
        assertFalse(tracker.isUnexpected(40, 2600))
        assertTrue(tracker.isUnexpected(40, 2601))
    }

    @Test fun `several in flight command targets are all recognized`() {
        val tracker = ResistanceCommandTracker()
        tracker.reset(40, 1000)
        tracker.commanded(50, 1100)
        tracker.commanded(60, 1200)
        assertFalse(tracker.isUnexpected(40, 1300))
        assertFalse(tracker.isUnexpected(50, 1300))
        assertFalse(tracker.isUnexpected(60, 1300))
        assertTrue(tracker.isUnexpected(55, 1300))
    }

    @Test fun `latest target remains expected after echo history expires`() {
        val tracker = ResistanceCommandTracker()
        tracker.reset(40, 1000)
        tracker.commanded(50, 1100)
        assertFalse(tracker.isUnexpected(50, 10000))
        assertTrue(tracker.isUnexpected(40, 10000))
    }

    @Test fun `reset discards targets from the previous control session`() {
        val tracker = ResistanceCommandTracker()
        tracker.reset(40, 1000)
        tracker.commanded(50, 1100)
        tracker.reset(60, 1200)
        assertFalse(tracker.isUnexpected(60, 1200))
        assertTrue(tracker.isUnexpected(40, 1200))
        assertTrue(tracker.isUnexpected(50, 1200))
    }
    @Test fun `first adjustment after a long hold accepts delayed preceding target`() {
        val tracker = ResistanceCommandTracker()
        tracker.reset(40, 0)
        tracker.commanded(38, 2000)
        assertFalse(tracker.isUnexpected(40, 2100))
        assertFalse(tracker.isUnexpected(40, 3500))
        assertTrue(tracker.isUnexpected(40, 3501))
        assertTrue(tracker.isUnexpected(2, 2100))
    }

    @Test fun `later commands do not indefinitely renew an older target`() {
        val tracker = ResistanceCommandTracker()
        tracker.reset(40, 0)
        tracker.commanded(38, 2000)
        tracker.commanded(36, 2500)
        tracker.commanded(34, 3000)
        tracker.commanded(34, 3400)
        assertTrue(tracker.isUnexpected(40, 3501))
        assertFalse(tracker.isUnexpected(38, 4000))
        assertTrue(tracker.isUnexpected(38, 4001))
        assertFalse(tracker.isUnexpected(36, 4500))
        assertTrue(tracker.isUnexpected(36, 4501))
    }

}
