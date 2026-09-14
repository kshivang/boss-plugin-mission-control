package ai.rever.boss.plugin.dynamic.missioncontrol

import ai.rever.boss.plugin.api.PanelComponentWithUI
import ai.rever.boss.plugin.api.PanelInfo
import ai.rever.boss.plugin.ui.BossThemeColors
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

import com.arkivanov.decompose.ComponentContext

class MissionControlPanelComponent(
    ctx: ComponentContext,
    override val panelInfo: PanelInfo,
    private val manager: MissionManager
) : PanelComponentWithUI, ComponentContext by ctx {



    @Composable
    override fun Content() {
        ai.rever.boss.plugin.ui.BossTheme {
            val state by manager.state.collectAsState()
            val mission = state.activeMission

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(BossThemeColors.BackgroundColor)
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "MISSION CONTROL",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = BossThemeColors.TextPrimary,
                    modifier = Modifier.padding(bottom = 24.dp)
                )

                if (mission == null) {
                    EmptyState()
                } else {
                    MissionStateView(mission, state.pendingHandoff)
                }
            }
        }

    }

    @Composable
    private fun EmptyState() {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "Mission Control is available for agent tasks.",
                color = BossThemeColors.TextPrimary,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }
    }

    @Composable
    private fun MissionStateView(mission: Mission, handoff: HandoffRequest?) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.Start
        ) {
            when (mission.status) {
                MissionStatus.RUNNING -> RunningView(mission)
                MissionStatus.WAITING_FOR_HUMAN -> WaitingView(mission, handoff)
                MissionStatus.COMPLETED -> CompletedView(mission)
                MissionStatus.FAILED -> FailedView(mission)
                MissionStatus.CANCELLED -> CancelledView(mission)
            }
        }
    }

    @Composable
    private fun RunningView(mission: Mission) {
        GoalSection(mission.goal)
        
        Spacer(Modifier.height(16.dp))
        
        Text("Current step", fontWeight = FontWeight.Bold, color = BossThemeColors.TextPrimary)
        Text(mission.step, color = BossThemeColors.TextSecondary)
        
        Spacer(Modifier.height(16.dp))
        
        Text("Status", fontWeight = FontWeight.Bold, color = BossThemeColors.TextPrimary)
        Text("RUNNING", color = BossThemeColors.AccentColor)
        
        Spacer(Modifier.height(32.dp))
        
        CancelButton(mission.missionId)
    }

    @Composable
    private fun WaitingView(mission: Mission, handoff: HandoffRequest?) {
        Card(
            backgroundColor = BossThemeColors.WarningColor.copy(alpha = 0.1f),
            elevation = 0.dp,
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
        ) {
            Text(
                "HUMAN ACTION REQUIRED",
                color = BossThemeColors.WarningColor,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(16.dp)
            )
        }
        
        GoalSection(mission.goal)
        
        Spacer(Modifier.height(16.dp))
        
        Text("The agent needs your attention.", color = BossThemeColors.TextPrimary)
        
        Spacer(Modifier.height(8.dp))
        
        Surface(
            color = BossThemeColors.SurfaceColor,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                mission.handoffReason ?: "No reason provided",
                color = BossThemeColors.TextSecondary,
                modifier = Modifier.padding(12.dp)
            )
        }
        
        Spacer(Modifier.height(32.dp))
        
        Button(
            onClick = { handoff?.let { manager.resolveHandoff("Human approved. Continue.", it) } },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                backgroundColor = BossThemeColors.AccentColor,
                contentColor = BossThemeColors.BackgroundColor
            )
        ) {
            Text("RETURN CONTROL")
        }
        
        Spacer(Modifier.height(16.dp))
        
        CancelButton(mission.missionId)
    }

    @Composable
    private fun CompletedView(mission: Mission) {
        Text("MISSION COMPLETED", color = BossThemeColors.SuccessColor, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        GoalSection(mission.goal)
        Spacer(Modifier.height(16.dp))
        Text(mission.step, color = BossThemeColors.TextSecondary)
    }

    @Composable
    private fun FailedView(mission: Mission) {
        Text("MISSION FAILED", color = BossThemeColors.ErrorColor, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        GoalSection(mission.goal)
        Spacer(Modifier.height(16.dp))
        Text("Failure reason: ${mission.step}", color = BossThemeColors.TextSecondary)
    }

    @Composable
    private fun CancelledView(mission: Mission) {
        Text("MISSION CANCELLED", color = BossThemeColors.TextMuted, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        GoalSection(mission.goal)
        Spacer(Modifier.height(16.dp))
        Text("The mission was cancelled by the human.", color = BossThemeColors.TextSecondary)
    }

    @Composable
    private fun GoalSection(goal: String) {
        Text(goal, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = BossThemeColors.TextPrimary)
    }

    @Composable
    private fun CancelButton(missionId: String) {
        OutlinedButton(
            onClick = { manager.cancelMission(missionId) },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = BossThemeColors.ErrorColor,
                backgroundColor = BossThemeColors.BackgroundColor
            )
        ) {
            Text("Cancel Mission")
        }
    }
}
