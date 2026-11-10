package com.eagertolisten.app.data.sync

import com.eagertolisten.app.data.sync.SyncFingerprint.FileInfo
import com.eagertolisten.app.data.sync.SyncFingerprint.Plan
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncFingerprintTest {

    private val hour = 60L * 60 * 1000

    private fun plan(
        force: Boolean = false,
        folderNow: String = "f1",
        folderSaved: String? = "f1",
        localNow: String = "l1",
        localSaved: String? = "l1",
        hasMain: Boolean = true,
        lastFull: Long = 10 * hour,
        now: Long = 11 * hour
    ) = SyncFingerprint.plan(force, folderNow, folderSaved, localNow, localSaved, hasMain, lastFull, now)

    @Test
    fun nothingChangedMeansNothingToDo() {
        assertEquals(Plan.NOTHING_TO_DO, plan())
    }

    @Test
    fun onlyThisDeviceChangedMeansWriteWithoutReading() {
        assertEquals(Plan.WRITE_ONLY, plan(localNow = "l2"))
    }

    @Test
    fun anythingElseIsAFullPass() {
        assertEquals("folder changed", Plan.FULL, plan(folderNow = "f2"))
        assertEquals("Sync button", Plan.FULL, plan(force = true))
        assertEquals("first sync", Plan.FULL, plan(folderSaved = null))
        assertEquals("first sync", Plan.FULL, plan(localSaved = null))
        assertEquals("main file gone", Plan.FULL, plan(hasMain = false))
        assertEquals("safety net", Plan.FULL, plan(now = 10 * hour + SyncFingerprint.FULL_SYNC_EVERY_MS + 1))
        assertEquals("clock moved back", Plan.FULL, plan(now = 9 * hour))
    }

    @Test
    fun folderFingerprintTracksSharedFilesOnly() {
        val base = listOf(
            FileInfo("eagertolisten_sync.json", 1000, 50),
            FileInfo("eagertolisten_bookmarks.jsonl", 200, 40)
        )
        val fp = SyncFingerprint.ofFolder(base)
        // Order, backups, temp files and unrelated files don't matter
        assertEquals(fp, SyncFingerprint.ofFolder(base.reversed() + FileInfo("eagertolisten_sync.bak.json", 9, 9) +
            FileInfo("eagertolisten_sync.tmp.json", 9, 9) + FileInfo("holiday.jpg", 9, 9)))
        // A changed file, or a conflict copy from another device, does
        assertNotEquals(fp, SyncFingerprint.ofFolder(listOf(base[0].copy(modifiedAt = 51), base[1])))
        assertNotEquals(fp, SyncFingerprint.ofFolder(listOf(base[0].copy(size = 1001), base[1])))
        assertNotEquals(fp, SyncFingerprint.ofFolder(base + FileInfo("eagertolisten_sync (1).json", 900, 45)))
        assertNotEquals(fp, SyncFingerprint.ofFolder(listOf(base[0], base[1].copy(modifiedAt = 41))))
    }

    @Test
    fun localFingerprintIgnoresOnlyTheTimeStamp() {
        fun export(writtenAt: Long, pos: Int) = JSONObject()
            .put("format", 2).put("deviceId", "pixel").put("writtenAt", writtenAt)
            .put("episodes", JSONArray().put(JSONObject().put("id", "f::e1").put("pos", pos)))
        assertEquals(SyncFingerprint.ofLocalState(export(1, 120)), SyncFingerprint.ofLocalState(export(2, 120)))
        assertNotEquals(SyncFingerprint.ofLocalState(export(1, 120)), SyncFingerprint.ofLocalState(export(1, 121)))
        // The export handed in isn't altered
        val e = export(7, 1)
        SyncFingerprint.ofLocalState(e)
        assertEquals(7L, e.getLong("writtenAt"))
    }

    @Test
    fun bookmarkFingerprintFollowsBookmarksRemovalsAndHeldCopies() {
        fun export(note: String, removed: Int) = JSONObject()
            .put("inspirations", JSONArray().put(JSONObject().put("id", "b1").put("note", note)))
            .put("inspirationsRemoved", JSONArray().apply { repeat(removed) { put(JSONObject().put("id", "r$it").put("removedAt", 5)) } })
            .put("episodes", JSONArray().put(JSONObject().put("pos", System.nanoTime())))
        val fp = SyncFingerprint.ofBookmarks(export("a", 0), 0)
        assertEquals("listening progress doesn't count", fp, SyncFingerprint.ofBookmarks(export("a", 0), 0))
        assertNotEquals(fp, SyncFingerprint.ofBookmarks(export("b", 0), 0))
        assertNotEquals(fp, SyncFingerprint.ofBookmarks(export("a", 1), 0))
        assertNotEquals(fp, SyncFingerprint.ofBookmarks(export("a", 0), 1))
    }

    @Test
    fun afterAMergeTheWriteIsSkippedOnlyWhenThisDeviceHasNothingNew() {
        assertTrue(SyncFingerprint.canSkipWriteAfterMerge(mustWrite = false, mainFileRead = true, localAfterMerge = "l1", localAtLastSync = "l1"))
        // The merge brought something in, or this device changed: its file must go out
        assertFalse(SyncFingerprint.canSkipWriteAfterMerge(false, true, "l2", "l1"))
        // Sync button or the 6-hour safety net: always write
        assertFalse(SyncFingerprint.canSkipWriteAfterMerge(true, true, "l1", "l1"))
        // No main file was read (first sync, or it was missing): write one
        assertFalse(SyncFingerprint.canSkipWriteAfterMerge(false, false, "l1", "l1"))
        assertFalse(SyncFingerprint.canSkipWriteAfterMerge(false, true, "l1", null))
    }
}
