package ai.rever.boss.plugin.dynamic.missioncontrol

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class MissionManagerTest {

    // --- CREATION ---

    @Test
    fun `creates valid mission`() {
        val manager = MissionManager()
        manager.createMission("m1", "Goal 1")
        val state = manager.state.value
        assertEquals("m1", state.activeMission!!.missionId)
        assertEquals(MissionStatus.RUNNING, state.activeMission.status)
    }

    @Test
    fun `rejects empty missionId`() {
        val manager = MissionManager()
        assertFailsWith<IllegalArgumentException> { manager.createMission("", "Goal 1") }
    }

    @Test
    fun `rejects empty goal`() {
        val manager = MissionManager()
        assertFailsWith<IllegalArgumentException> { manager.createMission("m1", "") }
    }

    @Test
    fun `rejects duplicate active mission`() {
        val manager = MissionManager()
        manager.createMission("m1", "Goal 1")
        assertFailsWith<IllegalStateException> { manager.createMission("m2", "Goal 2") }
    }

    // --- STATE UPDATES ---

    @Test
    fun `updates current step`() {
        val manager = MissionManager()
        manager.createMission("m1", "Goal")
        manager.updateMission("m1", "Step 2")
        assertEquals("Step 2", manager.state.value.activeMission!!.step)
    }

    @Test
    fun `rejects unknown mission update`() {
        val manager = MissionManager()
        manager.createMission("m1", "Goal")
        assertFailsWith<IllegalStateException> { manager.updateMission("m2", "Step") }
    }

    @Test
    fun `rejects updates after completion`() {
        val manager = MissionManager()
        manager.createMission("m1", "Goal")
        manager.completeMission()
        assertFailsWith<IllegalStateException> { manager.updateMission("m1", "Step") }
    }

    @Test
    fun `rejects updates after cancellation`() {
        val manager = MissionManager()
        manager.createMission("m1", "Goal")
        manager.cancelMission()
        assertFailsWith<IllegalStateException> { manager.updateMission("m1", "Step") }
    }

    // --- STATE TRANSITIONS ---

    @Test
    fun `RUNNING to WAITING_FOR_HUMAN via handoff`() = runTest {
        val manager = MissionManager()
        manager.createMission("m1", "Goal")
        val job = launch { manager.requestHandoff("m1", "reason") }
        delay(10)
        assertEquals(MissionStatus.WAITING_FOR_HUMAN, manager.state.value.activeMission!!.status)
        job.cancel()
    }

    @Test
    fun `WAITING_FOR_HUMAN to RUNNING via resolve`() = runTest {
        val manager = MissionManager()
        manager.createMission("m1", "Goal")
        val job = launch { manager.requestHandoff("m1", "reason") }
        delay(10)
        manager.resolveHandoff("OK")
        assertEquals(MissionStatus.RUNNING, manager.state.value.activeMission!!.status)
        job.cancel()
    }

    @Test
    fun `RUNNING to COMPLETED`() {
        val manager = MissionManager()
        manager.createMission("m1", "Goal")
        manager.completeMission()
        assertEquals(MissionStatus.COMPLETED, manager.state.value.activeMission!!.status)
    }

    @Test
    fun `RUNNING to FAILED`() {
        val manager = MissionManager()
        manager.createMission("m1", "Goal")
        manager.failMission()
        assertEquals(MissionStatus.FAILED, manager.state.value.activeMission!!.status)
    }

    @Test
    fun `RUNNING to CANCELLED`() {
        val manager = MissionManager()
        manager.createMission("m1", "Goal")
        manager.cancelMission()
        assertEquals(MissionStatus.CANCELLED, manager.state.value.activeMission!!.status)
    }

    @Test
    fun `invalid transitions rejected`() = runTest {
        val manager = MissionManager()
        manager.createMission("m1", "Goal")
        manager.completeMission()
        // COMPLETED -> RUNNING (reject)
        assertFailsWith<IllegalStateException> { manager.updateMission("m1", "step", MissionStatus.RUNNING) }
        // COMPLETED -> FAILED (reject)
        assertFailsWith<IllegalStateException> { manager.failMission() }
    }

    // --- HANDOFF ---

    @Test
    fun `request creates WAITING state and pending handoff exists`() = runTest {
        val manager = MissionManager()
        manager.createMission("m1", "Goal")
        val job = launch { manager.requestHandoff("m1", "reason") }
        delay(10)
        val state = manager.state.value
        assertEquals(MissionStatus.WAITING_FOR_HUMAN, state.activeMission!!.status)
        assertNotNull(state.pendingHandoff)
        job.cancel()
    }

    @Test
    fun `resolving handoff returns expected response`() = runTest {
        val manager = MissionManager()
        manager.createMission("m1", "Goal")
        var result: String? = null
        val job = launch { result = manager.requestHandoff("m1", "reason") }
        delay(10)
        manager.resolveHandoff("Yes")
        job.join()
        assertEquals("Yes", result)
    }

    @Test
    fun `repeated resolution is safe`() = runTest {
        val manager = MissionManager()
        manager.createMission("m1", "Goal")
        val job = launch { manager.requestHandoff("m1", "reason") }
        delay(10)
        manager.resolveHandoff("Resp 1")
        manager.resolveHandoff("Resp 2") // Ignored safely
        assertEquals(MissionStatus.RUNNING, manager.state.value.activeMission!!.status)
        job.join()
    }

    @Test
    fun `duplicate handoff is rejected`() = runTest {
        val manager = MissionManager()
        manager.createMission("m1", "Goal")
        val job = launch { manager.requestHandoff("m1", "reason 1") }
        delay(10)
        assertFailsWith<IllegalStateException> {
            manager.requestHandoff("m1", "reason 2")
        }
        job.cancel()
    }

    // --- CANCELLATION ---

    @Test
    fun `cancellation releases pending handoff and blocks mutations`() = runTest {
        val manager = MissionManager()
        manager.createMission("m1", "Goal")
        val deferred = async { runCatching { manager.requestHandoff("m1", "reason") } }
        delay(10)
        manager.cancelMission()
        val handoffResult = deferred.await()
        
        assertEquals(MissionStatus.CANCELLED, manager.state.value.activeMission!!.status)
        assertTrue(handoffResult.isFailure)
        assertTrue(handoffResult.exceptionOrNull() is IllegalStateException)
        
        assertFailsWith<IllegalStateException> { manager.updateMission("m1", "Step") }
    }

    // --- CLEANUP ---

    @Test
    fun `dispose cancels pending handoff and clears active mission`() = runTest {
        val manager = MissionManager()
        manager.createMission("m1", "Goal")
        val deferred = async { runCatching { manager.requestHandoff("m1", "reason") } }
        delay(10)
        manager.dispose()
        val handoffResult = deferred.await()
        
        assertNull(manager.state.value.activeMission)
        assertNull(manager.state.value.pendingHandoff)
        assertTrue(handoffResult.isFailure)
        
        // Idempotent
        manager.dispose()
    }

    // --- CONCURRENCY ---

    @Test
    fun `simultaneous handoff requests with same reason return same deferred`() = runTest {
        val manager = MissionManager()
        manager.createMission("m1", "Goal")
        val job1 = launch { manager.requestHandoff("m1", "reason") }
        val job2 = launch { manager.requestHandoff("m1", "reason") }
        delay(10)
        manager.resolveHandoff("OK")
        job1.join()
        job2.join()
    }

    @Test
    fun `cancellation and resolution race`() = runTest {
        val manager = MissionManager()
        manager.createMission("m1", "Goal")
        val job = launch { runCatching { manager.requestHandoff("m1", "reason") } }
        delay(10)
        val j1 = launch { manager.resolveHandoff("OK") }
        val j2 = launch { manager.cancelMission() }
        j1.join()
        j2.join()
        job.join()
        val status = manager.state.value.activeMission!!.status
        assertTrue(status == MissionStatus.RUNNING || status == MissionStatus.CANCELLED)
    }
    @Test
    fun `new missions can follow every terminal outcome`() {
        for (status in listOf(MissionStatus.COMPLETED, MissionStatus.FAILED, MissionStatus.CANCELLED)) {
            val manager = MissionManager()
            manager.createMission("old", "Old")
            manager.updateMission("old", "Finished", status)
            manager.createMission("new", "New")
            assertEquals("new", manager.state.value.activeMission!!.missionId)
            manager.cancelMission("old") // Stale UI callback must not cancel the replacement.
            assertEquals(MissionStatus.RUNNING, manager.state.value.activeMission!!.status)
        }
    }

    @Test
    fun `disposed provider cannot resurrect an invisible mission`() {
        val manager = MissionManager()
        manager.dispose()
        assertFailsWith<IllegalStateException> { manager.createMission("m1", "Goal") }
    }

    @Test
    fun `stale approval cannot resolve another handoff`() = runTest {
        val manager = MissionManager()
        manager.createMission("m1", "Goal")
        val first = launch { manager.requestHandoff("m1", "First") }
        delay(1)
        val old = manager.state.value.pendingHandoff!!
        manager.resolveHandoff("OK", old)
        first.join()
        manager.updateMission("m1", "Next step")
        val second = launch { manager.requestHandoff("m1", "Second") }
        delay(1)
        manager.resolveHandoff("Stale approval", old)
        assertEquals(MissionStatus.WAITING_FOR_HUMAN, manager.state.value.activeMission!!.status)
        assertTrue(!manager.state.value.pendingHandoff!!.responseDeferred.isCompleted)
        manager.dispose()
        second.join()
    }

    @Test
    fun `cancel before approved response delivery prevents continuation`() = runTest {
        val manager = MissionManager()
        manager.createMission("m1", "Goal")
        val pending = async { runCatching { manager.requestHandoff("m1", "R") } }
        delay(1)
        manager.resolveHandoff("OK")
        manager.cancelMission()
        assertTrue(pending.await().isFailure)
    }

    @Test
    fun `parallel requests and resolution share one logical result`() = runTest {
        repeat(50) {
            val manager = MissionManager()
            manager.createMission("m1", "Goal")
            val requests = List(8) { async(kotlinx.coroutines.Dispatchers.Default) { manager.requestHandoff("m1", "R") } }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                while (manager.state.value.pendingHandoff == null) kotlinx.coroutines.yield()
                manager.resolveHandoff("OK")
            }
            requests.forEach { assertEquals("OK", it.await()) }
            manager.updateMission("m1", "Acknowledged")
            assertNull(manager.state.value.pendingHandoff)
        }
    }

}
