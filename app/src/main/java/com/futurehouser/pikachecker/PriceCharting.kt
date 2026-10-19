package com.futurehouser.pikachecker

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
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
 * and the UI shows this WebView so the user can tap the checkbox once; the clearance cookie persists.
 */
class PriceCharting(context: Context) {

    private val mutex = Mutex()
    private var pageStarted: CompletableDeferred<Unit>? = null

    private val _needsVerification = MutableStateFlow(false)
    val needsVerification: StateFlow<Boolean> = _needsVerification

    @SuppressLint("SetJavaScriptEnabled")
    val webView: WebView = WebView(context.applicationContext).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.loadsImagesAutomatically = false
        settings.blockNetworkImage = true
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                if (url.startsWith("http")) pageStarted?.complete(Unit)
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = false
        }
    }

    /** Searches "<name> <number>" and returns the products whose title has "#<number>". */
    suspend fun search(card: CardDetection): List<Candidate> = mutex.withLock {
        val q = "${card.name} ${card.searchNumber}"
        val url = "$BASE/search-products?type=prices&q=" + Uri.encode(q)
        val page = load(url) ?: return emptyList()
        when (page.optString("state")) {
            "product" -> listOf(Candidate(page.optString("title"), page.optString("set"), page.optString("url")))
            "search" -> {
                val arr = page.optJSONArray("results") ?: JSONArray()
                val all = (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    Candidate(o.optString("title"), o.optString("set"), o.optString("url"))
                }.filter { it.set.contains("pokemon", ignoreCase = true) || it.url.contains("/pokemon") }
                val numberTag = Regex("#0*${Regex.escape(card.searchNumber.trimStart('0'))}(?![0-9A-Za-z])", RegexOption.IGNORE_CASE)
                val exact = all.filter { numberTag.containsMatchIn(it.title) }
                exact.ifEmpty { all.take(3) }
            }
            else -> emptyList()
        }
    }

    suspend fun price(candidate: Candidate): ProductPrice? = mutex.withLock {
        val page = load(toEnglish(candidate.url)) ?: return null
        if (page.optString("state") != "product") return null
        ProductPrice(
            title = page.optString("title").ifBlank { candidate.title },
            set = page.optString("set").ifBlank { candidate.set },
            url = toSpanish(page.optString("url").ifBlank { candidate.url }),
            psa10Usd = parseUsd(page.optString("psa10")),
            ungradedUsd = parseUsd(page.optString("ungraded")),
        )
    }

    private suspend fun load(url: String): JSONObject? = withContext(Dispatchers.Main) {
        val started = CompletableDeferred<Unit>()
        pageStarted = started
        webView.loadUrl(url)
        withTimeoutOrNull(15_000) { started.await() } ?: return@withContext null

        var challengeSince = 0L
        var emptyPolls = 0
        val deadline = System.currentTimeMillis() + 120_000
        try {
            while (System.currentTimeMillis() < deadline) {
                delay(400)
                val json = evaluate(EXTRACT_JS) ?: continue
                when (json.optString("state")) {
                    "challenge" -> {
                        if (challengeSince == 0L) challengeSince = System.currentTimeMillis()
                        // Cloudflare usually passes on its own in a few seconds; otherwise ask the user.
                        if (System.currentTimeMillis() - challengeSince > 5_000) _needsVerification.value = true
                    }
                    "product", "search" -> return@withContext json
                    "none" -> if (++emptyPolls > 3) return@withContext json
                }
                if (challengeSince == 0L && System.currentTimeMillis() > deadline - 100_000) return@withContext null
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

    companion object {
        const val BASE = "https://www.pricecharting.com"

        fun toSpanish(url: String): String =
            if (url.startsWith("$BASE/es/")) url else url.replace("$BASE/", "$BASE/es/")

        fun toEnglish(url: String): String = url.replace("$BASE/es/", "$BASE/")

        /** "$1,234.56" -> 1234.56; "-" or blank -> null. */
        fun parseUsd(text: String?): Double? {
            if (text.isNullOrBlank()) return null
            val cleaned = text.replace(Regex("[^0-9.,]"), "").replace(",", "")
            return cleaned.toDoubleOrNull()?.takeIf { it > 0 }
        }

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
