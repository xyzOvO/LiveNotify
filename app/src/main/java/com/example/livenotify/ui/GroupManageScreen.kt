package com.example.livenotify.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import com.example.livenotify.data.StreamerEntity

/**
 * 独立分组管理页：分组列表（全部 / 未分组 / 自定义组）+ 分组详情。
 *
 * 第一版单归属、固定图标 + 按名自动配色（不改数据库）。删除分组只让组内主播回落未分组，
 * 不删主播——这是分组数据规则，别在 UI 层破坏。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupManageScreen(vm: MainViewModel, onBack: () -> Unit) {
    val list by vm.streamers.collectAsStateWithLifecycle()
    val groupNames by vm.groups.collectAsStateWithLifecycle()
    val cs = MaterialTheme.colorScheme
    // 跟组合生命周期绑定的作用域：页面退出时自动取消，别用自建 CoroutineScope 泄漏
    val scope = rememberCoroutineScope()

    var selected by remember { mutableStateOf<String?>(null) } // null=全部；""=未分组；其它=组名
    var showCreate by remember { mutableStateOf(false) }
    var renameFor by remember { mutableStateOf<String?>(null) }
    var deleteFor by remember { mutableStateOf<String?>(null) }
    var moveFor by remember { mutableStateOf<StreamerEntity?>(null) }
    var actionFor by remember { mutableStateOf<StreamerEntity?>(null) }
    // 移除关注给一次后悔机会（与首页一致），确认后由 Snackbar 提供撤销
    var removeFor by remember { mutableStateOf<StreamerEntity?>(null) }
    val snackbar = remember { SnackbarHostState() }

    // 各分组统计
    val allCount = list.size
    val allLive = list.count { it.enabled && it.isLive }
    val ungrouped = list.filter { it.group.isEmpty() }
    val grouped = list.filter { it.group.isNotEmpty() }

    // 本页有内部层级（列表 ⇄ 分组详情），系统返回/侧滑必须先退到列表，
    // 而不是一下子 pop 掉整个分组页回首页。enabled=false 时让外层的导航栈接管。
    BackHandler(enabled = selected != null) { selected = null }

    Scaffold(
        // 同首页：不避让导航栏的话「撤销」会被手势条压住
        snackbarHost = { SnackbarHost(snackbar, modifier = Modifier.navigationBarsPadding()) },
        topBar = {
            TopAppBar(
                title = { Text(if (selected == null) "分组管理" else groupLabel(selected ?: "")) },
                navigationIcon = {
                    IconButton(onClick = { if (selected != null) selected = null else onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { showCreate = true }) {
                        Icon(Icons.Default.Add, contentDescription = "新建分组")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = Dimens.SpaceXL)
        ) {
            if (selected == null) {
                // 分组列表
                item {
                    GroupRow(
                        label = "全部关注", count = allCount, live = allLive,
                        color = cs.primary, onClick = { selected = null },
                        canManage = false
                    )
                }
                item {
                    GroupRow(
                        label = GROUP_DEFAULT, count = ungrouped.size,
                        live = ungrouped.count { it.enabled && it.isLive },
                        color = Color(readableGroupColor(UNGROUPED_COLOR, EffectiveDark.isDark)),
                        onClick = { selected = "" }, canManage = false
                    )
                }
                items(groupNames, key = { it }) { name ->
                    val members = grouped.filter { it.group == name }
                    GroupRow(
                        label = name,
                        count = members.size,
                        live = members.count { it.enabled && it.isLive },
                        color = Color(readableGroupColor(groupColorArgb(name), EffectiveDark.isDark)),
                        onClick = { selected = name },
                        canManage = true,
                        onRename = { renameFor = name },
                        onDelete = { deleteFor = name }
                    )
                }
            } else {
                // 分组详情：复用首页主播卡片
                val members = list.filter { s ->
                    if (selected.isNullOrEmpty()) s.group.isEmpty() else s.group == selected
                }
                if (members.isEmpty()) {
                    item {
                        Text(
                            "这个分组还没有主播。在分组列表里选「全部关注」，用卡片的 ⋮ 把主播移进来。",
                            style = MaterialTheme.typography.bodySmall,
                            color = cs.outline,
                            modifier = Modifier.padding(Dimens.SpaceL, Dimens.SpaceXL)
                        )
                    }
                } else {
                    items(members, key = { it.id }) { s ->
                        StreamerCard(
                            s = s,
                            now = System.currentTimeMillis(),
                            onClick = { actionFor = s },
                            onLongClick = { actionFor = s }
                        )
                    }
                }
            }
        }
    }

    if (showCreate) {
        GroupNameInput(
            title = "新建分组",
            label = "分组名",
            onConfirm = { raw ->
                showCreate = false
                scope.launch {
                    val name = vm.createGroup(raw)
                    if (name == null) {
                        vm.notify("分组名不能为空，且不超过 24 个字")
                    } else {
                        // 建完直接进这个组，用户能立刻看到「往这里放主播」的引导
                        selected = name
                        vm.notify("已新建分组「$name」")
                    }
                }
            },
            onDismiss = { showCreate = false }
        )
    }
    renameFor?.let { old ->
        GroupNameInput(
            title = "重命名分组",
            label = "新名称",
            initial = old,
            onConfirm = { raw ->
                renameFor = null
                scope.launch {
                    val err = vm.renameGroup(old, raw)
                    vm.notify(err ?: "已重命名分组")
                }
            },
            onDismiss = { renameFor = null }
        )
    }
    deleteFor?.let { name ->
        AlertDialog(
            onDismissRequest = { deleteFor = null },
            title = { Text("删除分组「$name」") },
            text = { Text("组内主播会转为「${GROUP_DEFAULT}」，不会被取消关注或删除。") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteGroup(name)
                    if (selected == name) selected = ""
                    deleteFor = null
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { deleteFor = null }) { Text("取消") } }
        )
    }
    moveFor?.let { s ->
        MoveGroupSheet(
            streamer = s,
            groups = groupNames,
            onPick = { name -> vm.setGroup(s, name); moveFor = null },
            onCreate = { raw ->
                moveFor = null
                scope.launch {
                    val name = vm.createGroup(raw, moveStreamer = s)
                    if (name == null) vm.notify("分组名不能为空，且不超过 24 个字")
                    else vm.notify("已新建分组「$name」并移入")
                }
            },
            onDismiss = { moveFor = null }
        )
    }
    actionFor?.let { s ->
        SimpleStreamerActions(
            s = s,
            onMove = { moveFor = s; actionFor = null },
            onToggle = { scope.launch { vm.setStreamerEnabled(s, !s.enabled) }; actionFor = null },
            onRemove = { removeFor = s; actionFor = null },
            onDismiss = { actionFor = null }
        )
    }
    removeFor?.let { s ->
        AlertDialog(
            onDismissRequest = { removeFor = null },
            title = { Text("移除关注") },
            text = { Text("将「${s.nickname}」从关注列表移除，历史记录会保留，删错了还能在提示里撤销。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.remove(s)
                        removeFor = null
                        // 与首页同一套：误删成本不低（要重新解析添加），给一次后悔机会
                        scope.launch {
                            val r = snackbar.showSnackbar(
                                message = "已移除「${s.nickname}」",
                                actionLabel = "撤销",
                                duration = SnackbarDuration.Short
                            )
                            if (r == SnackbarResult.ActionPerformed) vm.restore(s)
                        }
                    }
                ) { Text("移除") }
            },
            dismissButton = { TextButton(onClick = { removeFor = null }) { Text("取消") } }
        )
    }
}

@Composable
private fun GroupRow(
    label: String,
    count: Int,
    live: Int,
    color: Color,
    onClick: () -> Unit,
    canManage: Boolean,
    onRename: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.SpaceL, vertical = Dimens.SpaceXS)
            .clickable(onClick = onClick),
        shape = Dimens.ShapeMedium,
        colors = CardDefaults.cardColors(containerColor = cardSurface(0.5f))
    ) {
        Row(
            modifier = Modifier.padding(Dimens.SpaceM),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(color.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (label.isEmpty()) "?" else label.first().uppercaseChar().toString(),
                    color = color, fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium
                )
            }
            Spacer(Modifier.width(Dimens.SpaceM))
            Column(modifier = Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "$count 位" + if (live > 0) " · $live 直播中" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (live > 0) StatusColors.live() else MaterialTheme.colorScheme.outline
                )
            }
            if (canManage) {
                TextButton(
                    onClick = { onRename?.invoke() },
                    modifier = Modifier.heightIn(min = Dimens.MinTouch)
                ) { Text("改名") }
                TextButton(
                    onClick = { onDelete?.invoke() },
                    modifier = Modifier.heightIn(min = Dimens.MinTouch)
                ) { Text("删除") }
            }
        }
    }
}

@Composable
private fun GroupNameInput(
    title: String,
    label: String,
    initial: String = "",
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value, onValueChange = { value = it },
                label = { Text(label) }, singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = { TextButton(onClick = { if (value.isNotBlank()) onConfirm(value) }) { Text("确定") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun MoveGroupSheet(
    streamer: StreamerEntity,
    groups: List<String>,
    onPick: (String) -> Unit,
    onCreate: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var newName by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("移动「${streamer.nickname}」到") },
        text = {
            // 原来是 horizontalScroll，但条目纵向排列——分组一多底部「新建」就被挤没了
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                listOf("" to GROUP_DEFAULT).plus(groups.map { it to it }).forEach { (key, label) ->
                    TextButton(
                        onClick = { onPick(key) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = Dimens.MinTouch)
                    ) {
                        Text(if (key == streamer.group) "$label ✓" else label, modifier = Modifier.fillMaxWidth())
                    }
                }
                OutlinedTextField(
                    value = newName, onValueChange = { newName = it },
                    label = { Text("或新建分组") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                TextButton(
                    onClick = { if (newName.isNotBlank()) onCreate(newName) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = Dimens.MinTouch)
                ) {
                    Text("新建并移入", modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun SimpleStreamerActions(
    s: StreamerEntity,
    onMove: () -> Unit,
    onToggle: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(s.nickname, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Column {
                TextButton(onClick = onMove, modifier = Modifier.fillMaxWidth()) {
                    Text("移动分组", modifier = Modifier.fillMaxWidth())
                }
                TextButton(onClick = onToggle, modifier = Modifier.fillMaxWidth()) {
                    Text(if (s.enabled) "暂停关注" else "恢复关注", modifier = Modifier.fillMaxWidth())
                }
                TextButton(onClick = onRemove, modifier = Modifier.fillMaxWidth()) {
                    Text("移除关注", modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}
