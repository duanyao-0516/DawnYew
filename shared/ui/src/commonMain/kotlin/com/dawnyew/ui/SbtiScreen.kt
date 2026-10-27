package com.dawnyew.ui

import androidx.compose.foundation.Image
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
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.layout.ContentScale
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
import com.dawnyew.core.personality.SbtiCatalog
import com.dawnyew.core.personality.SbtiDimensionScore
import com.dawnyew.core.personality.SbtiQuestion
import com.dawnyew.core.personality.SbtiResult
import com.dawnyew.core.personality.SbtiResultMode
import com.dawnyew.presentation.SbtiController
import com.dawnyew.presentation.SbtiStage
import com.dawnyew.presentation.SbtiUiState
import org.jetbrains.compose.resources.painterResource

private enum class SbtiConfirmation { RESTART, CLEAR }

/** The controller owns the attempt; generation-bound overlays never survive a question-set change. */
@Composable internal fun SbtiPage(
    controller: SbtiController,
    onBackHandlerChanged: ((() -> Boolean)?) -> Unit = {},
) {
    val state by controller.state.collectAsState()
    var directory by remember(controller, state.generation) { mutableStateOf(false) }
    var confirmation by remember(controller, state.generation) { mutableStateOf<SbtiConfirmation?>(null) }
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

    when (state.stage) {
        SbtiStage.INTRO -> SbtiIntro(
            state = state,
            onStart = { controller.start(state.generation) },
            onResume = { controller.resume(state.generation) },
            onRestart = { confirmation = SbtiConfirmation.RESTART },
        )
        SbtiStage.QUESTIONS -> {
            val question = state.questions[state.questionIndex]
            key(question.id) {
                SbtiQuestionPage(
                    state = state,
                    question = question,
                    onAnswer = { controller.answer(state.generation, question.id, it) },
                    onPrevious = { controller.back(state.generation) },
                    onNext = {
                        if (state.questionIndex == state.questions.lastIndex) controller.submit(state.generation)
                        else controller.next(state.generation)
                    },
                    onDirectory = { directory = true },
                )
            }
        }
        SbtiStage.RESULT -> state.result?.let { result ->
            SbtiResultPage(
                result = result,
                onReview = { directory = true },
                onRestart = { confirmation = SbtiConfirmation.RESTART },
                onClear = { confirmation = SbtiConfirmation.CLEAR },
            )
        }
    }

    if (directory) SbtiQuestionDirectory(
        state = state,
        onDismiss = { directory = false },
        onSelect = { questionId ->
            directory = false
            controller.openQuestion(state.generation, questionId)
        },
        onSubmit = {
            directory = false
            controller.submit(state.generation)
        },
    )
    confirmation?.let { action ->
        SbtiConfirmationDialog(
            action = action,
            onDismiss = { confirmation = null },
            onConfirm = {
                confirmation = null
                when (action) {
                    SbtiConfirmation.RESTART -> controller.start(state.generation)
                    SbtiConfirmation.CLEAR -> controller.clear(state.generation)
                }
            },
        )
    }
}

@Composable private fun SbtiIntro(
    state: SbtiUiState,
    onStart: () -> Unit,
    onResume: () -> Unit,
    onRestart: () -> Unit,
) {
    Column(Modifier.fillMaxSize().navigationBarsPadding()) {
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(vertical = 16.dp)) {
            item {
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("MBTI已经过时，SBTI来了。", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
                    Text("31–32 题 · 随机顺序 · 完全离线", style = MaterialTheme.typography.titleMedium)
                    Text("根据第一反应作答。每次开始会随机排列题目，部分选择会增加一道补充题；本次作答期间顺序固定。", style = MaterialTheme.typography.bodyLarge)
                }
            }
            item {
                Spacer(Modifier.height(20.dp))
                SettingsSection {
                    SettingsText("仅供娱乐", "结果采用来源中的趣味规则和原文，不作为心理诊断、能力评价或人生决定的依据。")
                    SettingsDivider()
                    SettingsText("本次进度", "答案与结果仅在本进程保存，不写入账本或备份。结束进程、清空数据或恢复备份后，本次测试会被清除。")
                }
            }
            if (state.hasAttempt) item {
                Text(
                    if (state.result != null) "本次测试已完成，可查看结果或检查答案。"
                    else "已答 ${state.answers.size} / ${state.questions.size} 题，可从第 ${state.questionIndex + 1} 题继续。",
                    Modifier.padding(16.dp), style = MaterialTheme.typography.bodyLarge,
                )
            }
            item { SbtiSource() }
        }
        SbtiActions {
            Button(onClick = if (state.hasAttempt) onResume else onStart, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(when { state.result != null -> "查看本次结果"; state.hasAttempt -> "继续本次测试"; else -> "开始测试" })
            }
            if (state.hasAttempt) TextButton(onClick = onRestart, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("重新开始") }
        }
    }
}

