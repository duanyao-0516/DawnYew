package com.dawnyew.presentation

import com.dawnyew.core.ActivityScope
import com.dawnyew.core.Money
import com.dawnyew.core.validateActivityScope

/** Pending UI values never mutate the applied query or its pagination cursor. */
data class ActivityQueryDraft(
    val scope: ActivityScope,
    val start: String = scope.startDate.takeUnless { it == "0001-01-01" }.orEmpty(),
    val end: String = scope.endDate.takeUnless { it == "9999-12-31" }.orEmpty(),
    val minimum: String = scope.minAmountMinor?.let(Money::format).orEmpty(),
    val maximum: String = scope.maxAmountMinor?.let(Money::format).orEmpty(),
) {
    fun validatedScope(): ActivityScope = scope.copy(
        startDate = start.trim().ifEmpty { "0001-01-01" },
        endDate = end.trim().ifEmpty { "9999-12-31" },
        minAmountMinor = minimum.trim().takeIf { it.isNotEmpty() }?.let(::parseBound),
        maxAmountMinor = maximum.trim().takeIf { it.isNotEmpty() }?.let(::parseBound),
    ).also(::validateActivityScope)

    private fun parseBound(value: String): Long = if (Regex("0{1,9}(\\.0{1,2})?").matches(value)) 0 else Money.parse(value)

    fun selectLedgers(ids: Set<String>): ActivityQueryDraft = copy(scope = scope.copy(
        ledgerIds = ids, categoryIds = emptySet(), accountIds = emptySet(), projectIds = emptySet(), merchantIds = emptySet(), tagIds = emptySet(),
        accountUnassigned = false, projectUnassigned = false, merchantUnassigned = false, tagUnassigned = false,
    ))

    /** Deleted/archived visibility belongs to the route, not a user filter. */
    fun reset(): ActivityQueryDraft = ActivityQueryDraft(ActivityScope(deleted = scope.deleted, includeArchived = scope.includeArchived))
}
