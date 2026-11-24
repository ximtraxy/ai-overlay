package dev.aioverlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SafeNameTest {
    @Test fun plainName() = assertEquals("ritaglio_123.png", safeName("ritaglio_123.png"))
    @Test fun blocksTraversal() { assertNull(safeName("../shared_prefs/ui.xml")); assertNull(safeName("..")); assertNull(safeName(".png")) }
    @Test fun blocksNull() = assertNull(safeName(null))
}
