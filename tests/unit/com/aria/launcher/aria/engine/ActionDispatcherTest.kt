package com.aria.launcher.aria.engine

import com.aria.launcher.aria.engine.rules.RuleAction
import com.aria.launcher.aria.engine.rules.SurfacePriority
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionDispatcherTest {

    private class FakeExecutor(
        override val name: String,
        override val capabilities: Set<ActionCapability>,
        private val canHandle: Boolean,
        private val result: ActionResult = ActionResult.Success(),
    ) : ActionExecutor {
        override suspend fun canExecute(action: RuleAction) = canHandle
        override suspend fun execute(action: RuleAction) = result
    }

    @Test
    fun `dispatches to first capable executor`() = runBlocking {
        val dispatcher = ActionDispatcher(listOf(
            FakeExecutor("first", setOf(ActionCapability.OPEN_APP), canHandle = true,
                result = ActionResult.Success("first")),
            FakeExecutor("second", setOf(ActionCapability.OPEN_APP), canHandle = true,
                result = ActionResult.Success("second")),
        ))
        val result = dispatcher.dispatch(
            RuleAction.OpenApp("com.test.app"),
        )
        assertTrue(result is ActionResult.Success)
        assertTrue((result as ActionResult.Success).data == "first")
    }

    @Test
    fun `returns failure when no executor available`() = runBlocking {
        val dispatcher = ActionDispatcher(listOf(
            FakeExecutor("nope", emptySet(), canHandle = false),
        ))
        val result = dispatcher.dispatch(
            RuleAction.SurfaceApp("com.test.app", SurfacePriority.BOOST),
        )
        assertTrue(result is ActionResult.Failure)
    }

    @Test
    fun `skips incapable executors`() = runBlocking {
        val dispatcher = ActionDispatcher(listOf(
            FakeExecutor("skip", emptySet(), canHandle = false),
            FakeExecutor("handle", setOf(ActionCapability.OPEN_APP), canHandle = true,
                result = ActionResult.Success("handled")),
        ))
        val result = dispatcher.dispatch(
            RuleAction.OpenApp("com.test.app"),
        )
        assertTrue(result is ActionResult.Success)
        assertTrue((result as ActionResult.Success).data == "handled")
    }
}
