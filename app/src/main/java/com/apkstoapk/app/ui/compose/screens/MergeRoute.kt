package com.apkstoapk.app.ui.compose.screens

import android.app.Activity
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.apkstoapk.app.R
import com.apkstoapk.app.core.ApksMerger
import com.apkstoapk.app.core.InstallHelper
import com.apkstoapk.app.core.SplitSelector
import com.apkstoapk.app.util.IoUtils
import com.apkstoapk.app.util.SimpleApkLogger
import java.io.File
import java.util.concurrent.ExecutorService

@Composable
fun MergeRoute(
    activity: Activity,
    logger: SimpleApkLogger,
    executor: ExecutorService,
    lastOutput: File?,
    onLastOutputChange: (File?) -> Unit,
    mergeRunning: Boolean,
    onMergeRunningChange: (Boolean) -> Unit,
    onBack: () -> Unit
) {
    var inputUri by remember { mutableStateOf<Uri?>(null) }
    var fileName by remember { mutableStateOf(activity.getString(R.string.no_file)) }
    var soUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var soListText by remember { mutableStateOf(activity.getString(R.string.no_so)) }
    var statusText by remember { mutableStateOf("") }

    fun displayName(uri: Uri?): String {
        if (uri == null) return activity.getString(R.string.no_file)
        var result = uri.lastPathSegment
        try {
            activity.contentResolver.query(uri, null, null, null, null)?.use { c: Cursor ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) result = c.getString(idx)
                }
            }
        } catch (_: Exception) {
        }
        return result ?: uri.toString()
    }

    fun suggestOutputName(uri: Uri?): String {
        val name = displayName(uri)
        if (name.isBlank()) return "merged.apk"
        return name.replace(Regex("(?i)\\.(zip|apks|xapk|apkm|aspk)$"), "") + "-merged.apk"
    }

    fun refreshSoList(list: List<Uri>) {
        if (list.isEmpty()) {
            soListText = activity.getString(R.string.no_so)
            return
        }
        val sb = StringBuilder()
        sb.append(activity.getString(R.string.so_selected, list.size)).append('\n')
        list.forEachIndexed { i, uri ->
            val name = displayName(uri)
            sb.append(i + 1).append(". ").append(name)
                .append(" → lib/arm64-v8a/").append(name).append('\n')
        }
        soListText = sb.toString().trim()
    }

    val openDoc = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                activity.contentResolver.takePersistableUriPermission(
                    uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {
            }
            inputUri = uri
            fileName = displayName(uri)
            onLastOutputChange(null)
            statusText = ""
        }
    }
    val openSo = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNullOrEmpty()) return@rememberLauncherForActivityResult
        val next = ArrayList<Uri>()
        uris.forEach { uri ->
            try {
                activity.contentResolver.takePersistableUriPermission(
                    uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {
            }
            next.add(uri)
        }
        soUris = next
        onLastOutputChange(null)
        refreshSoList(next)
    }
    val createDoc = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.android.package-archive")
    ) { uri ->
        val out = lastOutput
        if (uri == null || out == null || !out.exists()) return@rememberLauncherForActivityResult
        executor.execute {
            try {
                activity.contentResolver.openOutputStream(uri)?.use { os ->
                    IoUtils.copy(out, os)
                } ?: throw java.io.IOException("openOutputStream failed")
                activity.runOnUiThread {
                    Toast.makeText(activity, R.string.saved_ok, Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                activity.runOnUiThread {
                    AlertDialog.Builder(activity)
                        .setTitle(R.string.error)
                        .setMessage(e.message ?: e.toString())
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
            }
        }
    }

    val hasApk = inputUri != null
    val hasSo = soUris.isNotEmpty()
    val hasResult = lastOutput != null && lastOutput.exists()
    val step = when {
        hasResult && !mergeRunning -> 4
        mergeRunning -> 3
        hasApk && hasSo -> 3
        hasApk -> 2
        else -> 1
    }
    val stepLabel = when (step) {
        4 -> "4 导出 / 安装"
        3 -> if (mergeRunning) "3 执行中…" else "3 开始执行"
        2 -> "2 选择 so（可选注入）"
        else -> "1 选择安装包"
    }

    MergeScreen(
        step = step,
        stepLabel = stepLabel,
        fileName = fileName,
        soListText = soListText,
        statusText = statusText,
        running = mergeRunning,
        canPickSo = hasApk,
        canMerge = hasApk && hasSo,
        canExport = hasResult,
        onBack = onBack,
        onPickApk = {
            openDoc.launch(
                arrayOf(
                    "application/zip",
                    "application/octet-stream",
                    "application/vnd.android.package-archive",
                    "application/*",
                    "*/*"
                )
            )
        },
        onPickSo = {
            if (inputUri == null) {
                Toast.makeText(activity, R.string.pick_apk_first, Toast.LENGTH_SHORT).show()
                return@MergeScreen
            }
            openSo.launch(arrayOf("application/octet-stream", "application/x-sharedlib", "*/*"))
        },
        onMerge = {
            val uri = inputUri
            if (uri == null) {
                Toast.makeText(activity, R.string.pick_apk_first, Toast.LENGTH_SHORT).show()
                return@MergeScreen
            }
            if (soUris.isEmpty()) {
                Toast.makeText(activity, R.string.pick_so_first, Toast.LENGTH_SHORT).show()
                return@MergeScreen
            }
            onMergeRunningChange(true)
            statusText = activity.getString(R.string.home_status_running)
            logger.clear()
            val soCopy = ArrayList(soUris)
            executor.execute {
                try {
                    val merger = ApksMerger(activity, logger)
                    val splits = merger.listSplits(uri)
                    if (splits.isEmpty()) {
                        throw IllegalStateException(activity.getString(R.string.no_splits))
                    }
                    logger.stage("分析输入分包", "Analyze input splits")
                    logger.ok("分包数量", "Split count", splits.size.toString())
                    splits.forEach { logger.item("分包", "Split", it) }
                    val exclude = SplitSelector.excludeNotForDevice(activity, splits)
                    if (exclude.isNotEmpty()) {
                        logger.bi("按当前设备自动排除分包", "Auto-exclude splits for device")
                        exclude.forEach { logger.item("排除", "Exclude", it) }
                    }
                    val outDir = File(activity.getExternalFilesDir(null), "output")
                    if (!outDir.exists()) outDir.mkdirs()
                    val opt = ApksMerger.Options().apply {
                        sign = true
                        force = false
                        autoEditManifest = true
                        forceExtractNativeLibsTrue = true
                        patchDex = true
                        outputFile = File(outDir, suggestOutputName(uri))
                        splitsToExclude = exclude
                        soUris = soCopy
                    }
                    logger.bi("输出文件", "Output file", opt.outputFile.absolutePath)
                    logger.bi("注入 .so 数量", "Inject .so count", soCopy.size.toString())
                    val result = merger.mergeUri(uri, opt)
                    activity.runOnUiThread {
                        onLastOutputChange(result.outputApk)
                        onMergeRunningChange(false)
                        statusText = activity.getString(R.string.merge_ok, result.elapsedMs) +
                                "\n" + result.outputApk.absolutePath
                        Toast.makeText(
                            activity,
                            activity.getString(R.string.merge_ok, result.elapsedMs),
                            Toast.LENGTH_SHORT
                        ).show()
                        logger.blank()
                        logger.ok("最终输出", "Final output", result.outputApk.absolutePath)
                        logger.bi("总耗时", "Elapsed", result.elapsedMs.toString() + " ms")
                    }
                } catch (e: Exception) {
                    activity.runOnUiThread {
                        onLastOutputChange(null)
                        onMergeRunningChange(false)
                        val msg = e.message ?: e.toString()
                        statusText = msg
                        logger.err("执行失败", "Run failed", msg)
                        logger.logError(msg, e)
                        AlertDialog.Builder(activity)
                            .setTitle(R.string.error)
                            .setMessage(msg)
                            .setPositiveButton(android.R.string.ok, null)
                            .show()
                    }
                }
            }
        },
        onExport = {
            if (lastOutput != null && lastOutput.exists()) {
                createDoc.launch(suggestOutputName(inputUri))
            }
        },
        onInstall = {
            if (lastOutput != null && lastOutput.exists()) {
                InstallHelper.installApk(activity, lastOutput)
            }
        }
    )
}
