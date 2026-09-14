package ai.rever.boss.plugin.dynamic.missioncontrol

import ai.rever.boss.plugin.api.DynamicPlugin
import ai.rever.boss.plugin.api.PluginContext

class MissionControlDynamicPlugin : DynamicPlugin {
    override val pluginId = "ai.rever.boss.plugin.dynamic.missioncontrol"
    override val displayName = "Mission Control"
    override val version = "0.1.0"
    override val description = "Shared human-agent workspace"
    override val author = "Risa Labs"
    override val url = "https://github.com/risa-labs-inc/boss-plugins"

    private var missionManager: MissionManager? = null
    private var pluginContext: PluginContext? = null

    override fun register(context: PluginContext) {
        check(pluginContext == null) { "Mission Control is already registered" }
        pluginContext = context
        val manager = MissionManager()
        missionManager = manager

        try {
            context.registerMcpToolProvider(MissionMcpTools(pluginId, manager))

            context.panelRegistry.registerPanel(MissionControlPanelInfo) { ctx, panelInfo ->
                MissionControlPanelComponent(
                    ctx = ctx,
                    panelInfo = panelInfo,
                    manager = manager
                )
            }
        } catch (failure: Throwable) {
            try {
                dispose()
            } catch (cleanupFailure: Throwable) {
                failure.addSuppressed(cleanupFailure)
            }
            throw failure
        }
    }

    override fun dispose() {
        val context = pluginContext
        pluginContext = null
        try {
            context?.unregisterMcpToolProvider(pluginId)
        } finally {
            try {
                context?.panelRegistry?.unregisterPanel(MissionControlPanelInfo.id)
            } finally {
                missionManager?.dispose()
                missionManager = null
            }
        }
    }
}
