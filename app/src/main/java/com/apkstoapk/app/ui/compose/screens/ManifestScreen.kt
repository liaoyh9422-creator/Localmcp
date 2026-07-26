package com.apkstoapk.app.ui.compose.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apkstoapk.app.R
import com.apkstoapk.app.ui.compose.components.PrimaryButton
import com.apkstoapk.app.ui.compose.components.ScreenScaffold
import com.apkstoapk.app.ui.compose.components.SecondaryButton
import com.apkstoapk.app.ui.compose.components.StepBar
import com.apkstoapk.app.ui.compose.theme.MdError
import com.apkstoapk.app.ui.compose.theme.MdLogBg
import com.apkstoapk.app.ui.compose.theme.MdStroke
import com.apkstoapk.app.ui.compose.theme.MdSuccess
import com.apkstoapk.app.ui.compose.theme.MdTextMuted
import com.apkstoapk.app.ui.compose.theme.MdTextPrimary

@Composable
fun ManifestScreen(
    step: Int,
    stepLabel: String,
    fileName: String,
    xmlText: String,
    onXmlChange: (String) -> Unit,
    validateText: String,
    validateOk: Boolean?,
    cursorText: String,
    searchText: String,
    onSearchChange: (String) -> Unit,
    gotoText: String,
    onGotoChange: (String) -> Unit,
    running: Boolean,
    canReload: Boolean,
    canSave: Boolean,
    canExport: Boolean,
    onBack: () -> Unit,
    onPick: () -> Unit,
    onReload: () -> Unit,
    onValidate: () -> Unit,
    onSave: () -> Unit,
    onExport: () -> Unit,
    onFindNext: () -> Unit,
    onGoto: () -> Unit,
    modifier: Modifier = Modifier
) {
    val validateColor = when (validateOk) {
        true -> MdSuccess
        false -> MdError
        null -> MdTextMuted
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        ScreenScaffold(title = stringResource(R.string.tool_manifest_title), onBack = onBack) {
            StepBar(current = step)
            Spacer(Modifier.height(8.dp))
            Text(stepLabel, color = MdTextMuted, fontSize = 13.sp)

            Spacer(Modifier.height(12.dp))
            Text(fileName, color = MdTextPrimary, fontSize = 13.sp)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                PrimaryButton(
                    text = stringResource(R.string.manifest_pick),
                    onClick = onPick,
                    enabled = !running,
                    modifier = Modifier.weight(1f)
                )
                SecondaryButton(
                    text = stringResource(R.string.manifest_reload),
                    onClick = onReload,
                    enabled = canReload && !running,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = searchText,
                    onValueChange = onSearchChange,
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    label = { Text(stringResource(R.string.manifest_search)) }
                )
                SecondaryButton(
                    text = stringResource(R.string.manifest_find_next),
                    onClick = onFindNext,
                    enabled = !running
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = gotoText,
                    onValueChange = onGotoChange,
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    label = { Text(stringResource(R.string.manifest_goto_line)) }
                )
                SecondaryButton(
                    text = stringResource(R.string.manifest_goto),
                    onClick = onGoto,
                    enabled = !running
                )
            }

            Spacer(Modifier.height(10.dp))
            if (running) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
            }
            Text(cursorText, color = MdTextMuted, fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
            TextField(
                value = xmlText,
                onValueChange = onXmlChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(360.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .border(1.dp, MdStroke, RoundedCornerShape(14.dp)),
                textStyle = TextStyle(
                    color = MdTextPrimary,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 16.sp
                ),
                placeholder = {
                    Text(stringResource(R.string.manifest_editor_hint), color = MdTextMuted)
                },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MdLogBg,
                    unfocusedContainerColor = MdLogBg,
                    disabledContainerColor = MdLogBg,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent
                ),
                enabled = !running
            )

            Spacer(Modifier.height(10.dp))
            Text(validateText, color = validateColor, fontSize = 12.sp, lineHeight = 16.sp)

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                SecondaryButton(
                    text = stringResource(R.string.manifest_validate),
                    onClick = onValidate,
                    enabled = xmlText.isNotEmpty() && !running,
                    modifier = Modifier.weight(1f)
                )
                SecondaryButton(
                    text = stringResource(R.string.manifest_export_xml),
                    onClick = onExport,
                    enabled = canExport && !running,
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(8.dp))
            PrimaryButton(
                text = stringResource(R.string.manifest_save_apk),
                onClick = onSave,
                enabled = canSave && !running,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}
