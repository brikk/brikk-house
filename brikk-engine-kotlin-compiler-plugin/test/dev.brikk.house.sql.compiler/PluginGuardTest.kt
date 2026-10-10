package dev.brikk.house.sql.compiler

import dev.brikk.house.sql.compiler.analysis.rethrowIfCancellation
import java.nio.channels.ClosedByInterruptException
import java.nio.channels.FileLockInterruptionException
import java.util.concurrent.CancellationException
import kotlin.test.Test
import kotlin.test.assertFails
import kotlin.test.assertSame

class PluginGuardTest {
    private open class ProcessCanceledException : RuntimeException()
    private class SessionCancelled : ProcessCanceledException()

    @Test
    fun cancellationIncludingSubclassesAndInterruptedCacheLocksPropagates() {
        for (failure in listOf(
            CancellationException(), InterruptedException(), ClosedByInterruptException(),
            FileLockInterruptionException(), ProcessCanceledException(), SessionCancelled(),
        )) {
            assertSame(failure, assertFails { rethrowIfCancellation(failure) })
        }
    }

    @Test
    fun ordinaryFailuresRemainRecoverable() {
        rethrowIfCancellation(IllegalStateException("synthetic failure"))
    }
}
