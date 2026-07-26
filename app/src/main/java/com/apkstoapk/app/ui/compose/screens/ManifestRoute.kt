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
import com.apkstoapk.app.core.ApkModuleIO
import com.apkstoapk.app.core.InputApkResolver
import com.apkstoapk.app.core.ManifestXmlOps
import com.apkstoapk.app.core.SignOps
import com.apkstoapk.app.ui.editor.ManifestXmlValidator
import com.apkstoapk.app.util.IoUtils
import com.apkstoapk.app.util.SimpleApkLogger
import com.reandroid.apk.ApkModule
import java.io.File
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService

@Composable
fun ManifestRoute(
    activity: Activity,
    logger: SimpleApkLogger,
    executor: ExecutorService,
    onLastOutputChange: (File?) -> Unit,
    onBack: () -> Unit
) {
    var inputUri by remember { mutableStateOf<Uri?>(null) }
    var fileName by remember { mutableStateOf(activity.getString(R.string.manifest_no_file)) }
    var xmlText by remember { mutableStateOf("") }
    var dirty by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }
    var workApk by remember { mutableStateOf<File?>(null) }
    var validateText by remember { mutableStateOf("") }
    var validateOk by remember { mutableStateOf<Boolean?>(null) }
    var searchText by remember { mutableStateOf("") }
    var gotoText by remember { mutableStateOf("") }
    var searchFrom by remember { mutableStateOf(0) }

    fun displayName(uri: Uri?): String {
        if (uri == null) return activity.getString(R.string.manifest_no_file)
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

    fun cursorText(): String {
        // simplified: no selection tracking yet
        return if (dirty) "已修改" else "就绪"
    }

    fun showValidate(vr: ManifestXmlValidator.Result, toastOk: Boolean) {
        if (vr.ok) {
            validateOk = true
            validateText = vr.message
            if (toastOk) Toast.makeText(activity, vr.message, Toast.LENGTH_SHORT).show()
        } else {
            validateOk = false
            var msg = "错误"
            if (vr.line > 0) msg += " L${vr.line}"
            if (vr.column > 0) msg += ":C${vr.column}"
            msg += " · ${vr.message}"
            validateText = msg
            if (toastOk) Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
        }
    }

    fun loadManifest(uri: Uri) {
        running = true
        validateText = activity.getString(R.string.manifest_loading)
        validateOk = null
        logger.clear()
        logger.stage("清单编辑", "Manifest editor")
        val display = displayName(uri)
        logger.bi("输入", "Input", display)
        executor.execute {
            var workDir: File? = null
            try {
                workDir = File(activity.cacheDir, "manifest_editor_" + System.currentTimeMillis())
                if (!workDir!!.mkdirs() && !workDir!!.isDirectory) {
                    throw java.io.IOException("无法创建工作目录: $workDir")
                }
                val resolved = InputApkResolver.resolve(activity, uri, display, workDir, logger)
                val apk = resolved.apkFile
                val xml: String
                ApkModuleIO.load(apk, logger).use { module: ApkModule ->
                    xml = ManifestXmlOps.toXml(module, logger) ?: ""
                }
                activity.runOnUiThread {
                    workApk = apk
                    xmlText = xml
                    dirty = false
                    searchFrom = 0
                    fileName = resolved.sourceLabel
                    running = false
                    val vr = ManifestXmlValidator.validate(xml)
                    if (resolved.fromContainer) {
                        showValidate(vr, false)
                        if (vr.ok) {
                            validateOk = null
                            validateText = activity.getString(
                                R.string.manifest_container_hint,
                                resolved.containerEntries.size
                            )
                        }
                        Toast.makeText(
                            activity,
                            activity.getString(
                                R.string.manifest_loaded_container,
                                resolved.containerEntries.size
                            ),
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        showValidate(vr, false)
                        Toast.makeText(activity, R.string.manifest_loaded, Toast.LENGTH_SHORT).show()
                    }
                    logger.ok("清单已加载", "Manifest loaded", xml.length.toString() + " chars")
                }
            } catch (e: Exception) {
                activity.runOnUiThread {
                    running = false
                    workApk = null
                    workDir?.let { IoUtils.deleteRecursively(it) }
                    val msg = e.message ?: e.toString()
                    validateOk = false
                    validateText = msg
                    logger.err("清单编辑失败", "Manifest editor failed", msg)
                    logger.logError(msg, e)
                    AlertDialog.Builder(activity)
                        .setTitle(R.string.error)
                        .setMessage(msg)
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
            }
        }
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
            dirty = false
            loadManifest(uri)
        }
    }
    val createXml = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/xml")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val xml = xmlText
        executor.execute {
            try {
                activity.contentResolver.openOutputStream(uri)?.use { os ->
                    OutputStreamWriter(os, StandardCharsets.UTF_8).use { w ->
                        w.write(xml)
                        w.flush()
                    }
                } ?: throw java.io.IOException("openOutputStream failed")
                activity.runOnUiThread {
                    Toast.makeText(activity, R.string.manifest_export_ok, Toast.LENGTH_SHORT).show()
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

    val hasFile = inputUri != null && workApk != null && workApk!!.isFile
    val hasText = xmlText.isNotEmpty()
    val step = when {
        running -> if (hasText) 4 else 2
        hasText && !dirty && workApk != null -> 4
        hasText && dirty -> 3
        hasText -> 3
        inputUri != null -> 2
        else -> 1
    }
    val stepLabel = when {
        running -> "处理中…"
        hasText && dirty -> "3 编辑中（未保存）"
        hasText && !dirty && workApk != null -> "4 已加载/可保存"
        hasText -> "3 编辑清单"
        inputUri != null -> "2 加载清单"
        else -> "1 选择 APK / APKS / XAPK"
    }

    ManifestScreen(
        step = step,
        stepLabel = stepLabel,
        fileName = fileName,
        xmlText = xmlText,
        onXmlChange = {
            xmlText = it
            dirty = true
        },
        validateText = validateText,
        validateOk = validateOk,
        cursorText = cursorText(),
        searchText = searchText,
        onSearchChange = { searchText = it },
        gotoText = gotoText,
        onGotoChange = { gotoText = it.filter { ch -> ch.isDigit() } },
        running = running,
        canReload = hasFile,
        canSave = hasFile && hasText,
        canExport = hasText,
        onBack = onBack,
        onPick = {
            openDoc.launch(
                arrayOf(
                    "application/vnd.android.package-archive",
                    "application/zip",
                    "application/x-zip-compressed",
                    "application/octet-stream",
                    "application/*",
                    "*/*"
                )
            )
        },
        onReload = { inputUri?.let { loadManifest(it) } },
        onValidate = {
            val vr = ManifestXmlValidator.validate(xmlText)
            showValidate(vr, true)
        },
        onSave = {
            val apk = workApk
            if (apk == null || !apk.isFile) {
                Toast.makeText(activity, R.string.manifest_pick_first, Toast.LENGTH_SHORT).show()
                return@ManifestScreen
            }
            val vr = ManifestXmlValidator.validate(xmlText)
            showValidate(vr, true)
            if (!vr.ok) return@ManifestScreen
            running = true
            logger.stage("写回清单", "Apply manifest")
            val xml = xmlText
            executor.execute {
                val outDir = File(activity.getExternalFilesDir(null), "manifest_out")
                if (!outDir.exists()) outDir.mkdirs()
                val unsigned = File(outDir, "manifest_edit_unsigned.apk")
                val signed = File(outDir, "manifest_edit_signed.apk")
                try {
                    ApkModuleIO.transform(apk, unsigned, { module, log ->
                        ManifestXmlOps.applyXml(module, xml, log)
                    }, logger)
                    SignOps.signDebug(activity, unsigned, signed, logger)
                    IoUtils.copy(signed, apk)
                    unsigned.delete()
                    activity.runOnUiThread {
                        running = false
                        dirty = false
                        onLastOutputChange(signed)
                        validateOk = true
                        validateText = activity.getString(R.string.manifest_save_ok, signed.absolutePath)
                        Toast.makeText(activity, R.string.manifest_saved, Toast.LENGTH_SHORT).show()
                        logger.ok("清单已写回并签名", "Manifest saved+signed", signed.absolutePath)
                    }
                } catch (e: Exception) {
                    activity.runOnUiThread {
                        running = false
                        val msg = e.message ?: e.toString()
                        validateOk = false
                        validateText = msg
                        logger.err("清单编辑失败", "Manifest editor failed", msg)
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
            if (xmlText.isEmpty()) {
                Toast.makeText(activity, R.string.manifest_empty, Toast.LENGTH_SHORT).show()
                return@ManifestScreen
            }
            var name = displayName(inputUri)
            val slash = maxOf(name.lastIndexOf('/'), name.lastIndexOf(':'))
            if (slash >= 0 && slash + 1 < name.length) name = name.substring(slash + 1)
            name = name.replace(Regex("(?i)\\.(apk|apks|xapk|apkm|aspk|zip)$"), "")
            createXml.launch("$name-AndroidManifest.xml")
        },
        onFindNext = {
            val q = searchText
            if (q.isEmpty()) {
                Toast.makeText(activity, R.string.manifest_search_empty, Toast.LENGTH_SHORT).show()
                return@ManifestScreen
            }
            var idx = xmlText.indexOf(q, searchFrom)
            if (idx < 0 && searchFrom > 0) idx = xmlText.indexOf(q, 0)
            if (idx < 0) {
                Toast.makeText(activity, R.string.manifest_search_none, Toast.LENGTH_SHORT).show()
            } else {
                searchFrom = idx + q.length
                Toast.makeText(activity, "找到 @ $idx", Toast.LENGTH_SHORT).show()
            }
        },
        onGoto = {
            val line = gotoText.toIntOrNull()
            if (line == null) {
                Toast.makeText(activity, R.string.manifest_goto_invalid, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(activity, "行 $line", Toast.LENGTH_SHORT).show()
            }
        }
    )
}
