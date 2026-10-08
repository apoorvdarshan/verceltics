package com.apoorvdarshan.verceltics.ui.vercel

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Process-wide latch behind `VercelWorkspaceScreen(onProjectsFirstLoaded = …)`: the first
 * successful project load in a process is reported once, however often the screen recomposes,
 * leaves composition or is recreated.
 */
object VercelProjectsFirstLoad {
    private val reported = AtomicBoolean(false)

    /** True exactly once per process. */
    fun consume(): Boolean = reported.compareAndSet(false, true)

    /** Re-arms the latch; for tests only. */
    internal fun resetForTesting() = reported.set(false)
}
