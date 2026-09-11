package ai.rever.boss.plugin.dynamic.missioncontrol

import ai.rever.boss.plugin.api.Panel
import ai.rever.boss.plugin.api.PanelId
import ai.rever.boss.plugin.api.PanelInfo
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Assignment

object MissionControlPanelInfo : PanelInfo {
    override val id = PanelId("mission-control", 100)
    override val displayName = "Mission Control"
    override val icon = Icons.AutoMirrored.Outlined.Assignment
    override val defaultSlotPosition = Panel.right
}
