package com.dawnyew.storage

internal val financialTables=linkedMapOf("accounts" to "accounts","entryDetails" to "entry_details","entryTags" to "entry_tags","transfers" to "transfers","dimensions" to "dimensions","contacts" to "contacts","obligations" to "obligations","recurringPlans" to "recurring_plans","recurringOccurrences" to "recurring_occurrences","savingsPlans" to "savings_plans","savingsMovements" to "savings_movements","savingsWishes" to "savings_wishes","budgetAllocations" to "budget_allocations","budgetReallocations" to "budget_reallocations")
internal val financialAssertions=mapOf(
    "accounts" to "length(NEW.`id`)=0 OR typeof(NEW.openingBalanceMinor)!='integer' OR NEW.openingBalanceMinor NOT BETWEEN -99999999999 AND 99999999999 OR NEW.`archived` NOT IN (0,1) OR NEW.`hidden` NOT IN (0,1) OR NEW.`includedInAssets` NOT IN (0,1) OR NEW.revision<1",
    "entry_details" to "length(NEW.`entryId`)=0 OR NEW.`recorded` NOT IN (0,1) OR NEW.`excludedFromBudget` NOT IN (0,1) OR NEW.`reconciled` NOT IN (0,1)",
    "entry_tags" to "length(NEW.`entryId`)=0",
    "transfers" to "length(NEW.`id`)=0 OR typeof(NEW.amountMinor)!='integer' OR NEW.amountMinor NOT BETWEEN 1 AND 99999999999 OR NEW.`recorded` NOT IN (0,1) OR NEW.`reconciled` NOT IN (0,1) OR NEW.`deleted` NOT IN (0,1) OR NEW.revision<1",
    "dimensions" to "length(NEW.`id`)=0 OR NEW.`archived` NOT IN (0,1) OR NEW.revision<1",
    "contacts" to "length(NEW.`id`)=0 OR NEW.`archived` NOT IN (0,1) OR NEW.revision<1",
    "obligations" to "length(NEW.`id`)=0 OR NEW.`archived` NOT IN (0,1) OR NEW.revision<1",
    "recurring_plans" to "length(NEW.`id`)=0 OR typeof(NEW.amountMinor)!='integer' OR NEW.amountMinor NOT BETWEEN 1 AND 99999999999 OR NEW.`paused` NOT IN (0,1) OR NEW.`archived` NOT IN (0,1) OR NEW.`recorded` NOT IN (0,1) OR NEW.`excludedFromBudget` NOT IN (0,1) OR NEW.`reconciled` NOT IN (0,1) OR NEW.revision<1",
    "recurring_occurrences" to "length(NEW.`id`)=0",
    "savings_plans" to "length(NEW.`id`)=0 OR NEW.`archived` NOT IN (0,1) OR NEW.revision<1",
    "savings_movements" to "length(NEW.`id`)=0 OR typeof(NEW.amountMinor)!='integer' OR NEW.amountMinor NOT BETWEEN 1 AND 99999999999 OR NEW.`deleted` NOT IN (0,1) OR NEW.revision<1",
    "savings_wishes" to "length(NEW.`id`)=0 OR typeof(NEW.amountMinor)!='integer' OR NEW.amountMinor NOT BETWEEN 1 AND 99999999999 OR NEW.revision<1",
    "budget_allocations" to "length(NEW.`id`)=0 OR typeof(NEW.amountMinor)!='integer' OR NEW.amountMinor NOT BETWEEN 0 AND 99999999999 OR NEW.revision<1",
    "budget_reallocations" to "length(NEW.`id`)=0 OR typeof(NEW.amountMinor)!='integer' OR NEW.amountMinor NOT BETWEEN 1 AND 99999999999",
)
