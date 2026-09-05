package dev.brikk.house.sql.verify

import dev.brikk.house.chdb.ChdbConfig
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ClickhouseVerifierTest {
    @Test
    fun unavailableNativeLibraryReturnsWarningInsteadOfThrowing() {
        // The regular focused test invocation deliberately has no libchdb. The verifier is
        // opt-in until brikk-chdb packages platform archives, rather than starting a CLI/server.
        // Constructed directly: the chDB verifier is a fidelity oracle, no longer registered in
        // SqlVerifiers.forEngine (which now returns the advisory ShardingSphere verifier).
        if (System.getProperty("brikk.chdb.integrationLibrary") == null) {
            val result = ClickhouseVerifier.create().verify("SELECT 1")
            assertFalse(result.verified)
            assertFalse(result.accepted)
            assertNotNull(result.warning)
        }
    }

    @Test
    fun nativeGrammarSmokeTestWhenLibraryIsConfigured() {
        // The same property used by brikk-chdb's integration test. Run with a v26.5.0 libchdb
        // and --enable-native-access=ALL-UNNAMED; otherwise this lightweight test is a no-op.
        val library = System.getProperty("brikk.chdb.integrationLibrary") ?: return
        ClickhouseVerifier.create(ChdbConfig(libraryPath = Path.of(library))).use { verifier ->
            assertTrue(verifier.verify("SELECT number FROM numbers(3)").verified)
            assertTrue(verifier.verify("SELECT number FROM numbers(3)").accepted)
            assertFalse(verifier.verify("SELECT FROM WHERE").accepted)
            assertTrue(verifier.verifyExpression("arrayMap(x -> x + 1, [1, 2])").accepted)
            assertFalse(verifier.verifyExpression("1 +").accepted)
        }
    }
}
