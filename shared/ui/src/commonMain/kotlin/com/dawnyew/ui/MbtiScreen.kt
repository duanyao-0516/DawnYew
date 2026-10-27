package com.dawnyew.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.dawnyew.core.personality.Mbti93Catalog
import com.dawnyew.core.personality.Mbti93Question
import com.dawnyew.core.personality.Mbti93Result
import com.dawnyew.core.personality.MbtiAxisScore
import com.dawnyew.presentation.MbtiController
import com.dawnyew.presentation.MbtiStage
import com.dawnyew.presentation.MbtiUiState

private enum class MbtiConfirmation { RESTART, CLEAR }

/** All answers belong to the controller; only disposable overlays live in this composition. */
@Composable internal fun MbtiPage(
    controller: MbtiController,
    onBackHandlerChanged: ((() -> Boolean)?) -> Unit = {},
) {
    val state by controller.state.collectAsState()
    var directory by remember(controller, state.generation) { mutableStateOf(false) }
    var confirmation by remember(controller, state.generation) { mutableStateOf<MbtiConfirmation?>(null) }
    val back = rememberUpdatedState<() -> Boolean> {
        when {
            !state.visible -> true
            confirmation != null -> { confirmation = null; true }
            directory -> { directory = false; true }
            else -> controller.back(state.generation)
        }
    }
    val registerBack = rememberUpdatedState(onBackHandlerChanged)
    DisposableEffect(controller) {
        var active = true
        registerBack.value { if (active) back.value() else true }
        onDispose {
            active = false
            registerBack.value(null)
        }
    }

    if (!state.visible) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("测试已暂停", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    val restart = {
        if (state.answers.isNotEmpty()) confirmation = MbtiConfirmation.RESTART
        else controller.start(state.generation)
    }
    when (state.stage) {
        MbtiStage.INTRO -> MbtiIntro(
            state = state,
            onStart = { controller.start(state.generation) },
            onResume = { controller.resume(state.generation) },
            onRestart = restart,
        )
        MbtiStage.QUESTIONS -> key(state.questionIndex) {
            MbtiQuestionPage(
                state = state,
                question = Mbti93Catalog.questions[state.questionIndex],
                onAnswer = { controller.answer(state.generation, Mbti93Catalog.questions[state.questionIndex].id, it) },
                onPrevious = { controller.back(state.generation) },
                onNext = {
                    if (state.questionIndex == Mbti93Catalog.questions.lastIndex) controller.submit(state.generation)
                    else controller.next(state.generation)
                },
                onDirectory = { directory = true },
            )
        }
        MbtiStage.RESULT -> state.result?.let { result ->
            MbtiResultPage(
                result = result,
                onReview = { directory = true },
                onRestart = { confirmation = MbtiConfirmation.RESTART },
                onClear = { confirmation = MbtiConfirmation.CLEAR },
            )
        }
    }

    if (directory) MbtiQuestionDirectory(
        state = state,
        onDismiss = { directory = false },
        onSelect = { controller.openQuestion(state.generation, it) },
        onSubmit = { controller.submit(state.generation) },
    )
    confirmation?.let { action ->
        MbtiConfirmationDialog(
            action = action,
            onDismiss = { confirmation = null },
            onConfirm = {
                confirmation = null
                when (action) {
                    MbtiConfirmation.RESTART -> controller.start(state.generation)
                    MbtiConfirmation.CLEAR -> controller.clear(state.generation)
                }
            },
        )
    }
}

@Composable private fun MbtiIntro(
    state: MbtiUiState,
    onStart: () -> Unit,
    onResume: () -> Unit,
    onRestart: () -> Unit,
) {
    Column(Modifier.fillMaxSize().navigationBarsPadding()) {
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(vertical = 16.dp)) {
            item {
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("了解自己的偏好", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
                    Text("93 题 · 五档选择 · 完全离线", style = MaterialTheme.typography.titleMedium)
                    Text("根据你平时更自然的反应作答。每题提供 A、B 两种描述，请选择更接近自己的程度；两者相当也算已作答。", style = MaterialTheme.typography.bodyLarge)
                }
            }
            item {
                Spacer(Modifier.height(20.dp))
                SettingsSection {
                    SettingsText("测试说明", "题目和计分来自 MyMBTI 的 93 题版本，非 MBTI Global 官方版。结果用于自我了解，不作为诊断或能力评价。")
                    SettingsDivider()
                    SettingsText("本次进度", "答案与结果仅在本进程保存，不写入账本或备份。结束进程、清空数据或恢复备份后，本次测试会被清除。")
                }
            }
            if (state.hasAttempt) item {
                Text(
                    if (state.result != null) "本次测试已完成，可查看结果或检查答案。" else "已答 ${state.answers.size} / 93 题，可从第 ${state.questionIndex + 1} 题继续。",
                    Modifier.padding(16.dp), style = MaterialTheme.typography.bodyLarge,
                )
            }
            item { MbtiSource() }
        }
        MbtiActions {
            Button(onClick = if (state.hasAttempt) onResume else onStart, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(when { state.result != null -> "查看本次结果"; state.hasAttempt -> "继续本次测试"; else -> "开始测试" })
            }
            if (state.hasAttempt) TextButton(onClick = onRestart, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("重新开始") }
        }
    }
}

