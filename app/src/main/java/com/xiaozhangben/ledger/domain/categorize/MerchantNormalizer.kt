package com.xiaozhangben.ledger.domain.categorize

/**
 * Turns the messy payee strings found in Chinese payment bills into something a
 * keyword rule can match.
 *
 * Real inputs look like:
 *   "支付宝-北京三快在线科技有限公司"   (Meituan, via its legal entity)
 *   "财付通-微信支付-星巴克"
 *   "肯德基(国贸店)"
 *   "上海拉扎斯信息科技有限公司"       (Ele.me)
 *
 * A keyword rule for "星巴克" only works because the prefix noise and the legal
 * suffix are removed first, and a rule for "美团" only works because the legal
 * entity is aliased to the consumer brand it belongs to.
 *
 * The raw string is always preserved separately (`counterpartyRaw`) so nothing is
 * lost: the UI shows the original and the rules run on the normalized form.
 */
object MerchantNormalizer {

    /** Full-width → half-width, so one set of rules covers both export versions. */
    private fun toHalfWidth(input: String): String {
        val sb = StringBuilder(input.length)
        for (ch in input) {
            val code = ch.code
            when {
                code == 0x3000 -> sb.append(' ')
                code in 0xFF01..0xFF5E -> sb.append((code - 0xFEE0).toChar())
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    /** Drops "(国贸店)", "【旗舰店】" style qualifiers. */
    private val BRACKETED = Regex("[（(\\[【｛{][^）)\\]】｝}]*[）)\\]】｝}]")

    private val LEADING_NOISE = Regex(
        "^(支付宝|财付通|微信支付|微信|银联|云闪付|京东支付|抖音支付|支付宝中国网络技术|支付宝网络技术)" +
            "[-－—_·*:：/\\\\\\s]*"
    )

    private val SEPARATORS = Regex("[-－—_·*|/\\\\]+")

    /**
     * Corporate and trade words that carry no brand information but do break
     * keyword matching. Removed anywhere in the string.
     */
    private val LEGAL_WORDS = listOf(
        "有限责任公司", "股份有限公司", "有限公司", "集团有限公司", "集团", "分公司", "子公司",
        "网络科技", "信息技术", "信息科技", "网络技术", "计算机系统", "电子商务", "电子科技",
        "文化传播", "文化传媒", "企业管理", "商务服务", "供应链管理", "商贸", "贸易", "实业",
        "科技", "物流", "配送服务", "服务", "中心", "商行", "经营部", "门市部",
        "官方旗舰店", "旗舰店", "专营店", "专卖店", "分店", "门店",
    )

    /**
     * Legal entity → consumer brand. Longest fragments first: a shorter alias
     * listed earlier would shadow a more specific one.
     */
    private val ALIASES: List<Pair<String, String>> = listOf(
        "三快在线" to "美团",
        "拉扎斯" to "饿了么",
        "小桔科技" to "滴滴",
        "嘀嘀无限" to "滴滴",
        "阿里妈妈" to "阿里",
        "阿里巴巴" to "阿里",
        "淘宝" to "淘宝",
        "天猫" to "天猫",
        "京东世纪" to "京东",
        "京东信息" to "京东",
        "寻梦信息" to "拼多多",
        "字节跳动" to "字节跳动",
        "微众银行" to "微众银行",
        "财付通支付" to "财付通",
        "腾讯" to "腾讯",
        "网易" to "网易",
        "星巴克" to "星巴克",
        "金拱门" to "麦当劳",
        "百胜" to "肯德基",
        "美团" to "美团",
        "滴滴出行" to "滴滴",
        "出行" to "出行",
    )

    /**
     * @return a lowercase, whitespace-collapsed, brand-substituted string.
     *         Never returns the input unchanged for known noise patterns.
     */
    fun normalize(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        var s = toHalfWidth(raw).trim()
        s = BRACKETED.replace(s, " ")

        // Prefixes can stack ("财付通-微信支付-星巴克"), so loop until stable.
        var previous: String
        do {
            previous = s
            s = LEADING_NOISE.replace(s, "")
        } while (s != previous)

        for (word in LEGAL_WORDS) {
            if (s.contains(word)) s = s.replace(word, " ")
        }

        s = SEPARATORS.replace(s, " ")
        s = s.replace(Regex("\\s+"), " ").trim()

        for ((fragment, brand) in ALIASES) {
            if (s.contains(fragment)) {
                s = brand
                break
            }
        }

        return s.lowercase()
    }

    /**
     * The text a rule should be matched against: normalized payee plus the raw
     * payee (so a rule can still target a fragment that normalization removed).
     */
    fun matchableText(counterpartyRaw: String?, productDesc: String?): String {
        val parts = listOfNotNull(
            normalize(counterpartyRaw),
            toHalfWidth(productDesc.orEmpty()).lowercase().trim(),
        )
        return parts.filter { it.isNotEmpty() }.joinToString(" ")
    }
}
