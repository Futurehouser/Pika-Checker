package com.futurehouser.pikachecker

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

data class Candidate(val title: String, val set: String, val url: String)

data class ProductPrice(
    val title: String,
    val set: String,
    /** PriceCharting product URL (Spanish site), used for the "Check in Pricecharting" link. */
    val url: String,
    val psa10Usd: Double?,
    val ungradedUsd: Double?,
)

/**
 * PriceCharting sits behind Cloudflare, which blocks plain HTTP clients, so pages are loaded in a real
 * WebView and read with JavaScript. If Cloudflare asks for a human check, [needsVerification] turns true
 * and the UI brings this WebView to the front so the user can complete it; the clearance cookie persists.
 *
 * Results are cached on disk for a day so repeated scans don't hit the site (and Cloudflare) again.
 */
class PriceCharting(context: Context) {

    private val mutex = Mutex()
    private var pageStarted: CompletableDeferred<Unit>? = null
    @Volatile private var cancelRequested = false
    private val cache = context.getSharedPreferences("pricecharting_cache", Context.MODE_PRIVATE)

    private val _needsVerification = MutableStateFlow(false)
    val needsVerification: StateFlow<Boolean> = _needsVerification

    /** URL of the page being loaded, for the "open in browser" fallback. */
    var currentUrl: String = BASE
        private set

