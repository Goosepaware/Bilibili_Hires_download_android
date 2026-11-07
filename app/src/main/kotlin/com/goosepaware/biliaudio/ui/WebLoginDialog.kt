package com.goosepaware.biliaudio.ui

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * App 内网页登录：单机也能登录（B 站密码 / 短信 / 扫码都行）。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebLoginDialog(onDone: (String) -> Unit, onCancel: () -> Unit) {
  val context = LocalContext.current
  var webView by remember { mutableStateOf<WebView?>(null) }
  var title by remember { mutableStateOf("B 站登录") }
  var canGoBack by remember { mutableStateOf(false) }

  BackHandler {
    val wv = webView
    if (wv != null && wv.canGoBack()) wv.goBack() else onCancel()
  }

  Dialog(
    onDismissRequest = onCancel,
    properties = DialogProperties(usePlatformDefaultWidth = false),
  ) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
      Surface(color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.fillMaxWidth().statusBarsPadding()) {
          TextButton(onClick = onCancel) { Text("取消") }
          Text(
            title,
            Modifier.weight(1f).padding(top = 14.dp),
            style = MaterialTheme.typography.titleSmall,
          )
          TextButton(onClick = {
            val cookies = CookieManager.getInstance().getCookie("https://www.bilibili.com").orEmpty()
            onDone(cookies)
          }) { Text("完成登录") }
        }
      }

      AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
          WebView(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(
              ViewGroup.LayoutParams.MATCH_PARENT,
              ViewGroup.LayoutParams.MATCH_PARENT,
            )
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            webViewClient = object : WebViewClient() {
              override fun onPageFinished(view: WebView, url: String) {
                title = view.title?.takeIf { it.isNotBlank() } ?: "B 站登录"
                canGoBack = view.canGoBack()
              }
            }
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            loadUrl("https://passport.bilibili.com/login")
          }.also { webView = it }
        },
      )
    }
  }
}
