package ai.rever.boss.plugin.dynamic.missioncontrol

import ai.rever.boss.plugin.api.McpToolArgs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class MissionMcpToolsTest {

    private fun createArgs(map: Map<String, String>): McpToolArgs {
        return McpToolArgs(map)
    }

    private fun getTool(tools: MissionMcpTools, name: String) =
        tools.tools().first { it.name == name }.handler

    // --- mission_create ---

    @Test
    fun `mission_create valid request`() = runTest {
        val manager = MissionManager()
        val tools = MissionMcpTools("test", manager, 50L)
        val handler = getTool(tools, "mission_create")

        val result = handler.call(createArgs(mapOf("mission_id" to "m1", "goal" to "G1")))
        assertFalse(result.isError)
        assertTrue(result.text.contains("m1"))
        
        val state = manager.state.value
        assertEquals("m1", state.activeMission!!.missionId)
    }

    @Test
    fun `mission_create blank mission ID`() = runTest {
        val manager = MissionManager()
        val tools = MissionMcpTools("test", manager, 50L)
        val handler = getTool(tools, "mission_create")

        val result = handler.call(createArgs(mapOf("mission_id" to "", "goal" to "G1")))
        assertTrue(result.isError)
        assertTrue(result.text.contains("must not be blank", ignoreCase = true) || result.text.contains("Missing"))
    }

    @Test
    fun `mission_create duplicate active mission`() = runTest {
        val manager = MissionManager()
        val tools = MissionMcpTools("test", manager, 50L)
        val handler = getTool(tools, "mission_create")

        handler.call(createArgs(mapOf("mission_id" to "m1", "goal" to "G1")))
        val result = handler.call(createArgs(mapOf("mission_id" to "m2", "goal" to "G2")))
        
        assertTrue(result.isError)
        assertTrue(result.text.contains("already active"))
    }

    // --- mission_update ---

    @Test
    fun `mission_update valid update`() = runTest {
        val manager = MissionManager()
        val tools = MissionMcpTools("test", manager, 50L)
        manager.createMission("m1", "Goal")

        val handler = getTool(tools, "mission_update")
        val result = handler.call(createArgs(mapOf("mission_id" to "m1", "step" to "Step 2", "status" to "RUNNING")))
        
        assertFalse(result.isError)
        assertEquals("Step 2", manager.state.value.activeMission!!.step)
    }

    @Test
    fun `mission_update unknown mission`() = runTest {
        val manager = MissionManager()
        val tools = MissionMcpTools("test", manager, 50L)
        manager.createMission("m1", "Goal")

        val handler = getTool(tools, "mission_update")
        val result = handler.call(createArgs(mapOf("mission_id" to "m2", "step" to "Step 2")))
        
        assertTrue(result.isError)
        assertTrue(result.text.contains("Unknown mission"))
    }

    @Test
    fun `mission_update terminal mission`() = runTest {
        val manager = MissionManager()
        val tools = MissionMcpTools("test", manager, 50L)
        manager.createMission("m1", "Goal")
        manager.completeMission()

        val handler = getTool(tools, "mission_update")
        val result = handler.call(createArgs(mapOf("mission_id" to "m1", "step" to "Step 2")))
        
        assertTrue(result.isError)
        assertTrue(result.text.contains("Cannot update terminal mission"))
    }

    // --- mission_handoff ---

    @Test
    fun `mission_handoff valid handoff enters WAITING and timeouts as PENDING`() = runTest {
        val manager = MissionManager()
        val tools = MissionMcpTools("test", manager, 50L) // Fast 50ms timeout
        manager.createMission("m1", "Goal")

        val handler = getTool(tools, "mission_handoff")
        
        // This will suspend for 50ms, then return PENDING
        val result = handler.call(createArgs(mapOf("mission_id" to "m1", "reason" to "Approve?")))
        
        assertFalse(result.isError)
        assertTrue(result.text.contains("PENDING"))
        
        assertEquals(MissionStatus.WAITING_FOR_HUMAN, manager.state.value.activeMission!!.status)
        assertEquals("Approve?", manager.state.value.activeMission!!.handoffReason)
    }

    @Test
    fun `mission_handoff repeated call reuses logical handoff`() = runTest {
        val manager = MissionManager()
        val tools = MissionMcpTools("test", manager, 50L)
        manager.createMission("m1", "Goal")
        val handler = getTool(tools, "mission_handoff")
        
        // First timeout
        handler.call(createArgs(mapOf("mission_id" to "m1", "reason" to "R1")))
        val handoff1 = manager.state.value.pendingHandoff
        
        // Second timeout
        handler.call(createArgs(mapOf("mission_id" to "m1", "reason" to "R1")))
        val handoff2 = manager.state.value.pendingHandoff
        
        assertTrue(handoff1 === handoff2)
    }

    @Test
    fun `mission_handoff mismatched reason rejected`() = runTest {
        val manager = MissionManager()
        val tools = MissionMcpTools("test", manager, 50L)
        manager.createMission("m1", "Goal")
        val handler = getTool(tools, "mission_handoff")
        
        handler.call(createArgs(mapOf("mission_id" to "m1", "reason" to "R1")))
        
        // Call with R2 while waiting for R1
        val result = handler.call(createArgs(mapOf("mission_id" to "m1", "reason" to "R2")))
        assertTrue(result.isError)
        assertTrue(result.text.contains("Concurrent handoff collision"))
    }

    @Test
    fun `mission_handoff resolved returns human response`() = runTest {
        val manager = MissionManager()
        val tools = MissionMcpTools("test", manager, 5000L) // Long timeout
        manager.createMission("m1", "Goal")
        val handler = getTool(tools, "mission_handoff")
        
        val deferredCall = async {
            handler.call(createArgs(mapOf("mission_id" to "m1", "reason" to "R1")))
        }
        
        delay(10)
        manager.resolveHandoff("Yes, approved.")
        
        val result = deferredCall.await()
        assertFalse(result.isError)
        assertTrue(result.text.contains("HUMAN_RESOLVED\nmission_id=m1\nresponse=Yes, approved.\nContinue the mission."))
    }

    // --- cancellation ---

    @Test
    fun `cancelled mission rejects update and handoff`() = runTest {
        val manager = MissionManager()
        val tools = MissionMcpTools("test", manager, 50L)
        manager.createMission("m1", "Goal")
        manager.cancelMission()

        val updateResult = getTool(tools, "mission_update")
            .call(createArgs(mapOf("mission_id" to "m1", "step" to "S")))
        assertTrue(updateResult.isError)
        
        val handoffResult = getTool(tools, "mission_handoff")
            .call(createArgs(mapOf("mission_id" to "m1", "reason" to "R")))
        assertTrue(handoffResult.isError)
    }

    @Test
    fun `pending handoff terminates on cancellation`() = runTest {
        val manager = MissionManager()
        val tools = MissionMcpTools("test", manager, 5000L)
        manager.createMission("m1", "Goal")
        val handler = getTool(tools, "mission_handoff")
        
        val deferredCall = async {
            handler.call(createArgs(mapOf("mission_id" to "m1", "reason" to "R1")))
        }
        
        delay(10)
        manager.cancelMission()
        
        val result = deferredCall.await()
        assertTrue(result.isError)
        assertTrue(result.text.contains("cancelled", ignoreCase = true))
    }

    @Test
    fun `genuine coroutine cancellation propagates`() = runTest {
        val manager = MissionManager()
        val tools = MissionMcpTools("test", manager, 5000L)
        manager.createMission("m1", "Goal")
        val handler = getTool(tools, "mission_handoff")
        
        val deferredCall = async {
            handler.call(createArgs(mapOf("mission_id" to "m1", "reason" to "R1")))
        }
        
        delay(10)
        // Simulate client disconnect / host cancel
        deferredCall.cancelAndJoin()
        
        assertTrue(deferredCall.isCancelled)
    }
}
