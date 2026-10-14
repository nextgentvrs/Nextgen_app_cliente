package com.nextgen.cliente

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var refresh: SwipeRefreshLayout

    // Página hospedada no GitHub Pages
    private val urlApp = "https://nextgentvrs.github.io/Nextgen_cliente/"
    private val hostApp = "nextgentvrs.github.io"

    private val pedirNotificacao = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    /** Ponte entre a página (site) e o app: usada para as notificações. */
    inner class PonteApp {
        @JavascriptInterface
        fun promoVista(id: String) {
            getSharedPreferences("promo", MODE_PRIVATE).edit().putString("ultima", id).apply()
        }

        /** Informa qual cliente está logado (vazio = saiu). */
        @JavascriptInterface
        fun setCliente(tel: String) {
            val p = getSharedPreferences("promo", MODE_PRIVATE)
            val limpo = tel.filter { it.isDigit() }
            if ((p.getString("cliente", "") ?: "") != limpo) {
                p.edit()
                    .putString("cliente", limpo)
                    .putString("msg_vista_ate", if (limpo.isEmpty()) "" else Util.agoraIso())
                    .apply()
            }
        }

        /** O cliente já viu as mensagens até estas datas: não notificar de novo. */
        @JavascriptInterface
        fun visto(msgData: String, geralData: String) {
            val p = getSharedPreferences("promo", MODE_PRIVATE)
            val e = p.edit()
            if (msgData.isNotEmpty() && msgData > (p.getString("msg_vista_ate", "") ?: "")) {
                e.putString("msg_vista_ate", msgData)
            }
            if (geralData.isNotEmpty() && geralData > (p.getString("geral_vista", "") ?: "")) {
                e.putString("geral_vista", geralData)
            }
            e.apply()
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this)
        refresh = SwipeRefreshLayout(this).apply {
            addView(webView)
            setOnRefreshListener { webView.reload() }
        }
        setContentView(refresh)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true      // mantém o login salvo (localStorage)
            cacheMode = WebSettings.LOAD_DEFAULT
        }
        webView.addJavascriptInterface(PonteApp(), "AndroidApp")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView, request: WebResourceRequest
            ): Boolean {
                val url: Uri = request.url
                return if (url.host == hostApp) {
                    false
                } else {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, url))
                    } catch (e: Exception) {
                    }
                    true
                }
            }

            override fun onPageFinished(view: WebView, url: String) {
                refresh.isRefreshing = false
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack() else finish()
            }
        })

        webView.loadUrl(urlApp)

        // permissão para notificações (Android 13+)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            pedirNotificacao.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        // verifica novas promoções a cada 15 min, mesmo com o app fechado
        val restricoes = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val pedido = PeriodicWorkRequestBuilder<PromoWorker>(15, TimeUnit.MINUTES)
            .setConstraints(restricoes)
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "checar_promocoes", ExistingPeriodicWorkPolicy.KEEP, pedido
        )
    }
}