@Composable private fun MbtiQuestionPage(
    state: MbtiUiState,
    question: Mbti93Question,
    onAnswer: (Int) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onDirectory: () -> Unit,
) {
    Column(Modifier.fillMaxSize().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                Text("${state.questionIndex + 1} / 93", style = MaterialTheme.typography.titleMedium)
                Text("已答 ${state.answers.size} 题", style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = onDirectory, modifier = Modifier.heightIn(min = 48.dp)) { Text("题号目录") }
        }
        SettingsDivider()
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                question.prompt,
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.clearAndSetSemantics { contentDescription = "第 ${question.id} 题：${question.prompt}"; heading() },
            )
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("A  ${question.optionA}", style = MaterialTheme.typography.bodyLarge)
                Text("B  ${question.optionB}", style = MaterialTheme.typography.bodyLarge)
            }
            MbtiAnswerChoices(state.answers[question.id], onAnswer)
            Text("选择后不会自动跳题，可随时检查或修改。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        MbtiActions {
            state.message?.let {
                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onPrevious, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Text(if (state.questionIndex == 0) "返回介绍" else "上一题")
                }
                Button(onClick = onNext, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Text(if (state.questionIndex == Mbti93Catalog.questions.lastIndex) "查看结果" else "下一题")
                }
            }
        }
    }
}

@Composable private fun MbtiAnswerChoices(answer: Int?, onAnswer: (Int) -> Unit) {
    val labels = listOf("更倾向 A", "略倾向 A", "两者相当", "略倾向 B", "更倾向 B")
    Column(Modifier.fillMaxWidth().selectableGroup()) {
        labels.forEachIndexed { index, label ->
            val value = index + 1
            Row(
                Modifier.fillMaxWidth()
                    .background(if (answer == value) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
                    .selectable(selected = answer == value, onClick = { onAnswer(value) }, role = Role.RadioButton)
                    .semantics(mergeDescendants = true) { contentDescription = "选项 $value：$label" }
                    .heightIn(min = 52.dp).padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RadioButton(selected = answer == value, onClick = null, modifier = Modifier.clearAndSetSemantics {})
                Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.clearAndSetSemantics {})
            }
            if (index < labels.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f))
        }
    }
}

