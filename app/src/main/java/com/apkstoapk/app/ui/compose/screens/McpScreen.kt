package com.apkstoapk.app.ui.compose.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apkstoapk.app.R
import com.apkstoapk.app.mcp.McpCapabilityStore
import com.apkstoapk.app.ui.compose.components.MonoText
import com.apkstoapk.app.ui.compose.components.Panel
import com.apkstoapk.app.ui.compose.components.PrimaryButton
import com.apkstoapk.app.ui.compose.components.ScreenTitle
import com.apkstoapk.app.ui.compose.components.SecondaryButton
import com.apkstoapk.app.ui.compose.components.SectionTitle
import com.apkstoapk.app.ui.compose.theme.MdLogBg
import com.apkstoapk.app.ui.compose.theme.MdPrimary
import com.apkstoapk.app.ui.compose.theme.MdStroke
import com.apkstoapk.app.ui.compose.theme.MdSuccess
import com.apkstoapk.app.ui.compose.theme.MdSurface
import com.apkstoapk.app.ui.compose.theme.MdTextMuted
import com.apkstoapk.app.ui.compose.theme.MdTextPrimary
import com.apkstoapk.app.ui.compose.theme.MdTextSecondary

data class CapabilityUi(
    val category: McpCapabilityStore.Category,
    val enabled: Boolean,
    val expanded: Boolean,
    val toolsText: String
)

@Composable
fun McpScreen(
    statusText: String,
    autoStart: Boolean,
    portText: String,
    onPortChange: (String) -> Unit,
    onAutoStartChange: (Boolean) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    preset: McpCapabilityStore.Preset,
    onPresetChange: (McpCapabilityStore.Preset) -> Unit,
    capabilitySummary: String,
    capabilities: List<CapabilityUi>,
    onToggleCategory: (McpCapabilityStore.Category, Boolean) -> Unit,
    onToggleExpand: (McpCapabilityStore.Category) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        ScreenTitle(title = stringResource(R.string.tab_mcp))
        Spacer(Modifier.height(14.dp))

        Text(
            text = statusText,
            color = MdTextPrimary,
            fontSize = 13.sp,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(MdSurface)
                .border(1.dp, MdStroke, RoundedCornerShape(16.dp))
                .padding(14.dp)
        )

        Spacer(Modifier.height(10.dp))
        Panel {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("打开应用自动启动", color = MdTextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Spacer(Modifier.height(2.dp))
                    Text("启动 App 时自动拉起 MCP 服务", color = MdTextMuted, fontSize = 12.sp)
                }
                Switch(checked = autoStart, onCheckedChange = onAutoStartChange)
            }
        }

        Spacer(Modifier.height(14.dp))
        OutlinedTextField(
            value = portText,
            onValueChange = onPortChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(stringResource(R.string.mcp_port_hint)) }
        )

        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            PrimaryButton(
                text = stringResource(R.string.mcp_start),
                onClick = onStart,
                modifier = Modifier.weight(1f)
            )
            SecondaryButton(
                text = stringResource(R.string.mcp_stop),
                onClick = onStop,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(Modifier.height(22.dp))
        SectionTitle(stringResource(R.string.mcp_preset_title))
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.mcp_preset_hint), color = MdTextMuted, fontSize = 12.sp)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            PresetChip(
                label = stringResource(R.string.mcp_preset_agent),
                selected = preset == McpCapabilityStore.Preset.AGENT,
                onClick = { onPresetChange(McpCapabilityStore.Preset.AGENT) },
                modifier = Modifier.weight(1f)
            )
            PresetChip(
                label = stringResource(R.string.mcp_preset_full),
                selected = preset == McpCapabilityStore.Preset.FULL,
                onClick = { onPresetChange(McpCapabilityStore.Preset.FULL) },
                modifier = Modifier.weight(1f)
            )
            PresetChip(
                label = stringResource(R.string.mcp_preset_safe),
                selected = preset == McpCapabilityStore.Preset.SAFE,
                onClick = { onPresetChange(McpCapabilityStore.Preset.SAFE) },
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(Modifier.height(22.dp))
        SectionTitle(stringResource(R.string.mcp_capability_title))
        Spacer(Modifier.height(6.dp))
        Text(capabilitySummary, color = MdTextMuted, fontSize = 12.sp)
        Spacer(Modifier.height(8.dp))

        capabilities.forEach { item ->
            CapabilityRow(
                item = item,
                onToggle = { onToggleCategory(item.category, it) },
                onExpand = { onToggleExpand(item.category) }
            )
            Spacer(Modifier.height(8.dp))
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun PresetChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bg = if (selected) MdPrimary.copy(alpha = 0.18f) else MdSurface
    val border = if (selected) MdPrimary else MdStroke
    val color = if (selected) MdPrimary else MdTextPrimary
    Box(
        modifier = modifier
            .height(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun CapabilityRow(
    item: CapabilityUi,
    onToggle: (Boolean) -> Unit,
    onExpand: () -> Unit
) {
    val locked = item.category.locked
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MdSurface)
            .border(1.dp, MdStroke, RoundedCornerShape(16.dp))
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClick = onExpand)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(if (item.enabled) MdSuccess else MdTextMuted)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(item.category.title, color = MdTextPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
                Spacer(Modifier.height(4.dp))
                val status = buildString {
                    append(if (locked) "始终开" else if (item.enabled) "开" else "关")
                    if (!locked) append(if (item.expanded) "  ·  点击收起" else "  ·  点击展开")
                }
                Text(
                    status,
                    color = if (item.enabled) MdSuccess else MdTextMuted,
                    fontSize = 12.sp
                )
                Spacer(Modifier.height(2.dp))
                Text(item.category.desc, color = MdTextMuted, fontSize = 12.sp)
            }
            Switch(
                checked = item.enabled,
                onCheckedChange = { if (!locked) onToggle(it) },
                enabled = !locked
            )
        }
        if (item.expanded) {
            Spacer(Modifier.height(10.dp))
            MonoText(
                text = item.toolsText,
                color = MdTextSecondary,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MdLogBg)
                    .padding(10.dp)
            )
        }
    }
}
