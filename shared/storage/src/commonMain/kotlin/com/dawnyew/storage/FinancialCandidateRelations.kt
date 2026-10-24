package com.dawnyew.storage

import androidx.room.Transactor
import com.dawnyew.core.*

/** Global financial rules use joins/aggregates and bounded record decoding, never a second in-memory ledger. */
internal suspend fun validateFinancialCandidateRelations(connection:Transactor) {
    suspend fun reject(sql:String,message:String) { connection.usePrepared(sql) { if(it.step()) invalid(message) } }
    reject("PRAGMA foreign_key_check","候选财务数据存在悬空引用")
    reject("SELECT 1 FROM entry_details d JOIN entries e ON e.id=d.entryId LEFT JOIN accounts a ON a.id=d.accountId LEFT JOIN dimensions p ON p.id=d.projectId LEFT JOIN dimensions m ON m.id=d.merchantId WHERE (a.id IS NOT NULL AND (a.ledgerId!=e.ledgerId OR a.openingAt>e.occurrence)) OR (p.id IS NOT NULL AND (p.ledgerId!=e.ledgerId OR p.kind!='PROJECT')) OR (m.id IS NOT NULL AND (m.ledgerId!=e.ledgerId OR m.kind!='MERCHANT')) OR (e.kind='REFUND' AND d.excludedFromBudget!=0) OR EXISTS(SELECT 1 FROM obligations o WHERE o.virtualAccountId=d.accountId) LIMIT 1","账单扩展账户、维度或退款预算关系无效")
    reject("SELECT 1 FROM entry_tags t JOIN entries e ON e.id=t.entryId JOIN dimensions d ON d.id=t.tagId WHERE d.ledgerId!=e.ledgerId OR d.kind!='TAG' LIMIT 1","账单标签关系无效")
    reject("SELECT 1 FROM obligations o JOIN accounts a ON a.id=o.virtualAccountId LEFT JOIN contacts c ON c.id=o.contactId WHERE a.ledgerId!=o.ledgerId OR a.openingBalanceMinor!=0 OR a.openingAt!=o.openingAt OR a.includedInAssets!=1 OR a.type!=CASE WHEN o.kind='BORROW' THEN 'PAYABLE' ELSE 'RECEIVABLE' END OR (c.id IS NOT NULL AND c.ledgerId!=o.ledgerId) LIMIT 1","单据专属账户或联系人关系无效")
    reject("SELECT 1 FROM transfers t LEFT JOIN accounts f ON f.id=t.fromAccountId LEFT JOIN accounts a ON a.id=t.toAccountId WHERE (f.id IS NOT NULL AND (f.ledgerId!=t.ledgerId OR f.openingAt>t.occurrence)) OR (a.id IS NOT NULL AND (a.ledgerId!=t.ledgerId OR a.openingAt>t.occurrence)) LIMIT 1","转账账户归属或期初日期无效")
    reject("SELECT 1 FROM transfers t WHERE (t.businessKind='MANUAL' AND (t.fromAccountId IS NULL OR t.toAccountId IS NULL OR t.businessId IS NOT NULL)) OR (t.businessKind IN ('MANUAL','SAVINGS') AND EXISTS(SELECT 1 FROM obligations o WHERE o.virtualAccountId=t.fromAccountId OR o.virtualAccountId=t.toAccountId)) LIMIT 1","普通转账或存钱不能使用单据专属账户")
    reject("SELECT 1 FROM transfers t LEFT JOIN savings_movements m ON m.id=t.businessId LEFT JOIN savings_plans p ON p.id=m.planId WHERE t.businessKind='SAVINGS' AND (m.id IS NULL OR m.transferId IS NOT t.id OR p.ledgerId!=t.ledgerId OR m.amountMinor!=t.amountMinor OR m.occurrence!=t.occurrence OR m.deleted!=t.deleted) LIMIT 1","存钱转账与进度不一致")
    reject("SELECT 1 FROM transfers t LEFT JOIN obligations o ON o.id=t.businessId WHERE t.businessKind IN ('OBLIGATION_PRINCIPAL','OBLIGATION_SETTLEMENT') AND (o.id IS NULL OR t.ledgerId!=o.ledgerId OR t.fromAccountId IS NULL OR t.toAccountId IS NULL OR CASE WHEN (o.kind='BORROW')=(t.businessKind='OBLIGATION_PRINCIPAL') THEN t.fromAccountId ELSE t.toAccountId END IS NOT o.virtualAccountId OR EXISTS(SELECT 1 FROM obligations v WHERE v.virtualAccountId=CASE WHEN (o.kind='BORROW')=(t.businessKind='OBLIGATION_PRINCIPAL') THEN t.toAccountId ELSE t.fromAccountId END)) LIMIT 1","借还转账来源或资金方向无效")
    reject("SELECT businessId FROM transfers WHERE deleted=0 AND recorded=1 AND businessKind IN ('OBLIGATION_PRINCIPAL','OBLIGATION_SETTLEMENT') GROUP BY businessId HAVING SUM(CASE WHEN businessKind='OBLIGATION_PRINCIPAL' THEN amountMinor ELSE -amountMinor END)<0 LIMIT 1","结算金额超过已入账本金")
    reject("SELECT 1 FROM recurring_plans p LEFT JOIN categories c ON c.id=p.categoryId LEFT JOIN dimensions d ON d.id=p.projectId LEFT JOIN dimensions m ON m.id=p.merchantId WHERE (p.kind!='TRANSFER' AND (c.id IS NULL OR c.ledgerId!=p.ledgerId OR c.direction!=CASE WHEN p.kind='INCOME' THEN 'INCOME' ELSE 'EXPENSE' END)) OR (d.id IS NOT NULL AND (d.ledgerId!=p.ledgerId OR d.kind!='PROJECT')) OR (m.id IS NOT NULL AND (m.ledgerId!=p.ledgerId OR m.kind!='MERCHANT')) LIMIT 1","周期计划分类或维度关系无效")
    for(field in listOf("accountId","fromAccountId","toAccountId")) reject("SELECT 1 FROM recurring_plans p JOIN accounts a ON a.id=p.$field WHERE a.ledgerId!=p.ledgerId OR EXISTS(SELECT 1 FROM obligations o WHERE o.virtualAccountId=a.id) LIMIT 1","周期计划账户归属或期初日期无效")
    connection.usePrepared("SELECT p.payload,a.openingAt,f.openingAt,t.openingAt FROM recurring_plans p LEFT JOIN accounts a ON a.id=p.accountId LEFT JOIN accounts f ON f.id=p.fromAccountId LEFT JOIN accounts t ON t.id=p.toAccountId") { rows ->
        while(rows.step()) {
            val plan=streamJson.decodeFromString<RecurringPlan>(rows.getText(0))
            val next=recurringNextOccurrence(plan)
            if(next!=null) for(column in 1..3) if(!rows.isNull(column) && rows.getText(column)>next) invalid("周期账户晚于下一未处理期次")
        }
    }
    reject("SELECT 1 FROM recurring_plans p LEFT JOIN recurring_occurrences o ON o.planId=p.id GROUP BY p.id HAVING COUNT(o.id)!=p.nextIndex OR (p.nextIndex>0 AND (MIN(o.`index`)!=0 OR MAX(o.`index`)!=p.nextIndex-1)) LIMIT 1","周期凭证与已处理次数不一致")
    reject("SELECT 1 FROM recurring_occurrences o JOIN recurring_plans p ON p.id=o.planId LEFT JOIN entries e ON e.id=o.entryId LEFT JOIN transfers t ON t.id=o.transferId WHERE (e.id IS NOT NULL AND e.ledgerId!=p.ledgerId) OR (t.id IS NOT NULL AND t.ledgerId!=p.ledgerId) LIMIT 1","周期生成结果账本不一致")
    connection.usePrepared("SELECT p.payload,o.`index`,o.scheduledAt FROM recurring_occurrences o JOIN recurring_plans p ON p.id=o.planId ORDER BY o.id") { rows ->
        while(rows.step()) {
            val p=streamJson.decodeFromString<RecurringPlan>(rows.getText(0))
            if(recurringDate(p,rows.getLong(1).toInt())!=rows.getText(2)) invalid("周期凭证时间与锚点不一致")
        }
    }
    reject("SELECT 1 FROM savings_movements m LEFT JOIN transfers t ON t.id=m.transferId LEFT JOIN savings_wishes w ON w.fulfilledMovementId=m.id WHERE (m.transferId IS NOT NULL AND (t.businessKind IS NOT 'SAVINGS' OR t.businessId IS NOT m.id)) OR (m.kind='WISH' AND (m.deleted!=0 OR w.id IS NULL OR w.planId!=m.planId)) OR (m.kind!='WISH' AND w.id IS NOT NULL) LIMIT 1","存钱记录或愿望完成关系无效")
    reject("SELECT 1 FROM savings_wishes w JOIN savings_movements m ON m.id=w.fulfilledMovementId WHERE m.planId!=w.planId OR m.deleted!=0 OR m.kind!='WISH' OR m.amountMinor!=w.amountMinor LIMIT 1","愿望完成金额或计划不一致")
    reject("SELECT planId FROM savings_movements WHERE deleted=0 GROUP BY planId HAVING SUM(CASE WHEN kind='DEPOSIT' THEN amountMinor ELSE -amountMinor END)<0 LIMIT 1","存钱余额不能为负")
    reject("SELECT 1 FROM budget_allocations a JOIN categories c ON c.id=a.categoryId WHERE a.ledgerId!=c.ledgerId OR c.direction!='EXPENSE' LIMIT 1","分类预算归属无效")
    reject("SELECT a.ledgerId,a.yearMonth FROM budget_allocations a JOIN budgets b ON b.ledgerId=a.ledgerId AND b.yearMonth=a.yearMonth GROUP BY a.ledgerId,a.yearMonth HAVING SUM(a.amountMinor)>MAX(b.amountMinor) LIMIT 1","分类额度超过月总预算")
    reject("SELECT 1 FROM budget_reallocations r JOIN budget_allocations f ON f.id=r.fromAllocationId JOIN budget_allocations t ON t.id=r.toAllocationId WHERE f.ledgerId!=r.ledgerId OR t.ledgerId!=r.ledgerId OR f.yearMonth!=r.yearMonth OR t.yearMonth!=r.yearMonth LIMIT 1","预算转移必须同账本同月份")
    reject("SELECT 1 FROM budget_reallocations r JOIN budget_reallocations p ON p.id=r.reversalOfId WHERE r.id=p.id OR p.fromAllocationId!=r.toAllocationId OR p.toAllocationId!=r.fromAllocationId OR p.amountMinor!=r.amountMinor OR p.reversalOfId IS NOT NULL LIMIT 1","预算撤回关系无效")
    // SQLite SUM rejects signed 64-bit overflow; the final asset sums are checked as well.
    var assets=0L;var liabilities=0L;var net=0L
    connection.usePrepared("SELECT accountId,SUM(delta),MAX(included) FROM (SELECT id AS accountId,openingBalanceMinor AS delta,includedInAssets AS included FROM accounts UNION ALL SELECT d.accountId,CASE WHEN e.kind='EXPENSE' THEN -e.amountMinor ELSE e.amountMinor END,0 FROM entries e JOIN entry_details d ON d.entryId=e.id WHERE e.deletionGroupId IS NULL AND d.recorded=1 AND d.accountId IS NOT NULL UNION ALL SELECT fromAccountId,-amountMinor,0 FROM transfers WHERE deleted=0 AND recorded=1 AND fromAccountId IS NOT NULL UNION ALL SELECT toAccountId,amountMinor,0 FROM transfers WHERE deleted=0 AND recorded=1 AND toAccountId IS NOT NULL) GROUP BY accountId") { rows ->
        while(rows.step()) if(rows.getLong(2)==1L) {
            val balance=rows.getLong(1);net=Money.add(net,balance)
            if(balance>=0) assets=Money.add(assets,balance) else {
                if(balance==Long.MIN_VALUE) invalid("负债汇总溢出")
                liabilities=Money.add(liabilities,-balance)
            }
        }
    }
}
