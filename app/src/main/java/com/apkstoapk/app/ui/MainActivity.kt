package com.apkstoapk.app.ui

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.apkstoapk.app.R
import com.apkstoapk.app.mcp.McpBootstrap
import com.apkstoapk.app.mcp.McpService
import com.apkstoapk.app.ui.compose.AppRoot
import com.apkstoapk.app.ui.compose.AppRoute
import com.apkstoapk.app.util.SimpleApkLogger
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Compose host: bottom tabs + tool workflows. No layout XML.
 */
class MainActivity : AppCompatActivity() {
    private val executor = Executors.newSingleThreadExecutor()
    private val sharedLogger = SimpleApkLogger()

    private lateinit var mcpBootstrap: McpBootstrap

    private var lastOutput by mutableStateOf<File?>(null)
    private var mergeRunning by mutableStateOf(false)
    private var openMergeSignal by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.app_name)

        McpService.setUiLogger(sharedLogger)
        mcpBootstrap = McpBootstrap(this)
        mcpBootstrap.onCreate()

        setContent {
            AppRoot(
                activity = this,
                logger = sharedLogger,
                executor = executor,
                mcpBootstrap = mcpBootstrap,
                lastOutput = lastOutput,
                onLastOutputChange = { lastOutput = it },
                mergeRunning = mergeRunning,
                onMergeRunningChange = { mergeRunning = it },
                initialRoute = AppRoute.Home,
                openMergeSignal = openMergeSignal
            )
        }

        maybeAutoStartMcp()
        handleIntent(intent)
    }

    private fun maybeAutoStartMcp() {
        if (!McpService.isAutoStartEnabled(this)) return
        if (McpService.isRunning()) return
        val port = McpService.getPreferredPort(this)
        sharedLogger.bi("MCP 自动启动", "MCP auto-start", "port $port")
        mcpBootstrap.startMcpService(port, true)
    }

    override fun onResume() {
        super.onResume()
        mcpBootstrap.onResume()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        mcpBootstrap.onStoragePermissionResult(requestCode, grantResults)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        val action = intent.action
        var uri: Uri? = null
        when (action) {
            Intent.ACTION_VIEW, Intent.ACTION_SEND -> {
                uri = intent.data
                if (uri == null) {
                    uri = intent.parcelableExtra(Intent.EXTRA_STREAM)
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                var list = intent.parcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                val clip: ClipData? = intent.clipData
                if ((list == null || list.isEmpty()) && clip != null) {
                    list = ArrayList()
                    for (i in 0 until clip.itemCount) {
                        clip.getItemAt(i).uri?.let { list!!.add(it) }
                    }
                }
                if (!list.isNullOrEmpty()) {
                    openMergeSignal++
                    Toast.makeText(this, R.string.pick_apk_first, Toast.LENGTH_SHORT).show()
                    return
                }
            }
        }
        if (uri != null) {
            val name = uri.lastPathSegment
            openMergeSignal++
            if (name != null && name.lowercase(Locale.US).endsWith(".so")) {
                Toast.makeText(this, R.string.pick_apk_first, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private inline fun <reified T : Parcelable> Intent.parcelableExtra(key: String): T? {
        return if (Build.VERSION.SDK_INT >= 33) {
            getParcelableExtra(key, T::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(key) as? T
        }
    }

    private inline fun <reified T : Parcelable> Intent.parcelableArrayListExtra(
        key: String
    ): ArrayList<T>? {
        return if (Build.VERSION.SDK_INT >= 33) {
            getParcelableArrayListExtra(key, T::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableArrayListExtra(key)
        }
    }

    override fun onDestroy() {
        mcpBootstrap.onDestroy()
        super.onDestroy()
        executor.shutdownNow()
    }
}
