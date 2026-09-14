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
import kotlin.test.assertNull

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
        assertTrue(result.text.contains("HUMAN_RESOLVED\nmission_id=m1\nresponse=Yes, approved.\nCall mission_update to acknowledge this response before the next handoff."))
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
    @Test
    fun `agent cannot bypass human handoff by updating or completing`() = runTest {
        val manager = MissionManager()
        val tools = MissionMcpTools("test", manager, 50L)
        manager.createMission("m1", "Goal")
        getTool(tools, "mission_handoff").call(createArgs(mapOf("mission_id" to "m1", "reason" to "Approve?")))
        for (status in listOf("RUNNING", "COMPLETED", "WAITING_FOR_HUMAN")) {
            val result = getTool(tools, "mission_update").call(createArgs(mapOf("mission_id" to "m1", "step" to "Bypass", "status" to status)))
            assertTrue(result.isError, status)
            assertEquals(MissionStatus.WAITING_FOR_HUMAN, manager.state.value.activeMission!!.status)
        }
        manager.resolveHandoff("Approved")
        val resumed = getTool(tools, "mission_update").call(createArgs(mapOf("mission_id" to "m1", "step" to "Resume")))
        assertFalse(resumed.isError)
        assertNull(manager.state.value.pendingHandoff)
    }

    @Test
    fun `terminal MCP updates release pending calls without transient running state`() = runTest {
        for (status in listOf("FAILED", "CANCELLED")) {
            val manager = MissionManager()
            val tools = MissionMcpTools("test", manager, 5000L)
            manager.createMission("m1", "Goal")
            val pending = async { getTool(tools, "mission_handoff").call(createArgs(mapOf("mission_id" to "m1", "reason" to "R"))) }
            delay(1)
            val result = getTool(tools, "mission_update").call(createArgs(mapOf("mission_id" to "m1", "step" to "Stopped", "status" to status)))
            assertFalse(result.isError)
            assertTrue(pending.await().isError)
            assertEquals(MissionStatus.valueOf(status), manager.state.value.activeMission!!.status)
            assertNull(manager.state.value.pendingHandoff)
        }
    }

    @Test
    fun `response survives timed out polls and is replayed until acknowledgement`() = runTest {
        val manager = MissionManager()
        val tools = MissionMcpTools("test", manager, 50L)
        manager.createMission("m1", "Goal")
        val handoff = getTool(tools, "mission_handoff")
        val args = createArgs(mapOf("mission_id" to "m1", "reason" to "R"))
        assertTrue(handoff.call(args).text.startsWith("PENDING"))
        manager.resolveHandoff("Approved")
        repeat(2) {
            val result = handoff.call(args)
            assertFalse(result.isError)
            assertTrue(result.text.contains("response=Approved"))
        }
        getTool(tools, "mission_update").call(createArgs(mapOf("mission_id" to "m1", "step" to "Acknowledged")))
        assertTrue(handoff.call(args).text.startsWith("PENDING"))
        manager.dispose()
    }

}
