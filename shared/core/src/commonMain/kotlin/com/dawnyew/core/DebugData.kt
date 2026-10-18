package com.dawnyew.core

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.daysUntil
import kotlinx.datetime.plus

const val DEBUG_BATCH_ADAPTER_ID = "dawnyew.debug.seed"
private const val DEBUG_BATCH_ADAPTER_VERSION = "1"

/** Called inside the repository's command transaction; all generated members use normal import validation. */
internal fun generateDebugEntries(
    original: LedgerState,
    command: Command.GenerateDebugEntries,
    operationId: String,
    payloadDigest: String,
    env: DomainEnvironment,
): Mutation {
    // The request digest survives receipt removal and does not depend on today's clock or category state.
    original.importBatches.firstOrNull { it.submissionId == operationId }?.let { batch ->
        if (batch.adapterId != DEBUG_BATCH_ADAPTER_ID || batch.adapterVersion != DEBUG_BATCH_ADAPTER_VERSION || batch.fileDigest != payloadDigest) {
            conflict("重复操作标识对应了不同的测试数据请求")
        }
        return Mutation(original, listOf(batch.id))
    }
    validateId(command.ledgerId)
    if (command.count !in 1..10_000) invalid("测试数据数量必须为 1–10,000 条")
    val range = DateRange(command.startDate, command.endDate)
    val now = env.localNow().also(::validateOccurrence)
    val localNow = LocalDateTime.parse(now)
    if (range.endDate > now.substringBefore('T')) invalid("测试数据日期不能晚于今天")
    val ledger = original.ledgers.find { it.id == command.ledgerId } ?: invalid("账本不存在")
    if (ledger.archived) invalid("请先取消归档该账本")
    val categories = original.categories.filter { it.ledgerId == ledger.id && it.enabled }.sortedWith(compareBy({ it.sortOrder }, { it.id }))
    if (categories.isEmpty()) invalid("该账本没有启用的分类，无法生成测试数据")
    val expenses = categories.filter { it.direction == Direction.EXPENSE }
    val incomes = categories.filter { it.direction == Direction.INCOME }
    val start = LocalDate.parse(range.startDate)
    val span = start.daysUntil(LocalDate.parse(range.endDate))
    val createdAt = env.utcNow()
    val batchId = env.id()
    var expenseIndex = 0
    var incomeIndex = 0
    val rows = List(command.count) { index ->
        val income = incomes.isNotEmpty() && (expenses.isEmpty() || index % 5 == 4)
        val category = if (income) incomes[incomeIndex++ % incomes.size] else expenses[expenseIndex++ % expenses.size]
        val offset = if (command.count == 1) 0 else (index.toLong() * span / (command.count - 1)).toInt()
        val date = start.plus(offset, DateTimeUnit.DAY)
        val maxSeconds = if (date == localNow.date) localNow.hour * 3600 + localNow.minute * 60 + localNow.second else 86_399
        val seconds = ((index * 4_861L + 9 * 3600) % (maxSeconds + 1)).toInt()
        val time = listOf(seconds / 3600, seconds / 60 % 60, seconds % 60).joinToString(":") { it.toString().padStart(2, '0') }
        val entry = Entry(
            id = env.id(), ledgerId = ledger.id, kind = if (income) EntryKind.INCOME else EntryKind.EXPENSE,
            amountMinor = (if (income) 10_000L else 100L) + index * 7_919L % 299_900L,
            categoryId = category.id, occurrence = "${date}T$time", note = "[DEBUG 测试] ${category.name} #${index + 1}",
            createdAt = createdAt, updatedAt = createdAt, originKind = OriginKind.IMPORT, creationOperationId = env.id(),
        )
        // The user explicitly requested this many synthetic records, even when business values coincide.
        ImportRow(index + 1, entry, keepSuspectedDuplicate = true)
    }
    val mappingDigest = Sha256.hex(categories.joinToString("\n") { "${it.id}:${it.direction.name}" }.encodeToByteArray())
    val plan = ImportPlan(
        batchId = batchId, submissionId = operationId, datasetId = original.datasetId, contentVersion = original.contentVersion,
        databaseEpoch = 0, adapterId = DEBUG_BATCH_ADAPTER_ID, adapterVersion = DEBUG_BATCH_ADAPTER_VERSION,
        // This internal adapter stores the command digest; there is no claimed external file or payment source.
        fileDigest = payloadDigest, mappingDigest = mappingDigest, rows = rows,
    ).sealed()
    return commitImport(original, plan, env)
}
