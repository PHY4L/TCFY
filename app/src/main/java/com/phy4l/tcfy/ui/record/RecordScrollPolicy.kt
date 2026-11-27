package com.phy4l.tcfy.ui.record

/**
 * Pure scroll-follow state policy for the live transcript list so tab switching
 * returns directly to the latest transcript while preserving manual scroll-up inspection.
 */
object RecordScrollPolicy {

    /**
     * Computes total visible item count including the optional live partial preview card.
     */
    fun totalListItems(segmentCount: Int, hasPartialPreview: Boolean): Int {
        return segmentCount + if (hasPartialPreview) 1 else 0
    }

    /**
     * Returns true when the user's visible viewport is near the latest item.
     */
    fun isNearLatest(lastVisibleIndex: Int?, totalItems: Int): Boolean {
        if (totalItems <= 2) return true
        val visible = lastVisibleIndex ?: return false
        return visible >= totalItems - 2
    }

    /**
     * Decides the target index to jump to when entering/returning to the Record tab.
     * Returns null when there are no items or user explicitly scrolled up to inspect history.
     */
    fun targetIndexOnTabEnter(totalItems: Int, autoFollowLatest: Boolean): Int? {
        if (totalItems <= 0 || !autoFollowLatest) return null
        return totalItems - 1
    }

    /**
     * Decides whether arriving segments should auto-scroll to the latest item.
     */
    fun targetIndexOnNewItems(
        totalItems: Int,
        autoFollowLatest: Boolean,
        lastVisibleIndex: Int?
    ): Int? {
        if (totalItems <= 0) return null
        if (autoFollowLatest || isNearLatest(lastVisibleIndex, totalItems)) {
            return totalItems - 1
        }
        return null
    }
}
