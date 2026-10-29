package com.eagertolisten.app.data.v4v

import org.json.JSONObject

data class BoostagramPayload(
    val podcast: String,
    val feedUrl: String,
    val episode: String? = null,
    val itemId: String? = null,
    val timestampSeconds: Long? = null,
    val speed: String? = null,
    val action: String = "boost",
    val appName: String = "Eager to Listen",
    val appVersion: String = "0.9.2-alpha",
    val message: String? = null,
    val senderName: String? = null,
    val valueMsat: Long,
    val valueMsatTotal: Long
) {
    companion object {
        const val TLV_RECORD_TYPE_PODCASTING_20: Long = 7629169L

        fun bytesToHex(bytes: ByteArray): String {
            val hexChars = CharArray(bytes.size * 2)
            val hexArray = "0123456789abcdef".toCharArray()
            for (j in bytes.indices) {
                val v = bytes[j].toInt() and 0xFF
                hexChars[j * 2] = hexArray[v ushr 4]
                hexChars[j * 2 + 1] = hexArray[v and 0x0F]
            }
            return String(hexChars)
        }
    }

    fun toJson(): JSONObject {
        val json = JSONObject()
        json.put("podcast", podcast)
        json.put("feed", feedUrl)
        if (!episode.isNullOrBlank()) json.put("episode", episode)
        if (!itemId.isNullOrBlank()) json.put("itemId", itemId)
        if (timestampSeconds != null && timestampSeconds >= 0) json.put("ts", timestampSeconds)
        if (!speed.isNullOrBlank()) json.put("speed", speed)
        json.put("action", action)
        json.put("app_name", appName)
        json.put("app_version", appVersion)
        if (!message.isNullOrBlank()) json.put("message", message)
        if (!senderName.isNullOrBlank()) json.put("sender_name", senderName)
        json.put("value_msat", valueMsat)
        json.put("value_msat_total", valueMsatTotal)
        return json
    }

    fun toJsonString(): String = toJson().toString()

    fun toTlvHex(): String {
        val bytes = toJsonString().toByteArray(Charsets.UTF_8)
        return bytesToHex(bytes)
    }
}
