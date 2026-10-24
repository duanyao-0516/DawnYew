package com.dawnyew.presentation

import com.dawnyew.core.LedgerState

/** Preferences, budget limits and receipts do not change activity/statistical sums or account balances. */
internal fun LedgerState.hasSameQueryContent(other:LedgerState):Boolean =
    datasetId==other.datasetId && ledgers==other.ledgers && categories==other.categories && entries==other.entries &&
        accounts==other.accounts && entryDetails==other.entryDetails && entryTags==other.entryTags && transfers==other.transfers &&
        dimensions==other.dimensions && contacts==other.contacts && obligations==other.obligations &&
        recurringPlans==other.recurringPlans && recurringOccurrences==other.recurringOccurrences &&
        savingsPlans==other.savingsPlans && savingsMovements==other.savingsMovements && savingsWishes==other.savingsWishes
