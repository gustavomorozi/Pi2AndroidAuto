package com.morozi.autophone

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import kotlin.concurrent.thread

private const val TAG = "ConfigWeb"

/** Port of the settings page, on every network the Pi is on. */
const val CONFIG_WEB_PORT = 8080

/**
 * A tiny settings page served by the app itself, so the car's details can be
 * set from any phone or PC on the same network, including the Pi's own setup
 * hotspot ([CarWifi.startSetupHotspot]). The app's own screen shows the same
 * page. The stored Wi-Fi password is never sent back out.
 */
class ConfigWebServer(private val context: Context, private val onSaved: () -> Unit) {

    @Volatile private var server: ServerSocket? = null

    fun start() {
        if (server != null) return
        thread(name = "config-web") {
            try {
                val socketServer = ServerSocket(CONFIG_WEB_PORT).also { server = it }
                Log.i(TAG, "Settings page on port $CONFIG_WEB_PORT")
                while (true) {
                    val socket = socketServer.accept()
                    thread(name = "config-web-client") { socket.use { handle(it) } }
                }
            } catch (e: Exception) {
                if (server != null) Log.e(TAG, "Settings page stopped", e)
            }
        }
    }

    fun stop() {
        server?.close()
        server = null
    }

    private fun handle(socket: Socket) {
        val input = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
        val requestLine = input.readLine() ?: return
        var contentLength = 0
        while (true) {
            val line = input.readLine() ?: break
            if (line.isEmpty()) break
            if (line.startsWith("Content-Length:", ignoreCase = true)) {
                contentLength = line.substringAfter(':').trim().toIntOrNull() ?: 0
            }
        }
        val (method, path) = requestLine.split(" ").let { it.getOrElse(0) { "" } to it.getOrElse(1) { "/" } }

        val settings = Settings(context)
        var message = ""
        when {
            method == "POST" && path == "/save" -> {
                val body = CharArray(contentLength).also { input.read(it) }.concatToString()
                apply(settings, parseForm(body))
                message = "Configuração salva."
                onSaved()
            }
            method == "POST" && path == "/hotspot" -> {
                CarWifi.startSetupHotspot(context)
                message = "Modo de configuração: o Pi está criando o próprio Wi‑Fi (veja o nome na tela)."
            }
            path != "/" -> return respond(socket, "404 Not Found", "text/plain", "not found")
        }
        respond(socket, "200 OK", "text/html; charset=utf-8", page(settings, message))
    }

    private fun parseForm(body: String): Map<String, String> =
        body.split("&").filter { it.contains("=") }.associate {
            val (k, v) = it.split("=", limit = 2)
            URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
        }

    private fun apply(settings: Settings, form: Map<String, String>) {
        form["ssid"]?.let { settings.carWifiSsid = it.trim() }
        // Empty password field means "keep the stored one".
        form["password"]?.takeIf { it.isNotEmpty() }?.let { settings.carWifiPassword = it }
        form["mode"]?.takeIf { it == Settings.MODE_LISTEN || it == Settings.MODE_CONNECT }?.let { settings.mode = it }
        form["host"]?.let { settings.carHost = it.trim() }
        form["port"]?.toIntOrNull()?.let { settings.carPort = it }
        form["listen_port"]?.toIntOrNull()?.let { settings.listenPort = it }
        form["max_height"]?.toIntOrNull()?.let { settings.maxVideoHeight = it }
        form["quality"]?.toIntOrNull()?.let { settings.videoQuality = it }
        Log.i(TAG, "Saved: ${settings.describe()}")
    }

    private fun respond(socket: Socket, status: String, type: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val out = socket.getOutputStream()
        out.write("HTTP/1.1 $status\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
        out.write(bytes)
        out.flush()
    }

    private fun page(s: Settings, message: String): String {
        fun esc(v: String) = v.replace("&", "&amp;").replace("<", "&lt;").replace("\"", "&quot;")
        fun sel(cond: Boolean) = if (cond) " selected" else ""
        return """
<!doctype html><html lang="pt-BR"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Pi2AndroidAuto</title>
<style>
body{font-family:sans-serif;max-width:480px;margin:16px auto;padding:0 16px;background:#10203f;color:#fff}
label{display:block;margin-top:12px}input,select,button{width:100%;padding:10px;font-size:16px;box-sizing:border-box;margin-top:4px}
button{background:#ff8c00;border:0;color:#fff;margin-top:20px}.msg{background:#1e3a6e;padding:10px;margin-top:12px}
small{color:#9ab}
</style></head><body>
<h1>Pi2AndroidAuto</h1>
${if (message.isNotEmpty()) "<div class=\"msg\">${esc(message)}</div>" else ""}
<p><small>Atual: ${esc(s.describe())}</small></p>
<p><small>Endereços desta página: ${CarWifi.localAddresses().joinToString(", ") { "http://$it:$CONFIG_WEB_PORT" }}</small></p>
${CarWifi.setupNetwork?.let { "<div class=\"msg\">Wi‑Fi de configuração ativo: ${esc(it)}</div>" } ?: ""}
<form method="post" action="/save">
<label>Wi‑Fi do carro (nome)<input name="ssid" value="${esc(s.carWifiSsid)}"></label>
<label>Senha do Wi‑Fi do carro<input name="password" type="password" placeholder="${if (s.carWifiPassword.isEmpty()) "" else "(guardada — deixe vazio para manter)"}"></label>
<label>Quem inicia a conexão
<select name="mode">
<option value="listen"${sel(s.mode == Settings.MODE_LISTEN)}>O carro conecta no Pi</option>
<option value="connect"${sel(s.mode == Settings.MODE_CONNECT)}>O Pi conecta no carro</option>
</select></label>
<label>IP do carro (modo "Pi conecta")<input name="host" value="${esc(s.carHost)}"></label>
<label>Porta do carro<input name="port" type="number" value="${s.carPort}"></label>
<label>Porta em que o Pi escuta<input name="listen_port" type="number" value="${s.listenPort}"></label>
<label>Resolução máxima do vídeo
<select name="max_height">
<option value="480"${sel(s.maxVideoHeight == 480)}>480p</option>
<option value="720"${sel(s.maxVideoHeight == 720)}>720p (recomendado no Pi 5)</option>
<option value="1080"${sel(s.maxVideoHeight == 1080)}>1080p</option>
</select></label>
<label>Qualidade do vídeo
<select name="quality">
<option value="50"${sel(s.videoQuality == 50)}>Baixa (leve, menos nítida)</option>
<option value="100"${sel(s.videoQuality == 100)}>Normal</option>
<option value="150"${sel(s.videoQuality == 150)}>Alta (texto mais nítido)</option>
<option value="200"${sel(s.videoQuality == 200)}>Máxima</option>
</select></label>
<small>Vale na próxima vez que o carro conectar.</small>
<button type="submit">Salvar</button>
</form>
<form method="post" action="/hotspot"><button type="submit">Criar Wi‑Fi de configuração do Pi</button></form>
</body></html>
""".trimIndent()
    }
}
