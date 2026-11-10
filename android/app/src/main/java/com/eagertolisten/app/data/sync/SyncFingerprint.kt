package com.eagertolisten.app.data.sync

import org.json.JSONObject
import java.security.MessageDigest

/**
 * "Has anything changed since the last sync?" (mirrored by desktop/src/core/syncRunner.js).
 *
 * A sync used to download, merge and re-upload the whole file every time the app went to the
 * background. Now each side is summed up in a short fingerprint:
 *  - the folder: name, size and modified time of every sync and bookmark-archive file in it
 *  - this device: a hash of what it would write (minus the time stamp)
 * If the folder's fingerprint matches the one saved after the last sync, nothing there needs
 * reading. If this device's matches too, nothing needs writing either.
 */
object SyncFingerprint {

    /** Even with matching fingerprints, do a full pass this often, as a safety net. */
    const val FULL_SYNC_EVERY_MS = 6L * 60 * 60 * 1000

    data class FileInfo(val name: String, val size: Long, val modifiedAt: Long)

    /** The files that make up the shared state: sync file, its conflict copies, the bookmark archive. */
    fun isSharedFile(name: String?): Boolean {
        if (name == null) return false
        val sync = name.startsWith("eagertolisten_sync") && name.endsWith(".json") &&
            !name.endsWith(".tmp.json") && !name.endsWith(".bak.json")
        return sync || BookmarkArchive.isArchiveFileName(name)
    }

    fun ofFolder(files: Collection<FileInfo>): String =
        files.filter { isSharedFile(it.name) }
            .sortedBy { it.name }
            .joinToString("|") { "${it.name}:${it.size}:${it.modifiedAt}" }

    /** Hash of what this device would write, ignoring when it was written. */
    fun ofLocalState(export: JSONObject): String {
        val copy = JSONObject(export.toString())
        copy.remove("writtenAt")
        val bytes = MessageDigest.getInstance("SHA-256").digest(copy.toString().toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /** Hash of the bookmark part of the export, plus how many removed bookmarks still wait for the archive. */
    fun ofBookmarks(export: JSONObject, heldCount: Int): String {
        val text = "${export.optJSONArray("inspirations")}|${export.optJSONArray("inspirationsRemoved")}|$heldCount"
        return MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    /**
     * After a full pass (the folder had changed, so everything in it was read and merged): the file
     * only needs writing again if this device now holds something the folder doesn't. If the merge
     * left this device exactly as it was at its last write, the other device's file already
     * includes everything from here. Skipping that write is also what stops two devices, or one
     * device and Drive's own upload timing, from re-triggering each other forever.
     */
    fun canSkipWriteAfterMerge(
        mustWrite: Boolean,
        mainFileRead: Boolean,
        localAfterMerge: String,
        localAtLastSync: String?
    ): Boolean = !mustWrite && mainFileRead && localAtLastSync != null && localAfterMerge == localAtLastSync

    enum class Plan { NOTHING_TO_DO, WRITE_ONLY, FULL }

    /**
     * @param force the Sync button, or a restore: always the full pass
     * @param hasMainFile the main sync file is in the folder (if not, it has to be written)
     */
    fun plan(
        force: Boolean,
        folderNow: String,
        folderAtLastSync: String?,
        localNow: String,
        localAtLastSync: String?,
        hasMainFile: Boolean,
        lastFullSyncAt: Long,
        now: Long
    ): Plan = when {
        force || folderAtLastSync == null || localAtLastSync == null -> Plan.FULL
        now - lastFullSyncAt > FULL_SYNC_EVERY_MS || now < lastFullSyncAt -> Plan.FULL
        folderNow != folderAtLastSync || !hasMainFile -> Plan.FULL
        localNow != localAtLastSync -> Plan.WRITE_ONLY
        else -> Plan.NOTHING_TO_DO
    }
}
