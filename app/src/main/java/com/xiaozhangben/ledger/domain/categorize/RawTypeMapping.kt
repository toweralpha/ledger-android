package com.xiaozhangben.ledger.domain.categorize

import com.xiaozhangben.ledger.domain.model.CategoryKeys
import com.xiaozhangben.ledger.domain.model.CategoryKind
import com.xiaozhangben.ledger.domain.model.ExcludeReason
import com.xiaozhangben.ledger.domain.model.TxDirection
import com.xiaozhangben.ledger.domain.model.TxSource

/**
 * Reads the transaction type / status columns that Alipay and WeChat bills already
 * provide. These are the highest-confidence signal available — far better than
 * guessing from a payee string — and they are what stops transfers, red packets
 * and Yu'ebao moves from being booked as spending.
 *
 * A `null` return means "no opinion": the row falls through to the rule engine.
 */
object RawTypeMapping {

    /** WeChat `当前状态` / Alipay `交易状态` values that mean the money came back. */
    private val REFUND_MARKERS = listOf("全额退款", "已退款", "部分退款", "退款成功", "对方已退还", "已退汇")

    /** Statuses that mean the payment never actually happened. */
    private val CLOSED_MARKERS = listOf("交易关闭", "已关闭", "支付失败", "交易失败", "已撤销", "已取消", "已失效", "关闭")

    // ---- Alipay `交易分类` -------------------------------------------------------
    private val ALIPAY_CATEGORY: Map<String, Pair<String, CategoryKind>> = mapOf(
        "餐饮美食" to (CategoryKeys.FOOD to CategoryKind.VARIABLE),
        "交通出行" to (CategoryKeys.TRANSPORT to CategoryKind.VARIABLE),
        "服饰装扮" to (CategoryKeys.SHOPPING to CategoryKind.VARIABLE),
        "日用百货" to (CategoryKeys.SHOPPING to CategoryKind.VARIABLE),
        "数码电器" to (CategoryKeys.SHOPPING to CategoryKind.VARIABLE),
        "美容美发" to (CategoryKeys.SERVICES to CategoryKind.VARIABLE),
        "生活服务" to (CategoryKeys.SERVICES to CategoryKind.VARIABLE),
        "文化休闲" to (CategoryKeys.ENTERTAINMENT to CategoryKind.VARIABLE),
        "医疗健康" to (CategoryKeys.HEALTH to CategoryKind.VARIABLE),
        "住房物业" to (CategoryKeys.HOUSING to CategoryKind.FIXED),
        "教育培训" to (CategoryKeys.EDUCATION to CategoryKind.VARIABLE),
        "运动户外" to (CategoryKeys.HEALTH to CategoryKind.VARIABLE),
        "酒店旅游" to (CategoryKeys.TRAVEL to CategoryKind.VARIABLE),
        // Exclusions
        "投资理财" to ("" to CategoryKind.VARIABLE),
        "信用借还" to ("" to CategoryKind.VARIABLE),
        "保险" to ("" to CategoryKind.VARIABLE),
        "转账" to ("" to CategoryKind.VARIABLE),
        "亲友代付" to ("" to CategoryKind.VARIABLE),
        "退款" to ("" to CategoryKind.VARIABLE),
        // Deliberately unmapped: "充值缴费" covers both phone top-ups and utility
        // bills, so keyword rules on 商品说明 decide instead of a coin flip here.
        // "商户服务" / "其他" are likewise not actionable.
    )

    private val ALIPAY_EXCLUDE_CATEGORY: Map<String, ExcludeReason> = mapOf(
        "投资理财" to ExcludeReason.INVEST,
        "信用借还" to ExcludeReason.INVEST,
        "保险" to ExcludeReason.INVEST,
        "转账" to ExcludeReason.TRANSFER,
        "亲友代付" to ExcludeReason.TRANSFER,
        "退款" to ExcludeReason.REFUND,
    )

