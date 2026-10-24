package com.portal.assistant.service

import com.portal.assistant.conversation.Trigger

/**
 * A [Trigger] as the string extras of the service's START Intent: [AssistantService.start] puts [encode]'s entries,
 * `onStartCommand` reads them back with [decode]. Pure (plain strings, no `Intent`) so the round trip is JVM-tested;
 * nothing else knows these keys.
 */
object TriggerExtras {
    private const val EXTRA_TRIGGER = "com.portal.assistant.extra.TRIGGER"
    private const val EXTRA_WAKE_ID = "com.portal.assistant.extra.WAKE_ID"
    private const val EXTRA_CHIP_TEXT = "com.portal.assistant.extra.CHIP_TEXT"
    private const val TRIGGER_WAKE_WORD = "wake"
    private const val TRIGGER_MIC_TAP = "tap"
    private const val TRIGGER_SUGGESTION_CHIP = "chip"

    fun encode(trigger: Trigger): Map<String, String> = when (trigger) {
        is Trigger.WakeWord -> mapOf(EXTRA_TRIGGER to TRIGGER_WAKE_WORD, EXTRA_WAKE_ID to trigger.wakeId)
        Trigger.MicTap -> mapOf(EXTRA_TRIGGER to TRIGGER_MIC_TAP)
        is Trigger.SuggestionChip -> mapOf(EXTRA_TRIGGER to TRIGGER_SUGGESTION_CHIP, EXTRA_CHIP_TEXT to trigger.text)
    }

    /** The [Trigger] [encode] wrote, or null when [extra] doesn't carry a whole one — then nothing starts. */
    fun decode(extra: (key: String) -> String?): Trigger? = when (extra(EXTRA_TRIGGER)) {
        TRIGGER_WAKE_WORD -> extra(EXTRA_WAKE_ID)?.let(Trigger::WakeWord)
        TRIGGER_MIC_TAP -> Trigger.MicTap
        TRIGGER_SUGGESTION_CHIP -> extra(EXTRA_CHIP_TEXT)?.takeIf { it.isNotBlank() }?.let(Trigger::SuggestionChip)
        else -> null
    }
}
