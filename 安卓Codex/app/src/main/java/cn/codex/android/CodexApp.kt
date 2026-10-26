@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package cn.codex.android

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.codex.core.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

private data class Destination(val id: String, val title: String, val icon: ImageVector)
private val destinations = listOf(
    Destination("files", "项目文件", Icons.Outlined.FolderOpen), Destination("changes", "查看修改", Icons.Outlined.Difference),
    Destination("terminal", "本机终端", Icons.Outlined.Terminal), Destination("skills", "项目技能", Icons.Outlined.AutoAwesome),
    Destination("mcp", "工具连接", Icons.Outlined.Extension), Destination("settings", "连接与偏好", Icons.Outlined.Tune),
    Destination("capabilities", "功能与限制", Icons.Outlined.Info)
)

@Composable fun CodexApp(controller: RuntimeController, state: ScreenState, send: (String, Boolean) -> Unit) {
    CompositionLocalProvider(LocalMotionEnabled provides state.config.motion) { CodexLayout(controller, state, send) }
}
private object NoMotion: MotionDurationScale { override val scaleFactor = 0f }
@Composable private fun CodexLayout(controller: RuntimeController, state: ScreenState, send: (String, Boolean) -> Unit) {
    var page by rememberSaveable { mutableStateOf("chat") }
    val composer: ComposerState = viewModel(key = "composer:${state.project}")
    var draft by composer.draft
    var search by rememberSaveable { mutableStateOf("") }
    var archived by rememberSaveable { mutableStateOf(false) }
    var projectDialog by remember { mutableStateOf(false) }
    var editDialog by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<SessionSummary?>(null) }
    var revertDialog by remember { mutableStateOf(false) }
    var moveTarget by remember { mutableStateOf<SessionSummary?>(null) }
    var sectionManager by remember { mutableStateOf(false) }
    var renameSectionTarget by remember { mutableStateOf<SessionSection?>(null) }
    var newSectionName by remember { mutableStateOf("") }
    var selectedSection by rememberSaveable(state.project) { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    var newValue by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val haptic = LocalHapticFeedback.current
    fun setDrawer(open: Boolean) {
        scope.launch { if (state.config.motion) { if (open) drawer.open() else drawer.close() } else withContext(NoMotion) { if (open) drawer.open() else drawer.close() } }
    }
    fun navigate(id: String) { page = id; setDrawer(false) }
    BackHandler(drawer.isOpen || page != "chat") { if (drawer.isOpen) setDrawer(false) else page = "chat" }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 840.dp
        val side: @Composable () -> Unit = {
            Column(Modifier.fillMaxHeight().width(304.dp).background(MaterialTheme.colorScheme.surfaceContainerLow).windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = 12.dp)) {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Code, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(27.dp))
                    Spacer(Modifier.width(10.dp)); Text("Codex", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.weight(1f)); Text("随行", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                }
                FilledTonalButton(onClick = { controller.newSession(); draft = ""; navigate("chat") }, enabled = !state.busy, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(14.dp)) {
                    Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(8.dp)); Text("开启新对话"); Spacer(Modifier.weight(1f)); Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, Modifier.size(18.dp))
                }
                TextButton(onClick = { newValue = ""; projectDialog = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.Folder, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(state.project, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)); Icon(Icons.Outlined.UnfoldMore, "切换项目", Modifier.size(18.dp))
                }
                destinations.forEach { d ->
                    NavigationDrawerItem(label = { Text(d.title, style = MaterialTheme.typography.bodyMedium) }, selected = page == d.id, onClick = { navigate(d.id) }, icon = { Icon(d.icon, null, Modifier.size(20.dp)) }, modifier = Modifier.height(45.dp))
                }
                HorizontalDivider(Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)
                Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (archived) "已归档会话" else "最近会话", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    IconButton(onClick = { archived = !archived }) { Icon(if (archived) Icons.Outlined.Unarchive else Icons.Outlined.Inventory2, if (archived) "显示最近会话" else "显示归档会话", Modifier.size(19.dp)) }
                }
                OutlinedTextField(search, { search = it }, placeholder = { Text("搜索会话标题") }, leadingIcon = { Icon(Icons.Outlined.Search, null, Modifier.size(18.dp)) }, singleLine = true, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("会话分类", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { sectionManager = true; newSectionName = "" }, enabled = !state.busy) { Text("管理分类") }
                }
                val sections = state.sections.filter { it.project == state.project }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    item { FilterChip(selected = selectedSection == "", onClick = { selectedSection = "" }, label = { Text("全部") }) }
                    item { FilterChip(selected = selectedSection == "__ungrouped__", onClick = { selectedSection = "__ungrouped__" }, label = { Text("未分类") }) }
                    items(sections, key = { it.id }) { section ->
                        FilterChip(selected = selectedSection == section.id, onClick = { selectedSection = section.id }, label = { Text(section.name, maxLines = 1) })
                    }
                }
                val visible = state.sessions.filter { it.project == state.project && it.archived == archived && it.title.contains(search, true) && (
                    selectedSection == "" || (selectedSection == "__ungrouped__" && it.sectionId == null) || selectedSection == it.sectionId
                ) }
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 12.dp)) {
                    items(visible, key = { it.id }) { s ->
                        ListItem(headlineContent = { Text(s.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium) }, leadingContent = { Icon(Icons.Outlined.ChatBubbleOutline, null, Modifier.size(17.dp)) }, trailingContent = { Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { controller.archive(s.id) }, enabled = !state.busy) { Icon(if (s.archived) Icons.Outlined.Unarchive else Icons.Outlined.Archive, if (s.archived) "恢复会话" else "归档会话", Modifier.size(17.dp)) }
                            IconButton(onClick = { moveTarget = s }, enabled = !state.busy) { Icon(Icons.Outlined.FolderOpen, "移动到分类", Modifier.size(17.dp)) }
                            IconButton(onClick = { deleteTarget = s }, enabled = !state.busy) { Icon(Icons.Outlined.DeleteOutline, "删除会话", Modifier.size(17.dp)) }
                        } }, colors = ListItemDefaults.colors(containerColor = if (state.session?.id == s.id && page == "chat") MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainerLow), modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(enabled = !state.busy) { controller.openSession(s.id); navigate("chat") })
                    }
                    if (visible.isEmpty()) item { Text("${if (archived) "还没有归档" else "从一个想法开始"}", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium) }
                }
                Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Smartphone, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("手机独立运行", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        val content: @Composable () -> Unit = {
            Scaffold(containerColor = MaterialTheme.colorScheme.background, topBar = {
                TopAppBar(title = {
                    Column {
                        Text(if (page == "chat") "Codex" else destinations.firstOrNull { it.id == page }?.title ?: "Codex", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                        Text(if (page == "chat") state.project else "手机工作区", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                }, navigationIcon = {
                    IconButton(onClick = { if (page != "chat") page = "chat" else if (!wide) setDrawer(true) }) { Icon(if (page == "chat") Icons.Outlined.Menu else Icons.AutoMirrored.Outlined.ArrowBack, if (page == "chat") "打开侧栏" else "返回对话") }
                }, actions = {
                    if (state.busy) IconButton(onClick = controller::stop) { Icon(Icons.Outlined.StopCircle, "停止任务", tint = MaterialTheme.colorScheme.primary) }
                    if (page == "chat") {
                        IconButton(onClick = { page = "files" }) { Icon(Icons.Outlined.FolderOpen, "打开项目文件", Modifier.size(22.dp)) }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreHoriz, "对话操作") }
                            DropdownMenu(menu, { menu = false }) {
                                DropdownMenuItem(text = { Text("重命名对话") }, onClick = { menu = false; editDialog = "rename"; newValue = state.session?.title.orEmpty() }, enabled = !state.busy)
                                DropdownMenuItem(text = { Text("从这里分叉") }, onClick = { menu = false; controller.fork() }, enabled = !state.busy)
                                DropdownMenuItem(text = { Text("设置任务目标") }, onClick = { menu = false; editDialog = "goal"; newValue = state.session?.goal.orEmpty() }, enabled = !state.busy)
                                DropdownMenuItem(text = { Text("压缩上下文") }, onClick = { menu = false; controller.compactContext() }, enabled = !state.busy && (state.session?.wire?.count { (it as? JsonObject)?.string("role") == "user" } ?: 0) >= 5)
                                DropdownMenuItem(text = { Text("审查当前修改") }, onClick = { menu = false; draft = "审查当前项目的修改，先查看 Git 差异，再检查错误、回归和遗漏，给出带文件位置的中文结论。" }, enabled = !state.busy)
                                DropdownMenuItem(text = { Text("撤销最近任务修改") }, onClick = { menu = false; revertDialog = true }, enabled = !state.busy && state.session?.lastTurnId != null)
                            }
                        }
                    }
                }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background))
            }) { padding ->
                Column(Modifier.fillMaxSize().padding(padding)) {
                    state.error?.let { error ->
                        Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) { Text(error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(vertical = 8.dp)); IconButton(onClick = controller::dismissError) { Icon(Icons.Outlined.Close, "关闭提示") } }
                        }
                    }
                    AnimatedContent(targetState = page, transitionSpec = { fadeIn(tween(if (state.config.motion) 220 else 0)) + slideInHorizontally { it / 32 } togetherWith fadeOut(tween(if (state.config.motion) 120 else 0)) }, label = "页面切换", modifier = Modifier.weight(1f)) { current ->
                        when (current) {
                            "chat" -> ChatScreen(state, draft, { draft = it }, { page = "settings" }, controller, { text ->
                                if (state.config.model.isBlank() || (state.config.apiKey.isBlank() && !state.config.allowLoopbackHttp)) { controller.fail("先连接模型，再开始对话"); page = "settings" }
                                else { haptic.performHapticFeedback(HapticFeedbackType.LongPress); send(text, false); draft = "" }
                            })
                            "files" -> FilesScreen(controller, state)
                            "changes" -> ChangesScreen(controller, state)
                            "terminal" -> TerminalScreen(state, controller) { send(it, true) }
                            "settings" -> SettingsScreen(controller, state)
                            "skills" -> SkillsScreen(controller, state)
                            "mcp" -> McpScreen(controller)
                            else -> CapabilitiesScreen()
                        }
                    }
                }
            }
        }
        if (wide) Row { side(); Box(Modifier.weight(1f)) { content() } }
        else ModalNavigationDrawer(drawerState = drawer, gesturesEnabled = drawer.isOpen, drawerContent = { ModalDrawerSheet(drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerLow) { side() } }) { content() }
    }
    state.approval?.let { approval -> ApprovalSheet(approval, controller::decide) }
    if (revertDialog) AlertDialog(onDismissRequest = { revertDialog = false }, title = { Text("撤销最近任务修改？") }, text = {
        Text("仅撤销本轮通过文件工具记录的修改。命令、Git 提交和其他外部副作用无法自动还原；文件被其他操作修改后将拒绝覆盖。")
    }, confirmButton = { TextButton(onClick = { controller.revertLastTask(); revertDialog = false }, enabled = !state.busy) { Text("确认撤销") } }, dismissButton = { TextButton(onClick = { revertDialog = false }) { Text("取消") } })
    moveTarget?.let { target ->
        AlertDialog(onDismissRequest = { moveTarget = null }, title = { Text("移动到分类") }, text = {
            Column {
                TextButton(onClick = { controller.moveSession(target.id, null); moveTarget = null }) { Text("未分类") }
                state.sections.filter { it.project == target.project }.forEach { section ->
                    TextButton(onClick = { controller.moveSession(target.id, section.id); moveTarget = null }) { Text(section.name) }
                }
            }
        }, confirmButton = { TextButton(onClick = { moveTarget = null }) { Text("取消") } })
    }
    if (sectionManager) AlertDialog(onDismissRequest = { sectionManager = false }, title = { Text("管理会话分类") }, text = {
        Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(newSectionName, { newSectionName = it }, label = { Text("新分类名称") }, singleLine = true)
            state.sections.filter { it.project == state.project }.forEach { section ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(section.name, Modifier.weight(1f))
                    IconButton(onClick = { renameSectionTarget = section; newValue = section.name; sectionManager = false }) { Icon(Icons.Outlined.Edit, "重命名分类") }
                    IconButton(onClick = { controller.deleteSection(section.id) }, enabled = !state.busy) { Icon(Icons.Outlined.DeleteOutline, "删除分类") }
                }
            }
            Text("分类下仍有会话时，需先将会话移出才可删除。", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
        }
    }, confirmButton = { TextButton(onClick = { controller.createSection(newSectionName); newSectionName = "" }, enabled = newSectionName.isNotBlank() && !state.busy) { Text("新建分类") } }, dismissButton = { TextButton(onClick = { sectionManager = false }) { Text("关闭") } })
    renameSectionTarget?.let { section ->
        AlertDialog(onDismissRequest = { renameSectionTarget = null }, title = { Text("重命名分类") }, text = {
            OutlinedTextField(newValue, { newValue = it }, singleLine = true, label = { Text("分类名称") })
        }, confirmButton = { TextButton(onClick = { controller.renameSection(section.id, newValue); renameSectionTarget = null }, enabled = newValue.isNotBlank() && !state.busy) { Text("保存") } }, dismissButton = { TextButton(onClick = { renameSectionTarget = null }) { Text("取消") } })
    }
    deleteTarget?.let { target ->
        AlertDialog(onDismissRequest = { deleteTarget = null }, title = { Text("删除会话？") }, text = { Text("将永久删除「${target.title}」的会话记录。已修改的项目文件不会删除。") }, confirmButton = {
            TextButton(onClick = { controller.deleteSession(target.id); deleteTarget = null }, enabled = !state.busy) { Text("确认删除") }
        }, dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } })
    }
    if (projectDialog) AlertDialog(onDismissRequest = { projectDialog = false }, title = { Text("项目") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            state.projects.forEach { p -> TextButton(onClick = { controller.newSession(p); projectDialog = false; page = "chat" }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.Folder, null); Spacer(Modifier.width(12.dp)); Text(p, Modifier.weight(1f), maxLines = 1) } }
            OutlinedTextField(newValue, { newValue = it }, label = { Text("新项目名称") }, singleLine = true)
        }
    }, confirmButton = { TextButton(onClick = { runCatching { controller.newSession(newValue.trim()); projectDialog = false; newValue = "" }.onFailure { controller.fail(it.message.orEmpty()) } }, enabled = !state.busy && newValue.isNotBlank()) { Text("创建项目") } }, dismissButton = { TextButton(onClick = { projectDialog = false }) { Text("关闭") } })
    if (editDialog.isNotBlank()) AlertDialog(onDismissRequest = { editDialog = "" }, title = { Text(if (editDialog == "rename") "对话名称" else "任务目标") }, text = { OutlinedTextField(newValue, { newValue = it }, minLines = if (editDialog == "goal") 3 else 1) }, confirmButton = { TextButton(onClick = { if (editDialog == "rename") controller.rename(newValue) else controller.goal(newValue); editDialog = "" }) { Text("保存") } }, dismissButton = { TextButton(onClick = { editDialog = "" }) { Text("取消") } })
}
