package com.eagertolisten.app.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppInfoTest {

    @Test
    fun constantsMatchAppBrandAndContact() {
        assertEquals("Eager to Listen", AppInfo.APP_NAME)
        assertEquals("michael.eger@michaelegerconsulting.com", AppInfo.DEVELOPER_EMAIL)
        assertEquals("https://github.com/michaeleger/eager-to-listen", AppInfo.GITHUB_URL)
        assertEquals(8, AppInfo.databaseVersion)
    }

    /**
     * DATABASE_VERSION is a copy of @Database(version) for the About page. Room writes one
     * schema file per version when the app compiles, so the newest file must match it.
     */
    @Test
    fun aboutPageDatabaseVersionMatchesNewestSchema() {
        val dir = java.io.File("schemas/com.eagertolisten.app.data.EagerDatabase")
        org.junit.Assume.assumeTrue("schema folder not found from ${java.io.File(".").absolutePath}", dir.isDirectory)
        val newest = dir.listFiles().orEmpty().mapNotNull { it.name.removeSuffix(".json").toIntOrNull() }.maxOrNull()
        assertEquals(newest, com.eagertolisten.app.data.EagerDatabase.DATABASE_VERSION)
    }

    @Test
    fun buildDiagnosticBlockContainsAllRequiredFields() {
        val block = AppInfo.buildDiagnosticBlock(
            vName = "0.9.2-alpha",
            vCode = 11,
            bType = "debug",
            appId = "com.eagertolisten.app",
            dbVersion = 7,
            device = "Google Pixel 10 (Android 15, API 35)"
        )

        assertTrue(block.contains("Eager to Listen v0.9.2-alpha (11)"))
        assertTrue(block.contains("App ID: com.eagertolisten.app"))
        assertTrue(block.contains("Build Variant: debug"))
        assertTrue(block.contains("Database Schema: v7"))
        assertTrue(block.contains("Device: Google Pixel 10 (Android 15, API 35)"))
        assertTrue(block.contains("Track: Closed Alpha Testing"))
    }
}
