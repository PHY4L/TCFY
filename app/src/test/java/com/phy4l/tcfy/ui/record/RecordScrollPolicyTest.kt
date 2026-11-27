package com.phy4l.tcfy.ui.record

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordScrollPolicyTest {

    @Test
    fun returningToRecordTabJumpsToLatestSegmentInsteadOfFirst() {
        val total = RecordScrollPolicy.totalListItems(segmentCount = 25, hasPartialPreview = false)
        assertEquals(25, total)
        assertEquals(24, RecordScrollPolicy.targetIndexOnTabEnter(total, autoFollowLatest = true))
    }

    @Test
    fun returningToRecordTabIncludesPartialPreviewCardAtBottom() {
        val total = RecordScrollPolicy.totalListItems(segmentCount = 10, hasPartialPreview = true)
        assertEquals(11, total)
        assertEquals(10, RecordScrollPolicy.targetIndexOnTabEnter(total, autoFollowLatest = true))
    }

    @Test
    fun respectsManualScrollUpWhenAutoFollowIsDisabled() {
        assertNull(RecordScrollPolicy.targetIndexOnTabEnter(totalItems = 25, autoFollowLatest = false))
        assertNull(
            RecordScrollPolicy.targetIndexOnNewItems(
                totalItems = 26,
                autoFollowLatest = false,
                lastVisibleIndex = 5
            )
        )
    }

    @Test
    fun resumesAutoScrollWhenNearBottomOrAutoFollowEnabled() {
        assertTrue(RecordScrollPolicy.isNearLatest(lastVisibleIndex = 24, totalItems = 26))
        assertFalse(RecordScrollPolicy.isNearLatest(lastVisibleIndex = 10, totalItems = 26))
        assertEquals(
            25,
            RecordScrollPolicy.targetIndexOnNewItems(
                totalItems = 26,
                autoFollowLatest = true,
                lastVisibleIndex = 0 // simulates stale layoutInfo right after tab return
            )
        )
    }
}
