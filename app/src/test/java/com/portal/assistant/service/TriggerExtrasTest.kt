package com.portal.assistant.service

import com.portal.assistant.conversation.Trigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** JVM tests for [TriggerExtras]: every [Trigger] survives the extras round trip, and a partial one decodes to null. */
class TriggerExtrasTest {

    private fun roundTrip(trigger: Trigger): Trigger? = TriggerExtras.decode(TriggerExtras.encode(trigger)::get)

    /** [trigger]'s extras with the entry holding [value] dropped (the keys are private to [TriggerExtras]). */
    private fun extrasWithout(trigger: Trigger, value: String) =
        TriggerExtras.encode(trigger).filterValues { it != value }

    @Test fun aWakeWordKeepsItsWakeId() {
        assertEquals(Trigger.WakeWord("w-42"), roundTrip(Trigger.WakeWord("w-42")))
    }

    @Test fun aMicTapRoundTrips() {
        assertEquals(Trigger.MicTap, roundTrip(Trigger.MicTap))
    }

    @Test fun aSuggestionChipKeepsItsText() {
        val chip = Trigger.SuggestionChip("What's the weather?")
        assertEquals(chip, roundTrip(chip))
    }

    @Test fun extrasWithNoTriggerKindDecodeToNull() {
        assertNull(TriggerExtras.decode { null })
    }

    @Test fun anUnknownTriggerKindDecodesToNull() {
        val extras = TriggerExtras.encode(Trigger.MicTap).mapValues { "telepathy" }
        assertNull(TriggerExtras.decode(extras::get))
    }

    @Test fun aWakeWordWithoutItsWakeIdDecodesToNull() {
        assertNull(TriggerExtras.decode(extrasWithout(Trigger.WakeWord("w-42"), value = "w-42")::get))
    }

    @Test fun aSuggestionChipWithoutTextDecodesToNull() {
        assertNull(TriggerExtras.decode(extrasWithout(Trigger.SuggestionChip("hello"), value = "hello")::get))
    }

    @Test fun aSuggestionChipWithBlankTextDecodesToNull() {
        val extras = TriggerExtras.encode(Trigger.SuggestionChip("hello")).mapValues { (_, value) ->
            if (value == "hello") "   " else value
        }
        assertNull(TriggerExtras.decode(extras::get))
    }
}
