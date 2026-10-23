package com.winlator.star.store.xbox

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Xbox Cloud Gaming sign-in from the store's own account: Xodus' `cloud-token` turns the stored Microsoft
 * account into a streaming-service token (no web sign-in), kept in memory only until shortly before it
 * expires. The request shapes follow Greenlight / xal-node by unknownskl (MIT).
 */
internal object XboxCloudSession {
    class Session(val account: String, val token: String, val offering: String, val baseUri: String, val regions: Int,
                  /** Microsoft account token for a session's /connect step; null when the account could not get one. */
                  val userToken: String?, private val expiresAt: Long) {
        val fresh get() = System.currentTimeMillis() < expiresAt - 5 * 60_000L
    }

    @Volatile private var cached: Session? = null

    fun get(app: Context, account: String): Session {
        cached?.takeIf { it.account == account && it.fresh }?.let { return it }
        val json = Xodus.run(app, listOf("cloud-token"), timeout = 60)
        require(json.getInt("schema") == 1 && json.getString("account") == account)
        val regions = json.getJSONArray("regions")
        val default = (0 until regions.length()).map { regions.getJSONObject(it) }.let { list -> list.firstOrNull { it.optBoolean("isDefault") } ?: list.firstOrNull() }
            ?: error("No Xbox Cloud Gaming region")
        val seconds = json.optLong("durationInSeconds").coerceIn(0, 24 * 3600)
        return Session(account, json.getString("gsToken"), json.getString("offering"), default.getString("baseUri"), regions.length(),
            json.optString("userToken").takeIf { !json.isNull("userToken") && it.isNotEmpty() }, System.currentTimeMillis() + seconds * 1000).also { cached = it }
    }

    fun clear() { cached = null }

    /** Product ids of the cloud titles this account may stream, from the streaming service itself. */
    fun titles(session: Session): Set<String> {
        val body = JSONObject(request(session, "/v2/titles"))
        val results = body.optJSONArray("results") ?: JSONArray()
        return (0 until results.length()).mapNotNull { results.optJSONObject(it)?.optJSONObject("details")?.optString("productId")?.uppercase() }
            .filter { it.matches(Regex("[A-Z0-9]{12}")) }.toSet()
    }

    private fun request(session: Session, path: String): String {
        val connection = URL(session.baseUri + path).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000; connection.readTimeout = 30_000
            connection.setRequestProperty("Authorization", "Bearer ${session.token}")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("x-gssv-client", "XboxComBrowser")
            check(connection.responseCode == 200) { "Xbox Cloud Gaming answered ${connection.responseCode}" }
            val text = connection.inputStream.use { it.readBytes() }.also { require(it.size <= 16_000_000) }
            return String(text, Charsets.UTF_8)
        } finally { connection.disconnect() }
    }
}
