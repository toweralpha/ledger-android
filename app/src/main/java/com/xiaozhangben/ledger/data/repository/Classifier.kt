package com.xiaozhangben.ledger.data.repository

import com.xiaozhangben.ledger.core.LogX
import com.xiaozhangben.ledger.data.local.RuleDao
import com.xiaozhangben.ledger.data.local.toDomain
import com.xiaozhangben.ledger.data.local.toEntity
import com.xiaozhangben.ledger.domain.categorize.Categorizer
import com.xiaozhangben.ledger.domain.categorize.ClassifyInput
import com.xiaozhangben.ledger.domain.categorize.ClassifyResult
import com.xiaozhangben.ledger.domain.categorize.MerchantNormalizer
import com.xiaozhangben.ledger.domain.categorize.RuleLearner
import com.xiaozhangben.ledger.domain.model.CategoryKind

/**
 * Owns the classifier and its rule cache.
 *
 * The rule table is small (a few hundred rows) and read on every captured payment,
 * so it is kept in memory and rebuilt only when a rule actually changes. A
 * `@Volatile` reference hands readers a consistent snapshot without locking the
 * classification path — capture happens on a system binder thread, where blocking
 * would be the wrong trade.
 */
class Classifier(private val ruleDao: RuleDao) {

    @Volatile
    private var categorizer: Categorizer = Categorizer(emptyList())

    @Volatile
    private var loaded: Boolean = false

    suspend fun ensureLoaded() {
        if (loaded) return
        refresh()
    }

    suspend fun refresh() {
        val rules = ruleDao.loadEnabled().map { it.toDomain() }
        categorizer = Categorizer(rules)
        loaded = true
        LogX.count("Classifier", "rulesLoaded", rules.size)
    }

    fun classify(input: ClassifyInput): ClassifyResult = categorizer.classify(input)

    /**
     * Records a manual correction as a rule.
     *
     * This is the mechanism that makes the app get more accurate the more it is
     * used: correct a merchant once and it is correct from then on. Returns the
     * rule id that will now match, or null when the payee is too generic to be
     * worth keying on (learning from "商户" would mislabel everything).
     */
    suspend fun learnFromCorrection(
        counterpartyRaw: String?,
        categoryKey: String,
        kind: CategoryKind = CategoryKind.VARIABLE,
        now: Long = System.currentTimeMillis(),
    ): Long? {
        val normalized = MerchantNormalizer.normalize(counterpartyRaw)
        if (normalized.isBlank()) return null

        // Already learned? Just move it to the corrected category and bump priority.
        val existing = ruleDao.findLearned(normalized)
        if (existing != null) {
            ruleDao.insert(
                existing.copy(
                    categoryKey = categoryKey,
                    kind = kind.name,
                    enabled = true,
                    updatedAt = now,
                )
            )
            refresh()
            return existing.id
        }

        val learned = RuleLearner.learnFrom(
            counterpartyRaw = counterpartyRaw,
            categoryKey = categoryKey,
            kind = kind,
            existingPriority = RuleLearner.LEARNED_PRIORITY_BASE,
        ) ?: return null

        val id = ruleDao.insert(learned.toEntity(now).copy(id = 0L))
        refresh()
        LogX.d("Classifier") { "learned rule $id from a manual correction" }
        return id
    }

    suspend fun bumpHit(ruleId: Long, now: Long = System.currentTimeMillis()) {
        if (ruleId <= 0L) return
        // Hit counts are diagnostics only; a failure here must never break capture.
        runCatching { ruleDao.incrementHit(ruleId, now) }
    }

    suspend fun setEnabled(ruleId: Long, enabled: Boolean, now: Long = System.currentTimeMillis()) {
        ruleDao.setEnabled(ruleId, enabled, now)
        refresh()
    }

    suspend fun deleteCustomRule(ruleId: Long) {
        ruleDao.deleteCustom(ruleId)
        refresh()
    }
}
