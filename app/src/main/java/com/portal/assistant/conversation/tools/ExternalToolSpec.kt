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
        val declarations = mutableListOf<JSONObject>()
        val authorityByName = LinkedHashMap<String, String>()
        val winnerPkg = HashMap<String, String>()
        val toolsByPkg = LinkedHashMap<String, MutableList<String>>()
        val summariesByPkg = LinkedHashMap<String, String>()

        providers.asSequence()
            .filter { it.pkg in enabledPkgs }
            .sortedBy { it.pkg } // deterministic collision order
            .forEach { provider ->
                val summary = provider.summary?.trim()?.takeIf { it.isNotEmpty() }
                if (summary == null) {
                    onDropped("provider ${provider.pkg}: missing ${ToolContract.META_SUMMARY}")
                    return@forEach
                }
                summariesByPkg.putIfAbsent(provider.pkg, summary)
                val array = runCatching { JSONArray(provider.declarationsJson ?: "") }.getOrNull()
                if (array == null) {
                    onDropped("provider ${provider.pkg}: declarations not a JSON array")
                    return@forEach
                }
                for (i in 0 until array.length()) {
                    val decl = array.optJSONObject(i)
                    if (decl == null) {
                        onDropped("provider ${provider.pkg}: declaration #$i not an object")
                        continue
                    }
                    val name = decl.optString("name").trim()
                    if (!ToolContract.isValidExternalName(name)) {
                        onDropped("provider ${provider.pkg}: invalid tool name '$name'")
                        continue
                    }
                    decl.put("name", name) // normalize (trimmed) so Gemini's declaration + the invoke map agree
                    if (decl.optString("description").isBlank()) {
                        onDropped("provider ${provider.pkg}: tool '$name' missing description")
                        continue
                    }
                    if (decl.optJSONObject("parameters") == null) {
                        onDropped("provider ${provider.pkg}: tool '$name' missing parameters")
                        continue
                    }
                    if (name in authorityByName) {
                        onDropped("collision: '$name' kept from ${winnerPkg[name]}, dropped from ${provider.pkg}")
                        continue
                    }
                    declarations.add(decl)
                    authorityByName[name] = provider.authority
                    winnerPkg[name] = provider.pkg
                    toolsByPkg.getOrPut(provider.pkg) { mutableListOf() }.add(name)
                }
            }
        val promptLines = toolsByPkg.keys.sorted().map { pkg ->
            val names = toolsByPkg.getValue(pkg).joinToString(", ")
            "- ${summariesByPkg.getValue(pkg)} Tools: $names."
        }
        return ExternalTools(declarations, authorityByName, promptLines)
    }

    /** Drop any external declaration whose name collides with a built-in — built-ins always win. */
    fun withoutBuiltinCollisions(external: List<JSONObject>, builtinNames: Set<String>): List<JSONObject> = external.filter { it.optString("name") !in builtinNames }
}
