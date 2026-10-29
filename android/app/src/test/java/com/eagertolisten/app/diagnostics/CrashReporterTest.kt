package com.eagertolisten.app.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Test

class CrashReporterTest {

    @Test
    fun subjectIncludesVersionBuildDeviceAndAndroid() {
        val subject = CrashReporter.buildSubject(
            versionName = "0.9.2-alpha",
            versionCode = 11,
            manufacturer = "Google",
            model = "Pixel 11 Pro X",
            androidRelease = "17"
        )
        assertEquals("ETL crash report - 0.9.2-alpha (build 11) - Google Pixel 11 Pro X / Android 17", subject)
    }

    @Test
    fun subjectDoesNotRepeatManufacturerAlreadyInModel() {
        val subject = CrashReporter.buildSubject("0.9.2-alpha", 11, "samsung", "Samsung Galaxy S26", "16")
        assertEquals("ETL crash report - 0.9.2-alpha (build 11) - Samsung Galaxy S26 / Android 16", subject)
    }

    @Test
    fun reportsGoToTheDeveloperAddress() {
        assertEquals("michael.eger@michaelegerconsulting.com", CrashReporter.DEVELOPER_EMAIL)
    }
}
