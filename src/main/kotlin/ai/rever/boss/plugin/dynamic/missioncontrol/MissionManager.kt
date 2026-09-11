package ai.rever.boss.plugin.dynamic.missioncontrol

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class MissionManager {

    data class State(
        val activeMission: Mission? = null,
        val pendingHandoff: HandoffRequest? = null
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun createMission(missionId: String, goal: String) {
        require(missionId.isNotBlank()) { "missionId must not be blank" }
        require(goal.isNotBlank()) { "goal must not be blank" }

        _state.update { current ->
            if (current.activeMission != null) {
                error("A mission is already active: ${current.activeMission.missionId}")
            }
            current.copy(
                activeMission = Mission(
                    missionId = missionId,
                    goal = goal,
                    step = "Initializing...",
                    status = MissionStatus.RUNNING
                )
            )
        }
    }

    fun updateMission(missionId: String, step: String, status: MissionStatus = MissionStatus.RUNNING) {
        _state.update { current ->
            val mission = current.activeMission ?: error("No active mission")
            if (mission.missionId != missionId) error("Unknown mission: $missionId")
            if (mission.status in listOf(MissionStatus.COMPLETED, MissionStatus.FAILED, MissionStatus.CANCELLED)) {
                error("Cannot update terminal mission")
            }

            if (status == MissionStatus.RUNNING) {
                if (mission.status != MissionStatus.RUNNING && mission.status != MissionStatus.WAITING_FOR_HUMAN) {
                    error("Invalid transition to RUNNING from ${mission.status}")
                }
            } else if (status == MissionStatus.WAITING_FOR_HUMAN) {
                 if (mission.status != MissionStatus.WAITING_FOR_HUMAN) {
                     error("Cannot transition to WAITING_FOR_HUMAN via updateMission")
                 }
            }

            current.copy(
                activeMission = mission.copy(step = step, status = status)
            )
        }
    }

    suspend fun requestHandoff(missionId: String, reason: String): String {
        var handoff: HandoffRequest? = null
        var shouldClearHandoff = false

        _state.update { current ->
            val mission = current.activeMission ?: error("No active mission")
            if (mission.missionId != missionId) error("Unknown mission: $missionId")
            
            if (current.pendingHandoff != null) {
                if (current.pendingHandoff.reason != reason) {
                    error("Concurrent handoff collision: requested reason does not match active handoff")
                }
                handoff = current.pendingHandoff
                
                if (handoff.responseDeferred.isCompleted) {
                    shouldClearHandoff = true
                    return@update current.copy(pendingHandoff = null)
                }
                
                return@update current
            }

            if (mission.status != MissionStatus.RUNNING) {
                error("Cannot request handoff from state: ${mission.status}")
            }

            val newHandoff = HandoffRequest(reason)
            handoff = newHandoff

            current.copy(
                activeMission = mission.copy(status = MissionStatus.WAITING_FOR_HUMAN, handoffReason = reason),
                pendingHandoff = newHandoff
            )
        }

        val result = handoff?.responseDeferred?.await() ?: error("Handoff is null")
        
        // If this await actually suspended and we just woke up, we need to clear the pendingHandoff
        // (if resolveHandoff hasn't already). Wait, resolveHandoff leaves pendingHandoff non-null 
        // precisely so we can read it here or in a subsequent call.
        // We can safely clear it now because we have the result.
        if (!shouldClearHandoff) {
            _state.update { current ->
                if (current.pendingHandoff === handoff) {
                    current.copy(pendingHandoff = null)
                } else current
            }
        }
        
        return result
    }

    fun resolveHandoff(response: String) {
        var resolvedHandoff: HandoffRequest? = null
        
        _state.update { current ->
            val mission = current.activeMission
            val handoff = current.pendingHandoff

            if (mission == null || mission.status != MissionStatus.WAITING_FOR_HUMAN || handoff == null) {
                return@update current
            }

            resolvedHandoff = handoff
            current.copy(
                activeMission = mission.copy(status = MissionStatus.RUNNING, handoffReason = null)
                // We deliberately DO NOT clear pendingHandoff here, so that if the agent
                // was timed out, its next poll will find it and collect the response.
            )
        }

        resolvedHandoff?.responseDeferred?.complete(response)
    }

    fun completeMission() {
        _state.update { current ->
            val mission = current.activeMission ?: error("No active mission")
            if (mission.status != MissionStatus.RUNNING) {
                error("Cannot complete mission from state: ${mission.status}")
            }
            current.copy(
                activeMission = mission.copy(status = MissionStatus.COMPLETED)
            )
        }
    }

    fun failMission() {
        var handoffToCancel: HandoffRequest? = null
        _state.update { current ->
            val mission = current.activeMission ?: error("No active mission")
            if (mission.status in listOf(MissionStatus.COMPLETED, MissionStatus.FAILED, MissionStatus.CANCELLED)) {
                error("Mission already terminal")
            }
            handoffToCancel = current.pendingHandoff
            current.copy(
                activeMission = mission.copy(status = MissionStatus.FAILED),
                pendingHandoff = null
            )
        }
        handoffToCancel?.responseDeferred?.completeExceptionally(IllegalStateException("Mission failed"))
    }

    fun cancelMission() {
        var handoffToCancel: HandoffRequest? = null
        
        _state.update { current ->
            val mission = current.activeMission
            if (mission == null || mission.status in listOf(MissionStatus.COMPLETED, MissionStatus.FAILED, MissionStatus.CANCELLED)) {
                return@update current
            }

            handoffToCancel = current.pendingHandoff

            current.copy(
                activeMission = mission.copy(status = MissionStatus.CANCELLED),
                pendingHandoff = null
            )
        }

        handoffToCancel?.responseDeferred?.completeExceptionally(IllegalStateException("Mission cancelled"))
    }

    fun dispose() {
        var handoffToCancel: HandoffRequest? = null
        
        _state.update { current ->
            handoffToCancel = current.pendingHandoff
            State()
        }
        
        handoffToCancel?.responseDeferred?.cancel()
    }
}
