package com.apkstoapk.app.ui.compose

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apkstoapk.app.BuildConfig
import com.apkstoapk.app.R
import com.apkstoapk.app.mcp.McpBootstrap
import com.apkstoapk.app.mcp.McpCapabilityStore
import com.apkstoapk.app.mcp.McpService
import com.apkstoapk.app.mcp.McpSystemCompat
import com.apkstoapk.app.ui.compose.screens.CapabilityUi
import com.apkstoapk.app.ui.compose.screens.HomeScreen
import com.apkstoapk.app.ui.compose.screens.LogScreen
import com.apkstoapk.app.ui.compose.screens.Lua2DexRoute
import com.apkstoapk.app.ui.compose.screens.ManifestRoute
import com.apkstoapk.app.ui.compose.screens.McpScreen
import com.apkstoapk.app.ui.compose.screens.MergeRoute
import com.apkstoapk.app.ui.compose.screens.SettingsScreen
import com.apkstoapk.app.ui.compose.theme.MdBg
import com.apkstoapk.app.ui.compose.theme.MdNavBg
import com.apkstoapk.app.ui.compose.theme.MdPrimary
import com.apkstoapk.app.ui.compose.theme.MdTextMuted
import com.apkstoapk.app.ui.compose.theme.MyToolsTheme
import com.apkstoapk.app.util.SimpleApkLogger
import com.google.gson.JsonObject
import java.io.File
import java.util.concurrent.ExecutorService

