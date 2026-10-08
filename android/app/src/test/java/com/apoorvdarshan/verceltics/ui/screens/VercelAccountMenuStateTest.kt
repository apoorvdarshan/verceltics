package com.apoorvdarshan.verceltics.ui.screens

import com.apoorvdarshan.verceltics.ui.VercelAccountUi
import com.apoorvdarshan.verceltics.ui.VercelConnectionStatus
import com.apoorvdarshan.verceltics.ui.vercel.VercelAccountRemoval
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Saved-state guards for the account menu: pending removals and projects of other accounts. */
class VercelAccountMenuStateTest {
    private val apoorv = VercelAccountUi("Apoorv", null, id = "user_a")
    private val studio = VercelAccountUi("Studio", null, id = "user_b")

    @Test
    fun pendingRemovalsSurviveAsStrings() {
        assertEquals(VercelAccountRemoval.All, VercelAccountRemoval.fromSaved(VercelAccountRemoval.All.saved))
        val one = VercelAccountRemoval.One("user:with:colons")
        assertEquals(one, VercelAccountRemoval.fromSaved(one.saved))
        assertNull(VercelAccountRemoval.fromSaved(null))
        assertNull(VercelAccountRemoval.fromSaved("account:"))
        assertNull(VercelAccountRemoval.fromSaved("something-else"))
    }

    @Test
    fun aRestoredRemovalIsDroppedOnceItsAccountIsGone() {
        val connected = VercelConnectionStatus.CONNECTED
        val removeStudio = VercelAccountRemoval.One(studio.id)

        assertTrue(isPendingRemovalStillValid(removeStudio, connected, listOf(apoorv, studio)))
        assertFalse(isPendingRemovalStillValid(removeStudio, connected, listOf(apoorv)))
        assertTrue(isPendingRemovalStillValid(VercelAccountRemoval.All, connected, listOf(apoorv)))
        assertFalse(isPendingRemovalStillValid(VercelAccountRemoval.All, VercelConnectionStatus.DISCONNECTED, emptyList()))
        assertTrue(
            "Nothing is decided while saved accounts are still restoring.",
            isPendingRemovalStillValid(removeStudio, VercelConnectionStatus.RESTORING, emptyList()),
        )
    }

    @Test
    fun aProjectClosesWhenAnotherAccountBecomesActive() {
        assertTrue(shouldCloseProjectForAccount("prj", projectAccountId = apoorv.id, activeAccountId = studio.id))
        assertFalse(shouldCloseProjectForAccount("prj", projectAccountId = apoorv.id, activeAccountId = apoorv.id))
        assertFalse("No project is open.", shouldCloseProjectForAccount(null, apoorv.id, studio.id))
        assertFalse("Older saved state did not record an account.", shouldCloseProjectForAccount("prj", null, studio.id))
        assertFalse("Restoring has no active account yet.", shouldCloseProjectForAccount("prj", apoorv.id, null))
    }
}
