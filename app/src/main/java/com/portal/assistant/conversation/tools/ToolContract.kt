package com.portal.assistant.conversation.tools

/**
 * The public, stable contract a **tool provider** (any app that wants to add capabilities the Portal
 * assistant can call) implements. The sibling of portal-wake's `WakeContract`: where wake plugins add
 * "hey X", tool providers add function-calling tools (turn on a light, open a camera, …).
 *
 * The assistant *discovers* providers at runtime: any installed app advertises tools by declaring an
 * **exported [android.content.ContentProvider]** carrying a [META_DECLARATIONS] meta-data string — a JSON
 * array of LLM tool/function declarations (the same OpenAPI subset the built-in tools use).
 *
 * Example — an app adding a "set light" tool:
 * ```xml
 * <provider
 *     android:name=".KasaToolProvider"
 *     android:authorities="com.example.kasa.tools"
 *     android:exported="true">
 *     <!-- value may be an @string/ resource; PackageManager resolves it to the JSON text. -->
 *     <meta-data android:name="com.portal.assistant.tools" android:value="@string/portal_tools" />
 *     <!-- required one-sentence summary for the Settings row (see [META_SUMMARY]). -->
 *     <meta-data android:name="com.portal.assistant.tools.summary" android:value="@string/portal_tools_summary" />
 * </provider>
 * ```
 * Manifest values must be string literals (XML cannot reference these constants); use these constants in
 * the *code* that reads/calls the provider. A provider does **not** need to depend on this library — the
 * literal strings are the contract; this object is the typed convenience for first-party apps.
 *
 * **Invocation** is a synchronous [android.content.ContentResolver.call] on the provider's authority:
 * method [METHOD_INVOKE], `arg` = the tool name, extras carry [EXTRA_ARGS_JSON] (the call args as a JSON
 * string). The provider returns a [android.os.Bundle] with [EXTRA_RESULT_JSON] (a JSON string the model
 * speaks back). Keep `call()` fast — the assistant times the call out (a few seconds) and a slow tool
 * blocks the conversation; defer heavy work to a worker and return an ack.
 *
 * **Tool names are reverse-domain and must not start with `portal.`** ([isValidExternalName]) — that
 * namespace belongs to the assistant's built-ins, and a provider can never override one (built-ins win any
 * collision). Use a name that includes your package, e.g. `com.example.kasa.set_light`.
 *
 * Whether a discovered provider is actually declared/invoked is gated by a user allowlist in the
 * assistant (off by default) — discovery alone grants nothing.
 */
object ToolContract {

    /** Contract revision — bump when the wire format changes (e.g. when async/streaming is added). */
    const val VERSION = 1

    /** Meta-data key on the provider component: a JSON array of tool declarations (see class doc). */
    const val META_DECLARATIONS = "com.portal.assistant.tools"

    /**
     * Required meta-data key on the provider component: a single human sentence summarizing what this app
     * adds, shown in the assistant's Settings → External tools row (e.g. "Control your smart plugs by
     * voice."). Keep it a short sentence — required field.
     */
    const val META_SUMMARY = "com.portal.assistant.tools.summary"

    /** [android.content.ContentResolver.call] method that invokes a tool (`arg` = tool name). */
    const val METHOD_INVOKE = "invoke"

    /** Bundle key (String): the call args as a JSON object string, passed in the invoke extras. */
    const val EXTRA_ARGS_JSON = "com.portal.assistant.tools.extra.ARGS"

    /** Bundle key (String): the result as a JSON object string, returned from the invoke call. */
    const val EXTRA_RESULT_JSON = "com.portal.assistant.tools.extra.RESULT"

    /**
     * The namespace guard: an external tool name must be non-blank, reverse-domain (contain a `.`), and
     * **must not** squat the built-in `portal.` namespace. Surrounding whitespace is ignored, and the
     * `portal.` check is case-insensitive so `" Portal.set_volume"` can't sneak in a confusing near-squatter.
     */
    fun isValidExternalName(name: String): Boolean {
        val n = name.trim()
        return n.isNotBlank() && !n.startsWith("portal.", ignoreCase = true) && n.contains('.')
    }
}
