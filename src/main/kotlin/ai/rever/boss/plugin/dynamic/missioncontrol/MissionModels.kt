package ai.rever.boss.plugin.dynamic.missioncontrol

import kotlinx.coroutines.CompletableDeferred

enum class MissionStatus {
    RUNNING,
    WAITING_FOR_HUMAN,
    COMPLETED,
    FAILED,
    CANCELLED
}

data class Mission(
    val missionId: String,
    val goal: String,
    val step: String,
    val status: MissionStatus,
    val handoffReason: String? = null
)

class HandoffRequest(
    val reason: String,
    val responseDeferred: CompletableDeferred<String> = CompletableDeferred()
)
