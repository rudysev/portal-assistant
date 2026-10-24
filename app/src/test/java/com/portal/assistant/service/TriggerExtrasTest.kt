package com.portal.assistant.service

import com.portal.assistant.conversation.Trigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** JVM tests for [TriggerExtras]: every [Trigger] survives the extras round trip, and a partial one decodes to null. */
class TriggerExtrasTest {

    private fun roundTrip(trigger: Trigger): Trigger? = TriggerExtras.decode(TriggerExtras.encode(trigger)::get)

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
        val extras = TriggerExtras.encode(Trigger.WakeWord("w-42"))
        assertNull(TriggerExtras.decode { key -> extras[key]?.takeIf { it != "w-42" } })
    }

    @Test fun aSuggestionChipWithoutTextDecodesToNull() {
        val extras = TriggerExtras.encode(Trigger.SuggestionChip("hello"))
        assertNull(TriggerExtras.decode { key -> extras[key]?.takeIf { it != "hello" } })
    }

    @Test fun aSuggestionChipWithBlankTextDecodesToNull() {
        val extras = TriggerExtras.encode(Trigger.SuggestionChip("hello")).mapValues { (_, value) ->
            if (value == "hello") "   " else value
        }
        assertNull(TriggerExtras.decode(extras::get))
    }
}
