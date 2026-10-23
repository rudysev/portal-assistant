package com.portal.assistant.conversation.tools

import org.json.JSONArray
import org.json.JSONObject

/** One installed tool provider as discovered from the manifest (pure data; the Android query lives elsewhere). */
data class DiscoveredProvider(val pkg: String, val authority: String, val declarationsJson: String?, val summary: String? = null)

/**
 * The validated external tools for a session: [declarations] to send to Gemini, [authorityByName] for invoke,
 * and [promptLines] for the system instruction (built in the same [parse] pass so names always match).
 */
data class ExternalTools(
    val declarations: List<JSONObject>,
    val authorityByName: Map<String, String>,
    val promptLines: List<String> = emptyList(),
)

/**
 * Pure policy turning discovered providers + the user allowlist into the external tools the assistant will
 * declare and route (Android-free; org.json is on the test classpath, same as [Timers]). Every place a
 * bad/hostile provider could cause trouble lives here and is unit-tested:
 *  - **allowlist**: a provider not in [enabledPkgs] contributes nothing;
 *  - **namespace**: a name failing [ToolContract.isValidExternalName] (blank, non-reverse-domain, or
 *    `portal.*` squatting a built-in) is dropped;
 *  - **shape**: a declaration missing a non-blank `description` or a `parameters` object is dropped, so
 *    Gemini never sees junk;
 *  - **summary**: a provider missing a non-blank [ToolContract.META_SUMMARY] contributes nothing (no
 *    declarations, no prompt line — same rule as Settings);
 *  - **collisions**: providers are processed in package-name order, the first claim of a name wins, the
 *    rest are dropped — so the result never varies run-to-run.
 *
 * Built-in names are the registry's job (built-ins always win over any external); this handles only
 * external↔external.
 */
object ExternalToolSpec {

    fun parse(
        providers: List<DiscoveredProvider>,
        enabledPkgs: Set<String>,
        onDropped: (String) -> Unit = {},
    ): ExternalTools {
        val collected = Collected(onDropped)
        providers.asSequence()
            .filter { it.pkg in enabledPkgs }
            .sortedBy { it.pkg } // deterministic collision order
            .forEach(collected::addProvider)
        val promptLines = collected.toolsByPkg.keys.sorted().map { pkg ->
            val names = collected.toolsByPkg.getValue(pkg).joinToString(", ")
            "- ${collected.summariesByPkg.getValue(pkg)} Tools: $names."
        }
        return ExternalTools(collected.declarations, collected.authorityByName, promptLines)
    }

    /** One [parse] pass's accepted tools, filled provider by provider in collision order. */
    private class Collected(private val onDropped: (String) -> Unit) {
        val declarations = mutableListOf<JSONObject>()
        val authorityByName = LinkedHashMap<String, String>()
        val toolsByPkg = LinkedHashMap<String, MutableList<String>>()
        val summariesByPkg = LinkedHashMap<String, String>()
        private val winnerPkg = HashMap<String, String>()

        fun addProvider(provider: DiscoveredProvider) {
            val summary = provider.summary?.trim()?.takeIf { it.isNotEmpty() }
            if (summary == null) {
                onDropped("provider ${provider.pkg}: missing ${ToolContract.META_SUMMARY}")
                return
            }
            summariesByPkg.putIfAbsent(provider.pkg, summary)
            val array = runCatching { JSONArray(provider.declarationsJson.orEmpty()) }.getOrNull()
            if (array == null) {
                onDropped("provider ${provider.pkg}: declarations not a JSON array")
                return
            }
            for (i in 0 until array.length()) {
                val decl = array.optJSONObject(i)
                if (decl == null) {
                    onDropped("provider ${provider.pkg}: declaration #$i not an object")
                } else {
                    addDeclaration(provider, decl)
                }
            }
        }

        private fun addDeclaration(provider: DiscoveredProvider, decl: JSONObject) {
            val name = decl.optString("name").trim()
            val problem = problemWith(decl, name, provider.pkg)
            if (problem != null) {
                onDropped(problem)
                return
            }
            decl.put("name", name) // normalize (trimmed) so Gemini's declaration + the invoke map agree
            declarations.add(decl)
            authorityByName[name] = provider.authority
            winnerPkg[name] = provider.pkg
            toolsByPkg.getOrPut(provider.pkg) { mutableListOf() }.add(name)
        }

        /** Why [decl] (named [name], from [pkg]) is dropped, or null when it is accepted. */
        private fun problemWith(decl: JSONObject, name: String, pkg: String): String? = when {
            !ToolContract.isValidExternalName(name) -> "provider $pkg: invalid tool name '$name'"
            decl.optString("description").isBlank() -> "provider $pkg: tool '$name' missing description"
            decl.optJSONObject("parameters") == null -> "provider $pkg: tool '$name' missing parameters"
            name in authorityByName -> "collision: '$name' kept from ${winnerPkg[name]}, dropped from $pkg"
            else -> null
        }
    }

    /** Drop any external declaration whose name collides with a built-in — built-ins always win. */
    fun withoutBuiltinCollisions(external: List<JSONObject>, builtinNames: Set<String>): List<JSONObject> = external.filter { it.optString("name") !in builtinNames }
}
