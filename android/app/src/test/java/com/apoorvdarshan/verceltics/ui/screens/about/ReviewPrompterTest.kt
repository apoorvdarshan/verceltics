package com.apoorvdarshan.verceltics.ui.screens.about

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReviewPrompterTest {
    @Test
    fun `first projects load prompts once after three seconds and persists the flag`() = runTest {
        val store = MemoryStore()
        val prompter = prompter(store)
        val shown = mutableListOf<String>()

        assertTrue(prompter.scheduleAutomaticPrompt(resolveHost = { "activity" }, showPrompt = { shown += it }))
        advanceTimeBy(2_999)
        runCurrent()
        assertEquals(emptyList<String>(), shown)
        assertFalse(store.hasPrompted)

        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf("activity"), shown)
        assertTrue(store.hasPrompted)
    }

    @Test
    fun `later loads and new prompters never prompt again`() = runTest {
        val store = MemoryStore()
        val shown = mutableListOf<String>()
        val prompter = prompter(store)
        prompter.scheduleAutomaticPrompt(resolveHost = { "first" }, showPrompt = { shown += it })
        advanceUntilIdle()

        assertFalse(prompter.scheduleAutomaticPrompt(resolveHost = { "second" }, showPrompt = { shown += it }))
        assertFalse(prompter(store).scheduleAutomaticPrompt(resolveHost = { "third" }, showPrompt = { shown += it }))
        advanceUntilIdle()

        assertEquals(listOf("first"), shown)
        assertEquals(1, store.writes)
    }

    @Test
    fun `repeated loads during the delay coalesce into one prompt`() = runTest {
        val store = MemoryStore()
        val prompter = prompter(store)
        val shown = mutableListOf<String>()

        assertTrue(prompter.scheduleAutomaticPrompt(resolveHost = { "a" }, showPrompt = { shown += it }))
        advanceTimeBy(1_000)
        assertFalse(prompter.scheduleAutomaticPrompt(resolveHost = { "b" }, showPrompt = { shown += it }))
        advanceUntilIdle()

        assertEquals(listOf("a"), shown)
    }

    @Test
    fun `a host that is gone after the delay skips the prompt without spending it`() = runTest {
        val store = MemoryStore()
        val prompter = prompter(store)
        val shown = mutableListOf<String>()

        prompter.scheduleAutomaticPrompt<String>(resolveHost = { null }, showPrompt = { shown += it })
        advanceUntilIdle()
        assertFalse(store.hasPrompted)

        assertTrue(prompter.scheduleAutomaticPrompt(resolveHost = { "returned" }, showPrompt = { shown += it }))
        advanceUntilIdle()
        assertEquals(listOf("returned"), shown)
        assertTrue(store.hasPrompted)
    }

    @Test
    fun `a previously persisted prompt is never scheduled`() = runTest {
        val store = MemoryStore(prompted = true)
        val shown = mutableListOf<String>()

        assertFalse(prompter(store).scheduleAutomaticPrompt(resolveHost = { "activity" }, showPrompt = { shown += it }))
        advanceUntilIdle()

        assertEquals(emptyList<String>(), shown)
    }

    private fun TestScope.prompter(store: ReviewPromptStore) = ReviewPrompter(
        store = store,
        launcher = { error("The automatic path uses the injected prompt in tests") },
        scope = this,
    )

    private class MemoryStore(prompted: Boolean = false) : ReviewPromptStore {
        var writes = 0
        override var hasPrompted: Boolean = prompted
            private set

        override fun markPrompted() {
            writes += 1
            hasPrompted = true
        }
    }
}
