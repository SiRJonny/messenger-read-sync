package com.csabi.readsync

import android.content.Context
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject

/**
 * Minimal HTTP endpoint. Only POST /read is accepted, and only with the
 * shared bearer token. Everything else gets 403/404.
 */
class ReadServer(
    bindHost: String?,
    port: Int,
    private val context: Context,
    private val onRead: (thread: String) -> Int,
) : NanoHTTPD(bindHost, port) {

    override fun serve(session: IHTTPSession): Response {
        val token = context.getSharedPreferences("readsync", Context.MODE_PRIVATE)
            .getString("token", "") ?: ""

        if (session.method != Method.POST || session.uri != "/read") {
            return notFound()
        }
        val auth = session.headers["authorization"] ?: ""
        val supplied = auth.removePrefix("Bearer ").removePrefix("bearer ").trim()
        if (token.isEmpty() || supplied.isEmpty() || !constantTimeEquals(supplied, token)) {
            return json(Response.Status.FORBIDDEN, JSONObject().put("error", "forbidden"))
        }

        val body = readBody(session)
        val thread = try {
            // Prefer the Base64 header: exact UTF-8 regardless of how
            // NanoHTTPD decoded the body bytes.
            session.headers["x-thread-name"]?.let {
                String(java.util.Base64.getDecoder().decode(it), Charsets.UTF_8)
            } ?: JSONObject(body).optString("thread", "")
        } catch (_: Exception) {
            ""
        }
        if (thread.isEmpty()) {
            return json(Response.Status.BAD_REQUEST, JSONObject().put("error", "missing thread"))
        }

        val dismissed = try {
            onRead(thread)
        } catch (e: Exception) {
            android.util.Log.e("ReadSync", "dismiss failed", e)
            -1
        }
        return json(Response.Status.OK, JSONObject().put("dismissed", dismissed))
    }

    private fun readBody(session: IHTTPSession): String {
        val map = HashMap<String, String>()
        session.parseBody(map)
        return map["postData"] ?: ""
    }

    private fun json(status: Response.Status, obj: JSONObject): Response =
        newFixedLengthResponse(status, "application/json", obj.toString())

    private fun notFound(): Response =
        json(Response.Status.NOT_FOUND, JSONObject().put("error", "not found"))

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
        return diff == 0
    }
}
