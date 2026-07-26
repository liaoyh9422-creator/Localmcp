package com.apkstoapk.app.ui.compose.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import com.apkstoapk.app.ui.compose.components.Panel
import com.apkstoapk.app.ui.compose.components.ScreenTitle
import com.apkstoapk.app.ui.compose.components.SectionTitle
import com.apkstoapk.app.ui.compose.components.StatusDot
import com.apkstoapk.app.ui.compose.components.ToolCard
import com.apkstoapk.app.ui.compose.theme.MdChipBg
import com.apkstoapk.app.ui.compose.theme.MdInfo
import com.apkstoapk.app.ui.compose.theme.MdPrimary
import com.apkstoapk.app.ui.compose.theme.MdSecondary
import com.apkstoapk.app.ui.compose.theme.MdStroke
import com.apkstoapk.app.ui.compose.theme.MdSuccess
import com.apkstoapk.app.ui.compose.theme.MdSurface
import com.apkstoapk.app.ui.compose.theme.MdTextMuted
import com.apkstoapk.app.ui.compose.theme.MdTextPrimary

@Composable
fun HomeScreen(
    taskStatus: String,
    mcpOnline: Boolean,
    mcpEndpoint: String,
    onOpenMcp: () -> Unit,
    onOpenMerge: () -> Unit,
    onOpenLua2Dex: () -> Unit,
    onOpenLua2DexModded: () -> Unit,
    onOpenManifest: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            ScreenTitle(title = stringResource(R.string.app_name), modifier = Modifier.weight(1f))
            Text(
                text = "TOOLS",
                color = MdPrimary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(MdChipBg)
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }
        Spacer(Modifier.height(6.dp))
        Text("快捷入口 · 本地处理", color = MdTextMuted, fontSize = 14.sp)

        Spacer(Modifier.height(14.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(MdSurface)
                .border(1.dp, MdStroke, RoundedCornerShape(16.dp))
                .clickable(onClick = onOpenMcp)
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            StatusDot(on = mcpOnline)
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("MCP", color = MdTextMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(2.dp))
                Text(
                    text = if (mcpOnline) "在线 · $mcpEndpoint" else "未运行",
                    color = if (mcpOnline) MdSuccess else MdTextPrimary,
                    fontSize = 13.sp
                )
            }
            Text("管理 ›", color = MdPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }

        Spacer(Modifier.height(10.dp))
        Panel {
            Text("任务", color = MdTextMuted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(taskStatus, color = MdTextPrimary, fontSize = 14.sp)
        }

        Spacer(Modifier.height(22.dp))
        SectionTitle("工具")
        Spacer(Modifier.height(12.dp))
        ToolCard(
            title = stringResource(R.string.tool_merge_title),
            subtitle = "1 选包 → 2 选项 → 3 执行 → 4 导出",
            accent = MdPrimary,
            onClick = onOpenMerge
        )
        Spacer(Modifier.height(10.dp))
        ToolCard(
            title = stringResource(R.string.tool_lua2dex_title),
            subtitle = "1 选文件 → 2 混淆 → 3 编译 → 4 导出",
            accent = MdSecondary,
            onClick = onOpenLua2Dex
        )
        Spacer(Modifier.height(10.dp))
        ToolCard(
            title = stringResource(R.string.tool_lua2dex_modded_title),
            subtitle = stringResource(R.string.tool_lua2dex_modded_sub),
            accent = MdInfo,
            onClick = onOpenLua2DexModded
        )
        Spacer(Modifier.height(10.dp))
        ToolCard(
            title = stringResource(R.string.tool_manifest_title),
            subtitle = "APK / APKS / XAPK · 编辑清单",
            accent = MdInfo,
            onClick = onOpenManifest
        )
        Spacer(Modifier.height(24.dp))
    }
}