@Composable private fun SbtiQuestionPage(
    state: SbtiUiState,
    question: SbtiQuestion,
    onAnswer: (Int) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onDirectory: () -> Unit,
) {
    Column(Modifier.fillMaxSize().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                Text("${state.questionIndex + 1} / ${state.questions.size}", style = MaterialTheme.typography.titleMedium)
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
                question.text,
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.clearAndSetSemantics {
                    contentDescription = "第 ${state.questionIndex + 1} 题：${question.text}"
                    heading()
                },
            )
            SbtiAnswerChoices(question, state.answers[question.id], onAnswer)
            Text("选择后不会自动跳题，可随时检查或修改。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        SbtiActions {
            state.message?.let {
                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onPrevious, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Text(if (state.questionIndex == 0) "返回介绍" else "上一题")
                }
                Button(onClick = onNext, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Text(if (state.questionIndex == state.questions.lastIndex) "查看结果" else "下一题")
                }
            }
        }
    }
}

@Composable private fun SbtiAnswerChoices(question: SbtiQuestion, answer: Int?, onAnswer: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().selectableGroup()) {
        question.options.forEachIndexed { index, option ->
            val letter = 'A' + index
            Row(
                Modifier.fillMaxWidth()
                    .background(if (answer == option.value) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
                    .selectable(selected = answer == option.value, onClick = { onAnswer(option.value) }, role = Role.RadioButton)
                    .semantics(mergeDescendants = true) { contentDescription = "选项 $letter：${option.label}" }
                    .heightIn(min = 52.dp).padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RadioButton(selected = answer == option.value, onClick = null, modifier = Modifier.clearAndSetSemantics {})
                Text("$letter  ${option.label}", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).clearAndSetSemantics {})
            }
            if (index < question.options.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f))
        }
    }
}

@Composable private fun SbtiResultPage(result: SbtiResult, onReview: () -> Unit, onRestart: () -> Unit, onClear: () -> Unit) {
    Column(Modifier.fillMaxSize().navigationBarsPadding()) {
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(vertical = 16.dp)) {
            item {
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        when (result.mode) {
                            SbtiResultMode.NORMAL -> "你的主类型"
                            SbtiResultMode.DRUNK -> "隐藏人格已激活"
                            SbtiResultMode.FALLBACK -> "系统强制兜底"
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(result.type.code, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
                    Text(result.type.name, style = MaterialTheme.typography.titleLarge)
                    SbtiMatchDescription(result)
                }
                Spacer(Modifier.height(16.dp))
            }
            item {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    val painter = painterResource(sbtiPosterResource(result.type.code))
                    val intrinsic = painter.intrinsicSize
                    val aspect = if (intrinsic.isSpecified && intrinsic.width.isFinite() && intrinsic.height.isFinite() && intrinsic.width > 0 && intrinsic.height > 0) {
                        intrinsic.width / intrinsic.height
                    } else 1f
                    Image(
                        painter = painter,
                        contentDescription = "${result.type.code}（${result.type.name}）原版结果图",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth().aspectRatio(aspect),
                    )
                    Text(result.type.intro, style = MaterialTheme.typography.titleMedium)
                    Text("该人格的简单解读", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                    Text(result.type.description, style = MaterialTheme.typography.bodyLarge)
                }
                Spacer(Modifier.height(24.dp))
            }
            item {
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("十五维度评分", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                    Text("每维 2–6 分；L 为 2–3 分，M 为 4 分，H 为 5–6 分。分数和档位不代表好坏。", style = MaterialTheme.typography.bodyMedium)
                }
            }
            result.dimensions.forEach { dimension -> item(key = dimension.id) {
                SbtiDimensionResult(dimension)
                SettingsDivider()
            } }
            item {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("友情提示", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (result.mode == SbtiResultMode.NORMAL) "本测试仅供娱乐，别拿它当诊断、面试、相亲、分手、招魂、算命或人生判决书。你可以笑，但别太当真。"
                        else "本测试仅供娱乐。隐藏人格和傻乐兜底都属于作者故意埋的损招，请勿把它当成医学、心理学、相学、命理学或灵异学依据。",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                SbtiAuthorNote()
                SbtiSource()
            }
        }
        SbtiActions {
            Button(onClick = onReview, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("检查/修改答案") }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onRestart, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("再测一次") }
                TextButton(onClick = onClear, modifier = Modifier.weight(1f).heightIn(min = 48.dp), colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("清除本次结果") }
            }
        }
    }
}

