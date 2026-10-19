package com.futurehouser.pikachecker

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.NumberFormat
import java.util.Locale

/** USD -> EUR using ECB reference rates (frankfurter.dev), cached for 12 hours. */
class Currency(context: Context) {

    private val prefs = context.getSharedPreferences("fx", Context.MODE_PRIVATE)

    @Volatile
    var usdToEur: Double = prefs.getFloat("usd_eur", DEFAULT_RATE.toFloat()).toDouble()
        private set

    suspend fun refresh() = withContext(Dispatchers.IO) {
        if (System.currentTimeMillis() - prefs.getLong("updated", 0) < 12 * 3600_000L) return@withContext
        runCatching {
            val conn = URL("https://api.frankfurter.dev/v1/latest?from=USD&to=EUR").openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val rate = JSONObject(body).getJSONObject("rates").getDouble("EUR")
            usdToEur = rate
            prefs.edit().putFloat("usd_eur", rate.toFloat()).putLong("updated", System.currentTimeMillis()).apply()
        }
    }

    fun format(usd: Double?): String {
        if (usd == null) return "—"
        return euroFormat.format(usd * usdToEur)
    }

    companion object {
        private const val DEFAULT_RATE = 0.90
        private val euroFormat: NumberFormat = NumberFormat.getCurrencyInstance(Locale("es", "ES")).apply {
            currency = java.util.Currency.getInstance("EUR")
        }
    }
}