    // Created with the Activity context: Turnstile needs a real window to run in.
    @SuppressLint("SetJavaScriptEnabled")
    val webView: WebView = WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.userAgentString = browserUserAgent(WebSettings.getDefaultUserAgent(context))
        // Don't announce the app package in X-Requested-With; Cloudflare treats it as an embedded bot.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
            WebSettingsCompat.setRequestedWithHeaderOriginAllowList(settings, emptySet())
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                if (url.startsWith("http")) pageStarted?.complete(Unit)
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = false
        }
    }

    /** Gives up on the page waiting for verification; the pending lookup fails and can be retried. */
    fun cancelVerification() {
        cancelRequested = true
    }

    /**
     * Searches "<name> <number>" and returns the products whose title has "#<number>".
     * Returns null when PriceCharting could not be reached (as opposed to an empty result).
     */
    suspend fun search(card: CardDetection): List<Candidate>? {
        cached("s:${card.key}")?.let { json ->
            val arr = JSONArray(json)
            return (0 until arr.length()).map { arr.getJSONObject(it).toCandidate() }
        }
        val result = mutex.withLock { searchOnline(card) } ?: return null
        put("s:${card.key}", JSONArray(result.map { it.toJson() }).toString())
        return result
    }

    private suspend fun searchOnline(card: CardDetection): List<Candidate>? {
        val q = "${card.name} ${card.searchNumber}"
        val url = "$BASE/search-products?type=prices&q=" + Uri.encode(q)
        val page = load(url) ?: return null
        return when (page.optString("state")) {
            "product" -> listOf(Candidate(page.optString("title"), page.optString("set"), page.optString("url")))
            "search" -> {
                val arr = page.optJSONArray("results") ?: JSONArray()
                val all = (0 until arr.length()).map { arr.getJSONObject(it).toCandidate() }
                    .filter { it.set.contains("pokemon", ignoreCase = true) || it.url.contains("/pokemon") }
                val numberTag = Regex("#0*${Regex.escape(card.searchNumber.trimStart('0'))}(?![0-9A-Za-z])", RegexOption.IGNORE_CASE)
                val exact = all.filter { numberTag.containsMatchIn(it.title) }
                exact.ifEmpty { all.take(3) }
            }
            else -> emptyList()
        }
    }

    suspend fun price(candidate: Candidate): ProductPrice? {
        val key = "p:" + toEnglish(candidate.url)
        cached(key)?.let { return JSONObject(it).toPrice() }
        val price = mutex.withLock { priceOnline(candidate) } ?: return null
        put(key, price.toJson().toString())
        return price
    }

    private suspend fun priceOnline(candidate: Candidate): ProductPrice? {
        val page = load(toEnglish(candidate.url)) ?: return null
        if (page.optString("state") != "product") return null
        return ProductPrice(
            title = page.optString("title").ifBlank { candidate.title },
            set = page.optString("set").ifBlank { candidate.set },
            url = toSpanish(page.optString("url").ifBlank { candidate.url }),
            psa10Usd = parseUsd(page.optString("psa10")),
            ungradedUsd = parseUsd(page.optString("ungraded")),
        )
    }

    private suspend fun load(url: String): JSONObject? = withContext(Dispatchers.Main) {
        cancelRequested = false
        currentUrl = url
        val started = CompletableDeferred<Unit>()
        pageStarted = started
        webView.loadUrl(url)
        if (withTimeoutOrNull(15_000) { started.await() } == null) return@withContext null

        val startedAt = System.currentTimeMillis()
        var challengeSince = 0L
        var emptyPolls = 0
        try {
            while (!cancelRequested) {
                // Poll gently while the user is solving the check, and never reload the page under them.
                delay(if (_needsVerification.value) 1200 else 400)
                val json = evaluate(EXTRACT_JS) ?: continue
                when (json.optString("state")) {
                    "challenge" -> {
                        if (challengeSince == 0L) challengeSince = System.currentTimeMillis()
                        // Cloudflare often passes on its own in a few seconds; otherwise ask the user.
                        if (System.currentTimeMillis() - challengeSince > 4_000) _needsVerification.value = true
                    }
                    "product", "search" -> {
                        CookieManager.getInstance().flush() // keep the clearance cookie for next launch
                        return@withContext json
                    }
                    "none" -> if (++emptyPolls > 3) return@withContext json
                }
                // Plain page loads time out; a pending human check waits until solved or cancelled.
                if (challengeSince == 0L && System.currentTimeMillis() - startedAt > 25_000) return@withContext null
            }
            null
        } finally {
            _needsVerification.value = false
        }
    }

    private suspend fun evaluate(js: String): JSONObject? {
        val result = CompletableDeferred<String?>()
        webView.evaluateJavascript(js) { result.complete(it) }
        val raw = result.await() ?: return null
        if (raw == "null") return null
        return runCatching { JSONObject(JSONArray("[$raw]").getString(0)) }.getOrNull()
    }

    private fun cached(key: String): String? {
        if (System.currentTimeMillis() - cache.getLong("$key@t", 0) > CACHE_MS) return null
        return cache.getString(key, null)
    }

    private fun put(key: String, value: String) {
        cache.edit().putString(key, value).putLong("$key@t", System.currentTimeMillis()).apply()
    }

    companion object {
        const val BASE = "https://www.pricecharting.com"
        private const val CACHE_MS = 24 * 3600_000L

        fun toSpanish(url: String): String =
            if (url.startsWith("$BASE/es/")) url else url.replace("$BASE/", "$BASE/es/")

        fun toEnglish(url: String): String = url.replace("$BASE/es/", "$BASE/")

        /** "$1,234.56" -> 1234.56; "-" or blank -> null. */
        fun parseUsd(text: String?): Double? {
            if (text.isNullOrBlank()) return null
            val cleaned = text.replace(Regex("[^0-9.,]"), "").replace(",", "")
            return cleaned.toDoubleOrNull()?.takeIf { it > 0 }
        }

        /**
         * The default WebView user agent carries a "wv" marker and a legacy "Version/4.0" token that
         * Cloudflare treats as an embedded bot, so its checkbox never passes. Present the Chrome engine
         * the WebView actually is.
         */
        fun browserUserAgent(default: String): String = default
            .replace("; wv)", ")")
            .replace(Regex(""" Version/\d+(\.\d+)*"""), "")
            .replace(Regex("""\s*Build/[^;)]+"""), "")

        private fun JSONObject.toCandidate() = Candidate(optString("title"), optString("set"), optString("url"))
        private fun Candidate.toJson() = JSONObject().put("title", title).put("set", set).put("url", url)
        private fun ProductPrice.toJson() = JSONObject().put("title", title).put("set", set).put("url", url)
            .put("psa10", psa10Usd ?: JSONObject.NULL).put("ungraded", ungradedUsd ?: JSONObject.NULL)
        private fun JSONObject.toPrice() = ProductPrice(
            optString("title"), optString("set"), optString("url"),
            if (isNull("psa10")) null else optDouble("psa10"),
            if (isNull("ungraded")) null else optDouble("ungraded"),
        )

        private val EXTRACT_JS = """
            (function(){
              var t = document.title || '';
              if (/just a moment|un momento|attention required|security verification/i.test(t) ||
                  document.querySelector('#challenge-form, #challenge-stage, .cf-turnstile')) {
                return JSON.stringify({state:'challenge'});
              }
              if (location.href.indexOf('http') !== 0) return JSON.stringify({state:'loading'});
              function txt(e){ return e ? e.textContent.replace(/\s+/g,' ').trim() : ''; }
              var h1 = document.querySelector('#product_name');
              if (h1) {
                var setLink = h1.querySelector('a');
                var title = '';
                h1.childNodes.forEach(function(n){ if (n.nodeType === 3) title += n.textContent; });
                title = title.replace(/\s+/g,' ').trim() || txt(h1);
                function price(id){ return txt(document.querySelector('#' + id + ' .price')); }
                var psa = price('manual_only_price');
                // Fallback: find the "PSA 10" column in the price table.
                if (!psa) {
                  var table = document.querySelector('#price_data');
                  if (table) {
                    var heads = Array.prototype.slice.call(table.querySelectorAll('thead th, thead td'));
                    var idx = heads.findIndex(function(h){ return /PSA\s*10/i.test(h.textContent); });
                    var row = table.querySelector('tbody tr');
                    if (idx >= 0 && row && row.children[idx]) psa = txt(row.children[idx].querySelector('.price') || row.children[idx]);
                  }
                }
                return JSON.stringify({state:'product', title:title, set:txt(setLink), url:location.href,
                                       psa10:psa, ungraded:price('used_price')});
              }
              var table = document.querySelector('#games_table');
              if (table) {
                var rows = Array.prototype.slice.call(table.querySelectorAll('tbody tr'));
                var results = rows.map(function(r){
                  var a = r.querySelector('td.title a');
                  if (!a) return null;
                  return {title: txt(a), url: a.href, set: txt(r.querySelector('td.console'))};
                }).filter(Boolean);
                return JSON.stringify({state:'search', results:results});
              }
              if (document.readyState === 'complete') return JSON.stringify({state:'none'});
              return JSON.stringify({state:'loading'});
            })();
        """.trimIndent()
    }
}
