package org.arxiv.physics

import android.app.Activity
import android.content.Intent
import androidx.core.view.WindowCompat
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.provider.Settings
import android.net.Uri
import java.util.concurrent.atomic.AtomicBoolean
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { PhysicsApp(application as ArxivApp) }
    }
}

val LocalAppDark = staticCompositionLocalOf { false }

@Composable
fun PhysicsTheme(content: @Composable () -> Unit) {
    val app = LocalContext.current.applicationContext as ArxivApp
    val dark = when (app.themeMode) { ThemeMode.SYSTEM -> isSystemInDarkTheme(); ThemeMode.LIGHT -> false; ThemeMode.DARK -> true }
    val context = LocalContext.current
    SideEffect {
        (context as? Activity)?.let { activity ->
            WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    val colors = if (Build.VERSION.SDK_INT >= 31) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else if (dark) darkColorScheme(primary = Color(0xFFACC7FF), secondary = Color(0xFFBBC7DE), tertiary = Color(0xFFC8BFE8))
    else lightColorScheme(primary = Color(0xFF315DA8), secondary = Color(0xFF56647D), tertiary = Color(0xFF695C86), surface = Color(0xFFFAF9FF))
    CompositionLocalProvider(LocalAppDark provides dark) { MaterialTheme(colorScheme = colors, content = content) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhysicsApp(app: ArxivApp) = PhysicsTheme {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var webOpen by rememberSaveable { mutableStateOf(false) }
    var preview by rememberSaveable { mutableStateOf<String?>(null) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var categoryDialog by remember { mutableStateOf(false) }
    val latest = remember { FeedController(app) }
    val search = remember { FeedController(app) }
    val paper = selected?.let { app.store.paper(it) }
    val previewPaper = preview?.let { app.store.paper(it) }
    if (settingsOpen) {
        SettingsScreen(app) { settingsOpen = false }
    } else if (previewPaper != null) {
        FullscreenPreview(app, previewPaper) { preview = null }
    } else if (webOpen) {
        FullscreenDeepSeek(app, { webOpen = false }, { webOpen = false; selected = it.id; preview = it.id })
    } else {
        BackHandler(paper != null) { selected = null }
        Scaffold(topBar = {
            if (paper != null) TopAppBar(title = { Text("论文详情") }, navigationIcon = {
                IconButton(onClick = { selected = null }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回列表") }
            }) else CenterAlignedTopAppBar(title = { Text(listOf("arXiv Physics", "历史检索", "资料库", "DeepSeek 网页")[tab], fontWeight = FontWeight.SemiBold) }, actions = {
                IconButton(onClick = { settingsOpen = true }) { Icon(Icons.Outlined.Settings, "设置") }
                if (tab == 0 || tab == 1) IconButton(onClick = { categoryDialog = true }) { Icon(Icons.Outlined.Tune, "管理订阅领域") }
            })
        }, bottomBar = {
            if (paper == null) NavigationBar {
                val labels = listOf("动态", "检索", "资料库", "网页")
                val icons = listOf(Icons.Outlined.Explore, Icons.Outlined.Search, Icons.Outlined.Bookmarks, Icons.Outlined.AccountCircle)
                labels.forEachIndexed { index, title -> NavigationBarItem(selected = tab == index, onClick = { tab = index }, icon = { Icon(icons[index], title) }, label = { Text(title) }) }
            }
        }) { insets ->
            Box(Modifier.padding(insets).fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                Box(Modifier.widthIn(max = 840.dp).fillMaxSize()) {
                    if (paper != null) PaperDetail(app, paper, { webOpen = true }, { preview = it.id })
                    else when (tab) {
                        0 -> LatestScreen(app, latest, { selected = it.id }, { categoryDialog = true })
                        1 -> SearchScreen(app, search, { selected = it.id })
                        2 -> LibraryScreen(app, { selected = it.id }, { webOpen = true })
                        3 -> AccountScreen { webOpen = true }
                    }
                }
            }
        }
    }
    if (categoryDialog) CategoryPicker("订阅物理领域", app.subscriptions, { categoryDialog = false }) { app.subscribe(it); categoryDialog = false }
}

@Composable
private fun LatestScreen(app: ArxivApp, controller: FeedController, open: (Paper) -> Unit, choose: () -> Unit) {
    var mode by rememberSaveable { mutableIntStateOf(1) }
    val query = when (mode) { 0 -> Physics.latestAllQuery(); 1 -> Physics.query(Physics.categories.map { it.id }); else -> Physics.query(app.subscriptions) }
    LaunchedEffect(query) { if (mode != 2 || app.subscriptions.isNotEmpty()) controller.load(query) }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 24.dp, vertical = 16.dp)) {
            Text("追踪物理前沿", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Text("从最新预印本，到读懂每一个公式。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("全站", "全部物理", "我的领域").forEachIndexed { i, label ->
                    FilterChip(selected = mode == i, onClick = { mode = i }, label = { Text(label) })
                }
            }
            if (mode == 2) TextButton(onClick = choose) { Text("已订阅 ${app.subscriptions.size} 个分类 · 管理") }
        }
        if (mode == 2 && app.subscriptions.isEmpty()) EmptyState(Icons.Outlined.Category, "还没有订阅领域", "选择感兴趣的物理分支，建立你的研究动态。", "选择领域", choose)
        else FeedList(controller, open, Modifier.weight(1f))
    }
}

@Composable
private fun SearchScreen(app: ArxivApp, controller: FeedController, open: (Paper) -> Unit) {
    var keywords by rememberSaveable { mutableStateOf("") }
    var from by rememberSaveable { mutableStateOf("") }
    var to by rememberSaveable { mutableStateOf("") }
    var ascending by rememberSaveable { mutableStateOf(false) }
    var selected by remember { mutableStateOf(app.subscriptions.ifEmpty { setOf("quant-ph") }) }
    var picker by remember { mutableStateOf(false) }
    var validation by remember { mutableStateOf<String?>(null) }
    var submitted by rememberSaveable { mutableStateOf(false) }
    var expanded by rememberSaveable { mutableStateOf(true) }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("按时间探索论文", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                if (submitted) TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起" else "筛选") }
            }
            if (expanded) {
                OutlinedTextField(value = keywords, onValueChange = { keywords = it }, label = { Text("标题、作者或关键词") },
                    leadingIcon = { Icon(Icons.Outlined.Search, null) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = from, onValueChange = { from = it }, label = { Text("开始日期") }, placeholder = { Text("YYYY-MM-DD") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(value = to, onValueChange = { to = it }, label = { Text("结束日期") }, placeholder = { Text("YYYY-MM-DD") }, singleLine = true, modifier = Modifier.weight(1f))
                }
                Text("日期采用 arXiv UTC 提交日期，留空表示不限。", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { picker = true }, modifier = Modifier.weight(1f)) { Text("${selected.size} 个领域 · 选择") }
                    FilterChip(selected = ascending, onClick = { ascending = !ascending }, label = { Text(if (ascending) "从旧到新" else "从新到旧") })
                }
                if (validation != null) Text(validation!!, color = MaterialTheme.colorScheme.error)
                Button(onClick = {
                    runCatching { SearchSpec(selected, keywords, from, to, ascending).query() }.onSuccess {
                        validation = null; submitted = true; expanded = false; controller.load(it, ascending)
                    }.onFailure { validation = if (it is java.time.format.DateTimeParseException) "日期格式应为 YYYY-MM-DD，且必须是有效日期。" else it.message }
                }, enabled = !controller.loading, modifier = Modifier.fillMaxWidth()) { Text("检索论文") }
            }
            Spacer(Modifier.height(8.dp))
        }
        if (submitted) FeedList(controller, open, Modifier.weight(1f))
        else EmptyState(Icons.Outlined.History, "探索过去的研究", "选定领域和日期范围，按时间轴查找相关论文。")
    }
    if (picker) CategoryPicker("检索领域", selected, { picker = false }) { selected = it; picker = false }
}

@Composable
private fun FeedList(controller: FeedController, open: (Paper) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${controller.total} 篇结果", style = MaterialTheme.typography.labelLarge)
                if (controller.fetched > 0) Text((if (controller.stale) "离线缓存 · " else "更新于 ") + SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(controller.fetched)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { controller.refresh() }, enabled = !controller.loading) { Icon(Icons.Outlined.Refresh, "刷新论文") }
        }
        if (controller.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (controller.error != null) {
            ErrorCard(controller.error!!, "重试") { controller.refresh() }
        }
        if (controller.papers.isEmpty() && !controller.loading && controller.error == null) EmptyState(Icons.AutoMirrored.Outlined.Article, "没有找到论文", "尝试其他关键词、分类或时间范围。")
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val grouped = controller.papers.groupBy { it.published.take(10) }
            grouped.forEach { (date, papers) ->
                item(key = "date:$date") { Text(date, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 8.dp, top = 8.dp)) }
                items(papers, key = { it.id }) { PaperCard(it) { open(it) } }
            }
            if (controller.hasMore) item { OutlinedButton(onClick = { controller.more() }, enabled = !controller.loading, modifier = Modifier.fillMaxWidth()) { Text("加载更多") } }
            if (controller.papers.isNotEmpty() && !controller.hasMore && !controller.loading) item { Text("已加载全部结果", modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun PaperCard(p: Paper, chineseTitle: String? = null, open: () -> Unit) {
    ElevatedCard(onClick = open, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(p.primary, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                Text(p.id, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            BilingualTitle(p.title, chineseTitle)
            Text(p.authors.joinToString(", "), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(p.summary.replace('\n', ' '), style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun PaperDetail(app: ArxivApp, p: Paper, onWeb: () -> Unit, onPreview: (Paper) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val job = app.jobs.find { it.id == p.id }
    var saved by remember(p.id) { mutableStateOf(app.store.saved(p.id)) }
    var downloaded by remember(p.id) { mutableStateOf(app.repository.pdf(p).exists()) }
    var hasMarkdown by remember(p.id, job?.updated) { mutableStateOf(app.repository.markdown(p).exists()) }
    var downloading by remember { mutableStateOf(false) }
    var progress by remember { mutableIntStateOf(0) }
    var notice by remember { mutableStateOf<String?>(null) }
    fun download(openWeb: Boolean) {
        scope.launch {
            downloading = true; notice = null
            try {
                val file = app.repository.download(p) { percent -> scope.launch { progress = percent } }
                downloaded = true
                if (openWeb) { app.web.prepare(p, file); onWeb() }
                else app.recordDownloaded(p)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { notice = e.message ?: "下载失败，请重试" }
            finally { downloading = false }
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(p.categories.joinToString(" · "), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = { saved = !saved; app.store.save(p.id, saved) }) { Icon(if (saved) Icons.Outlined.BookmarkAdded else Icons.Outlined.BookmarkAdd, if (saved) "取消收藏" else "收藏论文") }
        }
        if (hasMarkdown) FilledTonalButton(onClick = { onPreview(p) }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.MenuBook, null); Spacer(Modifier.width(8.dp)); Text("全屏阅读本地译文") }
        SelectionContainer { Text(p.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold) }
        Text(p.authors.joinToString(", "), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("提交 ${p.published.take(10)} · 更新 ${p.updated.take(10)}\narXiv:${p.id}", style = MaterialTheme.typography.bodySmall)
        HorizontalDivider()
        Text("摘要", style = MaterialTheme.typography.titleMedium)
        SelectionContainer { Text(p.summary, style = MaterialTheme.typography.bodyLarge) }
        if (p.comment.isNotBlank()) Text("备注：${p.comment}")
        if (p.journal.isNotBlank()) Text("期刊：${p.journal}")
        if (p.doi.isNotBlank()) Text("DOI：${p.doi}")
        TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(p.absUrl))) }) { Text("在 arXiv 查看") }
        if (downloading) {
            Text(if (progress < 0) "正在下载 PDF" else "正在下载 PDF · $progress%")
            if (progress < 0) LinearProgressIndicator(Modifier.fillMaxWidth()) else LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
        }
        OutlinedButton(onClick = { if (downloaded) openPdf(context, app.repository.pdf(p)) { notice = it } else download(false) }, enabled = !downloading, modifier = Modifier.fillMaxWidth()) { Text(if (downloaded) "打开本地 PDF" else "下载 PDF") }
        if (downloaded) TextButton(onClick = { shareFile(context, app.repository.pdf(p), "application/pdf") }) { Text("导出 / 分享 PDF") }
        Button(onClick = { download(true) }, enabled = !downloading, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Outlined.Translate, null); Spacer(Modifier.width(8.dp)); Text(if (downloaded) "${if (hasMarkdown) "重新" else ""}翻译 · 打开 DeepSeek" else "下载并翻译 · 打开 DeepSeek")
        }
        Text("打开网页后自动准备 PDF 和指令，由你确认解析完成并手动发送。复制回答超过 500 字符时自动保存，并打开本篇论文的全屏译文。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (notice != null) ErrorCard(notice!!, "关闭") { notice = null }
    }
}

@Composable
private fun LibraryScreen(app: ArxivApp, open: (Paper) -> Unit, account: () -> Unit) {
    var section by rememberSaveable { mutableIntStateOf(0) }
    val saved = remember(app.jobs, section) { app.store.library() }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = section == 0, onClick = { section = 0 }, label = { Text("收藏") })
            FilterChip(selected = section == 1, onClick = { section = 1 }, label = { Text("下载与翻译") })
        }
        if (section == 0 && saved.isEmpty()) EmptyState(Icons.Outlined.Bookmarks, "收藏值得重读的论文", "在论文详情中点击收藏，稍后可从这里离线查看基本信息。")
        else if (section == 1 && app.jobs.isEmpty()) EmptyState(Icons.Outlined.Translate, "还没有保存记录", "下载论文后会显示在这里；手动复制译文后保存到本地。")
        else LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (section == 0) items(saved, key = { it.id }) { p ->
                val chinese = savedTitle(app, p)
                PaperCard(p, chinese) { open(p) }
            }
            else items(app.jobs, key = { it.id }) { job ->
                val paper = app.store.paper(job.id)
                ElevatedCard(onClick = { if (paper != null) open(paper) }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        BilingualTitle(paper?.title ?: job.id, paper?.let { savedTitle(app, it) })
                        JobCard(job, compact = true)
                        if (job.stage in setOf(Stage.FAILED, Stage.PARTIAL, Stage.INTERRUPTED)) TextButton(onClick = account) { Text("查看 DeepSeek 网页") }
                    }
                }
            }
        }
    }
}

@Composable
private fun JobCard(job: TranslationJob, compact: Boolean = false) {
    if (job.stage == Stage.DOWNLOADED) {
        Text("本地文件已下载", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
        return
    }
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().padding(if (compact) 12.dp else 0.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(job.stage.label, style = MaterialTheme.typography.labelLarge)
            Text(job.message, style = MaterialTheme.typography.bodySmall)
            if (job.stage == Stage.DOWNLOADING) {
                if (job.progress < 0) LinearProgressIndicator(Modifier.fillMaxWidth())
                else LinearProgressIndicator(progress = { job.progress / 100f }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun AccountScreen(open: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("DeepSeek 官方网页", style = MaterialTheme.typography.headlineSmall)
        Text("在应用内全屏登录和对话。论文详情的翻译按钮会准备 PDF 与全文翻译指令，由你手动发送，复制回答超过 500 字符后，自动保存并打开本地译文。")
        Button(onClick = open, modifier = Modifier.fillMaxWidth()) { Text("全屏打开 DeepSeek") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FullscreenDeepSeek(app: ArxivApp, close: () -> Unit, preview: (Paper) -> Unit) {
    val context = LocalContext.current
    val engine = remember { app.web }
    val importer = remember { ClipboardTranslationImporter(app) }
    var menu by remember { mutableStateOf(false) }
    var logout by remember { mutableStateOf(false) }
    var preparationRun by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val lifecycle = (context as ComponentActivity).lifecycle
    var foreground by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ -> foreground = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(foreground, engine.paper?.id, preparationRun) { if (foreground) engine.monitor() }
    val currentPreview by rememberUpdatedState(preview)
    DisposableEffect(foreground, engine.paper?.id) {
        val paper = engine.paper
        if (foreground && paper != null) importer.start(paper, { currentPreview(it) }, { engine.showStatus(it) })
        onDispose { importer.stop() }
    }
    BackHandler { close() }
    Scaffold(contentWindowInsets = WindowInsets.safeDrawing, topBar = {
        TopAppBar(title = { Text("DeepSeek", maxLines = 1) }, navigationIcon = { IconButton(onClick = close) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回论文") } }, actions = {
            if (engine.savedId != null) IconButton(onClick = { engine.paper?.let(preview) }) { Icon(Icons.Outlined.MenuBook, "全屏阅读已保存译文") }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "网页操作") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("重新加载网页") }, onClick = { engine.reload(); menu = false })
                    if (engine.paper != null) DropdownMenuItem(text = { Text("重新准备附件与指令") }, onClick = { engine.retryAttachment(); preparationRun++; menu = false })
                    if (engine.paper != null) DropdownMenuItem(text = { Text("填入继续翻译指令") }, onClick = { scope.launch { engine.prepareContinuation() }; menu = false })
                    DropdownMenuItem(text = { Text("退出登录") }, onClick = { logout = true; menu = false })
                }
            }
        })
    }) { insets ->
        Column(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets).imePadding()) {
            if (engine.paper != null) Text(engine.status, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
            AndroidView(modifier = Modifier.fillMaxWidth().weight(1f), factory = {
                engine.attachContext(context)
                (engine.view.parent as? ViewGroup)?.removeView(engine.view)
                FrameLayout(context).apply { addView(engine.view, FrameLayout.LayoutParams(-1, -1)) }
            }, onRelease = { it.removeAllViews(); engine.detachContext() })
        }
    }
    if (logout) AlertDialog(onDismissRequest = { logout = false }, title = { Text("退出 DeepSeek？") }, text = { Text("已保存的论文和译文会保留。") }, confirmButton = { TextButton(onClick = { engine.logout(); logout = false }) { Text("退出") } }, dismissButton = { TextButton(onClick = { logout = false }) { Text("取消") } })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FullscreenPreview(app: ArxivApp, paper: Paper, close: () -> Unit) {
    val context = LocalContext.current
    var text by remember(paper.id) { mutableStateOf<String?>(null) }
    val version = app.jobs.find { it.id == paper.id }?.updated
    LaunchedEffect(paper.id, version) { text = withContext(Dispatchers.IO) { app.repository.markdown(paper).let { if (it.exists()) it.readText() else "" } } }
    BackHandler(onBack = close)
    Scaffold(topBar = { TopAppBar(title = { Text("中文译文", maxLines = 1) }, navigationIcon = { IconButton(onClick = close) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") } }, actions = {
        if (!text.isNullOrEmpty()) IconButton(onClick = { shareFile(context, app.repository.markdown(paper), "text/markdown") }) { Icon(Icons.Outlined.Share, "导出 Markdown") }
    }) }) { insets ->
        Box(Modifier.fillMaxSize().padding(insets)) {
            if (text == null) CircularProgressIndicator(Modifier.align(Alignment.Center))
            else if (text!!.isEmpty()) EmptyState(Icons.Outlined.MenuBook, "还没有译文", "请在 DeepSeek 中发送翻译请求，复制回答超过 500 字符后自动保存并预览。")
            else MarkdownReader(text!!, LocalAppDark.current, Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun CategoryPicker(title: String, initial: Set<String>, dismiss: () -> Unit, apply: (Set<String>) -> Unit) {
    var selected by remember { mutableStateOf(initial) }
    var filter by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = dismiss, title = { Text(title) }, text = {
        Column {
            OutlinedTextField(value = filter, onValueChange = { filter = it }, label = { Text("搜索名称或分类代码") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row {
                TextButton(onClick = { selected = Physics.categories.map { it.id }.toSet() }) { Text("全选") }
                TextButton(onClick = { selected = emptySet() }) { Text("清空") }
                Text("${selected.size}/${Physics.categories.size}", modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.labelMedium)
            }
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                Physics.categories.filter { it.id.contains(filter, true) || it.name.contains(filter) || it.group.contains(filter) }.groupBy { it.group }.forEach { (group, categories) ->
                    item { Text(group, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.titleSmall) }
                    items(categories, key = { it.id }) { c ->
                        Surface(onClick = { selected = if (c.id in selected) selected - c.id else selected + c.id }, modifier = Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = c.id in selected, onCheckedChange = { selected = if (it) selected + c.id else selected - c.id })
                                Column { Text(c.name, style = MaterialTheme.typography.bodyMedium); Text(c.id, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                        }
                    }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = { apply(selected) }, enabled = selected.isNotEmpty()) { Text("保存选择") } }, dismissButton = { TextButton(onClick = dismiss) { Text("取消") } })
}

@Composable
private fun EmptyState(icon: ImageVector, title: String, description: String, action: String? = null, onAction: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, null, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (action != null) FilledTonalButton(onClick = onAction) { Text(action) }
    }
}

@Composable
private fun ErrorCard(message: String, action: String, callback: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium, modifier = Modifier.padding(16.dp).fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) { Text(message); TextButton(onClick = callback) { Text(action) } }
    }
}

private fun shareFile(context: android.content.Context, file: java.io.File, type: String) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(type).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "导出文件"))
}
private fun openPdf(context: android.content.Context, file: java.io.File, failure: (String) -> Unit) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/pdf").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
        .onFailure { failure("未找到 PDF 阅读器，请使用「导出 / 分享 PDF」保存文件。") }
}

@Composable
private fun savedTitle(app: ArxivApp, p: Paper): String? {
    val version = app.jobs.find { it.id == p.id }?.updated
    val title by produceState<String?>(null, p.id, version) {
        value = withContext(Dispatchers.IO) {
            runCatching { app.repository.markdown(p).bufferedReader().use { reader ->
                val prefix = CharArray(12000)
                val count = reader.read(prefix)
                TranslationTitle.extract(if (count > 0) String(prefix, 0, count) else "")
            } }.getOrNull()
        }
    }
    return title
}

@Composable
private fun BilingualTitle(original: String, translated: String?) {
    if (translated != null) Text(translated, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    Text(original, style = if (translated == null) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
        color = if (translated == null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(app: ArxivApp, close: () -> Unit) {
    BackHandler(onBack = close)
    Scaffold(topBar = { TopAppBar(title = { Text("设置") }, navigationIcon = {
        IconButton(onClick = close) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") }
    }) }) { insets ->
        Column(Modifier.fillMaxSize().padding(insets).verticalScroll(rememberScrollState()).padding(24.dp)) {
            Text("外观模式", style = MaterialTheme.typography.titleLarge)
            Text("即时应用到界面与 Markdown 阅读器", modifier = Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            ThemeMode.entries.forEach { mode ->
                Surface(onClick = { app.setTheme(mode) }, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = app.themeMode == mode, onClick = { app.setTheme(mode) })
                        Text(mode.label, modifier = Modifier.padding(start = 12.dp))
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
            UpdateSettings(app)

        }
    }
}

@Composable
private fun UpdateSettings(app: ArxivApp) {
    val manager = app.updates
    val context = LocalContext.current
    val activity = context as ComponentActivity
    val scope = rememberCoroutineScope()
    val active = remember { AtomicBoolean(true) }
    var installing by remember { mutableStateOf(false) }
    var mirrorInput by remember { mutableStateOf(manager.mirror) }
    DisposableEffect(Unit) { active.set(true); onDispose { active.set(false) } }
    LaunchedEffect(Unit) { manager.restore() }
    val installer = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        installing = false
        manager.notice("安装界面已关闭；若尚未安装，可再次点击安装")
    }
    fun install() {
        if (installing) return
        installing = true
        scope.launch {
            try { installer.launch(manager.installIntent()) }
            catch (e: kotlinx.coroutines.CancellationException) { installing = false; throw e }
            catch (e: Exception) { installing = false; manager.notice("无法打开安装界面：${e.message}") }
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (app.packageManager.canRequestPackageInstalls()) install()
        else manager.notice("未允许安装更新；请允许后再点击安装")
    }
    fun requestInstall() {
        if (!active.get() || !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            manager.notice("更新已下载，可在设置中点击安装"); return
        }
        if (app.packageManager.canRequestPackageInstalls()) install()
        else {
            manager.notice("请允许此应用安装更新，然后返回继续")
            runCatching { permission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}"))) }
                .onFailure { manager.notice("无法打开安装权限，请在系统设置中允许安装未知应用") }
        }
    }
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("应用更新", style = MaterialTheme.typography.titleLarge)
            Text("当前版本 ${BuildConfig.VERSION_NAME}")
            Text(manager.message, style = MaterialTheme.typography.bodyMedium)
            if (manager.busy) {
                if (manager.message.startsWith("正在下载")) LinearProgressIndicator(progress = { manager.progress }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            OutlinedButton(onClick = { manager.check() }, enabled = !manager.busy && !installing, modifier = Modifier.fillMaxWidth()) { Text("检查更新") }
            manager.release?.let { info ->
                if (info.notes.isNotBlank()) Text(info.notes, style = MaterialTheme.typography.bodySmall)
                Button(onClick = { if (manager.ready) requestInstall() else manager.download { requestInstall() } }, enabled = !manager.busy && !installing, modifier = Modifier.fillMaxWidth()) {
                    Text(if (manager.ready) "安装 ${info.versionName}" else "下载并安装 ${info.versionName} · ${info.size / 1024 / 1024} MB")
                }
            }
            OutlinedTextField(value = mirrorInput, onValueChange = { mirrorInput = it },
                label = { Text("备用镜像清单地址（可选）") }, placeholder = { Text("https://你的服务器/update.json") },
                singleLine = true, enabled = !manager.busy, modifier = Modifier.fillMaxWidth())
            TextButton(onClick = {
                runCatching { manager.saveMirror(mirrorInput) }.onFailure { manager.notice(it.message ?: "镜像地址无效") }
            }, enabled = !manager.busy) { Text("保存镜像设置") }
            Text("同时检查 GitHub 和 CDN 备用清单。国内镜像需放置 update.json 及同目录 APK；安装前校验 SHA-256 和原应用签名。安装需在系统界面确认。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