    // ---- WeChat `交易类型` ------------------------------------------------------
    private val WECHAT_EXCLUDE_TYPE: Map<String, ExcludeReason> = mapOf(
        "转账" to ExcludeReason.TRANSFER,
        "群收款" to ExcludeReason.TRANSFER,
        "亲属卡" to ExcludeReason.TRANSFER,
        "零钱提现" to ExcludeReason.INVEST,
        "信用卡还款" to ExcludeReason.INVEST,
        "零钱通转入" to ExcludeReason.INVEST,
        "零钱通转出" to ExcludeReason.INVEST,
        "理财通买入" to ExcludeReason.INVEST,
        "理财通卖出" to ExcludeReason.INVEST,
        "分付还款" to ExcludeReason.INVEST,
        "二维码收款" to ExcludeReason.TRANSFER,
        "收款码收款" to ExcludeReason.TRANSFER,
    )

    /**
     * Status check runs first and independently of direction: a reversed payment is
     * a refund whether the export shows it on the original row or as its own row.
     */
    fun fromStatus(status: String?): ExcludeReason? {
        if (status.isNullOrBlank()) return null
        val s = status.trim()
        if (REFUND_MARKERS.any { s.contains(it) }) return ExcludeReason.REFUND
        if (CLOSED_MARKERS.any { s.contains(it) }) return ExcludeReason.CLOSED
        return null
    }

    fun fromRawType(source: TxSource, rawType: String?, direction: TxDirection): ClassifyResult? {
        if (rawType.isNullOrBlank()) return null
        val t = rawType.trim()

        if (source == TxSource.ALIPAY) {
            ALIPAY_EXCLUDE_CATEGORY[t]?.let { reason ->
                return ClassifyResult(
                    categoryKey = if (reason == ExcludeReason.REFUND) CategoryKeys.REFUND_POOL else CategoryKeys.UNCLASSIFIED,
                    kind = CategoryKind.VARIABLE,
                    excludeReason = reason,
                    matchedRuleLabel = "支付宝分类：$t",
                    confidence = 0.95f,
                )
            }
            ALIPAY_CATEGORY[t]?.let { (key, kind) ->
                return ClassifyResult(
                    categoryKey = key,
                    kind = kind,
                    matchedRuleLabel = "支付宝分类：$t",
                    confidence = 0.95f,
                )
            }
            // Alipay flags red packets as their own category; outgoing is real spending.
            if (t.contains("红包")) {
                return if (direction == TxDirection.EXPENSE) {
                    ClassifyResult(
                        categoryKey = CategoryKeys.SOCIAL,
                        kind = CategoryKind.VARIABLE,
                        matchedRuleLabel = "支付宝分类：$t",
                        confidence = 0.9f,
                    )
                } else {
                    ClassifyResult(
                        categoryKey = CategoryKeys.UNCLASSIFIED,
                        kind = CategoryKind.VARIABLE,
                        excludeReason = ExcludeReason.TRANSFER,
                        matchedRuleLabel = "支付宝分类：$t",
                        confidence = 0.9f,
                    )
                }
            }
            return null
        }

        if (source == TxSource.WECHAT) {
            WECHAT_EXCLUDE_TYPE[t]?.let { reason ->
                return ClassifyResult(
                    categoryKey = CategoryKeys.UNCLASSIFIED,
                    kind = CategoryKind.VARIABLE,
                    excludeReason = reason,
                    matchedRuleLabel = "微信类型：$t",
                    confidence = 0.95f,
                )
            }
            if (t.contains("红包")) {
                // Sending a red packet is a real transfer of value, but people budget
                // it under 人情往来 rather than excluding it outright.
                return if (direction == TxDirection.EXPENSE) {
                    ClassifyResult(
                        categoryKey = CategoryKeys.SOCIAL,
                        kind = CategoryKind.VARIABLE,
                        matchedRuleLabel = "微信类型：$t",
                        confidence = 0.9f,
                    )
                } else {
                    ClassifyResult(
                        categoryKey = CategoryKeys.UNCLASSIFIED,
                        kind = CategoryKind.VARIABLE,
                        excludeReason = ExcludeReason.TRANSFER,
                        matchedRuleLabel = "微信类型：$t",
                        confidence = 0.9f,
                    )
                }
            }
            // "商户消费" and "扫二维码付款" carry no category: the payee decides.
            return null
        }

        return null
    }
}
