package com.eagertolisten.app.data.v4v

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BoostagramPayloadTest {

    @Test
    fun testGoldenJsonStructure() {
        val payload = BoostagramPayload(
            podcast = "Eager to Listen",
            feedUrl = "https://example.com/rss.xml",
            episode = "Episode 42: Building for Android",
            itemId = "guid-12345",
            timestampSeconds = 312L,
            speed = "1.0",
            action = "boost",
            appName = "Eager to Listen",
            appVersion = "0.9.0-alpha",
            message = "Great episode, love the local-first focus!",
            senderName = "Alice",
            valueMsat = 5_000_000L,
            valueMsatTotal = 5_000_000L
        )

        val json = payload.toJson()

        assertEquals("Eager to Listen", json.getString("podcast"))
        assertEquals("https://example.com/rss.xml", json.getString("feed"))
        assertEquals("Episode 42: Building for Android", json.getString("episode"))
        assertEquals("guid-12345", json.getString("itemId"))
        assertEquals(312L, json.getLong("ts"))
        assertEquals("1.0", json.getString("speed"))
        assertEquals("boost", json.getString("action"))
        assertEquals("Eager to Listen", json.getString("app_name"))
        assertEquals("0.9.0-alpha", json.getString("app_version"))
        assertEquals("Great episode, love the local-first focus!", json.getString("message"))
        assertEquals("Alice", json.getString("sender_name"))
        assertEquals(5_000_000L, json.getLong("value_msat"))
        assertEquals(5_000_000L, json.getLong("value_msat_total"))
    }

    @Test
    fun testTlvHexEncoding() {
        val payload = BoostagramPayload(
            podcast = "Test Pod",
            feedUrl = "https://test.com/feed",
            valueMsat = 1000L,
            valueMsatTotal = 1000L
        )

        val hex = payload.toTlvHex()
        assertNotNull(hex)
        assertTrue(hex.isNotBlank())
        // Hex string should have even length
        assertEquals(0, hex.length % 2)

        // Decode hex back to UTF-8 string
        val bytes = ByteArray(hex.length / 2) { i ->
            hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        val decoded = String(bytes, Charsets.UTF_8)
        assertTrue(decoded.contains("Test Pod"))
        assertTrue(decoded.contains("https://test.com/feed"))
        assertEquals(7629169L, BoostagramPayload.TLV_RECORD_TYPE_PODCASTING_20)
    }

    @Test
    fun testNwcConnectionParsing() {
        val uri = "nostr+walletconnect://b889ff5b1513b641e2a13e3668573f1e32e056ade6d823a03fc4da88f7116b47?relay=wss%3A%2F%2Frelay.damus.io&secret=71a8c14c1407c113601979c6145d3694fd250e696ffbfa1084fa80473e0ff884&lud16=host%40getalby.com"
        val parsed = NwcConnection.parse(uri).getOrThrow()

        assertEquals("b889ff5b1513b641e2a13e3668573f1e32e056ade6d823a03fc4da88f7116b47", parsed.walletPubkey)
        assertEquals("wss://relay.damus.io", parsed.relayUrl)
        assertEquals("71a8c14c1407c113601979c6145d3694fd250e696ffbfa1084fa80473e0ff884", parsed.secret)
        assertEquals("host@getalby.com", parsed.lud16)
    }
}
