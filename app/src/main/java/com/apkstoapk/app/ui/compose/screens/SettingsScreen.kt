package com.apkstoapk.app.ui.compose.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apkstoapk.app.R
import com.apkstoapk.app.ui.compose.components.Panel
import com.apkstoapk.app.ui.compose.components.PrimaryButton
import com.apkstoapk.app.ui.compose.components.ScreenTitle
import com.apkstoapk.app.ui.compose.components.SecondaryButton
import com.apkstoapk.app.ui.compose.components.SectionTitle
import com.apkstoapk.app.ui.compose.theme.MdTextMuted
import com.apkstoapk.app.ui.compose.theme.MdTextPrimary

@Composable
fun SettingsScreen(
    appInfo: String,
    shizukuStatus: String,
    batteryStatus: String,
    batterySystemLabel: String,
    batteryBusy: Boolean,
    onOpenFilePermission: () -> Unit,
    onRequestShizuku: () -> Unit,
    onBatterySystem: () -> Unit,
    onBatteryShizuku: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        ScreenTitle(title = stringResource(R.string.tab_settings))
        Spacer(Modifier.height(14.dp))

        Panel {
            Text(appInfo, color = MdTextPrimary, fontSize = 14.sp, lineHeight = 20.sp)
        }

        Spacer(Modifier.height(18.dp))
        SectionTitle(stringResource(R.string.settings_permission_title))
        Spacer(Modifier.height(10.dp))
        Panel {
            Text(shizukuStatus, color = MdTextPrimary, fontSize = 13.sp)
            Spacer(Modifier.height(12.dp))
            PrimaryButton(
                text = stringResource(R.string.mcp_file_permission),
                onClick = onOpenFilePermission,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            SecondaryButton(
                text = stringResource(R.string.mcp_shizuku_auth),
                onClick = onRequestShizuku,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(Modifier.height(18.dp))
        SectionTitle(stringResource(R.string.settings_keepalive_title))
        Spacer(Modifier.height(10.dp))
        Panel {
            Text(batteryStatus, color = MdTextPrimary, fontSize = 13.sp, lineHeight = 20.sp)
            Spacer(Modifier.height(12.dp))
            PrimaryButton(
                text = batterySystemLabel,
                onClick = onBatterySystem,
                enabled = !batteryBusy,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            SecondaryButton(
                text = stringResource(R.string.settings_battery_shizuku),
                onClick = onBatteryShizuku,
                enabled = !batteryBusy,
                modifier = Modifier.fillMaxWidth()
            )
            if (batteryBusy) {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.settings_battery_applying),
                    color = MdTextMuted,
                    fontSize = 12.sp
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