@Composable private fun SbtiMatchDescription(result: SbtiResult) {
    val best = result.ranked.first()
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small) {
        Text(
            when (result.mode) {
                SbtiResultMode.NORMAL -> "匹配度 ${best.similarity}% · 精准命中 ${best.exact}/15 维"
                SbtiResultMode.DRUNK -> "匹配度 100% · 酒精异常因子已接管"
                SbtiResultMode.FALLBACK -> "标准人格库最高匹配仅 ${best.similarity}%"
            },
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.labelLarge,
        )
    }
    Text(
        when (result.mode) {
            SbtiResultMode.NORMAL -> "维度命中度较高，当前结果可视为你的第一人格画像。"
            SbtiResultMode.DRUNK -> "乙醇亲和性过强，系统已直接跳过常规人格审判。"
            SbtiResultMode.FALLBACK -> "标准人格库对你的脑回路集体罢工了，于是系统把你强制分配给了 HHHH。"
        },
        style = MaterialTheme.typography.bodyLarge,
    )
    Text(
        "以上匹配说明沿用来源文案。匹配度是算法指标，不代表概率或准确率；隐藏人格的 100% 是源规则提示。",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable private fun SbtiDimensionResult(dimension: SbtiDimensionScore) {
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(dimension.model, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(dimension.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        Text("${dimension.level} / ${dimension.score} 分", style = MaterialTheme.typography.titleSmall)
        Text(dimension.explanation, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable private fun SbtiQuestionDirectory(state: SbtiUiState, onDismiss: () -> Unit, onSelect: (String) -> Unit, onSubmit: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("题号目录", style = MaterialTheme.typography.titleLarge)
                Text("已答 ${state.answers.size} / ${state.questions.size} · 带 ✓ 的题目已作答", style = MaterialTheme.typography.bodyMedium)
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(56.dp), modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    itemsIndexed(state.questions, key = { _, question -> question.id }) { index, question ->
                        val answered = question.id in state.answers
                        val current = index == state.questionIndex
                        OutlinedButton(
                            onClick = { onSelect(question.id) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).semantics {
                                contentDescription = "第 ${index + 1} 题，${if (answered) "已答" else "未答"}${if (current) "，当前题" else ""}"
                            },
                            contentPadding = PaddingValues(4.dp),
                            colors = ButtonDefaults.outlinedButtonColors(containerColor = if (current) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface),
                        ) {
                            Text("${index + 1}${if (answered) " ✓" else ""}", modifier = Modifier.clearAndSetSemantics {})
                        }
                    }
                }
                if (state.stage == SbtiStage.QUESTIONS) Button(onClick = onSubmit, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("查看结果") }
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("关闭目录") }
            }
        }
    }
}

@Composable private fun SbtiConfirmationDialog(action: SbtiConfirmation, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (action == SbtiConfirmation.RESTART) "重新开始测试？" else "清除本次结果？") },
        text = { Text("本次答案和结果会被清除，无法恢复。") },
        confirmButton = { TextButton(onClick = onConfirm) { Text(if (action == SbtiConfirmation.RESTART) "重新开始" else "确认清除") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable private fun SbtiActions(content: @Composable ColumnScope.() -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp), content = content)
    }
}

@Composable private fun SbtiAuthorNote() {
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("作者的话", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        Text("本测试首发于b站up主蛆肉儿串儿（UID417038183），初衷是劝诫一位爱喝酒的朋友戒酒。", style = MaterialTheme.typography.bodyMedium)
        Text("由于作者的人格是SHIT愤世者，所以平等的攻击了各位，在此抱歉！！不过我是一个绝世大美女，你们一定会原谅我，有B站的朋友们也可以关注我。", style = MaterialTheme.typography.bodyMedium)
        Text("关于这个测试，我没法很好的平衡娱乐和专业性，因此对于一些人格的阐释较为模糊或完全不准（如屌丝可能并非真的屌丝），如有冒犯非常抱歉！！", style = MaterialTheme.typography.bodyMedium)
        Text("再鉴于时间精力有限，就随便搞了一个先这样玩玩，后续会慢慢完善修改的，总之好玩为主，还请不要用于盈利呀。", style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable private fun SbtiSource() {
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("题库与图片来源", style = MaterialTheme.typography.titleSmall)
        Text("原作者：B站 @Q肉儿串儿（蛆肉儿串儿，UID417038183）", style = MaterialTheme.typography.bodySmall)
        Text("镜像与存档：UnluckyNinja / SBTI-test · sbti.unun.dev", style = MaterialTheme.typography.bodySmall)
        Text(SbtiCatalog.SOURCE_URL, style = MaterialTheme.typography.bodySmall)
        Text("版本：${SbtiCatalog.SOURCE_COMMIT}", style = MaterialTheme.typography.bodySmall)
        Text("本版本保留来源的题目、规则、结果文案和原图，所有内容随应用离线提供。", style = MaterialTheme.typography.bodySmall)
    }
}
