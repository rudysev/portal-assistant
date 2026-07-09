package com.portal.assistant.conversation

import com.portal.assistant.conversation.tools.DiscoveredProvider
import com.portal.assistant.conversation.tools.ExternalToolSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure JVM tests for external-tool prompt enrichment. */
class ExternalToolPromptTest {

    private fun kasaProvider() = DiscoveredProvider(
        pkg = "com.portal.kasa",
        authority = "com.portal.kasa.tools",
        declarationsJson = """[{"name":"com.portal.kasa.set_plug","description":"Turn a smart plug on or off by name.","parameters":{"type":"OBJECT","properties":{"plug_name":{"type":"STRING"},"on":{"type":"BOOLEAN"}},"required":["plug_name","on"]}}]""",
        summary = "Control your smart plugs by voice.",
    )

    @Test fun linesIncludeEnabledProviderSummaryAndToolNames() {
        val parsed = ExternalToolSpec.parse(listOf(kasaProvider()), setOf("com.portal.kasa"))
        assertEquals(
            listOf("- Control your smart plugs by voice. Tools: com.portal.kasa.set_plug."),
            ExternalToolPrompt.lines(parsed),
        )
    }

    @Test fun disabledProviderContributesNothing() {
        val parsed = ExternalToolSpec.parse(listOf(kasaProvider()), emptySet())
        assertTrue(ExternalToolPrompt.lines(parsed).isEmpty())
    }

    @Test fun enrichAddsHeaderWhenLinesPresent() {
        val out = ExternalToolPrompt.enrich("BASE", listOf("- Control your smart plugs by voice. Tools: com.portal.kasa.set_plug."))
        assertTrue(out.startsWith("BASE"))
        assertTrue(out.contains(ExternalToolPrompt.HEADER))
        assertTrue(out.contains("com.portal.kasa.set_plug"))
    }

    @Test fun enrichNoOpWhenEmpty() {
        assertEquals("BASE", ExternalToolPrompt.enrich("BASE", emptyList()))
    }

    @Test fun providerWithoutSummaryHasNoLinesOrDeclarations() {
        val noSummary = kasaProvider().copy(summary = null)
        val parsed = ExternalToolSpec.parse(listOf(noSummary), setOf("com.portal.kasa"))
        assertTrue(parsed.declarations.isEmpty())
        assertTrue(ExternalToolPrompt.lines(parsed).isEmpty())
    }

    @Test fun multipleProvidersOrderedByPackageName() {
        val kasa = kasaProvider()
        val calendar = DiscoveredProvider(
            pkg = "com.portal.calendar",
            authority = "com.portal.calendar.tools",
            declarationsJson = """[{"name":"com.portal.calendar.add_event","description":"Add a calendar event.","parameters":{"type":"OBJECT","properties":{}}}]""",
            summary = "Manage your calendar by voice.",
        )
        val parsed = ExternalToolSpec.parse(listOf(kasa, calendar), setOf(kasa.pkg, calendar.pkg))
        assertEquals(
            listOf(
                "- Manage your calendar by voice. Tools: com.portal.calendar.add_event.",
                "- Control your smart plugs by voice. Tools: com.portal.kasa.set_plug.",
            ),
            ExternalToolPrompt.lines(parsed),
        )
    }

    @Test fun promptLinesMatchDeclarationsAfterCrossPackageCollision() {
        val decl = """{"name":"shared.tool","description":"do it","parameters":{"type":"OBJECT","properties":{}}}"""
        val aaa = DiscoveredProvider("dev.aaa.app", "dev.aaa.app.tools", "[$decl]", summary = "AAA tools.")
        val bbb = DiscoveredProvider("dev.bbb.app", "dev.bbb.app.tools", "[$decl]", summary = "BBB tools.")
        val parsed = ExternalToolSpec.parse(listOf(bbb, aaa), setOf("dev.aaa.app", "dev.bbb.app"))
        assertEquals(1, parsed.declarations.size)
        assertEquals(listOf("shared.tool"), parsed.declarations.map { it.optString("name") })
        assertEquals(
            listOf("- AAA tools. Tools: shared.tool."),
            ExternalToolPrompt.lines(parsed),
        )
    }
}
