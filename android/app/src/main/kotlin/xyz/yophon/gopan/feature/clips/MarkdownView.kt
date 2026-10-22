package xyz.yophon.gopan.feature.clips

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import xyz.yophon.gopan.R
import xyz.yophon.gopan.core.data.markdown.MarkdownLinkRoute
import xyz.yophon.gopan.core.data.markdown.MarkdownLinks
import xyz.yophon.gopan.core.ui.components.ErrorState

/**
 * 服务端渲染好的 Markdown 页(/md/{id},doc/23 五.2)放进 WebView:关掉 JavaScript、不碰本地文件与 content://。
 * 点链接:本站的 /md/ 地址(那篇里链到的另一篇、相对路径的文件)留在里面,换上新 token 再开,返回键回上一篇;
 * 别的网址交给浏览器;file: / javascript: 之类什么都不做。页面开着久了 token 过期,点到的链接与重试都会换新的。
 *
 * [url] 为 null 表示地址还在拼(显示进度条);变了(比如深浅主题变了)整页重新打开。
 */
@Composable
fun MarkdownView(
    url: String?,
    baseUrl: String,
    retoken: suspend (String) -> String,
    modifier: Modifier = Modifier,
    background: Color = MaterialTheme.colorScheme.background,
) {
    val scope = rememberCoroutineScope()
    val currentRetoken by rememberUpdatedState(retoken)
    val fontScale = LocalDensity.current.fontScale
    val holder = remember { WebHolder() }
    // 站内点过去之前的那几页:返回键回去(换新 token 重新打开,不用 WebView 自己的历史,免得带着过期的 token)
    val history = remember { mutableStateListOf<String>() }
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }

    fun load(target: String, fresh: Boolean) {
        val view = holder.view ?: return
        scope.launch {
            val next = if (fresh) runCatching { currentRetoken(target) }.getOrDefault(target) else target
            holder.current = next
            holder.retried401 = false
            view.loadUrl(next)
        }
    }

    BackHandler(enabled = history.isNotEmpty()) {
        load(history.removeAt(history.lastIndex), fresh = true)
    }

    Box(modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize().testTag("markdown_view"),
            factory = { context ->
                WebView(context).apply {
                    setBackgroundColor(background.toArgb())
                    settings.apply {
                        javaScriptEnabled = false
                        javaScriptCanOpenWindowsAutomatically = false
                        allowFileAccess = false
                        allowContentAccess = false
                        domStorageEnabled = false
                        setGeolocationEnabled(false)
                        // 不开新窗口:target=_blank 的外链也走 shouldOverrideUrlLoading
                        setSupportMultipleWindows(false)
                        mediaPlaybackRequiresUserGesture = true
                        builtInZoomControls = true
                        displayZoomControls = false
                        cacheMode = WebSettings.LOAD_DEFAULT
                        textZoom = (fontScale * 100).toInt()
                    }
                    webViewClient = MarkdownClient(
                        baseUrl = baseUrl,
                        scope = scope,
                        holder = holder,
                        onInside = { target ->
                            holder.current?.let { history.add(it) }
                            load(target, fresh = true)
                        },
                        onState = { isLoading, isFailed ->
                            loading = isLoading
                            failed = isFailed
                        },
                        retoken = { currentRetoken(it) },
                    )
                    holder.view = this
                }
            },
            update = { view ->
                if (url != null && url != holder.root) {
                    holder.root = url
                    holder.current = url
                    holder.retried401 = false
                    history.clear()
                    view.loadUrl(url)
                }
            },
            onRelease = { view ->
                holder.view = null
                view.stopLoading()
                view.destroy()
            },
        )
        if (url == null || loading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter))
        }
        if (failed) {
            ErrorState(
                message = stringResource(R.string.md_load_failed),
                // 盖住 WebView 自己的「网页无法打开」
                modifier = Modifier.fillMaxSize().background(background),
                onRetry = { holder.current?.let { load(it, fresh = true) } },
            )
        }
    }
}

/** WebView 与它当前打开的地址(Compose 状态之外,给 WebViewClient 与重试用) */
private class WebHolder {
    var view: WebView? = null

    /** 最初打开的那篇(参数里的地址) */
    var root: String? = null

    /** 正在看的那篇 */
    var current: String? = null

    /** 这一页已经因为 401(token 过期)换过一次 token 了 */
    var retried401 = false

    /** 正在打开的这一页没加载出来(断网等;onPageFinished 在 onReceivedError 之后还会来) */
    var failed = false
}

private class MarkdownClient(
    private val baseUrl: String,
    private val scope: CoroutineScope,
    private val holder: WebHolder,
    private val onInside: (String) -> Unit,
    private val onState: (loading: Boolean, failed: Boolean) -> Unit,
    private val retoken: suspend (String) -> String,
) : WebViewClient() {

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        if (!request.isForMainFrame) return false
        val target = request.url.toString()
        when (MarkdownLinks.route(target, baseUrl)) {
            // 站内的跳转(/md/{id}/f?p=b.md → /md/{b}):照常跟过去,地址里是刚换的 token,也不算一次「点过去」
            MarkdownLinkRoute.INSIDE -> if (request.isRedirect) return false else onInside(target)
            MarkdownLinkRoute.EXTERNAL -> openExternal(view.context, target)
            MarkdownLinkRoute.BLOCK -> Unit
        }
        return true
    }

    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
        holder.failed = false
        onState(true, false)
    }

    override fun onPageFinished(view: WebView, url: String?) {
        onState(false, holder.failed)
    }

    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
        if (!request.isForMainFrame) return
        holder.failed = true
        onState(false, true)
    }

    /** 正文页 401:token 刚好过期,换一个再开一次(只试一次;还不行就留着服务端那页「登录已过期」) */
    override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
        if (!request.isForMainFrame || errorResponse.statusCode != 401 || holder.retried401) return
        val current = holder.current ?: return
        holder.retried401 = true
        scope.launch {
            val fresh = runCatching { retoken(current) }.getOrNull() ?: return@launch
            holder.current = fresh
            view.loadUrl(fresh)
        }
    }
}

/** 交给系统打开(浏览器、邮件、拨号);没有能接的应用时提示一下 */
private fun openExternal(context: Context, url: String) {
    val intent = Intent(Intent.ACTION_VIEW, url.toUri()).addCategory(Intent.CATEGORY_BROWSABLE)
    if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, R.string.clips_msg_no_app, Toast.LENGTH_SHORT).show()
    }
}
