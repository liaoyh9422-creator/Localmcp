package com.apkstoapk.app.ui.compose.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
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
fun Lua2DexScreen(
    title: String,
    targetLabel: String,
    step: Int,
    stepLabel: String,
    fileName: String,
    resultText: String,
    obfuscate: Boolean,
    running: Boolean,
    canCompile: Boolean,
    canExport: Boolean,
    onBack: () -> Unit,
    onPick: () -> Unit,
    onObfuscateChange: (Boolean) -> Unit,
    onCompile: () -> Unit,
    onExport: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        ScreenScaffold(title = title, onBack = onBack) {
            StepBar(current = step)
            Spacer(Modifier.height(8.dp))
            Text(stepLabel, color = MdTextMuted, fontSize = 13.sp)
            if (targetLabel.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(targetLabel, color = MdTextMuted, fontSize = 12.sp)
            }

            Spacer(Modifier.height(14.dp))
            Panel {
                Text("Lua 文件", color = MdTextMuted, fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                Text(fileName, color = MdTextPrimary, fontSize = 14.sp)
                Spacer(Modifier.height(12.dp))
                PrimaryButton(
                    text = stringResource(R.string.lua2dex_pick),
                    onClick = onPick,
                    enabled = !running,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(Modifier.height(10.dp))
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = obfuscate,
                        onCheckedChange = onObfuscateChange,
                        enabled = !running
                    )
                    Text(stringResource(R.string.lua2dex_obfuscate), color = MdTextPrimary)
                }
            }

            Spacer(Modifier.height(10.dp))
            if (running) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
            }
            Panel {
                Text("结果", color = MdTextMuted, fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                Text(resultText, color = MdTextPrimary, fontSize = 13.sp, lineHeight = 18.sp)
            }

            Spacer(Modifier.height(14.dp))
            PrimaryButton(
                text = stringResource(R.string.lua2dex_start),
                onClick = onCompile,
                enabled = canCompile && !running,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            SecondaryButton(
                text = stringResource(R.string.lua2dex_export),
                onClick = onExport,
                enabled = canExport && !running,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}
