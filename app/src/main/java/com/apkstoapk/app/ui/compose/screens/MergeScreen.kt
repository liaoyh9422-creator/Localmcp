package com.apkstoapk.app.ui.compose.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apkstoapk.app.R
import com.apkstoapk.app.ui.compose.components.Panel
import com.apkstoapk.app.ui.compose.components.PrimaryButton
import com.apkstoapk.app.ui.compose.components.ScreenScaffold
import com.apkstoapk.app.ui.compose.components.SecondaryButton
import com.apkstoapk.app.ui.compose.components.StepBar
import com.apkstoapk.app.ui.compose.theme.MdTextMuted
import com.apkstoapk.app.ui.compose.theme.MdTextPrimary

@Composable
fun MergeScreen(
    step: Int,
    stepLabel: String,
    fileName: String,
    soListText: String,
    statusText: String,
    running: Boolean,
    canPickSo: Boolean,
    canMerge: Boolean,
    canExport: Boolean,
    onBack: () -> Unit,
    onPickApk: () -> Unit,
    onPickSo: () -> Unit,
    onMerge: () -> Unit,
    onExport: () -> Unit,
    onInstall: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        ScreenScaffold(title = stringResource(R.string.tool_merge_title), onBack = onBack) {
            StepBar(current = step)
            Spacer(Modifier.height(8.dp))
            Text(stepLabel, color = MdTextMuted, fontSize = 13.sp)

            Spacer(Modifier.height(14.dp))
            Panel {
                Text("安装包", color = MdTextMuted, fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                Text(fileName, color = MdTextPrimary, fontSize = 14.sp)
                Spacer(Modifier.height(12.dp))
                PrimaryButton(
                    text = stringResource(R.string.pick_apk),
                    onClick = onPickApk,
                    enabled = !running,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(Modifier.height(10.dp))
            Panel {
                Text("so 注入", color = MdTextMuted, fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                Text(soListText, color = MdTextPrimary, fontSize = 13.sp, lineHeight = 18.sp)
                Spacer(Modifier.height(12.dp))
                SecondaryButton(
                    text = stringResource(R.string.pick_so),
                    onClick = onPickSo,
                    enabled = canPickSo && !running,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(Modifier.height(10.dp))
            if (running) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
            }
            Panel {
                Text("状态", color = MdTextMuted, fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                Text(
                    text = statusText.ifBlank { "—" },
                    color = MdTextPrimary,
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
            }

            Spacer(Modifier.height(14.dp))
            PrimaryButton(
                text = stringResource(R.string.start_run),
                onClick = onMerge,
                enabled = canMerge && !running,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                SecondaryButton(
                    text = stringResource(R.string.export_apk),
                    onClick = onExport,
                    enabled = canExport && !running,
                    modifier = Modifier.weight(1f)
                )
                SecondaryButton(
                    text = stringResource(R.string.install_apk),
                    onClick = onInstall,
                    enabled = canExport && !running,
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
