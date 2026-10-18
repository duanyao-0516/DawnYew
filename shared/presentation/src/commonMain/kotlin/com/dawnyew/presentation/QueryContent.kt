package com.dawnyew.presentation

import com.dawnyew.core.LedgerState

/** Preferences and operation receipts advance the storage version without changing query results. */
internal fun LedgerState.hasSameQueryContent(other:LedgerState):Boolean =
    datasetId==other.datasetId && ledgers==other.ledgers && categories==other.categories && entries==other.entries
