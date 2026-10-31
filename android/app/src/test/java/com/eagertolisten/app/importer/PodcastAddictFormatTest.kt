package com.eagertolisten.app.importer

import org.junit.Assert.assertEquals
import org.junit.Test

/** Values taken from a real Podcast Addict backup (Oct 2026). */
class PodcastAddictFormatTest {

    @Test
    fun parsesTextDurations() {
        assertEquals(1932L, PodcastAddictFormat.parseDurationText("32:12"))
        assertEquals(4118L, PodcastAddictFormat.parseDurationText("01:08:38"))
        assertEquals(1932L, PodcastAddictFormat.parseDurationText("1932"))
        assertEquals(0L, PodcastAddictFormat.parseDurationText(""))
        assertEquals(0L, PodcastAddictFormat.parseDurationText(null))
        assertEquals(0L, PodcastAddictFormat.parseDurationText("about an hour"))
    }

    @Test
    fun prefersMillisecondDurations() {
        // Planet Money episode: duration_ms 1932251, rssfeed_duration_ms 1782000, duration "32:12"
        assertEquals(1932L, PodcastAddictFormat.durationSeconds(1932251L, 1782000L, "32:12"))
        assertEquals(1782L, PodcastAddictFormat.durationSeconds(0L, 1782000L, "32:12"))
        assertEquals(1932L, PodcastAddictFormat.durationSeconds(0L, 0L, "32:12"))
    }

    @Test
    fun readsPerShowSpeedsOnlyWhenEnabled() {
        val xml = """
            <map>
                <boolean name="pref_speedPlaybackOn_86" value="true" />
                <float name="pref_speedAdjustment" value="1.2" />
                <float name="pref_speedAdjustment_86" value="1.2" />
                <float name="pref_speedAdjustment_40" value="1.5" />
                <boolean name="pref_speedPlaybackOn_57" value="true" />
                <float name="pref_speedAdjustment_57" value="9.0" />
            </map>
        """.trimIndent()
        val speeds = PodcastAddictFormat.parsePerShowSpeeds(xml)
        assertEquals(mapOf("86" to 1.2f), speeds) // 40 not enabled; 57 out of range
        assertEquals(emptyMap<String, Float>(), PodcastAddictFormat.parsePerShowSpeeds(null))
    }

    @Test
    fun picksTheDatabaseNotThePreferencesXml() {
        assertEquals(false, PodcastAddictFormat.isDatabaseEntry("com.bambuna.podcastaddict_preferences.xml"))
        assertEquals(true, PodcastAddictFormat.isDatabaseEntry("podcastAddict.db"))
        assertEquals(true, PodcastAddictFormat.isPreferencesEntry("com.bambuna.podcastaddict_preferences.xml"))
        assertEquals(false, PodcastAddictFormat.isPreferencesEntry("podcastAddict.db"))
    }
}