@Composable private fun MbtiResultPage(result: Mbti93Result, onReview: () -> Unit, onRestart: () -> Unit, onClear: () -> Unit) {
    Column(Modifier.fillMaxSize().navigationBarsPadding()) {
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(vertical = 16.dp)) {
            item {
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("本次结果", style = MaterialTheme.typography.titleMedium)
                    Text(result.type, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
                    Text(result.typeName, style = MaterialTheme.typography.titleLarge)
                    Text("以下为八项原始得分，不代表概率或准确率。", style = MaterialTheme.typography.bodyMedium)
                    if (result.axes.all { it.firstScore == 0 && it.secondScore == 0 }) {
                        Text("所有维度均为 0，无法从这些答案判断倾向。ESTJ 仅为源规则在同分时选取 E、S、T、J 的结果。", style = MaterialTheme.typography.bodyLarge)
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
            result.axes.forEach { axis -> item(key = axis.first.name) {
                MbtiAxisResult(axis)
                SettingsDivider()
            } }
            item {
                Text("同分维度沿用源规则，分别取 E、S、T、J。选择中间档的 ${result.neutralCount} 题不计分。", Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
                Text("此结果用于自我了解，非 MBTI Global 官方报告。", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                MbtiSource()
            }
        }
        MbtiActions {
            Button(onClick = onReview, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("检查/修改答案") }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onRestart, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("再测一次") }
                TextButton(onClick = onClear, modifier = Modifier.weight(1f).heightIn(min = 48.dp), colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("清除本次结果") }
            }
        }
    }
}

@Composable private fun MbtiAxisResult(axis: MbtiAxisScore) {
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("${axis.first.name} ${axis.first.label}  ${axis.firstScore}", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Text("${axis.second.name} ${axis.second.label}  ${axis.secondScore}", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        }
        val total = axis.firstScore + axis.secondScore
        // A zero score has no colored segment; two zeros leave the entire track neutral.
        Row(Modifier.fillMaxWidth().height(8.dp).background(MaterialTheme.colorScheme.outlineVariant).clearAndSetSemantics {}) {
            if (total > 0) {
                if (axis.firstScore > 0) Box(Modifier.weight(axis.firstScore.toFloat()).fillMaxHeight().background(MaterialTheme.colorScheme.primary))
                if (axis.secondScore > 0) Box(Modifier.weight(axis.secondScore.toFloat()).fillMaxHeight().background(MaterialTheme.colorScheme.secondary))
            }
        }
        if (axis.isTie) Text("同分 · 源规则取 ${axis.first.name}", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable private fun MbtiQuestionDirectory(state: MbtiUiState, onDismiss: () -> Unit, onSelect: (Int) -> Unit, onSubmit: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("题号目录", style = MaterialTheme.typography.titleLarge)
                Text("已答 ${state.answers.size} / 93 · 带 ✓ 的题目已作答", style = MaterialTheme.typography.bodyMedium)
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(56.dp), modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    itemsIndexed(Mbti93Catalog.questions, key = { _, question -> question.id }) { index, question ->
                        val answered = question.id in state.answers
                        val current = index == state.questionIndex
                        OutlinedButton(
                            onClick = { onSelect(index) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).semantics {
                                contentDescription = "第 ${question.id} 题，${if (answered) "已答" else "未答"}${if (current) "，当前题" else ""}"
                            },
                            contentPadding = PaddingValues(4.dp),
                            colors = ButtonDefaults.outlinedButtonColors(containerColor = if (current) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface),
                        ) {
                            Text("${question.id}${if (answered) " ✓" else ""}", modifier = Modifier.clearAndSetSemantics {})
                        }
                    }
                }
                if (state.stage == MbtiStage.QUESTIONS) Button(onClick = onSubmit, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("查看结果") }
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("关闭目录") }
            }
        }
    }
}

@Composable private fun MbtiConfirmationDialog(action: MbtiConfirmation, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (action == MbtiConfirmation.RESTART) "重新开始测试？" else "清除本次结果？") },
        text = { Text("本次答案和结果会被清除，无法恢复。") },
        confirmButton = { TextButton(onClick = onConfirm) { Text(if (action == MbtiConfirmation.RESTART) "重新开始" else "确认清除") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable private fun MbtiActions(content: @Composable ColumnScope.() -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp), content = content)
    }
}

@Composable private fun MbtiSource() {
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("题库来源", style = MaterialTheme.typography.titleSmall)
        Text("MyMBTI · 93 题版本", style = MaterialTheme.typography.bodySmall)
        Text(Mbti93Catalog.SOURCE_URL, style = MaterialTheme.typography.bodySmall)
        Text("版本：${Mbti93Catalog.SOURCE_COMMIT}", style = MaterialTheme.typography.bodySmall)
    }
}
