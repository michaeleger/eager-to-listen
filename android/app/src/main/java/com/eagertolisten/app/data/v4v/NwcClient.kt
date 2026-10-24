package com.eagertolisten.app.data.v4v

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

data class NwcPaymentResult(
    val preimage: String? = null,
    val paymentHash: String? = null,
    val feesPaidMsat: Long? = null,
    val errorMessage: String? = null
)

class NwcClient(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
) {

    companion object {
        private const val TAG = "NwcClient"
        const val KIND_NWC_REQUEST = 23194
        const val KIND_NWC_RESPONSE = 23195
    }

    /**
     * Builds the JSON payload for a pay_keysend NIP-47 request.
     */
    fun buildKeysendPayload(
        recipientPubkey: String,
        amountMsats: Long,
        tlvHex: String? = null,
        preimage: String? = null
    ): JSONObject {
        val root = JSONObject()
        root.put("method", "pay_keysend")
        val params = JSONObject()
        params.put("amount", amountMsats)
        params.put("pubkey", recipientPubkey)
        if (!preimage.isNullOrBlank()) {
            params.put("preimage", preimage)
        }
        if (!tlvHex.isNullOrBlank()) {
            val tlvArray = JSONArray()
            val rec = JSONObject()
            rec.put("type", BoostagramPayload.TLV_RECORD_TYPE_PODCASTING_20)
            rec.put("value", tlvHex)
            tlvArray.put(rec)
            params.put("tlv_records", tlvArray)
        }
        root.put("params", params)
        return root
    }

    /**
     * Builds the JSON payload for a pay_invoice NIP-47 request.
     */
    fun buildInvoicePayload(invoice: String, amountMsats: Long? = null): JSONObject {
        val root = JSONObject()
        root.put("method", "pay_invoice")
        val params = JSONObject()
        params.put("invoice", invoice)
        if (amountMsats != null && amountMsats > 0) {
            params.put("amount", amountMsats)
        }
        root.put("params", params)
        return root
    }

    /**
     * Executes a NIP-47 request over WebSocket to the configured relay.
     */
    suspend fun sendRequest(
        connection: NwcConnection,
        requestPayload: JSONObject,
        timeoutMs: Long = 30_000L
    ): Result<NwcPaymentResult> = withContext(Dispatchers.IO) {
        val result = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<Result<NwcPaymentResult>> { cont ->
                val wsUrl = connection.relayUrl
                val request = Request.Builder().url(wsUrl).build()
                val subscriptionId = "nwc_" + UUID.randomUUID().toString().take(8)

                var webSocket: WebSocket? = null

                val listener = object : WebSocketListener() {
                    override fun onOpen(ws: WebSocket, response: Response) {
                        Log.d(TAG, "Connected to NWC relay: $wsUrl")
                        // Send subscription filter for responses from the wallet
                        val reqMessage = JSONArray().apply {
                            put("REQ")
                            put(subscriptionId)
                            put(JSONObject().apply {
                                put("kinds", JSONArray().put(KIND_NWC_RESPONSE))
                                put("authors", JSONArray().put(connection.walletPubkey))
                            })
                        }
                        ws.send(reqMessage.toString())

                        // Publish NIP-47 Event kind 23194
                        val eventJson = JSONObject().apply {
                            put("id", UUID.randomUUID().toString().replace("-", ""))
                            put("pubkey", connection.walletPubkey)
                            put("created_at", System.currentTimeMillis() / 1000L)
                            put("kind", KIND_NWC_REQUEST)
                            put("tags", JSONArray().put(JSONArray().put("p").put(connection.walletPubkey)))
                            put("content", requestPayload.toString())
                        }

                        val eventMessage = JSONArray().apply {
                            put("EVENT")
                            put(eventJson)
                        }
                        ws.send(eventMessage.toString())
                    }

                    override fun onMessage(ws: WebSocket, text: String) {
                        try {
                            val arr = JSONArray(text)
                            val type = arr.optString(0)
                            if (type == "EVENT") {
                                val evObj = arr.optJSONObject(2)
                                val content = evObj?.optString("content") ?: ""
                                if (content.isNotBlank()) {
                                    val respJson = JSONObject(content)
                                    val err = respJson.optJSONObject("error")
                                    if (err != null) {
                                        val errMsg = err.optString("message", "Payment error")
                                        if (cont.isActive) cont.resume(Result.failure(Exception(errMsg)))
                                    } else {
                                        val res = respJson.optJSONObject("result")
                                        val preimage = res?.optString("preimage")
                                        val paymentHash = res?.optString("payment_hash")
                                        val fees = res?.optLong("fees_paid")
                                        if (cont.isActive) {
                                            cont.resume(Result.success(NwcPaymentResult(preimage, paymentHash, fees)))
                                        }
                                    }
                                    ws.close(1000, "Done")
                                }
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Error parsing relay message: ${e.message}")
                        }
                    }

                    override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                        Log.e(TAG, "NWC WebSocket failure: ${t.message}", t)
                        if (cont.isActive) {
                            cont.resume(Result.failure(t))
                        }
                    }

                    override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                        Log.d(TAG, "NWC WebSocket closed ($code): $reason")
                    }
                }

                webSocket = httpClient.newWebSocket(request, listener)
                cont.invokeOnCancellation {
                    try {
                        webSocket?.close(1000, "Cancelled")
                    } catch (_: Exception) {}
                }
            }
        }

        result ?: Result.failure(Exception("NWC payment timed out after ${timeoutMs / 1000}s"))
    }
}