@Composable
fun AppRoot(
    activity: Activity,
    logger: SimpleApkLogger,
    executor: ExecutorService,
    mcpBootstrap: McpBootstrap,
    lastOutput: File?,
    onLastOutputChange: (File?) -> Unit,
    mergeRunning: Boolean,
    onMergeRunningChange: (Boolean) -> Unit,
    initialRoute: AppRoute = AppRoute.Home,
    openMergeSignal: Int = 0
) {
    MyToolsTheme {
        var route by remember { mutableStateOf(initialRoute) }
        var selectedTab by remember { mutableStateOf(MainTab.Home) }
        var tick by remember { mutableStateOf(0) }

        LaunchedEffect(openMergeSignal) {
            if (openMergeSignal > 0) {
                route = AppRoute.Merge
            }
        }

        DisposableEffect(mcpBootstrap) {
            val listener = McpBootstrap.StatusListener { tick++ }
            mcpBootstrap.setStatusListener(listener)
            onDispose { mcpBootstrap.setStatusListener(null) }
        }

        // log live updates
        var logText by remember { mutableStateOf(logger.getLines().joinToString("\n")) }
        DisposableEffect(logger) {
            val l = SimpleApkLogger.Listener {
                logText = logger.getLines().joinToString("\n")
            }
            logger.addListener(l)
            onDispose { logger.removeListener(l) }
        }

        val showBottomBar = route is AppRoute.Home ||
                route is AppRoute.Mcp ||
                route is AppRoute.Log ||
                route is AppRoute.Settings

        BackHandler(enabled = !showBottomBar) {
            route = AppRoute.Home
            selectedTab = MainTab.Home
        }

        Surface(modifier = Modifier.fillMaxSize(), color = MdBg) {
            Column(modifier = Modifier.fillMaxSize()) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    when (route) {
                        AppRoute.Home -> {
                            val task = when {
                                mergeRunning -> stringResource(R.string.home_status_running)
                                lastOutput != null && lastOutput.exists() ->
                                    stringResource(R.string.home_status_done, lastOutput.absolutePath)
                                else -> stringResource(R.string.home_status_idle)
                            }
                            HomeScreen(
                                taskStatus = task,
                                mcpOnline = McpService.isRunning(),
                                mcpEndpoint = McpService.getEndpoint(),
                                onOpenMcp = {
                                    selectedTab = MainTab.Mcp
                                    route = AppRoute.Mcp
                                },
                                onOpenMerge = { route = AppRoute.Merge },
                                onOpenLua2Dex = { route = AppRoute.Lua2Dex },
                                onOpenLua2DexModded = { route = AppRoute.Lua2DexModded },
                                onOpenManifest = { route = AppRoute.Manifest }
                            )
                        }

                        AppRoute.Mcp -> McpRouteContent(
                            activity = activity,
                            mcpBootstrap = mcpBootstrap,
                            tick = tick,
                            onChanged = { tick++ }
                        )

                        AppRoute.Log -> LogScreen(
                            logText = logText,
                            onClear = {
                                logger.clear()
                                McpService.clearLog()
                                logText = ""
                            }
                        )

                        AppRoute.Settings -> SettingsRouteContent(
                            activity = activity,
                            mcpBootstrap = mcpBootstrap,
                            executor = executor,
                            tick = tick,
                            onChanged = { tick++ }
                        )

                        AppRoute.Merge -> MergeRoute(
                            activity = activity,
                            logger = logger,
                            executor = executor,
                            lastOutput = lastOutput,
                            onLastOutputChange = onLastOutputChange,
                            mergeRunning = mergeRunning,
                            onMergeRunningChange = onMergeRunningChange,
                            onBack = {
                                route = AppRoute.Home
                                selectedTab = MainTab.Home
                            }
                        )

                        AppRoute.Lua2Dex -> Lua2DexRoute(
                            activity = activity,
                            logger = logger,
                            executor = executor,
                            target = com.apkstoapk.app.runtime.GgLuajTarget.STOCK,
                            onBack = {
                                route = AppRoute.Home
                                selectedTab = MainTab.Home
                            }
                        )

                        AppRoute.Lua2DexModded -> Lua2DexRoute(
                            activity = activity,
                            logger = logger,
                            executor = executor,
                            target = com.apkstoapk.app.runtime.GgLuajTarget.MODDED_GG,
                            onBack = {
                                route = AppRoute.Home
                                selectedTab = MainTab.Home
                            }
                        )

                        AppRoute.Manifest -> ManifestRoute(
                            activity = activity,
                            logger = logger,
                            executor = executor,
                            onLastOutputChange = onLastOutputChange,
                            onBack = {
                                route = AppRoute.Home
                                selectedTab = MainTab.Home
                            }
                        )
                    }
                }

                if (showBottomBar) {
                    BottomBar(
                        selected = selectedTab,
                        onSelect = { tab ->
                            selectedTab = tab
                            route = when (tab) {
                                MainTab.Home -> AppRoute.Home
                                MainTab.Mcp -> AppRoute.Mcp
                                MainTab.Log -> AppRoute.Log
                                MainTab.Settings -> AppRoute.Settings
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun BottomBar(
    selected: MainTab,
    onSelect: (MainTab) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MdNavBg)
            .navigationBarsPadding()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        BottomTabItem(
            selected = selected == MainTab.Home,
            icon = Icons.Outlined.Home,
            label = stringResource(R.string.tab_home),
            onClick = { onSelect(MainTab.Home) }
        )
        BottomTabItem(
            selected = selected == MainTab.Mcp,
            icon = Icons.Outlined.Share,
            label = stringResource(R.string.tab_mcp),
            onClick = { onSelect(MainTab.Mcp) }
        )
        BottomTabItem(
            selected = selected == MainTab.Log,
            icon = Icons.Outlined.Info,
            label = stringResource(R.string.tab_log),
            onClick = { onSelect(MainTab.Log) }
        )
        BottomTabItem(
            selected = selected == MainTab.Settings,
            icon = Icons.Outlined.Settings,
            label = stringResource(R.string.tab_settings),
            onClick = { onSelect(MainTab.Settings) }
        )
    }
}

@Composable
private fun BottomTabItem(
    selected: Boolean,
    icon: ImageVector,
    label: String,
    onClick: () -> Unit
) {
    val color = if (selected) MdPrimary else MdTextMuted
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) Color(0x142DD4BF) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = label, tint = color, modifier = Modifier.size(20.dp))
        if (selected) {
            Spacer(modifier = Modifier.width(6.dp))
            Text(label, color = color, fontSize = 12.sp)
        }
    }
}

@Composable
private fun McpRouteContent(
    activity: Activity,
    mcpBootstrap: McpBootstrap,
    tick: Int,
    onChanged: () -> Unit
) {
    val store = remember { McpCapabilityStore(activity) }
    // force recompose when tick changes
    @Suppress("UNUSED_VARIABLE")
    val force = tick

    var autoStart by remember { mutableStateOf(McpService.isAutoStartEnabled(activity)) }
    var portText by remember { mutableStateOf(McpService.getCurrentPort().toString()) }
    var preset by remember { mutableStateOf(store.getPreset()) }
    var expanded by remember { mutableStateOf(setOf<McpCapabilityStore.Category>()) }

    // first open default agent
    LaunchedEffect(Unit) {
        if (!activity.getSharedPreferences(McpCapabilityStore.PREFS, 0).contains("preset")) {
            store.applyPreset(McpCapabilityStore.Preset.AGENT)
            preset = store.getPreset()
        }
    }

    val status = if (McpService.isRunning()) {
        stringResource(
            R.string.mcp_status_running,
            mcpBootstrap.addressText(),
            McpService.getSelfCheckText()
        )
    } else {
        stringResource(R.string.mcp_status_stopped)
    }

    val caps = McpCapabilityStore.uiCategories().map { cat ->
        CapabilityUi(
            category = cat,
            enabled = store.isCategoryEnabled(cat),
            expanded = expanded.contains(cat),
            toolsText = store.toolsJoinedForUi(cat)
        )
    }

    McpScreen(
        statusText = status,
        autoStart = autoStart,
        portText = portText,
        onPortChange = { portText = it.filter { ch -> ch.isDigit() }.take(5) },
        onAutoStartChange = {
            autoStart = it
            McpService.setAutoStartEnabled(activity, it)
        },
        onStart = {
            val port = mcpBootstrap.parsePort(portText)
            portText = port.toString()
            McpService.setPreferredPort(activity, port)
            mcpBootstrap.startMcpService(port)
            onChanged()
        },
        onStop = {
            mcpBootstrap.stopMcpService()
            onChanged()
        },
        preset = preset,
        onPresetChange = {
            store.applyPreset(it)
            preset = store.getPreset()
            onChanged()
        },
        capabilitySummary = store.summaryText(),
        capabilities = caps,
        onToggleCategory = { cat, enabled ->
            store.setCategoryEnabled(cat, enabled)
            onChanged()
        },
        onToggleExpand = { cat ->
            expanded = if (expanded.contains(cat)) expanded - cat else expanded + cat
        }
    )
}

@Composable
private fun SettingsRouteContent(
    activity: Activity,
    mcpBootstrap: McpBootstrap,
    executor: ExecutorService,
    tick: Int,
    onChanged: () -> Unit
) {
    @Suppress("UNUSED_VARIABLE")
    val force = tick
    var batteryBusy by remember { mutableStateOf(false) }
    val unrestricted = McpSystemCompat.isBatteryUnrestricted(activity)
    val mcpRunning = if (McpService.isRunning()) {
        stringResource(R.string.settings_battery_mcp_on)
    } else {
        stringResource(R.string.settings_battery_mcp_off)
    }
    val batteryStatus = stringResource(
        R.string.settings_battery_status,
        if (unrestricted) stringResource(R.string.settings_battery_ok)
        else stringResource(R.string.settings_battery_limited),
        mcpRunning
    )
    val appInfo = stringResource(
        R.string.settings_app_info,
        stringResource(R.string.app_name),
        BuildConfig.VERSION_NAME,
        BuildConfig.APPLICATION_ID
    )

    SettingsScreen(
        appInfo = appInfo,
        shizukuStatus = stringResource(R.string.mcp_shizuku_status, mcpBootstrap.shizukuStatusText()),
        batteryStatus = if (batteryBusy) stringResource(R.string.settings_battery_applying) else batteryStatus,
        batterySystemLabel = if (unrestricted) {
            stringResource(R.string.settings_battery_system_done)
        } else {
            stringResource(R.string.settings_battery_system)
        },
        batteryBusy = batteryBusy,
        onOpenFilePermission = { mcpBootstrap.openAllFilesPermissionPage() },
        onRequestShizuku = {
            mcpBootstrap.requestShizukuAuth()
            onChanged()
        },
        onBatterySystem = {
            try {
                val r = McpSystemCompat.openBatteryOptimizationSettings(activity)
                val msg = if (r.has("message")) r.get("message").asString
                else activity.getString(R.string.settings_battery_opened)
                Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(activity, e.message ?: e.toString(), Toast.LENGTH_SHORT).show()
            }
            onChanged()
        },
        onBatteryShizuku = {
            if (batteryBusy) return@SettingsScreen
            batteryBusy = true
            executor.execute {
                try {
                    val args = JsonObject().apply { addProperty("mode", "shizuku") }
                    val r = McpSystemCompat.batteryFix(activity, args)
                    val msg = if (r.has("message")) r.get("message").asString
                    else activity.getString(R.string.settings_battery_shizuku_done)
                    activity.runOnUiThread {
                        batteryBusy = false
                        Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
                        onChanged()
                    }
                } catch (e: Exception) {
                    activity.runOnUiThread {
                        batteryBusy = false
                        Toast.makeText(activity, e.message ?: e.toString(), Toast.LENGTH_SHORT).show()
                        onChanged()
                    }
                }
            }
        }
    )
}
