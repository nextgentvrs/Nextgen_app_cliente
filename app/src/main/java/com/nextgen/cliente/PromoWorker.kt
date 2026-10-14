package com.nextgen.cliente

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object Util {
    fun agoraIso(): String {
        val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        f.timeZone = TimeZone.getTimeZone("UTC")
        return f.format(Date())
    }
}

/** Roda a cada 15 min: confere promoção nova, mensagem pessoal nova e aviso geral novo. */
class PromoWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    private data class Promo(val id: String, val titulo: String, val premio: String)

    private val base =
        "https://firestore.googleapis.com/v1/projects/${Config.PROJETO}/databases/(default)/documents"

    private fun prefs() = applicationContext.getSharedPreferences("promo", Context.MODE_PRIVATE)

    override fun doWork(): Result {
        try { checarPromocao() } catch (e: Exception) { }
        try { checarMensagens() } catch (e: Exception) { }
        try { checarAvisoGeral() } catch (e: Exception) { }
        return Result.success()
    }

    // ---------- HTTP ----------
    private fun requisicao(url: String, corpo: String? = null): String? {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 15000
        if (corpo != null) {
            c.requestMethod = "POST"
            c.setRequestProperty("Content-Type", "application/json")
            c.doOutput = true
            c.outputStream.use { it.write(corpo.toByteArray()) }
        }
        if (c.responseCode != 200) return null
        return c.inputStream.bufferedReader().use { it.readText() }
    }

    private fun str(f: JSONObject, k: String): String =
        f.optJSONObject(k)?.optString("stringValue", "") ?: ""

    // ---------- 1) promoções ----------
    private fun checarPromocao() {
        val corpo = """{"structuredQuery":{"from":[{"collectionId":"promocoes"}],""" +
            """"where":{"fieldFilter":{"field":{"fieldPath":"status"},"op":"EQUAL","value":{"stringValue":"ativa"}}},"limit":10}}"""
        val texto = requisicao("$base:runQuery?key=${Config.API_KEY}", corpo) ?: return

        val hoje = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val arr = JSONArray(texto)
        var melhor: Promo? = null
        var melhorTs = ""
        for (i in 0 until arr.length()) {
            val d = arr.getJSONObject(i).optJSONObject("document") ?: continue
            val f = d.optJSONObject("fields") ?: continue
            val fim = str(f, "dataFim")
            if (fim.isNotEmpty() && fim < hoje) continue
            val id = d.getString("name").substringAfterLast("/")
            val ts = f.optJSONObject("criadaEm")?.optString("timestampValue", "") ?: ""
            if (melhor == null || ts > melhorTs) {
                melhor = Promo(id, str(f, "titulo"), str(f, "premio"))
                melhorTs = ts
            }
        }
        val promo = melhor ?: return
        if (prefs().getString("ultima", "") != promo.id) {
            notificar("promocoes", "Promoções", 1001,
                "🎁 Nova promoção: ${promo.titulo}",
                "Concorra a ${promo.premio}. Toque para participar!")
            prefs().edit().putString("ultima", promo.id).apply()
        }
    }

    // ---------- 2) mensagens individuais ----------
    private fun checarMensagens() {
        val p = prefs()
        val cliente = p.getString("cliente", "") ?: ""
        if (!cliente.matches(Regex("\\d{8,15}"))) return
        val vista = p.getString("msg_vista_ate", "") ?: ""
        if (vista.isEmpty()) return

        val url = "$base/clientes/$cliente/mensagens_app?pageSize=100" +
            "&mask.fieldPaths=data&mask.fieldPaths=titulo&mask.fieldPaths=mensagem" +
            "&key=${Config.API_KEY}"
        val texto = requisicao(url) ?: return
        val docs = JSONObject(texto).optJSONArray("documents") ?: return

        var novas = 0
        var maisNova = vista
        var tituloUlt = ""
        var textoUlt = ""
        for (i in 0 until docs.length()) {
            val f = docs.getJSONObject(i).optJSONObject("fields") ?: continue
            val data = str(f, "data")
            if (data > vista) {
                novas++
                if (data > maisNova) {
                    maisNova = data
                    tituloUlt = str(f, "titulo")
                    textoUlt = str(f, "mensagem")
                }
            }
        }
        if (novas == 0) return
        if (novas == 1) {
            notificar("avisos", "Mensagens e avisos", 1002,
                "💬 " + tituloUlt.ifEmpty { "Mensagem do Suporte" }, textoUlt)
        } else {
            notificar("avisos", "Mensagens e avisos", 1002,
                "💬 Você tem $novas novas mensagens", "Toque para ler no app.")
        }
        p.edit().putString("msg_vista_ate", maisNova).apply()
    }

    // ---------- 3) aviso geral ----------
    private fun checarAvisoGeral() {
        val url = "$base/configuracoes/aviso_player" +
            "?mask.fieldPaths=ativo&mask.fieldPaths=titulo&mask.fieldPaths=mensagem" +
            "&mask.fieldPaths=atualizadoEm&key=${Config.API_KEY}"
        val texto = requisicao(url) ?: return
        val f = JSONObject(texto).optJSONObject("fields") ?: return
        val atual = str(f, "atualizadoEm")
        if (atual.isEmpty()) return

        val p = prefs()
        val visto = p.getString("geral_vista", "") ?: ""
        if (visto.isEmpty()) {              // primeira vez: só marca, não notifica o que já existia
            p.edit().putString("geral_vista", atual).apply()
            return
        }
        if (atual <= visto) return

        val ativo = f.optJSONObject("ativo")?.optBoolean("booleanValue", false) ?: false
        val mensagem = str(f, "mensagem")
        if (ativo && mensagem.isNotEmpty()) {
            notificar("avisos", "Mensagens e avisos", 1003,
                "📢 " + str(f, "titulo").ifEmpty { "Aviso Importante" }, mensagem)
        }
        p.edit().putString("geral_vista", atual).apply()
    }

    // ---------- notificação ----------
    private fun notificar(canal: String, nomeCanal: String, id: Int, titulo: String, texto: String) {
        val ctx = applicationContext
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return

        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(canal, nomeCanal, NotificationManager.IMPORTANCE_HIGH)
            )
        }
        val intent = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            ctx, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(ctx, canal)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(titulo)
            .setContentText(texto)
            .setStyle(NotificationCompat.BigTextStyle().bigText(texto))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        nm.notify(id, n)
    }
}
