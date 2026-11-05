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
        assertEquals(7, AppInfo.databaseVersion)
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
