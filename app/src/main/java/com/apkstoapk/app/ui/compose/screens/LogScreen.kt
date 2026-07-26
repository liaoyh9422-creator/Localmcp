package com.apkstoapk.app.ui.compose.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apkstoapk.app.R
import com.apkstoapk.app.ui.compose.components.SecondaryButton
import com.apkstoapk.app.ui.compose.components.ScreenTitle
import com.apkstoapk.app.ui.compose.theme.MdLogBg
import com.apkstoapk.app.ui.compose.theme.MdStroke
import com.apkstoapk.app.ui.compose.theme.MdTextSecondary

@Composable
fun LogScreen(
    logText: String,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scroll = rememberScrollState()
    LaunchedEffect(logText) {
        scroll.animateScrollTo(scroll.maxValue)
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ScreenTitle(title = stringResource(R.string.tab_log), modifier = Modifier.weight(1f))
            SecondaryButton(text = stringResource(R.string.clear_log), onClick = onClear)
        }
        Spacer(Modifier.height(14.dp))
        Text(
            text = logText.ifBlank { stringResource(R.string.log_empty) },
            color = MdTextSecondary,
            fontSize = 12.sp,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(16.dp))
                .background(MdLogBg)
                .border(1.dp, MdStroke, RoundedCornerShape(16.dp))
                .verticalScroll(scroll)
                .padding(12.dp)
        )
    }
}
