package com.futurehouser.pikachecker

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import org.json.JSONArray
import org.json.JSONObject

data class HistoryEntry(
    /** Same identity as [CardDetection.key]: name + collector number. */
    val key: String,
    val title: String,
    val set: String,
    val printedNumber: String,
    val url: String,
    val psa10Usd: Double?,
    val ungradedUsd: Double?,
    val time: Long,
)

/** Detected cards, newest first, one entry per card. Persisted across launches. */
class ScanHistory(context: Context) {

    private val prefs = context.getSharedPreferences("history", Context.MODE_PRIVATE)
    val entries = mutableStateListOf<HistoryEntry>()

    init {
        runCatching {
            val arr = JSONArray(prefs.getString("entries", "[]"))
            for (i in 0 until arr.length()) entries += arr.getJSONObject(i).toEntry()
        }
    }

    /** Adds the card at the top; if it was already logged, the old entry is replaced and moves up. */
    fun record(entry: HistoryEntry) {
        val existing = entries.indexOfFirst { it.key == entry.key || it.url == entry.url }
        if (existing == 0 && entries[0].copy(time = entry.time) == entry) return
        if (existing >= 0) entries.removeAt(existing)
        entries.add(0, entry)
        while (entries.size > MAX) entries.removeAt(entries.lastIndex)
        save()
    }

    fun clear() {
        entries.clear()
        save()
    }

    private fun save() {
        prefs.edit().putString("entries", JSONArray(entries.map { it.toJson() }).toString()).apply()
    }

    companion object {
        private const val MAX = 100

        private fun HistoryEntry.toJson() = JSONObject()
            .put("key", key).put("title", title).put("set", set).put("number", printedNumber).put("url", url)
            .put("psa10", psa10Usd ?: JSONObject.NULL).put("ungraded", ungradedUsd ?: JSONObject.NULL)
            .put("time", time)

        private fun JSONObject.toEntry() = HistoryEntry(
            key = optString("key"),
            title = optString("title"),
            set = optString("set"),
            printedNumber = optString("number"),
            url = optString("url"),
            psa10Usd = if (isNull("psa10")) null else optDouble("psa10"),
            ungradedUsd = if (isNull("ungraded")) null else optDouble("ungraded"),
            time = optLong("time"),
        )
    }
}
