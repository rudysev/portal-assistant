package com.portal.assistant.conversation.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tool contract strings are a **published API**: provider apps hard-code these exact literals in their
 * manifests and IPC, and the assistant reads/calls them. Changing a value silently breaks every provider,
 * so this test locks the wire format. If you intend to change the contract, change it here deliberately.
 */
class ToolContractTest {

    @Test fun wireFormatIsStable() {
        assertEquals("com.portal.assistant.tools", ToolContract.META_DECLARATIONS)
        assertEquals("invoke", ToolContract.METHOD_INVOKE)
        assertEquals("com.portal.assistant.tools.extra.ARGS", ToolContract.EXTRA_ARGS_JSON)
        assertEquals("com.portal.assistant.tools.extra.RESULT", ToolContract.EXTRA_RESULT_JSON)
        assertEquals(1, ToolContract.VERSION)
    }

    @Test fun rejectsPortalNamespaceAndBlanks() {
        assertFalse(ToolContract.isValidExternalName("portal.set_volume")) // can't squat a built-in
        assertFalse(ToolContract.isValidExternalName(""))
        assertFalse(ToolContract.isValidExternalName("   "))
        assertFalse(ToolContract.isValidExternalName("setlight")) // not reverse-domain (no dot)
    }

    @Test fun acceptsReverseDomainNames() {
        assertTrue(ToolContract.isValidExternalName("com.example.demo.set_light"))
        assertTrue(ToolContract.isValidExternalName("com.example.nest.open_camera"))
    }

    @Test fun trimsWhitespaceAndGuardsPortalCaseInsensitively() {
        assertTrue(ToolContract.isValidExternalName(" com.example.demo.set_light ")) // surrounding space tolerated
        assertFalse(ToolContract.isValidExternalName(" portal.set_volume")) // leading space can't sneak past
        assertFalse(ToolContract.isValidExternalName("Portal.set_volume")) // case-insensitive guard
    }
}
