package com.xiaozhangben.ledger.domain.categorize

import com.xiaozhangben.ledger.core.LogX
import com.xiaozhangben.ledger.domain.model.CategoryKeys
import com.xiaozhangben.ledger.domain.model.CategoryKind
import com.xiaozhangben.ledger.domain.model.ExcludeReason
import com.xiaozhangben.ledger.domain.model.RuleMatch
import com.xiaozhangben.ledger.domain.model.RuleOrigin
import com.xiaozhangben.ledger.domain.model.RuleTarget
import com.xiaozhangben.ledger.domain.model.TxDirection
import com.xiaozhangben.ledger.domain.model.TxSource

data class ClassifyInput(
    val source: TxSource,
    val direction: TxDirection,
    val rawType: String? = null,
    val status: String? = null,
    val counterpartyRaw: String? = null,
    val productDesc: String? = null,
    val payMethod: String? = null,
)

data class ClassifyResult(
    val categoryKey: String,
    val kind: CategoryKind = CategoryKind.VARIABLE,
    val excludeReason: ExcludeReason? = null,
    val matchedRuleId: Long? = null,
    val matchedRuleLabel: String? = null,
    val confidence: Float = 0f,
) {
    val isExcluded: Boolean get() = excludeReason != null

    /** Rows we could not explain. They must be surfaced, never folded into "其他". */
    val needsTriage: Boolean
        get() = !isExcluded && (categoryKey.isEmpty() || categoryKey == CategoryKeys.UNCLASSIFIED)
}

/**
 * The classification engine.
 *
 * Pipeline order is deliberate and the engine is explainable at every step —
 * [ClassifyResult.matchedRuleLabel] is shown in the transaction detail screen so a
 * wrong classification can be understood and corrected rather than merely resented.
 *
 *   1. bill status  -> refund / cancelled        (structural, highest confidence)
 *   2. bill type    -> transfer / investment / brand category
 *   3. rules, by ascending priority              (learned and user rules sit below preset)
 *   4. triage queue                              (explicitly NOT "其他")
 *
 * No machine-learning model: for a personal ledger, keyword rules plus one-shot
 * learning beat a model on accuracy, latency, battery and explainability.
 */
class Categorizer(rules: List<RuleDef>) {

    private val rules: List<RuleDef> = rules
        .asSequence()
        .filter { it.enabled && it.pattern.isNotBlank() }
        .sortedWith(compareBy({ it.priority }, { it.id }))
        .toList()

    private val regexCache = HashMap<String, Regex>()

    fun classify(input: ClassifyInput): ClassifyResult {
        // 1. Reversed or abandoned payments.
        RawTypeMapping.fromStatus(input.status)?.let { reason ->
            return ClassifyResult(
                categoryKey = if (reason == ExcludeReason.REFUND) CategoryKeys.REFUND_POOL else CategoryKeys.UNCLASSIFIED,
                excludeReason = reason,
                matchedRuleLabel = "账单状态：${input.status}",
                confidence = 1f,
            )
        }

        // 2. The bill's own type column.
        RawTypeMapping.fromRawType(input.source, input.rawType, input.direction)?.let { return it }

        // 3. Rules.
        val normalizedPayee = MerchantNormalizer.normalize(input.counterpartyRaw)
        val haystackAny = buildString {
            append(normalizedPayee)
            // Keep the untouched payee in the haystack too: normalization strips some
            // fragments, and a rule may legitimately target one of them.
            input.counterpartyRaw?.let { append(' ').append(it.lowercase()) }
            input.productDesc?.let { append(' ').append(it.lowercase()) }
            input.rawType?.let { append(' ').append(it.lowercase()) }
        }.trim()
        val productText = input.productDesc.orEmpty().lowercase()
        val typeText = input.rawType.orEmpty().lowercase()

        for (rule in rules) {
            val haystack = when (rule.target) {
                RuleTarget.ANY -> haystackAny
                RuleTarget.COUNTERPARTY -> normalizedPayee.ifEmpty { input.counterpartyRaw.orEmpty().lowercase() }
                RuleTarget.PRODUCT -> productText
                RuleTarget.RAW_TYPE -> typeText
            }
            if (haystack.isEmpty()) continue
            if (!matches(rule, haystack)) continue

            return ClassifyResult(
                categoryKey = rule.categoryKey ?: CategoryKeys.UNCLASSIFIED,
                kind = rule.kind,
                excludeReason = rule.excludeReason,
                matchedRuleId = rule.id,
                matchedRuleLabel = rule.label,
                confidence = when (rule.origin) {
                    RuleOrigin.LEARNED -> 0.9f
                    RuleOrigin.USER -> 0.92f
                    RuleOrigin.PRESET -> 0.7f
                },
            )
        }

        // 4. Nothing matched. Keep it visible instead of dumping it into 其他.
        LogX.count("Categorizer", "triaged", 1)
        return ClassifyResult(categoryKey = CategoryKeys.UNCLASSIFIED, confidence = 0f)
    }

    private fun matches(rule: RuleDef, haystack: String): Boolean = when (rule.match) {
        RuleMatch.CONTAINS -> haystack.contains(rule.pattern.lowercase())
        RuleMatch.EQUALS -> haystack.trim() == rule.pattern.trim().lowercase()
        RuleMatch.REGEX -> {
            val regex = regexCache.getOrPut(rule.pattern) {
                try {
                    Regex(rule.pattern, RegexOption.IGNORE_CASE)
                } catch (_: Exception) {
                    // An invalid user regex must not crash classification of every row.
                    Regex("(?!)")
                }
            }
            regex.containsMatchIn(haystack)
        }
    }
}

/**
 * Turns a manual correction into a rule, which is the whole mechanism by which the
 * app gets more accurate the more it is used.
 *
 * The learned pattern is the normalized payee, so it generalises across the legal
 * entity's many spellings. Learned rules take the reserved low priority band, so
 * they outrank the shipped preset library but can still be reordered by hand.
 */
object RuleLearner {

    const val LEARNED_PRIORITY_BASE = 500

    /** Payees whose normalized form is too generic to key a rule on. */
    private val TOO_GENERIC = setOf("", "微信支付", "支付宝", "财付通", "个人", "商户", "商家", "未知")

    fun learnFrom(
        counterpartyRaw: String?,
        categoryKey: String,
        kind: CategoryKind = CategoryKind.VARIABLE,
        existingPriority: Int = LEARNED_PRIORITY_BASE,
        id: Long = 0L,
    ): RuleDef? {
        val pattern = MerchantNormalizer.normalize(counterpartyRaw)
        if (pattern.isBlank() || pattern in TOO_GENERIC) return null
        // A one-character pattern would match almost anything; refuse rather than
        // poison the rule table with an over-broad rule.
        if (pattern.length < 2) return null

        return RuleDef(
            id = id,
            priority = existingPriority,
            pattern = pattern,
            target = RuleTarget.COUNTERPARTY,
            match = RuleMatch.CONTAINS,
            categoryKey = categoryKey,
            kind = kind,
            origin = RuleOrigin.LEARNED,
            label = "$pattern → $categoryKey（已学习）",
        )
    }
}
