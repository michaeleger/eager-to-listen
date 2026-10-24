package com.eagertolisten.app.data.model

data class Chapter(
    val startTime: Double,
    val endTime: Double? = null,
    val title: String,
    val img: String? = null,
    val url: String? = null
)

data class Person(
    val name: String,
    val role: String? = null,
    val group: String? = null,
    val img: String? = null,
    val href: String? = null
)

data class Soundbite(
    val startTime: Double,
    val duration: Double,
    val title: String? = null
)

data class TranscriptCue(
    val startMs: Long,
    val endMs: Long,
    val text: String
)

data class ValueRecipient(
    val name: String,
    val type: String = "node",
    val address: String,
    val split: Int = 1,
    val customKey: String? = null,
    val customValue: String? = null,
    val fee: Boolean = false
)

data class PodcastValue(
    val type: String = "lightning",
    val method: String = "keysend",
    val suggested: Double? = null,
    val recipients: List<ValueRecipient> = emptyList()
) {
    fun toJson(): String {
        val root = org.json.JSONObject()
        root.put("type", type)
        root.put("method", method)
        suggested?.let { root.put("suggested", it) }
        val arr = org.json.JSONArray()
        recipients.forEach { r ->
            val obj = org.json.JSONObject()
            obj.put("name", r.name)
            obj.put("type", r.type)
            obj.put("address", r.address)
            obj.put("split", r.split)
            r.customKey?.let { obj.put("customKey", it) }
            r.customValue?.let { obj.put("customValue", it) }
            obj.put("fee", r.fee)
            arr.put(obj)
        }
        root.put("recipients", arr)
        return root.toString()
    }

    companion object {
        fun fromJson(jsonString: String?): PodcastValue? {
            if (jsonString.isNullOrBlank()) return null
            return try {
                val root = org.json.JSONObject(jsonString)
                val type = root.optString("type", "lightning")
                val method = root.optString("method", "keysend")
                val suggested = if (root.has("suggested")) root.optDouble("suggested") else null
                val arr = root.optJSONArray("recipients") ?: org.json.JSONArray()
                val list = mutableListOf<ValueRecipient>()
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    list.add(
                        ValueRecipient(
                            name = obj.optString("name", "Recipient"),
                            type = obj.optString("type", "node"),
                            address = obj.optString("address", ""),
                            split = obj.optInt("split", 1),
                            customKey = obj.optString("customKey").ifBlank { null },
                            customValue = obj.optString("customValue").ifBlank { null },
                            fee = obj.optBoolean("fee", false)
                        )
                    )
                }
                PodcastValue(type, method, suggested, list)
            } catch (_: Exception) {
                null
            }
        }
    }
}
