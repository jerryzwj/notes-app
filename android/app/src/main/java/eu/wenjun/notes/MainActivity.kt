package eu.wenjun.notes

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import java.io.File

/**
 * 记事本 Android 壳：WebView 加载 https://note.wenjun.eu.org
 * 网页端本身就是完整应用（PWA），这里只补三件事：
 *  1. 返回键先交给网页（关编辑器、清搜索），再退回/挂后台
 *  2. 导出 JSON 时写进系统下载目录
 *  3. 断网时显示本地错误页
 */
class MainActivity : Activity() {

    private lateinit var webView: WebView
    private val startUrl = "https://note.wenjun.eu.org/"

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this)
        webView.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        webView.setBackgroundColor(Color.parseColor("#f6f1e6"))
        setContentView(webView)

        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = true
            cacheMode = WebSettings.LOAD_DEFAULT
            loadWithOverviewMode = true
            useWideViewPort = true
        }

        webView.addJavascriptInterface(Bridge(), "AndroidBridge")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url: Uri = request.url
                return if (url.host == "note.wenjun.eu.org") {
                    false
                } else {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, url))
                    } catch (e: Exception) {
                        // 没有浏览器可用就忽略
                    }
                    true
                }
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    view.loadUrl("file:///android_asset/offline.html")
                }
            }
        }

        if (savedInstanceState == null) webView.loadUrl(startUrl) else webView.restoreState(savedInstanceState)
    }

    override fun onBackPressed() {
        webView.evaluateJavascript("(window.__nativeBack && window.__nativeBack()) ? '1' : '0'") { value ->
            if (value == "\"1\"") return@evaluateJavascript
            if (webView.canGoBack()) webView.goBack() else moveTaskToBack(true)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }

    /** 供网页调用：把文本写进“下载”目录 */
    inner class Bridge {
        @JavascriptInterface
        fun saveFile(name: String, content: String): Boolean {
            return try {
                val safeName = name.replace(Regex("[/\\\\:*?\"<>|]"), "_").ifBlank { "notes.json" }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, safeName)
                        put(MediaStore.Downloads.MIME_TYPE, "application/json")
                        put(MediaStore.Downloads.IS_PENDING, 1)
                    }
                    val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                        ?: return false
                    contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray()) }
                    values.clear()
                    values.put(MediaStore.Downloads.IS_PENDING, 0)
                    contentResolver.update(uri, values, null, null)
                } else {
                    val dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: filesDir
                    File(dir, safeName).writeText(content)
                }
                runOnUiThread { Toast.makeText(this@MainActivity, "已保存到下载目录", Toast.LENGTH_SHORT).show() }
                true
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this@MainActivity, "保存失败：${e.message}", Toast.LENGTH_LONG).show() }
                false
            }
        }
    }
}
