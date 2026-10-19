package com.futurehouser.pikachecker

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.mlkit.vision.text.Text
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

sealed interface Lookup {
    data object Searching : Lookup
    data object NotFound : Lookup
    /** PriceCharting could not be reached or the human check was cancelled. */
    data object Failed : Lookup
    data class Found(
        val candidates: List<Candidate>,
        val index: Int,
        val price: ProductPrice?,
        val loadingPrice: Boolean,
    ) : Lookup
}

/** Turns OCR frames into stable card detections and drives the PriceCharting lookups. */
class ScanController(
    private val scope: CoroutineScope,
    private val priceCharting: PriceCharting,
) {
    var detection by mutableStateOf<CardDetection?>(null)
        private set
    var lastSeen by mutableStateOf(0L)
        private set
    val lookups = mutableStateMapOf<String, Lookup>()

    private var pendingKey: String? = null
    private var pendingCount = 0
    private var busy = false

    fun onText(text: Text?) {
        // Don't queue new lookups while the user is completing the Cloudflare check.
        if (priceCharting.needsVerification.value) return
        val card = text?.let(CardRecognizer::recognize) ?: return
        // Require the same reading on consecutive frames before trusting it.
        if (card.key == pendingKey) pendingCount++ else {
            pendingKey = card.key
            pendingCount = 1
        }
        if (pendingCount < 2 && lookups[card.key] == null) return

        detection = card
        lastSeen = System.currentTimeMillis()
        if (lookups[card.key] == null && !busy) startLookup(card)
    }

    private fun startLookup(card: CardDetection) {
        busy = true
        lookups[card.key] = Lookup.Searching
        scope.launch {
            try {
                val candidates = runCatching { priceCharting.search(card) }.getOrNull()
                if (candidates == null) {
                    lookups[card.key] = Lookup.Failed
                    return@launch
                }
                if (candidates.isEmpty()) {
                    lookups[card.key] = Lookup.NotFound
                    return@launch
                }
                lookups[card.key] = Lookup.Found(candidates, 0, null, loadingPrice = true)
                val price = runCatching { priceCharting.price(candidates[0]) }.getOrNull()
                lookups[card.key] = Lookup.Found(candidates, 0, price, loadingPrice = false)
            } finally {
                busy = false
            }
        }
    }

    /** Cycle to the next PriceCharting match when the same name/number exists in several sets. */
    fun nextMatch(key: String) {
        val current = lookups[key] as? Lookup.Found ?: return
        if (current.candidates.size < 2 || current.loadingPrice) return
        val next = (current.index + 1) % current.candidates.size
        lookups[key] = current.copy(index = next, price = null, loadingPrice = true)
        scope.launch {
            val price = runCatching { priceCharting.price(current.candidates[next]) }.getOrNull()
            val now = lookups[key] as? Lookup.Found ?: return@launch
            if (now.index == next) lookups[key] = now.copy(price = price, loadingPrice = false)
        }
    }

    fun retry(key: String) {
        lookups.remove(key)
    }
}
