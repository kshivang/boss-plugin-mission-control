package ai.rever.boss.plugin.dynamic.missioncontrol

import ai.rever.boss.plugin.api.McpToolDefinition
import ai.rever.boss.plugin.api.McpToolHandler
import ai.rever.boss.plugin.api.McpToolProvider
import ai.rever.boss.plugin.api.McpToolResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

class MissionMcpTools(
    override val providerId: String,
    private val manager: MissionManager,
    private val handoffTimeoutMs: Long = 10_000L
) : McpToolProvider {

    override fun tools(): List<McpToolDefinition> = listOf(
        McpToolDefinition(
            name = "mission_create",
            description = "Creates a new mission in Mission Control.",
            inputSchema = CREATE_SCHEMA,
            readOnly = false,
            handler = McpToolHandler { args ->
                val missionId = args.string("mission_id")
                    ?: return@McpToolHandler McpToolResult("Missing required argument: mission_id", isError = true)
                val goal = args.string("goal")
                    ?: return@McpToolHandler McpToolResult("Missing required argument: goal", isError = true)

                try {
                    manager.createMission(missionId, goal)
                    McpToolResult("MISSION_CREATED\nmission_id=$missionId\nstatus=RUNNING")
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    McpToolResult(e.message ?: "Failed to create mission", isError = true)
                }
            }
        ),
        McpToolDefinition(
            name = "mission_update",
            description = "Updates the active mission's step or status.",
            inputSchema = UPDATE_SCHEMA,
            readOnly = false,
            handler = McpToolHandler { args ->
                val missionId = args.string("mission_id")
                    ?: return@McpToolHandler McpToolResult("Missing required argument: mission_id", isError = true)
                val step = args.string("step")
                    ?: return@McpToolHandler McpToolResult("Missing required argument: step", isError = true)
                val statusStr = args.string("status") ?: "RUNNING"

                val status = try {
                    MissionStatus.valueOf(statusStr)
                } catch (e: IllegalArgumentException) {
                    return@McpToolHandler McpToolResult("Invalid status: $statusStr", isError = true)
                }

                try {
                    if (status == MissionStatus.COMPLETED) {
                        manager.updateMission(missionId, step, MissionStatus.RUNNING)
                        manager.completeMission()
                    } else if (status == MissionStatus.FAILED) {
                        manager.updateMission(missionId, step, MissionStatus.RUNNING)
                        manager.failMission()
                    } else if (status == MissionStatus.CANCELLED) {
                        manager.updateMission(missionId, step, MissionStatus.RUNNING)
                        manager.cancelMission()
                    } else {
                        manager.updateMission(missionId, step, status)
                    }
                    McpToolResult("MISSION_UPDATED\nmission_id=$missionId\nstatus=$status\nstep=$step")
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    McpToolResult(e.message ?: "Failed to update mission", isError = true)
                }
            }
        ),
        McpToolDefinition(
            name = "mission_handoff",
            description = "Requests human resolution or approval before proceeding.",
            inputSchema = HANDOFF_SCHEMA,
            readOnly = false,
            handler = McpToolHandler { args ->
                val missionId = args.string("mission_id")
                    ?: return@McpToolHandler McpToolResult("Missing required argument: mission_id", isError = true)
                val reason = args.string("reason")
                    ?: return@McpToolHandler McpToolResult("Missing required argument: reason", isError = true)

                try {
                    val response = withTimeoutOrNull(handoffTimeoutMs) {
                        manager.requestHandoff(missionId, reason)
                    }
                    if (response == null) {
                        McpToolResult("PENDING\nmission_id=$missionId\nreason=Human has not responded yet.\nCall mission_handoff again with the same mission and reason.")
                    } else {
                        McpToolResult("HUMAN_RESOLVED\nmission_id=$missionId\nresponse=$response\nContinue the mission.")
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    if (e.message == "Mission cancelled") {
                        McpToolResult("MISSION_CANCELLED\nmission_id=$missionId\nreason=Human cancelled the mission.\nDo not continue.", isError = true)
                    } else {
                        McpToolResult(e.message ?: "Failed to request handoff", isError = true)
                    }
                }
            }
        )
    )

    private companion object {
        const val CREATE_SCHEMA =
            """{"type":"object","properties":{"mission_id":{"type":"string","description":"Unique identifier for the mission."},"goal":{"type":"string","description":"The overall goal of the mission."}},"required":["mission_id","goal"]}"""
        const val UPDATE_SCHEMA =
            """{"type":"object","properties":{"mission_id":{"type":"string","description":"The mission id."},"step":{"type":"string","description":"The current step being executed."},"status":{"type":"string","description":"The status of the mission. Defaults to RUNNING. Valid values: RUNNING, COMPLETED, FAILED, CANCELLED."}},"required":["mission_id","step"]}"""
        const val HANDOFF_SCHEMA =
            """{"type":"object","properties":{"mission_id":{"type":"string","description":"The mission id."},"reason":{"type":"string","description":"The reason for handoff to human."}},"required":["mission_id","reason"]}"""
    }
}
