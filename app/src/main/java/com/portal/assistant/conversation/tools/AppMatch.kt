package com.portal.assistant.conversation.tools

import kotlin.math.roundToInt

/** One launchable app: its on-screen [label] and [pkg]. Pure (two strings) so [AppMatch] is unit-tested. */
data class AppEntry(val label: String, val pkg: String)

/**
 * Pure app-name → app resolution (Android-free, unit-tested; same split as [VolumeMath] / [MediaSelection]).
 * [AppLauncher] supplies the installed [AppEntry] list (real PackageManager labels) and acts on the result.
 *
 * The model forwards the user's whole phrase ("open Spotify", "launch the calendar"), so [best] strips a
 * leading verb/article, then scores each app by tiers: exact > prefix > contains > token-overlap >
 * shared-prefix (fuzzy). Short queries are guarded so "ca"/"go" can't false-positive on the fuzzy tiers, a
 * tiny alias map covers unambiguous nicknames ("browser" → Chrome) as a *fallback only* (never overriding a
 * direct match), and ties resolve deterministically. Above [PICK_THRESHOLD] it returns a [Result.pick];
 * below it, the closest labels as [Result.candidates] so the model can ask "did you mean…?".
 */
object AppMatch {

    data class Result(val pick: AppEntry?, val candidates: List<String>, val tier: String, val score: Int)

    /** Leading filler dropped from the query: launch verbs + articles ("open the calendar" → "calendar"). */
    private val FILLER = setOf(
        "open", "launch", "start", "run", "show", "bring", "go", "to", "up", "the", "a", "an", "my", "please",
    )

    /** Unambiguous nicknames only — applied as a fallback. Let the contains/token tiers handle the rest
     *  (no broad aliases like music → Spotify/Tidal/Portal-player or video → Plex). Keyed by compact query. */
    private val ALIASES = mapOf("browser" to "chrome", "web" to "chrome", "internet" to "chrome")

    private const val MIN_QUERY_LEN = 3 // below this, only an exact label match auto-picks
    private const val CONTAINS_MIN = 4 // a mid-substring match (label contains q) needs a longer q than a prefix
    private const val PICK_THRESHOLD = 60 // exact(100)/prefix(80)/contains(60) auto-pick; weaker → candidates
    private const val MAX_CANDIDATES = 3

    fun best(query: String, apps: List<AppEntry>): Result {
        val tokens = tokenize(query).dropWhile { it in FILLER }
        if (tokens.isEmpty() || apps.isEmpty()) return Result(null, emptyList(), "none", 0)
        val raw = match(tokens, apps)
        if (raw.pick != null) return raw
        // Only consult the alias map if the literal name didn't resolve — so a real "Browser" label still wins.
        ALIASES[tokens.joinToString("")]?.let { alias ->
            val aliased = match(listOf(alias), apps)
            if (aliased.pick != null) return aliased
        }
        return raw
    }

    private fun match(nameTokens: List<String>, apps: List<AppEntry>): Result {
        val q = nameTokens.joinToString("")
        if (q.isEmpty()) return Result(null, emptyList(), "none", 0)
        val ranked = apps
            .map { app -> app to scoreTier(q, nameTokens, app) }
            .filter { it.second.second > 0 }
            .sortedWith(
                compareByDescending<Pair<AppEntry, Pair<String, Int>>> { it.second.second }
                    .thenBy { compact(it.first.label).length } // tighter (shorter) label wins at equal score
                    .thenBy { it.first.label }
                    .thenBy { it.first.pkg }, // fully deterministic for duplicate labels
            )
        val top = ranked.firstOrNull() ?: return Result(null, emptyList(), "none", 0)
        val (tier, score) = top.second
        return if (score >= PICK_THRESHOLD) {
            Result(top.first, emptyList(), tier, score)
        } else {
            // distinct: two same-label packages (e.g. two "Settings") shouldn't offer the model a repeated name.
            Result(null, ranked.map { it.first.label }.distinct().take(MAX_CANDIDATES), tier, score)
        }
    }

    /** (tier, score) for one app. [q] is the compact query; both [q] and label sides are length-guarded. */
    private fun scoreTier(q: String, qTokens: List<String>, app: AppEntry): Pair<String, Int> {
        val label = compact(app.label)
        if (q == label) return "exact" to 100
        if (q.length < MIN_QUERY_LEN || label.length < MIN_QUERY_LEN) return "none" to 0
        if (label.startsWith(q) || q.startsWith(label)) return "prefix" to 80
        // q.contains(label): the whole label is inside the query (label is the anchor, e.g. "googlephotos" ⊃
        // "photos") — safe at any length. label.contains(q): q is a mid-substring of the label — only trust it
        // for a longer q, else a 3-char fragment ("ify") would auto-pick "Spotify".
        if (q.contains(label) || (q.length >= CONTAINS_MIN && label.contains(q))) return "contains" to 60
        val j = jaccard(qTokens.toSet(), tokenize(app.label).toSet())
        val tokenScore = if (j > 0.0) (j * 40).roundToInt().coerceIn(1, 40) else 0
        val cp = commonPrefixLen(q, label) // catches misheard names sharing a stem ("spotty" ~ "spotify")
        val fuzzyScore = if (cp >= MIN_QUERY_LEN) cp else 0
        return when {
            tokenScore >= fuzzyScore && tokenScore > 0 -> "token" to tokenScore
            fuzzyScore > 0 -> "fuzzy" to fuzzyScore
            else -> "none" to 0
        }
    }

    private fun tokenize(s: String): List<String> = s.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }

    private fun compact(s: String): String = s.lowercase().filter { it.isLetterOrDigit() }

    private fun commonPrefixLen(a: String, b: String): Int {
        val n = minOf(a.length, b.length)
        var i = 0
        while (i < n && a[i] == b[i]) i++
        return i
    }

    private fun jaccard(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val inter = a.intersect(b).size.toDouble()
        return inter / (a.size + b.size - inter)
    }
}
