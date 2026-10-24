package com.eagertolisten.app.data.v4v

import java.net.URI
import java.net.URLDecoder

data class NwcConnection(
    val walletPubkey: String,
    val relayUrl: String,
    val secret: String,
    val lud16: String? = null
) {
    companion object {
        fun parse(uriString: String): Result<NwcConnection> {
            val trimmed = uriString.trim()
            if (!trimmed.startsWith("nostr+walletconnect://") && !trimmed.startsWith("nostrwalletconnect://")) {
                return Result.failure(IllegalArgumentException("Invalid NWC URI scheme (expected nostr+walletconnect://)"))
            }
            return try {
                val normalized = trimmed
                    .replaceFirst("nostr+walletconnect://", "https://")
                    .replaceFirst("nostrwalletconnect://", "https://")
                val uri = URI(normalized)
                val pubkey = uri.host ?: return Result.failure(IllegalArgumentException("Missing wallet pubkey in URI host"))
                val queryParams = uri.rawQuery?.split("&")?.associate {
                    val parts = it.split("=", limit = 2)
                    val k = URLDecoder.decode(parts[0], "UTF-8")
                    val v = if (parts.size > 1) URLDecoder.decode(parts[1], "UTF-8") else ""
                    k to v
                } ?: emptyMap()

                val relay = queryParams["relay"] ?: return Result.failure(IllegalArgumentException("Missing relay URL parameter"))
                val secret = queryParams["secret"] ?: return Result.failure(IllegalArgumentException("Missing secret parameter"))
                val lud16 = queryParams["lud16"]

                Result.success(
                    NwcConnection(
                        walletPubkey = pubkey.lowercase(),
                        relayUrl = relay,
                        secret = secret.lowercase(),
                        lud16 = lud16
                    )
                )
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }
}
