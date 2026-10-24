package tv.dandan.app.remote

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import tv.dandan.app.PlayerActivity
import tv.dandan.app.data.AppRepository
import tv.dandan.app.player.PlaybackBridge
import tv.dandan.app.player.PlaybackStatus
import tv.dandan.core.MediaEntry
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.FutureTask
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.ArrayBlockingQueue

/** 应用进程内共享的手机输入与遥控服务，启动时准备，用户可停止或重新配对。 */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class RemoteControlServer(context: Context, private val repo: AppRepository) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var socket: ServerSocket? = null
    private var workers: ThreadPoolExecutor? = null
    private val clients = java.util.concurrent.ConcurrentHashMap.newKeySet<Socket>()
    @Volatile private var token = ""
    private val inputId = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    val isRunning: Boolean get() = socket?.isClosed == false
    var pairingUrl: String = ""
        private set

    @Synchronized fun start(): String {
        if (isRunning) return pairingUrl
        val bytes = ByteArray(24).also { SecureRandom().nextBytes(it) }
        token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        val server = ServerSocket(0).also { it.soTimeout = 1000 }
        socket = server
        workers = ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(8))
        val address = NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>().firstOrNull { !it.isLoopbackAddress && it.isSiteLocalAddress }?.hostAddress ?: "127.0.0.1"
        pairingUrl = "http://$address:${server.localPort}/?token=$token"
        Thread({
            while (socket === server && !server.isClosed) {
                try {
                    val client = server.accept()
                    clients.add(client)
                    try { workers?.execute { serve(client) } ?: client.close() }
                    catch (_: java.util.concurrent.RejectedExecutionException) { clients.remove(client); client.close() }
                } catch (_: java.net.SocketTimeoutException) { /* 定期检查关闭。 */ }
                catch (_: Exception) { if (server.isClosed) break }
            }
        }, "TV-配对服务").apply { isDaemon = true; start() }
        return pairingUrl
    }
    @Synchronized fun inputUrl(id: String): String {
        require(inputId.matches(id)) { "手机输入会话编号无效" }
        val address = URI(start())
        return "${address.scheme}://${address.rawAuthority}/input/$id?${address.rawQuery}"
    }
    @Synchronized fun stop() {
        socket?.close(); socket = null
        workers?.shutdownNow(); workers = null
        clients.forEach { runCatching { it.close() } }; clients.clear()
        token = ""; pairingUrl = ""
        PhoneInputBroker.serviceStopped()
    }
    private fun serve(client: Socket) {
      try { client.use { connection ->
        connection.soTimeout = 3000
        try {
            val request = RemoteHttp.read(connection.getInputStream())
            val path = request.path.substringBefore('?')
            if (request.method == "GET" && path == "/") {
                write(connection, 200, "text/html; charset=utf-8", RemotePage.html)
                return@use
            }
            if (request.method == "GET" && path.startsWith("/input/")) {
                if (inputId.matches(path.removePrefix("/input/"))) write(connection, 200, "text/html; charset=utf-8", RemotePage.formHtml)
                else write(connection, 404, "application/json", error("不存在的手机输入页面").toString())
                return@use
            }
            if (!path.startsWith("/api/") && path != "/init") {
                write(connection, 404, "application/json", error("不存在的页面或接口").toString())
                return@use
            }
            if (!RemoteHttp.authorized(request.headers["authorization"], token)) {
                write(connection, 401, "application/json", error("配对已失效，请重新配对").toString()); return@use
            }
            val data = when {
                request.method == "GET" && path.startsWith("/api/input/") && inputId.matches(path.removePrefix("/api/input/")) -> onMain {
                    JSONObject().put("success", true).put("errorCode", 0).put("form", PhoneInputBroker.snapshot(path.removePrefix("/api/input/")) ?: JSONObject.NULL)
                }
                request.method == "POST" && path == "/api/input" -> onMain { PhoneInputBroker.submit(JSONObject(request.body)) }
                request.method == "POST" && path == "/api/input/cancel" -> onMain { PhoneInputBroker.cancel(JSONObject(request.body).getString("id")) }
                request.method == "GET" && path == "/api/status" -> onMain { response(PlaybackBridge.controller?.status()) }
                request.method == "POST" && path == "/api/control" -> {
                    val body = JSONObject(request.body)
                    val action = body.optString("action")
                    val params = body.keys().asSequence().filter { it != "action" }.associateWith { body.get(it).toString() }
                    onMain { val controller = PlaybackBridge.controller ?: throw IllegalArgumentException("电视当前没有播放媒体"); response(controller.command(action, params)) }
                }
                request.method == "POST" && path == "/api/open" -> {
                    val url = JSONObject(request.body).optString("url").trim()
                    val uri = URI(url)
                    require(url.length <= 8192 && uri.scheme in listOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null) { "请输入不含账号密码的 HTTP 或 HTTPS 媒体地址" }
                    val mediaId = UUID.nameUUIDFromBytes(url.toByteArray(Charsets.UTF_8)).toString()
                    onMain { PlayerActivity.open(app, MediaEntry(mediaId, uri.path.substringAfterLast('/').ifBlank { "手机投送" }.take(200), url)); response(null) }
                }
                request.method == "POST" && path == "/api/config" -> {
                    val body = JSONObject(request.body)
                    val changed = tv.dandan.app.data.RepositoryTransactions.run {
                        RemoteConfigRules.apply(repo.settings(), body).also(repo::saveSettings)
                    }
                    onMain { PlaybackBridge.controller?.configure(changed) }
                    response(null)
                }
                request.method == "GET" && path == "/init" -> JSONObject().put("success", true).put("version", 2).put("errorCode", 0)
                else -> { write(connection, 404, "application/json", error("不存在的遥控接口").toString()); return@use }
            }
            write(connection, 200, "application/json; charset=utf-8", data.toString())
        } catch (_: java.util.concurrent.TimeoutException) {
            runCatching { write(connection, 408, "application/json", error("电视操作超时，请重试").toString()) }
        } catch (e: IllegalArgumentException) {
            runCatching { write(connection, 400, "application/json; charset=utf-8", error(e.message?.take(300) ?: "输入格式不正确").toString()) }
        } catch (_: Exception) {
            // 外部异常可能包含媒体地址或账号，协议只返回固定错误，不回显异常。
            runCatching { write(connection, 400, "application/json", error("请求无效或当前操作不可用，请检查电视状态和参数").toString()) }
        }
      } } finally { clients.remove(client) }
    }
    private fun <T> onMain(action: () -> T): T {
        val task = FutureTask(action)
        main.post(task)
        return try { task.get(2, TimeUnit.SECONDS) }
        catch (e: java.util.concurrent.ExecutionException) { throw (e.cause ?: e) }
        catch (e: Exception) { task.cancel(false); throw e }
    }
    private fun error(message: String) = JSONObject().put("success", false).put("errorCode", 400).put("errorMessage", message)
    private fun response(status: PlaybackStatus?): JSONObject {
        val json = JSONObject().put("success", true).put("errorCode", 0)
        if (status == null) return json.put("status", JSONObject.NULL)
        return json.put("status", JSONObject().put("title", status.title).put("playing", status.playing)
            .put("position", status.position).put("duration", status.duration).put("bufferedPercent", status.bufferedPercent)
            .put("speed", status.speed).put("volumePercent", status.volumePercent))
    }
    private fun write(socket: Socket, code: Int, type: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val header = "HTTP/1.1 $code ${if (code == 200) "OK" else "Error"}\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nReferrer-Policy: no-referrer\r\nConnection: close\r\n\r\n"
        socket.getOutputStream().apply { write(header.toByteArray(Charsets.US_ASCII)); write(bytes); flush() }
    }
}
