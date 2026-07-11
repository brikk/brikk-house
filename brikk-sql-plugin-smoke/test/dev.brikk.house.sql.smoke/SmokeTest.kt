package dev.brikk.house.sql.smoke

import kotlin.test.Test
import kotlin.test.assertTrue

class SmokeTest {
    @Test
    fun `plugin transformed the call in a real toolchain compilation`() {
        // The stub body throws, so this only passes if the plugin's IR rewrite ran
        // during this module's actual `./kotlin build` compilation.
        val rendered = events()
        assertTrue(rendered.startsWith("BRIKK["), "expected marker constant, got: $rendered")
        assertTrue("FROM rumble_import.events" in rendered)
    }
}
