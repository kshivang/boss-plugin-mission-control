package ai.rever.boss.plugin.dynamic.missioncontrol

import ai.rever.boss.plugin.api.PluginManifest
import ai.rever.boss.plugin.api.PluginContext
import ai.rever.boss.plugin.api.PanelRegistry
import ai.rever.boss.plugin.api.McpToolProvider
import kotlinx.coroutines.test.runTest
import ai.rever.boss.plugin.api.McpToolArgs
import kotlinx.serialization.json.Json
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class PluginContractTest {
    @Test
    fun `processed manifest can be decoded by the host API`() {
        val source = checkNotNull(javaClass.getResource("/META-INF/boss-plugin/plugin.json")).readText()
        val manifest = Json { ignoreUnknownKeys = true }.decodeFromString<PluginManifest>(source)
        val plugin = MissionControlDynamicPlugin()
        assertEquals(plugin.pluginId, manifest.pluginId)
        assertEquals(plugin.displayName, manifest.displayName)
        assertEquals(plugin.version, manifest.version)
        assertEquals("1.0.89", manifest.apiVersion)
        assertEquals(plugin.javaClass.name, manifest.mainClass)
    }

    @Test
    fun `disposal unregisters tools and invalidates retained handlers`() = runTest {
        val calls = mutableListOf<String>()
        var provider: McpToolProvider? = null
        val panels = PanelRegistry()
        val context = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(PluginContext::class.java)) { _, method, args ->
            when (method.name) {
                "getPanelRegistry" -> panels
                "registerMcpToolProvider" -> { provider = args!![0] as McpToolProvider; null }
                "unregisterMcpToolProvider" -> { calls.add("unregister:" + args!![0]); null }
                else -> null
            }
        } as PluginContext
        val plugin = MissionControlDynamicPlugin()
        plugin.register(context)
        assertEquals(1, panels.getAllPanels().size)
        val create = provider!!.tools().first { it.name == "mission_create" }.handler
        assertFalse(create.call(McpToolArgs(mapOf("mission_id" to "m1", "goal" to "Goal"))).isError)
        plugin.dispose()
        plugin.dispose()
        assertEquals(1, calls.count { it == "unregister:" + plugin.pluginId })
        assertTrue(panels.getAllPanels().isEmpty())
        assertTrue(create.call(McpToolArgs(mapOf("mission_id" to "m2", "goal" to "Goal"))).isError)
    }
    @Test
    fun `failed registration rolls back tools and closes the captured manager`() = runTest {
        var provider: McpToolProvider? = null
        var unregisters = 0
        val context = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(PluginContext::class.java)) { _, method, args ->
            when (method.name) {
                "getPanelRegistry" -> error("Panel registration unavailable")
                "registerMcpToolProvider" -> { provider = args!![0] as McpToolProvider; null }
                "unregisterMcpToolProvider" -> { unregisters++; null }
                else -> null
            }
        } as PluginContext
        val plugin = MissionControlDynamicPlugin()
        assertFailsWith<IllegalStateException> { plugin.register(context) }
        assertEquals(1, unregisters)
        val create = provider!!.tools().first { it.name == "mission_create" }.handler
        assertTrue(create.call(McpToolArgs(mapOf("mission_id" to "m1", "goal" to "Goal"))).isError)
    }

}
