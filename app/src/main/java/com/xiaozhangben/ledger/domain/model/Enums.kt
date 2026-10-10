package com.xiaozhangben.ledger.domain.model

/** Where a transaction came from. */
enum class TxSource {
    ALIPAY,
    WECHAT,
    MANUAL,
    BANK,
    UNKNOWN;

    val label: String
        get() = when (this) {
            ALIPAY -> "支付宝"
            WECHAT -> "微信"
            MANUAL -> "手动"
            BANK -> "银行卡"
            UNKNOWN -> "其他"
        }
}

enum class TxDirection {
    EXPENSE,
    INCOME,

    /**
     * Alipay writes "不计收支" and WeChat writes "/" for rows that move no money in
     * or out (e.g. a red packet that expired). Modelling this explicitly keeps the
     * spending total honest without pretending the row is excluded for a reason.
     */
    NEUTRAL;

    val label: String
        get() = when (this) {
            EXPENSE -> "支出"
            INCOME -> "收入"
            NEUTRAL -> "不计收支"
        }
}

/** Fixed costs are unavoidable monthly commitments; variable costs are the ones a budget can actually steer. */
enum class CategoryKind {
    FIXED,
    VARIABLE;

    val label: String get() = if (this == FIXED) "固定" else "弹性"
}

/**
 * Why a row is kept out of the spending total.
 *
 * This enum is the single most important guard against a meaningless budget:
 * real bills are full of transfers, red packets, refunds, Yu'ebao moves and card
 * repayments, and counting any of them as spending inflates "已用" permanently.
 */
enum class ExcludeReason {
    /** Moving money between the user's own accounts, or person-to-person transfer. */
    TRANSFER,

    /** Money coming back. Offset against the original category, never booked as income. */
    REFUND,

    /** 余额宝 / 零钱通 / 理财通 / 基金 / 信用卡还款 / 提现 / 充值. */
    INVEST,

    /** Closed or failed payment. */
    CLOSED,

    /** Same real-world payment captured through a second channel (notification + CSV). */
    DUPLICATE;

    val label: String
        get() = when (this) {
            TRANSFER -> "转账"
            REFUND -> "退款"
            INVEST -> "理财/还款"
            CLOSED -> "已关闭"
            DUPLICATE -> "重复"
        }
}

/** Which text field a rule inspects. */
enum class RuleTarget {
    ANY,
    COUNTERPARTY,
    PRODUCT,
    RAW_TYPE
}

enum class RuleMatch {
    CONTAINS,
    EQUALS,
    REGEX
}

enum class RuleOrigin {
    /** Shipped with the app. */
    PRESET,

    /** Created automatically when the user corrects a category. */
    LEARNED,

    /** Created by hand in the rules screen. */
    USER
}

/**
 * Stable string keys for the built-in categories.
 *
 * Preset rules reference keys (not database ids) so the rule table can ship ahead
 * of user data and survive a database rebuild.
 */
object CategoryKeys {
    const val FOOD = "food"
    const val TRANSPORT = "transport"
    const val SHOPPING = "shopping"
    const val HOUSING = "housing"
    const val TELECOM = "telecom"
    const val ENTERTAINMENT = "entertainment"
    const val HEALTH = "health"
    const val SOCIAL = "social"
    const val EDUCATION = "education"
    const val SERVICES = "services"
    const val TRAVEL = "travel"
    const val DIGITAL = "digital"
    const val OTHER = "other"

    /** Not a category the user assigned; the triage queue. */
    const val UNCLASSIFIED = "unclassified"

    /** Refunds that could not be matched to an original transaction. */
    const val REFUND_POOL = "refund_pool"

    /** Categories pre-selected on the quick-entry keypad, in display order. */
    val QUICK_ENTRY = listOf(FOOD, TRANSPORT, SHOPPING, ENTERTAINMENT, SERVICES, OTHER)
}
