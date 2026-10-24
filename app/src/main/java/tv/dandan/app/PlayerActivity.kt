package tv.dandan.app

import android.app.Activity
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.media3.common.util.UnstableApi
import tv.dandan.app.data.MagnetEngine
import tv.dandan.app.data.MagnetPlayback
import tv.dandan.app.data.AppRepository
import tv.dandan.app.data.OnlineSubtitles
import tv.dandan.app.data.SubtitleStore
import tv.dandan.app.data.CloudDanmakuFilters
import tv.dandan.app.data.DanmakuText
import tv.dandan.app.ui.SubtitleForms
import tv.dandan.app.player.*
import tv.dandan.app.remote.PhoneField
import tv.dandan.app.remote.PhoneForm
import tv.dandan.app.remote.PhoneInputBroker
import tv.dandan.app.ui.BlockedFormRules
import tv.dandan.app.ui.components.PhoneFormUiState
import tv.dandan.app.ui.components.Toaster
import tv.dandan.app.ui.player.EpisodeItem
import tv.dandan.app.ui.player.PlayerActions
import tv.dandan.app.ui.player.PlayerMenu
import tv.dandan.app.ui.player.PlayerOverlay
import tv.dandan.app.ui.player.PlayerPhase
import tv.dandan.app.ui.player.PlayerUi
import tv.dandan.app.ui.player.SeekHint
import tv.dandan.core.*
import java.util.concurrent.Executors

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class PlayerActivity : ComponentActivity(), PlayerActions {
    private lateinit var repository: AppRepository
    private lateinit var session: PlayerSession
    private lateinit var playerView: FrameLayout
    private lateinit var subtitleFiles: SubtitleFiles
    private var subtitleEncoding = "自动"
    private var subtitleRevision = 0
    private var originalDanmaku = emptyList<DanmakuComment>()
    private var danmakuRevision = 0
    private data class PickerTicket(val generation: Int, val subtitleRevision: Int)
    private val pickerTickets = mutableMapOf<Int, PickerTicket>()
    private var subtitleFont: android.graphics.Typeface? = null
    private lateinit var danmaku: DanmakuView
    private lateinit var subtitle: TextView
    /** Compose 叠加层的状态：控制栏、选集、面板、倒计时都由它驱动。 */
    private val ui = PlayerUi()
    private val toaster = Toaster()
    private val phoneUi = PhoneFormUiState()
    /** 非解码器错误（如磁链失败、配置读取失败）也走同一张失败卡片。 */
    private var fatalMessage: String? = null
    private var matchLabel = ""
    private var seekDelta = 0L
    private var seekAt = 0L
    private var seekSerial = 0
    private var boostRestore: Float? = null
    private var upNextLeft = 0
    private val upNextTick = object : Runnable {
        override fun run() {
            if (ui.upNextSeconds == null) return
            upNextLeft--
            if (upNextLeft <= 0) playUpNext() else { ui.upNextSeconds = upNextLeft; handler.postDelayed(this, 1000) }
        }
    }
    private val handler = Handler(Looper.getMainLooper())
    private val io = java.util.concurrent.ThreadPoolExecutor(2, 2, 0, java.util.concurrent.TimeUnit.MILLISECONDS, java.util.concurrent.LinkedBlockingQueue())
    private val metadata = Executors.newSingleThreadExecutor()
    private val persist = Executors.newSingleThreadExecutor()
    private var sessionClosed = true
    private var generation = 0
    private var settings = AppSettings()
    private var subtitleTimeline = SubtitleTimeline(emptyList())
    private var queue = emptyList<MediaEntry>()
    private var entry: MediaEntry? = null
    private var phoneInputSerial = 0
    private var phoneActive = false
    private var foreground = false
    private var destroyed = false
    private var loading = false
    private var lastSave = 0L
    private var lastSubtitle = ""
    private var episodeShift = 0L
    private var restoredPosition: Long? = null
    private val hideControls = Runnable { if (ui.menus.isEmpty() && !phoneActive && !loading && fatalMessage == null && ::session.isInitialized && session.error == null && runCatching { session.status().playing }.getOrDefault(false)) showControls(false) }
    /** 播放网络视频时保持 Wi-Fi 高性能模式。 */
    private val wifiLock by lazy { WifiPerformanceLock(this) }
    /** 网络来源的实时下载速度（字节/秒，平滑后），缓冲时与视频码率一起显示。 */
    private var meterBytes = 0L
    private var meterAt = 0L
    private var meterLoading: Boolean? = null
    private var throughput = ThroughputEstimate()
    private var throughputSource: String? = null
    private var netLine: String? = null
    private var netSlow = false
    private var netLogTicks = 0
    private var loggedOpens = 0
    private var loggedWaste = 0L
    private fun pollNetwork() {
        val media = entry ?: return
        if (MagnetPlayback.isMagnet(media) || media.uri.substringBefore(':').lowercase() !in listOf("http", "https", "smb", "ftp")) { netLine = null; netSlow = false; meterAt = 0; return }
        val meter = tv.dandan.app.data.TransferMeter
        val now = android.os.SystemClock.elapsedRealtime()
        val loading = session.loading
        if (meterAt == 0L) { meterAt = now; meterBytes = meter.bytes; meterLoading = loading; return }
        if (now - meterAt < 1000) return
        val bytes = meter.bytes
        val rate = (bytes - meterBytes) * 1000.0 / (now - meterAt)
        // 这一秒播放器一直在下载才计入判断；内核不报告下载状态时（VLC）按缓冲状态和速度近似
        val downloading = if (loading != null) loading && meterLoading == true else ui.phase == PlayerPhase.BUFFERING || rate > 64 * 1024
        meterBytes = bytes; meterAt = now; meterLoading = loading
        throughput.add(rate, downloading)
        val byteRate = StreamingRules.averageByteRate(media.sizeBytes, ui.duration)
        netSlow = throughput.slow(byteRate)
        StreamingHealth.slowNetwork = netSlow
        netLine = StreamingRules.bufferingLine(throughput.current, byteRate)
        // 便于排查卡顿：每 5 秒记录一次速度、码率、缓冲与读取情况
        if (++netLogTicks % 5 == 0) {
            val opens = meter.opens; val waste = meter.wastedBytes
            android.util.Log.i("PlaybackNet", "phase=${ui.phase} rate=${StreamingRules.formatRate(throughput.current)} avg=${StreamingRules.formatRate(throughput.sustained)} video=${StreamingRules.formatRate(byteRate)} slow=$netSlow loading=$loading ahead=${session.bufferedAheadMs / 1000}s position=${ui.position / 1000}s opens=+${opens - loggedOpens} unused=+${(waste - loggedWaste) shr 10}KB")
            loggedOpens = opens; loggedWaste = waste
        }
    }
    /** 磁链播放时的下载速度与进度，每秒最多刷新一次。 */
    private var magnetLine: String? = null
    private var magnetPolled = 0L
    private fun pollMagnet() {
        val media = entry ?: return
        if (!MagnetPlayback.isMagnet(media)) { magnetLine = null; return }
        val now = System.currentTimeMillis()
        if (now - magnetPolled < 1000) return
        magnetPolled = now
        metadata.execute {
            val status = runCatching { MagnetEngine.get(applicationContext).status() }.getOrNull()
            val line = status?.let { st ->
                val percent = if (st.totalBytes > 0) " · 已下载 ${(st.downloadedBytes * 100 / st.totalBytes)}%" else ""
                "${st.speedBytesPerSec / 1024} KB/s$percent"
            }
            handler.post { magnetLine = line }
        }
    }
    private val tick = object : Runnable {
        override fun run() {
            if (!foreground || destroyed || !::session.isInitialized || sessionClosed) return
            pollMagnet()
            pollNetwork()
            updatePlayback()
            val clock = session.position
            danmaku.update(clock, settings)
            val currentText = if (session.overlaySubtitle) subtitleTimeline.at(clock, settings.subtitleDelayMs) else ""
            if (currentText != lastSubtitle) { subtitle.text = currentText; lastSubtitle = currentText }
            val subtitleAlpha = if (ui.shelfOpen && ui.controlsVisible) 0f else 1f
            if (subtitle.alpha != subtitleAlpha) subtitle.animate().alpha(subtitleAlpha).setDuration(200).start()
            if (System.currentTimeMillis() - lastSave > 10_000) saveProgress()
            handler.postDelayed(this, 50)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { handleBack() }
        })
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        repository = AppRepository(this)
        subtitleFiles = SubtitleFiles(this, repository)
        buildScreen()
        val opening = Intent(intent)
        @Suppress("DEPRECATION") val restored = savedInstanceState?.getSerializable(STATE_ENTRY) as? MediaEntry
        if (restored != null) { opening.putExtra(EXTRA_ENTRY, restored); restoredPosition = savedInstanceState.getLong(STATE_POSITION).coerceAtLeast(0) }
        acceptIntent(opening)
    }

    private fun buildScreen() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        playerView = FrameLayout(this).apply { isFocusable = false; contentDescription = "视频画面" }
        root.addView(playerView, FrameLayout.LayoutParams(-1, -1))
        danmaku = DanmakuView(this)
        root.addView(danmaku, FrameLayout.LayoutParams(-1, -1))
        subtitle = TextView(this).apply {
            gravity = Gravity.CENTER; setTextColor(Color.WHITE); textSize = 24f
            setShadowLayer(3f, 0f, 0f, Color.BLACK); isFocusable = false
            setPadding(dp(48), 0, dp(48), 0)
        }
        root.addView(subtitle, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { bottomMargin = dp(56) })
        // 控制栏、选集、设置面板、加载与失败状态全部由 Compose 叠加层绘制，并接管遥控器方向键。
        val overlay = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { PlayerOverlay(ui, this@PlayerActivity, toaster, phoneUi) }
        }
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
    }

    private fun acceptIntent(source: Intent) {
        @Suppress("DEPRECATION") val requested = source.getSerializableExtra(EXTRA_ENTRY) as? MediaEntry
        @Suppress("DEPRECATION") val supplied = (source.getSerializableExtra(EXTRA_QUEUE) as? ArrayList<*>)?.filterIsInstance<MediaEntry>().orEmpty()
        if (requested == null || requested.directory || requested.uri.isBlank()) { fatal("没有可播放的媒体"); return }
        PlaybackBridge.controller?.let { existing -> runCatching { existing.command("exit", emptyMap()) } }
        queue = supplied.filter { !it.directory && it.uri.isNotBlank() }.ifEmpty { listOf(requested) }
        if (queue.none { it.id == requested.id }) queue = listOf(requested) + queue
        loadEpisodes()
        session = PlayerSession(this, repository, queue, ::updatePlayback, { message -> if (::session.isInitialized && !loading && session.current?.id == entry?.id) fatal(message) }, {
            saveProgress()
            val next = session.index + 1
            if (next < queue.size) startUpNext() else { ui.finished = true; updatePlayback() }
        }, ::exitPlayer, ::applyConfiguration)
        sessionClosed = false
        session.setForeground(foreground)
        session.attach(playerView)
        PlaybackBridge.controller = session
        openEntry(requested)
        if (foreground) { handler.removeCallbacks(tick); handler.post(tick) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        closeSession()
        setIntent(intent)
        acceptIntent(intent)
    }

    private fun openEntry(media: MediaEntry) {
        if (::session.isInitialized) { saveProgress(); session.pause() }
        val request = ++generation
        cancelPhoneInput()
        io.queue.clear()
        val resumedPosition = restoredPosition
        restoredPosition = null
        entry = media
        cancelUpNextTimer(); ui.finished = false; fatalMessage = null; ui.error = null; matchLabel = ""
        // 同一来源连播下一集时沿用测得的网速：新文件的码率一读出来就能判断是否跟得上，起播即可多缓冲一些
        if (media.sourceId != throughputSource) { throughput = ThroughputEstimate(); throughputSource = media.sourceId }
        meterAt = 0; netLine = null; netSlow = false; StreamingHealth.slowNetwork = false
        if (ui.boosting) { ui.boosting = false; boostRestore = null }
        ui.title = PlayerUiRules.displayTitle(media.title); ui.currentIndex = queue.indexOfFirst { it.id == media.id }.coerceAtLeast(0)
        ui.danmakuCount = -1; ui.position = 0; ui.duration = 0
        subtitleEncoding = "自动"
        originalDanmaku = emptyList(); danmakuRevision++
        loading = true
        subtitleTimeline = SubtitleTimeline(emptyList()); subtitle.text = ""; lastSubtitle = ""
        danmaku.load(DanmakuTimeline(emptyList())); episodeShift = 0
        ui.loadingText = "正在打开…"
        ui.phase = PlayerPhase.LOADING
        showControls(true)
        metadata.execute {
            try {
                val configuration = repository.settings()
                val progress = repository.progress(media.id)
                val binding = repository.episodeFor(media.id)
                // 磁链条目先取种子并启动下载，解码器拿到的是引擎的本机地址；历史仍保存稳定的 magnet-play 地址。
                val playable = if (MagnetPlayback.isMagnet(media)) {
                    handler.post { if (isCurrent(request)) ui.loadingText = "正在获取种子并连接节点…" }
                    MagnetPlayback.resolve(MagnetEngine.get(applicationContext), media).also {
                        handler.post { if (isCurrent(request)) ui.loadingText = "已连接节点，正在缓冲开头…" }
                    }
                } else media
                handler.post {
                    if (isCurrent(request)) {
                        settings = configuration
                        tv.dandan.app.data.ParallelReading.enabled = configuration.multiConnection
                        episodeShift = binding?.shiftMs ?: 0
                        applySubtitleStyle()
                        session.prepare(playable, progress, settings.playbackSpeed, position = resumedPosition, play = foreground, settings = settings)
                        loading = false
                        updatePlayback(); scheduleHide()
                        loadSidecars(request, media, binding)
                    }
                }
            } catch (e: Exception) { handler.post { if (isCurrent(request)) { loading = false; fatal(if (MagnetPlayback.isMagnet(media)) (e.message ?: "磁链播放失败，请换一个资源") else "无法打开媒体配置，请返回后重试") } } }
        }
    }

    private fun loadSidecars(request: Int, media: MediaEntry, binding: EpisodeBinding?) {
        if (media.subtitleUri.isNotBlank()) loadSubtitle(media.subtitleUri, media.sourceId, request)
        else {
            val revision = subtitleRevision
            background(request, { OnlineSubtitles(this, repository).autoMatch(media) }, { uri ->
                if (uri != null && revision == subtitleRevision && entry?.subtitleUri.isNullOrBlank()) loadOnlineSubtitle(uri, request)
            }, null)
        }
        if (media.danmakuUri.isNotBlank()) {
            background(request, { prepareDanmaku(DanmakuXml.parse(repository.readText(media.danmakuUri, media.sourceId))) }, { displayDanmaku(it); toast("已加载本地弹幕") }, "弹幕导入失败，请检查文件")
        } else if (binding != null) downloadComments(binding.episodeId, binding.shiftMs, request, false)
        else if (settings.danmakuEnabled) background(request, { repository.knowsFileHash(media) }, { known -> if (known) matchName(false) else deferAutoMatch(request) }, null)
    }

    /**
     * 自动匹配弹幕要读取文件前 16MB 计算特征（算过的文件会记住，下次直接匹配；片库里已识别的剧集直接用绑定，不走这里）。
     * 本机文件直接匹配；网络来源挑时机读取，不和播放抢带宽：网速够用时连续播放 8 秒后读；网速跟不上码率时等缓冲攒到 30 秒
     * 或用户暂停再读，最多等 3 分钟。实测在网速跟不上时趁起播一起读，会让起播多等十来秒，所以不这样做。
     */
    private fun deferAutoMatch(request: Int) {
        val media = entry ?: return
        if (media.uri.substringBefore(':').lowercase() in listOf("content", "file")) { matchName(false); return }
        var steady = 0
        var paused = 0
        var waited = 0
        handler.postDelayed(object : Runnable {
            override fun run() {
                if (!isCurrent(request)) return
                waited++
                when (ui.phase) {
                    PlayerPhase.PLAYING -> { steady++; paused = 0 }
                    PlayerPhase.PAUSED -> paused++
                    else -> paused = 0
                }
                val comfortable = (steady >= 8 && !netSlow) || session.bufferedAheadMs >= 30_000
                if (comfortable || paused >= 2 || waited >= 180) matchName(false) else handler.postDelayed(this, 1000)
            }
        }, 1000)
    }

    private fun updatePlayback() {
        if (!::session.isInitialized || destroyed || sessionClosed) return
        val status = runCatching { session.status() }.getOrNull() ?: return
        if (session.state != DecoderState.IDLE && !ui.boosting) settings = settings.copy(playbackSpeed = status.speed)
        if (!loading && session.error != null && fatalMessage == null) fatal(session.error!!)
        ui.phase = when {
            fatalMessage != null -> PlayerPhase.ERROR
            loading -> PlayerPhase.LOADING
            session.state == DecoderState.BUFFERING || session.state == DecoderState.IDLE -> PlayerPhase.BUFFERING
            session.state == DecoderState.ENDED -> PlayerPhase.ENDED
            status.playing -> PlayerPhase.PLAYING
            else -> PlayerPhase.PAUSED
        }
        ui.position = status.position; ui.duration = status.duration; ui.bufferedPercent = status.bufferedPercent
        ui.speed = if (ui.boosting) boostRestore ?: status.speed else status.speed
        ui.engineLabel = if (session.backend == PlaybackEngine.VLC) "VLC" else "Media3"
        ui.networkLine = magnetLine?.takeIf { entry?.let(MagnetPlayback::isMagnet) == true } ?: netLine?.takeIf { ui.phase == PlayerPhase.BUFFERING }
        ui.networkHint = StreamingRules.SLOW_HINT.takeIf { netSlow && ui.phase == PlayerPhase.BUFFERING }
        ui.danmakuOn = settings.danmakuEnabled
        ui.subtitleOn = !entry?.subtitleUri.isNullOrBlank()
        ui.detail = listOf(matchLabel, if (queue.size > 1) "播放列表 ${session.index + 1} / ${queue.size}" else "").filter { it.isNotBlank() }.joinToString(" · ")
        ui.currentIndex = session.index
    }

    /** 选集栏数据：先用文件名生成，再在后台补上每集的观看进度。 */
    private fun loadEpisodes() {
        val items = queue.mapIndexed { index, media -> EpisodeItem(PlayerUiRules.episodeLabel(media.title, index), PlayerUiRules.displayTitle(media.title), -1f) }
        ui.episodes = items
        val snapshot = queue
        metadata.execute {
            val watched = snapshot.map { media -> repository.progress(media.id)?.let { PlayerUiRules.watchedFraction(it.positionMs, it.durationMs) } ?: -1f }
            handler.post { if (!destroyed && queue === snapshot) ui.episodes = items.mapIndexed { i, item -> item.copy(watched = watched[i]) } }
        }
    }

    // —— 叠加层回调（PlayerActions）——
    override fun toggle() { if (ui.finished) { replay(); return }; command("toggle") }
    override fun seekTo(positionMs: Long) { command("seek", mapOf("position" to positionMs.coerceAtLeast(0).toString())) }
    override fun seekBy(offsetMs: Long) {
        val now = System.currentTimeMillis()
        seekDelta = PlayerUiRules.accumulateSeek(seekDelta, seekAt, now, offsetMs > 0)
        seekAt = now
        ui.seekHint = SeekHint(seekDelta, ++seekSerial)
        command("seekBy", mapOf("offset" to offsetMs.toString()))
    }
    override fun playEpisode(index: Int) { if (index in queue.indices && index != session.index) { saveProgress(); openEntry(queue[index]) } }
    override fun subtitleMenu() = subtitleMenuInternal()
    override fun danmakuMenu() = danmakuMenuInternal()
    override fun speedMenu() {
        val speeds = listOf(.5f, .75f, 1f, 1.25f, 1.5f, 2f)
        val current = speeds.indexOfFirst { kotlin.math.abs(it - settings.playbackSpeed) < .01f }
        choose("播放倍速", listOf("0.5×", "0.75×", "正常", "1.25×", "1.5×", "2×"), checked = current, subtitle = "播放时按住右键可临时 2 倍速") {
            val value = speeds[it]
            command("speed", mapOf("speed" to value.toString())); updateSettings(settings.copy(playbackSpeed = value))
        }
    }
    override fun settingsMenu() {
        choose("更多设置", listOf("画面比例", "音轨", "解码内核 · ${if (session.backend == PlaybackEngine.VLC) "VLC" else "Media3"}", "播放器音量 · ${runCatching { session.status().volumePercent }.getOrDefault(100)}%", "网络读取 · ${if (settings.multiConnection) "多连接" else "单连接"}", "退出播放")) {
            when (it) {
                0 -> choose("画面比例", listOf("适应画面", "填满屏幕", "裁剪填充"), checked = currentFit.ordinal) { i -> currentFit = VideoFit.entries[i]; session.setFit(currentFit) }
                1 -> selectTrack(DecoderTrackKind.AUDIO)
                2 -> chooseBackend()
                3 -> {
                    val volume = runCatching { session.status().volumePercent }.getOrDefault(100)
                    choose("播放器音量", (0..10).map { v -> "${v * 10}%" }, checked = (volume + 5) / 10) { i -> command("volume", mapOf("percent" to (i * 10).toString())) }
                }
                4 -> choose("网络读取", listOf("多连接（3 个连接分段下载）", "单连接"), checked = if (settings.multiConnection) 0 else 1, subtitle = "拖动进度或重新打开后生效") { i ->
                    updateSettings(settings.copy(multiConnection = i == 0))
                }
                5 -> exitPlayer()
            }
        }
    }
    override fun retry() {
        val media = entry ?: return exitPlayer()
        fatalMessage = null; ui.error = null
        if (::session.isInitialized && session.current?.id == media.id && session.error != null) { session.retry(); showControls(true) } else openEntry(media)
        updatePlayback()
    }
    override fun chooseEngine() = chooseBackend()
    override fun exit() = exitPlayer()
    override fun playUpNext() {
        val next = session.index + 1
        cancelUpNextTimer()
        if (next in queue.indices) openEntry(queue[next])
    }
    override fun cancelUpNext() { cancelUpNextTimer(); ui.finished = false; showControls(true) }
    override fun replay() {
        ui.finished = false
        command("seek", mapOf("position" to "0")); command("play")
        showControls(true)
    }
    override fun menuSelect(index: Int) {
        val menu = ui.menus.lastOrNull() ?: return
        val depth = ui.menus.size
        // 选择后若没有打开下一级菜单，就关闭整个面板回到画面；打开了则保留上级，返回键可以退回。
        menu.onSelect(index)
        if (ui.menus.size == depth && ui.menus.lastOrNull() === menu) closeMenus()
    }
    override fun boost(active: Boolean) {
        if (active == ui.boosting || !::session.isInitialized) return
        if (active) {
            val speed = runCatching { session.status().speed }.getOrDefault(1f)
            if (speed >= 2f) return
            boostRestore = speed; ui.boosting = true
            runCatching { session.command("speed", mapOf("speed" to "2.0")) }
        } else {
            ui.boosting = false
            runCatching { session.command("speed", mapOf("speed" to (boostRestore ?: settings.playbackSpeed).toString())) }
            boostRestore = null
        }
        updatePlayback()
    }
    override fun interacted() { if (ui.controlsVisible) scheduleHide() }
    override fun showControls(show: Boolean) {
        if (show && !ui.controlsVisible) ui.focusSerial++
        ui.controlsVisible = show
        subtitle.animate().translationY(if (show) -dp(190).toFloat() else 0f).setDuration(240).start()
        if (show) scheduleHide() else handler.removeCallbacks(hideControls)
    }
    private var currentFit = VideoFit.FIT
    private fun closeMenus() { ui.menus.clear(); ui.focusSerial++; scheduleHide() }
    private fun startUpNext() {
        if (ui.boosting) boost(false)
        upNextLeft = PlayerUiRules.UP_NEXT_SECONDS
        ui.menus.clear()
        ui.upNextSeconds = upNextLeft
        handler.removeCallbacks(upNextTick); handler.postDelayed(upNextTick, 1000)
    }
    private fun cancelUpNextTimer() { handler.removeCallbacks(upNextTick); ui.upNextSeconds = null }

    private fun command(action: String, arguments: Map<String, String> = emptyMap()) {
        runCatching { session.command(action, arguments) }.onFailure { toast(it.message ?: "操作失败") }
        updatePlayback()
    }

    /** 遥控器方向键由 Compose 叠加层处理；这里只接收媒体键（播放/暂停、快进快退、上一集下一集）。 */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (phoneActive || !::session.isInitialized || fatalMessage != null) return super.onKeyDown(keyCode, event)
        when (event.keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY -> command("play")
            KeyEvent.KEYCODE_MEDIA_PAUSE -> command("pause")
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { command("toggle"); showControls(true) }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> seekBy(PlayerUiRules.SEEK_STEP_MS)
            KeyEvent.KEYCODE_MEDIA_REWIND -> seekBy(-PlayerUiRules.SEEK_STEP_MS)
            KeyEvent.KEYCODE_MEDIA_NEXT -> playEpisode(session.index + 1)
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> playEpisode(session.index - 1)
            KeyEvent.KEYCODE_MEDIA_STOP -> exitPlayer()
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }

    /** 返回键顺序：设置面板逐级返回 → 取消连播倒计时 → 隐藏控制栏 → 退出播放器。 */
    private fun handleBack() {
        when {
            ui.menus.isNotEmpty() -> { ui.menus.removeAt(ui.menus.lastIndex); if (ui.menus.isEmpty()) closeMenus() }
            ui.upNextSeconds != null -> cancelUpNext()
            ui.finished || fatalMessage != null || loading -> exitPlayer()
            ui.controlsVisible -> showControls(false)
            else -> exitPlayer()
        }
    }

    private fun scheduleHide() { handler.removeCallbacks(hideControls); handler.postDelayed(hideControls, PlayerUiRules.AUTO_HIDE_MS) }

    private fun fatal(message: String) {
        fatalMessage = message
        cancelUpNextTimer(); ui.menus.clear()
        ui.error = message
        ui.canSwitchEngine = ::session.isInitialized && session.current != null
        ui.phase = PlayerPhase.ERROR
    }

    private fun chooseBackend() {
        choose("解码内核", PlaybackEngine.entries.map { if (it == PlaybackEngine.MEDIA3) "Media3" else "VLC · 原生 ASS / 内嵌字幕" }, checked = PlaybackEngine.entries.indexOf(session.backend), subtitle = "切换后保留播放位置与设置") { index ->
            val engine = PlaybackEngine.entries[index]
            settings = settings.copy(playbackEngine = engine)
            session.switchBackend(engine)
            val saved = settings
            persist.execute { runCatching { repository.saveSettings(saved) }.onFailure { handler.post { if (!destroyed) toast("内核设置保存失败") } } }
            updatePlayback()
            if (session.error == null) { fatalMessage = null; ui.error = null; showControls(true) }
        }
    }

    private fun selectTrack(kind: DecoderTrackKind) {
        val candidates = session.tracks(kind)
        val options = listOf(if (kind == DecoderTrackKind.TEXT) "关闭本集字幕" else "自动选择") + candidates.map { it.label }
        val checked = candidates.indexOfFirst { it.selected }.let { if (it >= 0) it + 1 else 0 }
        if (candidates.isEmpty()) { toast(if (kind == DecoderTrackKind.TEXT) "当前没有可用字幕轨，可导入外挂字幕" else "当前没有可用音轨"); return }
        choose(if (kind == DecoderTrackKind.TEXT) "字幕轨" else "音轨", options, checked = checked, subtitle = if (kind == DecoderTrackKind.TEXT) "当前内核识别到的内嵌字幕，仅对本集生效" else "") { choice ->
            if (kind == DecoderTrackKind.TEXT) {
                subtitleRevision++
                subtitleTimeline = SubtitleTimeline(emptyList()); subtitle.text = ""; lastSubtitle = ""
                entry = entry?.copy(subtitleUri = "")
            }
            runCatching { session.selectTrack(kind, candidates.getOrNull(choice - 1)) }.onFailure { toast(it.message ?: "选择轨道失败") }
        }
    }

    private fun subtitleMenuInternal() {
        choose("字幕", listOf("选择内嵌 / 原生字幕轨", "导入 SRT / VTT / ASS / TTML", "从手机填写字幕地址", "关闭外挂字幕", "字幕延迟", "字幕字号", "字幕颜色", "文件编码：$subtitleEncoding", "导入 TTF / OTF 字体", "在线字幕搜索 / 匹配 / 配置", "字幕描边")) {
            when (it) {
                0 -> selectTrack(DecoderTrackKind.TEXT)
                1 -> pickFile(REQUEST_SUBTITLE)
                2 -> input("导入字幕地址", "", "填写 HTTP / HTTPS 字幕地址", required = true, validator = { value ->
                    runCatching { require(Uri.parse(value).scheme in listOf("http", "https")) }.exceptionOrNull()?.let { "请填写 HTTP / HTTPS 地址" }
                }) { value -> loadSubtitle(value, "", generation) }
                3 -> { subtitleRevision++; subtitleTimeline = SubtitleTimeline(emptyList()); subtitle.text = ""; lastSubtitle = ""; entry = entry?.copy(subtitleUri = ""); session.externalSubtitle(null); toast("已关闭外挂字幕，可继续选择内嵌轨") }
                4 -> choose("字幕延迟（正数延后；VLC 支持原生字幕）", listOf("提前 2 秒", "提前 1 秒", "同步", "延后 1 秒", "延后 2 秒")) { i -> updateSettings(settings.copy(subtitleDelayMs = listOf(-2000L, -1000L, 0L, 1000L, 2000L)[i])) }
                5 -> choose("字幕字号（ASS 优先采用文件样式）", listOf("18", "24", "30", "36", "42")) { i -> updateSettings(settings.copy(subtitleSizeSp = listOf(18f, 24f, 30f, 36f, 42f)[i])) }
                6 -> choose("字幕文字色（ASS 优先采用文件样式）", listOf("白色", "黄色", "青色")) { i -> updateSettings(settings.copy(subtitleColor = listOf(Color.WHITE, Color.YELLOW, Color.CYAN)[i])) }
                7 -> choose("字幕文件编码", SubtitleEncoding.choices) { i -> subtitleEncoding = SubtitleEncoding.choices[i]; entry?.subtitleUri?.takeIf(String::isNotBlank)?.let { uri -> loadSubtitle(uri, entry?.sourceId.orEmpty(), generation) } }
                8 -> pickFile(REQUEST_FONT)
                9 -> onlineSubtitleMenu()
                10 -> choose("字幕描边（ASS 优先文件样式）", listOf("无", "细", "标准", "粗")) { i -> updateSettings(settings.copy(subtitleStroke = i.toFloat())) }
            }
        }
    }

    private fun danmakuMenuInternal() {
        choose("弹幕", listOf(if (settings.danmakuEnabled) "关闭弹幕" else "开启弹幕", "导入本地 XML", "按官方文件名匹配", "搜索番剧并选剧集", "弹幕样式", "关键词屏蔽", "正则屏蔽", if (settings.cloudDanmakuBlock) "关闭云屏蔽" else "开启云屏蔽", "弹幕语言")) {
            when (it) {
                0 -> updateSettings(settings.copy(danmakuEnabled = !settings.danmakuEnabled))
                1 -> pickFile(REQUEST_DANMAKU)
                2 -> matchName(true)
                3 -> input("搜索番剧名称", "", "请输入至少两个字符的番剧关键词", required = true, validator = { if (it.trim().length < 2) "请至少输入两个字符" else null }) { query -> searchEpisode(query) }
                4 -> danmakuStyle()
                5 -> input("关键词屏蔽（每行一项）", settings.blockedKeywords.joinToString("\n"), "包含任一关键词的弹幕会被隐藏；最多200条、每条200字符", multiline = true,
                    validator = { BlockedFormRules.validate(settings, keywordText = it) }) { value -> updateSettings(BlockedFormRules.apply(settings, keywordText = value)) }
                6 -> input("正则屏蔽（每行一项）", settings.blockedPatterns.joinToString("\n"), "无效正则可在手机继续修改；最多100条、每条256字符", multiline = true,
                    validator = { BlockedFormRules.validate(settings, patternText = it) }) { value ->
                    updateSettings(BlockedFormRules.apply(settings, patternText = value))
                }
                7 -> updateSettings(settings.copy(cloudDanmakuBlock = !settings.cloudDanmakuBlock))
                8 -> choose("弹幕语言", listOf("原始", "简体", "繁体")) { i -> updateSettings(settings.copy(danmakuLanguage = DanmakuLanguage.entries[i])) }
            }
        }
    }
    private fun danmakuStyle() {
        choose("弹幕样式", listOf("字号", "透明度", "经过时间", "描边", "显示区域", "同屏上限", if (settings.showScrolling) "隐藏滚动" else "显示滚动", if (settings.showTop) "隐藏顶部" else "显示顶部", if (settings.showBottom) "隐藏底部" else "显示底部")) {
            when (it) {
                0 -> choose("弹幕字号", listOf("18", "24", "30", "36")) { i -> updateSettings(settings.copy(danmakuSizeSp = listOf(18f, 24f, 30f, 36f)[i])) }
                1 -> choose("弹幕透明度", listOf("25%", "50%", "75%", "100%")) { i -> updateSettings(settings.copy(danmakuAlpha = listOf(.25f, .5f, .75f, 1f)[i])) }
                2 -> choose("弹幕经过屏幕时间", listOf("4 秒", "6 秒", "8 秒", "12 秒")) { i -> updateSettings(settings.copy(danmakuDurationMs = listOf(4000L, 6000L, 8000L, 12000L)[i])) }
                3 -> choose("弹幕描边", listOf("无", "细", "标准", "粗")) { i -> updateSettings(settings.copy(danmakuStroke = listOf(0f, 1f, 2f, 3f)[i])) }
                4 -> choose("最大显示行数", listOf("4 行", "8 行", "12 行", "16 行")) { i -> updateSettings(settings.copy(danmakuMaxLines = listOf(4, 8, 12, 16)[i])) }
                5 -> choose("同屏弹幕上限", listOf("20", "40", "60", "100")) { i -> updateSettings(settings.copy(danmakuMaxCount = listOf(20, 40, 60, 100)[i])) }
                6 -> updateSettings(settings.copy(showScrolling = !settings.showScrolling))
                7 -> updateSettings(settings.copy(showTop = !settings.showTop))
                8 -> updateSettings(settings.copy(showBottom = !settings.showBottom))
            }
        }
    }

    private fun matchName(manual: Boolean) {
        val media = entry ?: return
        val request = generation
        val durationSec = (ui.duration / 1000).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
        background(request, {
            if (!repository.credentials().configured) {
                if (manual) throw IllegalArgumentException("请先在设置中配置弹弹play AppId 与 AppSecret")
                emptyList<EpisodeMatch>()
            } else {
                // 文件前 16MB 的 MD5 能让服务端精确命中弹幕库；读取失败（例如来源不支持随机读取）时退回只按文件名匹配。
                val hash = runCatching { repository.fileHash(media) }.onFailure { android.util.Log.w("DanmakuMatch", "计算文件特征失败", it) }.getOrNull()
                repository.api.matchByName(media.title, media.sizeBytes, hash, durationSec)
            }
        }, { matches ->
            when {
                matches.isEmpty() -> if (manual) toast("没有匹配结果，可手动搜索番剧")
                manual -> choose("请确认匹配剧集", matches.map { "${it.animeTitle} · ${it.episodeTitle}" }) { bindEpisode(matches[it], request) }
                matches.size == 1 && matches.first().exact -> bindEpisode(matches.first(), request)
                else -> toast("找到弹幕候选，可在弹幕菜单手动确认匹配")
            }
        }, if (manual) "文件名匹配失败，请检查凭证与网络" else "弹幕自动匹配失败，可在弹幕菜单重试或导入 XML")
    }
    private fun searchEpisode(query: String) {
        if (query.isBlank()) { toast("请输入番剧名称"); return }
        val request = generation
        background(request, {
            require(repository.credentials().configured) { "请先在设置中配置弹弹play AppId 与 AppSecret" }
            repository.api.searchEpisodes(query.trim())
        }, { matches ->
            if (matches.isEmpty()) toast("没有搜索结果")
            else choose("选择番剧与剧集", matches.map { "${it.animeTitle} · ${it.episodeTitle}" }) { bindEpisode(matches[it], request) }
        }, "搜索失败，请检查凭证与网络")
    }
    private fun bindEpisode(match: EpisodeMatch, request: Int) {
        val media = entry ?: return
        background(request, { repository.saveEpisode(media, EpisodeBinding(match.episodeId, match.shiftMs)); prepareDanmaku(repository.api.comments(match.episodeId)) }, { comments ->
            matchLabel = "${match.animeTitle} · ${match.episodeTitle}"
            displayDanmaku(comments, match.shiftMs); toast("已加载 ${comments.count} 条弹幕"); updatePlayback()
        }, "剧集弹幕下载失败，可重新匹配或导入本地 XML")
    }
    private fun downloadComments(id: Long, shift: Long, request: Int, manual: Boolean) {
        background(request, { prepareDanmaku(repository.api.comments(id)) }, { displayDanmaku(it, shift); if (manual) toast("弹幕已加载") }, "弹幕下载失败，可在弹幕菜单重试匹配")
    }

    private fun pickFile(code: Int) {
        if (pickerTickets.isNotEmpty()) { toast("请先完成当前文件选择"); return }
        pickerTickets[code] = PickerTicket(generation, subtitleRevision)
        val picker = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        runCatching { startActivityForResult(picker, code) }.onFailure { pickerTickets.remove(code); toast("设备没有可用文件选择器") }
    }
    @Deprecated("系统文件选择器回调")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val ticket = pickerTickets.remove(requestCode) ?: return
        if (!isCurrent(ticket.generation) || (requestCode in listOf(REQUEST_SUBTITLE, REQUEST_FONT) && ticket.subtitleRevision != subtitleRevision)) return
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val request = ticket.generation
        when (requestCode) {
            REQUEST_SUBTITLE -> loadSubtitle(uri.toString(), "", request)
            REQUEST_FONT -> background(request, { subtitleFiles.font(uri.toString()).also { android.graphics.Typeface.createFromFile(it) } }, { file ->
                subtitleFont = android.graphics.Typeface.createFromFile(file); subtitle.typeface = subtitleFont; session.subtitleFont(file); toast("字幕字体已加载；ASS 字体附件由 VLC 原生处理")
            }, "字体导入失败：请使用 TTF / OTF 字体")
            REQUEST_DANMAKU -> background(request, { prepareDanmaku(DanmakuXml.parse(repository.readText(uri.toString()))) }, {
                entry = entry?.copy(danmakuUri = uri.toString()); displayDanmaku(it); toast("已导入 ${it.count} 条弹幕")
            }, "弹幕导入失败：只支持安全的弹弹play XML")
        }
    }

    private data class PreparedDanmaku(val comments: List<DanmakuComment>, val count: Int)
    private fun prepareDanmaku(comments: List<DanmakuComment>) = PreparedDanmaku(comments, comments.size)
    private fun displayDanmaku(prepared: PreparedDanmaku, shift: Long = 0) {
        originalDanmaku = prepared.comments
        episodeShift = shift
        ui.danmakuCount = prepared.count
        rebuildDanmaku()
    }
    private fun rebuildDanmaku() {
        val revision = ++danmakuRevision
        val comments = originalDanmaku; val language = settings.danmakuLanguage; val shift = episodeShift
        background(generation, { DanmakuTimeline(DanmakuText.convert(comments, language), CloudDanmakuFilters(this).rules()) }, { timeline ->
            if (revision == danmakuRevision) danmaku.load(timeline, shift)
        }, "弹幕规则或语言处理失败")
    }
    private fun loadSubtitle(uri: String, sourceId: String, request: Int) {
        val revision = ++subtitleRevision
        val online = Uri.parse(uri).authority == packageName + ".subtitles"
        val encoding = if (online && subtitleEncoding == "自动") "UTF-8" else subtitleEncoding
        val useRaw = online && subtitleEncoding != "自动"
        background(request, {
            val readUri = if (useRaw) OnlineSubtitles(this, repository).rawUri(uri) ?: uri else uri
            subtitleFiles.load(readUri, displayName(Uri.parse(uri)), encoding)
        }, { imported ->
            if (revision != subtitleRevision) return@background
            entry = entry?.copy(subtitleUri = uri)
            subtitleTimeline = imported.timeline; lastSubtitle = ""; subtitle.text = ""
            session.externalSubtitle(imported.external)
            toast(if (imported.external.styled && session.backend == PlaybackEngine.MEDIA3) "ASS 文字已加载；切换 VLC 可使用原生样式与字体附件" else "字幕已加载")
        }, "字幕导入失败，可选择文件编码后重试，支持 SRT / VTT / ASS / TTML")
    }
    private fun loadOnlineSubtitle(uri: String, request: Int) {
        subtitleEncoding = "自动"
        loadSubtitle(uri, "", request)
    }
    private fun onlineSubtitleMenu() {
        val media = entry ?: return
        choose("在线字幕", listOf("在手机填写名称并搜索", "按当前视频指纹匹配", "在手机配置服务凭证")) { option ->
            val request = generation
            val revision = if (option != 2) ++subtitleRevision else subtitleRevision
            when (option) {
                0 -> input("搜索在线字幕", media.title.substringBeforeLast('.', media.title).take(200), "输入作品名称，回到电视核对发行版本并选择下载。", required = true,
                    validator = { if (it.trim().length < 2) "名称至少需要 2 个字符" else null }) { query ->
                    background(request, { OnlineSubtitles(this, repository).search(query.trim(), media) }, { results ->
                        if (revision != subtitleRevision) return@background
                        if (results.isEmpty()) toast("未找到字幕，请切换语言或搜索名称")
                        else choose("选择字幕（下载使用账户配额）", results.take(100).map { "${it.language} · ${it.title}\n${it.release}${if (it.hashMatched) " · 指纹匹配" else ""}" }) { index ->
                            background(request, { OnlineSubtitles(this, repository).download(results[index]) }, { uri -> if (revision == subtitleRevision) loadOnlineSubtitle(uri, request) }, "字幕下载失败，请检查配置与账户配额")
                        }
                    }, "字幕搜索失败，请在设置中检查字幕服务配置")
                }
                1 -> background(request, { OnlineSubtitles(this, repository).autoMatch(media) }, { uri ->
                    if (revision != subtitleRevision) return@background
                    if (uri == null) toast("没有唯一可靠的指纹匹配，请手动搜索；自动匹配需先开启") else loadOnlineSubtitle(uri, request)
                }, "字幕指纹匹配失败，请检查服务配置或手动搜索")
                2 -> background(request, { SubtitleStore(this).read() }, { preferences ->
                    phoneForm(SubtitleForms.configuration(preferences.account), SubtitleForms::validate) { values ->
                        background(request, { SubtitleStore(this).save(preferences.copy(account = SubtitleForms.account(values))) }, { toast("字幕服务凭证已保存") }, "字幕服务配置保存失败")
                    }
                }, "无法读取字幕服务配置")
            }
        }
    }
    private fun displayName(uri: Uri): String {
        if (uri.scheme != "content") return uri.lastPathSegment.orEmpty()
        return runCatching { contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else ""
        }.orEmpty() }.getOrDefault("")
    }
    private fun applySubtitleStyle() {
        subtitle.textSize = settings.subtitleSizeSp
        subtitle.setTextColor(settings.subtitleColor)
        subtitle.typeface = subtitleFont
        subtitle.setShadowLayer(settings.subtitleStroke * resources.displayMetrics.density, 0f, 0f, Color.BLACK)
        if (::session.isInitialized && !sessionClosed) session.subtitleStyle(settings)
    }
    private fun applyConfiguration(configuration: AppSettings) {
        val languageChanged = settings.danmakuLanguage != configuration.danmakuLanguage
        settings = configuration
        tv.dandan.app.data.ParallelReading.enabled = configuration.multiConnection
        if (languageChanged && originalDanmaku.isNotEmpty()) rebuildDanmaku()
        applySubtitleStyle()
        if (::session.isInitialized && !sessionClosed) danmaku.update(session.position, settings)
    }
    private fun updateSettings(configuration: AppSettings) {
        applyConfiguration(configuration)
        persist.execute { runCatching { repository.saveSettings(configuration) }.onFailure { handler.post { if (!destroyed) toast("设置保存失败") } } }
        danmaku.update(session.position, settings)
        toast("设置已生效")
    }

    /** 在右侧面板打开一页选项；在某个选项的回调里再次调用即进入下一级，返回键逐级退回。 */
    private fun choose(title: String, options: List<String>, checked: Int = -1, subtitle: String = "", selected: (Int) -> Unit) {
        if (destroyed) return
        handler.removeCallbacks(hideControls)
        ui.menus.add(PlayerMenu(title, options, checked, subtitle, selected))
    }
    private fun input(
        title: String,
        initial: String,
        hint: String,
        multiline: Boolean = false,
        required: Boolean = false,
        validator: (String) -> String? = { null },
        accepted: (String) -> Unit,
    ) = phoneForm(PhoneForm(title, hint, listOf(PhoneField("value", title, initial, multiline = multiline, required = required, maxLength = if (multiline) 65536 else 8192))),
        { values -> validator(values["value"].orEmpty()) }, { values -> accepted(values["value"].orEmpty()) })

    private fun phoneForm(form: PhoneForm, validator: (Map<String, String>) -> String?, accepted: (Map<String, String>) -> Unit) {
        cancelPhoneInput()
        ui.menus.clear()
        handler.removeCallbacks(hideControls)
        val request = generation
        val serial = phoneInputSerial
        phoneActive = true
        PhoneInputBroker.request(this, repository, this, form, phoneUi, validator,
            onSubmit = { values ->
                if (isCurrent(request) && serial == phoneInputSerial) { phoneInputSerial++; phoneActive = false; accepted(values); showControls(true) }
            },
            onCancel = {
                if (isCurrent(request) && serial == phoneInputSerial) { phoneInputSerial++; phoneActive = false; showControls(true) }
            },
        )
    }
    private fun cancelPhoneInput() {
        phoneInputSerial++
        phoneActive = false
        PhoneInputBroker.cancelOwner(this)
    }
    private fun <T> background(request: Int, work: () -> T, success: (T) -> Unit, failure: String?) {
        io.execute {
            try { val result = work(); handler.post { if (isCurrent(request)) success(result) } }
            catch (e: Exception) {
                android.util.Log.w("Player", failure ?: "后台任务失败", e)
                // 附上具体原因（网络、认证、服务端返回的错误说明），用户不必再猜是哪一步失败
                val detail = e.message?.takeIf { it.isNotBlank() && e is java.io.IOException }?.let { "（$it）" }.orEmpty()
                handler.post { if (isCurrent(request) && failure != null) toast(if (e is IllegalArgumentException && e.message?.contains("AppId") == true) e.message!! else failure + detail) }
            }
        }
    }
    private fun isCurrent(request: Int) = !destroyed && request == generation
    private fun saveProgress() {
        if (!::session.isInitialized || sessionClosed) return
        val progress = runCatching { session.snapshot() }.getOrNull() ?: return
        val actual = progress.copy(media = entry?.takeIf { it.id == progress.media.id } ?: progress.media)
        val epoch = repository.historyEpoch()
        lastSave = System.currentTimeMillis()
        persist.execute { runCatching { repository.saveProgress(actual, epoch) } }
    }
    private fun closeSession() {
        if (!::session.isInitialized || sessionClosed) return
        if (entry?.let(MagnetPlayback::isMagnet) == true) metadata.execute { runCatching { MagnetEngine.get(applicationContext).stopPlay() } }
        generation++
        cancelPhoneInput()
        handler.removeCallbacks(tick); handler.removeCallbacks(hideControls)
        saveProgress()
        sessionClosed = true
        session.release()
    }
    private fun exitPlayer() { closeSession(); finish() }
    override fun onResume() {
        super.onResume(); foreground = true
        wifiLock.hold(true); tv.dandan.app.data.PlaybackGate.active = true
        if (::session.isInitialized && !sessionClosed) session.setForeground(true)
        handler.removeCallbacks(tick); handler.post(tick)
        if (::session.isInitialized && session.current != null && !loading) { showControls(true); updatePlayback() }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        if (::session.isInitialized && !sessionClosed) {
            entry?.let { outState.putSerializable(STATE_ENTRY, it) }
            outState.putLong(STATE_POSITION, if (entry?.id == session.current?.id) session.position else 0)
        }
        super.onSaveInstanceState(outState)
    }
    override fun onPause() {
        foreground = false
        wifiLock.hold(false); tv.dandan.app.data.PlaybackGate.active = false
        cancelPhoneInput()
        handler.removeCallbacks(tick); handler.removeCallbacks(hideControls)
        if (::session.isInitialized && !sessionClosed) { runCatching { session.setForeground(false) }; saveProgress() }
        super.onPause()
    }
    override fun onDestroy() {
        destroyed = true
        cancelPhoneInput()
        closeSession(); handler.removeCallbacksAndMessages(null)
        subtitleFiles.close()
        io.shutdownNow()
        metadata.shutdownNow()
        persist.shutdown()
        super.onDestroy()
    }
    private fun toast(message: String) { toaster.show(message) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val EXTRA_ENTRY = "media_entry"
        private const val EXTRA_QUEUE = "media_queue"
        private const val STATE_ENTRY = "current_entry"
        private const val STATE_POSITION = "current_position"
        private const val REQUEST_SUBTITLE = 10
        private const val REQUEST_DANMAKU = 11
        private const val REQUEST_FONT = 12
        fun open(context: Context, entry: MediaEntry, queue: List<MediaEntry> = listOf(entry)) {
            val intent = Intent(context, PlayerActivity::class.java).putExtra(EXTRA_ENTRY, entry).putExtra(EXTRA_QUEUE, ArrayList(queue))
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }
}
