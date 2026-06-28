package com.portal.assistant.conversation

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure tests for the markdown stripper that cleans the model's streamed text for the spoken-answer transcript. */
class MarkdownTest {

    @Test fun stripsBold() = assertEquals("Espresso: the foundation.", Markdown.strip("**Espresso:** the foundation."))

    @Test fun stripsItalicAndInlineCode() = assertEquals("use foo now", Markdown.strip("use *foo* `now`"))

    @Test fun stripsNumberedList() = assertEquals("Espresso\nLatte", Markdown.strip("1. Espresso\n2. Latte"))

    @Test fun stripsBullets() = assertEquals("one\ntwo", Markdown.strip("- one\n* two"))

    @Test fun stripsHeading() = assertEquals("Today", Markdown.strip("## Today"))

    @Test fun stripsLinkToItsText() = assertEquals("see the docs", Markdown.strip("see the [docs](https://x.io/a)"))

    @Test fun leavesOrdinaryPunctuationAlone() = assertEquals("It's 1.5 mi, e.g. north.", Markdown.strip("It's 1.5 mi, e.g. north."))

    @Test fun emptyStaysEmpty() = assertEquals("", Markdown.strip(""))

    @Test fun isIdempotent() {
        val once = Markdown.strip("**Hi** there")
        assertEquals(once, Markdown.strip(once))
    }
}
