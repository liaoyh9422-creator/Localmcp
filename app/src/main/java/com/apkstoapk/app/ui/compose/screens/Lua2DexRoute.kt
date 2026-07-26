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
import com.apkstoapk.app.runtime.GgLuajTarget
import com.apkstoapk.app.runtime.LuaToDexRuntime
import com.apkstoapk.app.util.IoUtils
import com.apkstoapk.app.util.SimpleApkLogger
import java.io.File
import java.util.Locale
import java.util.concurrent.ExecutorService

@Composable
fun Lua2DexRoute(
    activity: Activity,
    logger: SimpleApkLogger,
    executor: ExecutorService,
    target: GgLuajTarget = GgLuajTarget.STOCK,
    onBack: () -> Unit
) {
    var inputUri by remember { mutableStateOf<Uri?>(null) }
    var fileName by remember { mutableStateOf(activity.getString(R.string.lua2dex_no_file)) }
    var resultText by remember { mutableStateOf(activity.getString(R.string.lua2dex_no_result)) }
    var obfuscate by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }
    var lastDex by remember { mutableStateOf<File?>(null) }
    val targetLabel = if (target == GgLuajTarget.MODDED_GG)
        activity.getString(R.string.lua2dex_target_modded)
    else activity.getString(R.string.lua2dex_target_stock)
    val screenTitle = if (target == GgLuajTarget.MODDED_GG)
        activity.getString(R.string.tool_lua2dex_modded_title)
    else activity.getString(R.string.tool_lua2dex_title)

    fun displayName(uri: Uri?): String {
        if (uri == null) return activity.getString(R.string.lua2dex_no_file)
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

    fun sanitizeFileName(name: String?): String {
        if (name.isNullOrBlank()) return "chunk.lua"
        var n = name.trim()
        val slash = maxOf(n.lastIndexOf('/'), n.lastIndexOf(':'))
        if (slash >= 0 && slash + 1 < n.length) n = n.substring(slash + 1)
        n = n.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        return if (n.isEmpty()) "chunk.lua" else n
    }

    fun stripExt(name: String): String {
        val i = name.lastIndexOf('.')
        return if (i > 0) name.substring(0, i) else name
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
            lastDex = null
            resultText = activity.getString(R.string.lua2dex_no_result)
        }
    }
    val createDoc = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        val dex = lastDex
        if (uri == null || dex == null || !dex.exists()) return@rememberLauncherForActivityResult
        executor.execute {
            try {
                activity.contentResolver.openOutputStream(uri)?.use { os ->
                    IoUtils.copy(dex, os)
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

    val hasInput = inputUri != null
    val hasResult = lastDex != null && lastDex!!.exists()
    val step = when {
        running -> 3
        hasResult -> 4
        hasInput -> 2
        else -> 1
    }
    val stepLabel = when {
        running -> "3 编译中…"
        hasResult -> "4 导出 DEX"
        hasInput -> "2 确认混淆 · 然后编译"
        else -> "1 选择 Lua 文件"
    }

    Lua2DexScreen(
        title = screenTitle,
        targetLabel = targetLabel,
        step = step,
        stepLabel = stepLabel,
        fileName = fileName,
        resultText = resultText,
        obfuscate = obfuscate,
        running = running,
        canCompile = hasInput,
        canExport = hasResult,
        onBack = onBack,
        onPick = {
            openDoc.launch(arrayOf("text/x-lua", "text/plain", "application/octet-stream", "*/*"))
        },
        onObfuscateChange = { obfuscate = it },
        onCompile = {
            val uri = inputUri
            if (uri == null) {
                Toast.makeText(activity, R.string.lua2dex_pick_first, Toast.LENGTH_SHORT).show()
                return@Lua2DexScreen
            }
            running = true
            resultText = activity.getString(R.string.lua2dex_running)
            logger.clear()
            val display = displayName(uri)
            val doObf = obfuscate
            executor.execute {
                var workDir: File? = null
                try {
                    logger.stage("Lua→DEX 编译", "Lua to DEX compile")
                    logger.bi("输入", "Input", display)
                    logger.bi("混淆", "Obfuscate", doObf.toString())
                    logger.bi("目标", "Target", target.name)
                    workDir = File(activity.cacheDir, "lua2dex_ui_" + System.currentTimeMillis())
                    if (!workDir!!.mkdirs() && !workDir!!.isDirectory) {
                        throw IllegalStateException("无法创建临时目录: $workDir")
                    }
                    var safeName = sanitizeFileName(display)
                    if (!safeName.lowercase(Locale.US).endsWith(".lua")) safeName += ".lua"
                    val luaFile = File(workDir, safeName)
                    IoUtils.copy(uri, activity, luaFile)
                    logger.ok("已复制输入", "Copied input", luaFile.absolutePath)
                    val outDir = File(activity.getExternalFilesDir(null), "lua_plugins")
                    if (!outDir.exists() && !outDir.mkdirs()) {
                        throw IllegalStateException("无法创建输出目录: $outDir")
                    }
                    val base = stripExt(safeName)
                    val suffix = buildString {
                        if (target == GgLuajTarget.MODDED_GG) append("_gg")
                        if (doObf) append("_obf")
                    }
                    val outDex = File(outDir, base + suffix + ".dex")
                    logger.bi("输出目标", "Output target", outDex.absolutePath)
                    val result = LuaToDexRuntime.compileLuaToDex(activity, luaFile, doObf, outDex, target)
                    if (!result.log.isNullOrEmpty()) {
                        result.log.split('\n').forEach { line ->
                            if (line.isNotEmpty()) logger.item("编译", "Compile", line)
                        }
                    }
                    if (!result.ok || result.dexPath == null) {
                        throw IllegalStateException(
                            if (result.log.isNullOrBlank()) "Lua→DEX 编译失败" else result.log.trim()
                        )
                    }
                    val dexFile = File(result.dexPath)
                    if (!dexFile.isFile) throw IllegalStateException("dex 未生成: ${result.dexPath}")
                    val classCount = result.classNames?.size ?: 0
                    val primary = result.classNames?.firstOrNull()
                    result.classNames?.forEach { logger.item("类", "Class", it) }
                    activity.runOnUiThread {
                        lastDex = dexFile
                        running = false
                        resultText = buildString {
                            append(activity.getString(R.string.lua2dex_result_ok)).append('\n')
                            append("dex: ").append(dexFile.absolutePath).append('\n')
                            append("size: ").append(dexFile.length()).append(" bytes\n")
                            append("classes: ").append(classCount).append('\n')
                            if (primary != null) append("primary: ").append(primary).append('\n')
                            append("target: ").append(target.name).append('\n')
                            append("obfuscate: ").append(doObf).append('\n')
                            append("elapsed: ").append(result.elapsedMs).append(" ms")
                        }
                        Toast.makeText(
                            activity,
                            activity.getString(R.string.lua2dex_ok, result.elapsedMs),
                            Toast.LENGTH_SHORT
                        ).show()
                        logger.blank()
                        logger.ok("最终 dex", "Final dex", dexFile.absolutePath)
                        logger.bi("总耗时", "Elapsed", result.elapsedMs.toString() + " ms")
                    }
                } catch (e: Exception) {
                    activity.runOnUiThread {
                        lastDex = null
                        running = false
                        resultText = activity.getString(R.string.lua2dex_no_result)
                        val msg = e.message ?: e.toString()
                        logger.err("执行失败", "Run failed", msg)
                        logger.logError(msg, e)
                        AlertDialog.Builder(activity)
                            .setTitle(R.string.error)
                            .setMessage(msg)
                            .setPositiveButton(android.R.string.ok, null)
                            .show()
                    }
                } finally {
                    workDir?.let { IoUtils.deleteRecursively(it) }
                }
            }
        },
        onExport = {
            val uri = inputUri
            val base = stripExt(sanitizeFileName(displayName(uri)))
            createDoc.launch(base + buildString {
                if (target == GgLuajTarget.MODDED_GG) append("_gg")
                if (obfuscate) append("_obf")
            } + ".dex")
        }
    )
}
