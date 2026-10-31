package com.eagertolisten.app.data.local

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalFolderFormatTest {

    private val tree = "content://com.android.externalstorage.documents/tree/primary%3APodcast%20Media%2FSynth%20Zone"

    @Test
    fun localIdsRoundTrip() {
        val id = LocalFolderFormat.feedIdFor(tree)
        assertEquals(true, LocalFolderFormat.isLocalFeed(id))
        assertEquals(tree, LocalFolderFormat.treeUriOf(id))
        assertEquals(false, LocalFolderFormat.isLocalFeed("https://feeds.example.com/show"))
        assertEquals(false, LocalFolderFormat.isLocalFeed(null))
    }

    @Test
    fun recognizesAudioFiles() {
        assertEquals(true, LocalFolderFormat.isAudioFile("ep01.mp3", null))
        assertEquals(true, LocalFolderFormat.isAudioFile("Show.M4A", null))
        assertEquals(true, LocalFolderFormat.isAudioFile("noext", "audio/mpeg"))
        assertEquals(false, LocalFolderFormat.isAudioFile("cover.jpg", "image/jpeg"))
        assertEquals(false, LocalFolderFormat.isAudioFile("notes.txt", null))
    }

    @Test
    fun titlesFromFileNames() {
        assertEquals("03 - Synth Zone Live", LocalFolderFormat.titleFromFileName("03 - Synth Zone_Live.mp3"))
        assertEquals("I'm Sorry I Haven't a Clue", LocalFolderFormat.titleFromFileName("I'm Sorry I Haven't a Clue.mp3"))
        assertEquals("noext", LocalFolderFormat.titleFromFileName("noext"))
    }

    @Test
    fun showTitleFallsBackToFolderName() {
        assertEquals("Synth Zone", LocalFolderFormat.showTitle(null, tree))
        assertEquals("My Folder", LocalFolderFormat.showTitle("My Folder", tree))
    }

    @Test
    fun naturalOrderSortsNumbersByValue() {
        val sorted = listOf("Episode 10.mp3", "episode 2.mp3", "Episode 1.mp3").sortedWith(LocalFolderFormat.naturalOrder)
        assertEquals(listOf("Episode 1.mp3", "episode 2.mp3", "Episode 10.mp3"), sorted)
    }

    @Test
    fun publishTimesFollowFilenameOrderEvenWhenCopiedTogether() {
        // All copied at the same moment: times must still increase in filename order
        val t = 1_700_000_000_000L
        val result = LocalFolderFormat.publishTimesInNameOrder(listOf("Ep 10.mp3" to t, "Ep 2.mp3" to t, "Ep 1.mp3" to t))
        assertEquals(listOf("Ep 1.mp3", "Ep 2.mp3", "Ep 10.mp3"), result.map { it.first })
        assertEquals(listOf(t, t + 1, t + 2), result.map { it.second })
        // A later-named file with an older date still plays after the one before it
        val mixed = LocalFolderFormat.publishTimesInNameOrder(listOf("A.mp3" to 500L, "B.mp3" to 100L))
        assertEquals(listOf("A.mp3" to 500L, "B.mp3" to 501L), mixed)
    }
}
