package ai.rever.boss.plugin.dynamic.missioncontrol

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class MissionManager {
    data class State(
        val activeMission: Mission? = null,
        val pendingHandoff: HandoffRequest? = null
    )

    // Serialize transitions and their deferred completions. StateFlow.update lambdas
    // can retry, so they must not capture side effects or select a deferred to complete.
    private val lock = Any()
    private var disposed = false
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun createMission(missionId: String, goal: String) = synchronized(lock) {
        check(!disposed) { "Mission Control is disposed" }
        require(missionId.isNotBlank()) { "missionId must not be blank" }
        require(goal.isNotBlank()) { "goal must not be blank" }
        val current = _state.value.activeMission
        check(current == null || current.status.isTerminal()) {
            "A mission is already active: ${current?.missionId}"
        }
        check(current?.missionId != missionId) { "Use a new missionId for a new mission" }
        _state.value = State(activeMission = Mission(missionId, goal, "Initializing...", MissionStatus.RUNNING))
    }

    fun updateMission(missionId: String, step: String, status: MissionStatus = MissionStatus.RUNNING) = synchronized(lock) {
        require(step.isNotBlank()) { "step must not be blank" }
        val mission = activeMission(missionId)
        transition(mission, step, status)
    }

    private fun activeMission(missionId: String? = null): Mission {
        check(!disposed) { "Mission Control is disposed" }
        val mission = _state.value.activeMission ?: error("No active mission")
        check(missionId == null || mission.missionId == missionId) { "Unknown mission: $missionId" }
        check(!mission.status.isTerminal()) { "Cannot update terminal mission" }
        return mission
    }

    private fun transition(mission: Mission, step: String, status: MissionStatus) {
        check(status != MissionStatus.WAITING_FOR_HUMAN) {
            "Cannot transition to WAITING_FOR_HUMAN via updateMission"
        }
        val handoff = _state.value.pendingHandoff
        if (status == MissionStatus.RUNNING || status == MissionStatus.COMPLETED) {
            check(handoff == null || handoff.responseDeferred.isCompleted) {
                "Human handoff must be resolved before continuing or completing"
            }
        }
        _state.value = State(activeMission = mission.copy(step = step, status = status, handoffReason = null))
        if (status == MissionStatus.FAILED || status == MissionStatus.CANCELLED) {
            val message = if (status == MissionStatus.FAILED) "Mission failed" else "Mission cancelled"
            handoff?.responseDeferred?.completeExceptionally(IllegalStateException(message))
        }
    }

    suspend fun requestHandoff(missionId: String, reason: String): String {
        require(reason.isNotBlank()) { "reason must not be blank" }
        val handoff = synchronized(lock) {
            val mission = activeMission(missionId)
            val current = _state.value.pendingHandoff
            if (current != null) {
                check(current.reason == reason) {
                    "Concurrent handoff collision: requested reason does not match active handoff"
                }
                current
            } else {
                check(mission.status == MissionStatus.RUNNING) { "Cannot request handoff from state: ${mission.status}" }
                HandoffRequest(reason).also {
                    _state.value = State(mission.copy(status = MissionStatus.WAITING_FOR_HUMAN, handoffReason = reason), it)
                }
            }
        }
        // Keep completed responses until mission_update acknowledges them. A timeout
        // or disconnect can otherwise consume an approval without delivering it.
        val response = handoff.responseDeferred.await()
        synchronized(lock) {
            val mission = _state.value.activeMission
            check(!disposed && mission?.missionId == missionId && !mission.status.isTerminal()) {
                "Mission ended before handoff response was delivered"
            }
        }
        return response
    }

    fun resolveHandoff(response: String, expectedHandoff: HandoffRequest? = null) = synchronized(lock) {
        val current = _state.value
        val mission = current.activeMission
        val handoff = current.pendingHandoff
        if (disposed || mission?.status != MissionStatus.WAITING_FOR_HUMAN || handoff == null ||
            (expectedHandoff != null && expectedHandoff !== handoff)) return@synchronized
        _state.value = current.copy(activeMission = mission.copy(status = MissionStatus.RUNNING, handoffReason = null))
        handoff.responseDeferred.complete(response)
    }

    fun completeMission() = synchronized(lock) {
        val mission = activeMission()
        transition(mission, mission.step, MissionStatus.COMPLETED)
    }

    fun failMission() = synchronized(lock) {
        val mission = activeMission()
        transition(mission, mission.step, MissionStatus.FAILED)
    }

    fun cancelMission(expectedMissionId: String? = null) = synchronized(lock) {
        val mission = _state.value.activeMission
        if (disposed || mission == null || mission.status.isTerminal() ||
            (expectedMissionId != null && mission.missionId != expectedMissionId)) return@synchronized
        transition(mission, mission.step, MissionStatus.CANCELLED)
    }

    fun dispose() = synchronized(lock) {
        disposed = true
        val handoff = _state.value.pendingHandoff
        _state.value = State()
        handoff?.responseDeferred?.cancel()
    }

    private fun MissionStatus.isTerminal() = this == MissionStatus.COMPLETED ||
        this == MissionStatus.FAILED || this == MissionStatus.CANCELLED
}
