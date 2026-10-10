package com.xiaozhangben.ledger.domain.categorize

import com.xiaozhangben.ledger.domain.model.CategoryKeys
import com.xiaozhangben.ledger.domain.model.ExcludeReason
import com.xiaozhangben.ledger.domain.model.TxDirection
import com.xiaozhangben.ledger.domain.model.TxSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MerchantNormalizerTest {

    @Test
    fun `strips the alipay prefix and resolves the legal entity to its brand`() {
        // Meituan pays through "北京三快在线科技有限公司"; no keyword rule for "美团"
        // would ever fire without this aliasing step.
        assertEquals("美团", MerchantNormalizer.normalize("支付宝-北京三快在线科技有限公司"))
        assertEquals("美团", MerchantNormalizer.normalize("北京三快在线科技有限公司"))
    }

    @Test
    fun `strips stacked prefixes`() {
        assertEquals("星巴克", MerchantNormalizer.normalize("财付通-微信支付-星巴克"))
        assertEquals("星巴克", MerchantNormalizer.normalize("微信支付-星巴克"))
    }

    @Test
    fun `removes branch qualifiers in brackets`() {
        assertEquals("肯德基", MerchantNormalizer.normalize("肯德基(国贸店)"))
        assertEquals("星巴克", MerchantNormalizer.normalize("星巴克【旗舰店】"))
    }

    @Test
    fun `resolves eleme`() {
        assertEquals("饿了么", MerchantNormalizer.normalize("上海拉扎斯信息科技有限公司"))
    }

    @Test
    fun `keeps an unknown merchant usable as a rule pattern`() {
        assertEquals("老王小卖部", MerchantNormalizer.normalize("老王小卖部"))
    }

    @Test
    fun `empty input is empty, not a crash`() {
        assertEquals("", MerchantNormalizer.normalize(null))
        assertEquals("", MerchantNormalizer.normalize("   "))
    }
}

class CategorizerTest {

    private val categorizer = Categorizer(PresetRules.seedRules())

    private fun classify(
        source: TxSource = TxSource.ALIPAY,
        direction: TxDirection = TxDirection.EXPENSE,
        rawType: String? = null,
        status: String? = null,
        counterparty: String? = null,
        product: String? = null,
    ) = categorizer.classify(
        ClassifyInput(
            source = source,
            direction = direction,
            rawType = rawType,
            status = status,
            counterpartyRaw = counterparty,
            productDesc = product,
        )
    )

    @Test
    fun `alipay category column is trusted directly`() {
        assertEquals(CategoryKeys.FOOD, classify(rawType = "餐饮美食").categoryKey)
        assertEquals(CategoryKeys.TRANSPORT, classify(rawType = "交通出行").categoryKey)
        assertEquals(CategoryKeys.HOUSING, classify(rawType = "住房物业").categoryKey)
    }

    @Test
    fun `transfers are excluded, never classified as spending`() {
        val result = classify(rawType = "转账", counterparty = "张三")
        assertEquals(ExcludeReason.TRANSFER, result.excludeReason)
        assertTrue(result.isExcluded)
    }

    @Test
    fun `investment moves and card repayments are excluded`() {
        assertEquals(ExcludeReason.INVEST, classify(rawType = "投资理财").excludeReason)
        assertEquals(ExcludeReason.INVEST, classify(rawType = "信用借还").excludeReason)
        assertEquals(
            ExcludeReason.INVEST,
            classify(source = TxSource.WECHAT, rawType = "信用卡还款").excludeReason,
        )
        assertEquals(
            ExcludeReason.INVEST,
            classify(source = TxSource.WECHAT, rawType = "零钱通转入", direction = TxDirection.NEUTRAL).excludeReason,
        )
    }

    @Test
    fun `a refund is a refund, not income`() {
        val result = classify(rawType = "退款", status = "退款成功", counterparty = "星巴克")
        assertEquals(ExcludeReason.REFUND, result.excludeReason)
        assertEquals(CategoryKeys.REFUND_POOL, result.categoryKey)
    }

    @Test
    fun `a closed payment is not spending`() {
        assertEquals(ExcludeReason.CLOSED, classify(status = "交易关闭").excludeReason)
        assertEquals(
            ExcludeReason.CLOSED,
            classify(source = TxSource.WECHAT, status = "已关闭").excludeReason,
        )
    }

    @Test
    fun `keyword rules classify by merchant`() {
        assertEquals(CategoryKeys.FOOD, classify(counterparty = "星巴克").categoryKey)
        assertEquals(CategoryKeys.FOOD, classify(counterparty = "肯德基(国贸店)").categoryKey)
        assertEquals(CategoryKeys.TRANSPORT, classify(counterparty = "滴滴出行").categoryKey)
        assertEquals(CategoryKeys.SHOPPING, classify(counterparty = "天猫超市").categoryKey)
        assertEquals(CategoryKeys.HEALTH, classify(counterparty = "老百姓大药房").categoryKey)
    }

    @Test
    fun `specific rules beat generic ones`() {
        // "美团单车" must not be swallowed by the catch-all "美团" -> 餐饮 rule.
        assertEquals(CategoryKeys.TRANSPORT, classify(counterparty = "美团单车", product = "骑行费用").categoryKey)
        assertEquals(CategoryKeys.FOOD, classify(counterparty = "美团外卖", product = "黄焖鸡米饭").categoryKey)
    }

    @Test
    fun `an unknown merchant is triaged, not filed under other`() {
        val result = classify(counterparty = "老王小卖部", product = "杂项")
        assertEquals(CategoryKeys.UNCLASSIFIED, result.categoryKey)
        assertTrue(result.needsTriage)
        assertFalse(result.isExcluded)
    }

    @Test
    fun `classification is explainable`() {
        val result = classify(counterparty = "星巴克")
        assertNotNull(result.matchedRuleLabel)
        assertTrue(result.confidence > 0f)
    }

    @Test
    fun `transaction type column beats a misleading payee`() {
        // WeChat marks card repayments in its type column; the payee is a bank.
        val result = classify(
            source = TxSource.WECHAT,
            rawType = "信用卡还款",
            counterparty = "招商银行",
        )
        assertEquals(ExcludeReason.INVEST, result.excludeReason)
    }

    @Test
    fun `outgoing red packet is social spending and incoming is a transfer`() {
        assertEquals(
            CategoryKeys.SOCIAL,
            classify(source = TxSource.WECHAT, rawType = "微信红包", direction = TxDirection.EXPENSE).categoryKey,
        )
        assertEquals(
            ExcludeReason.TRANSFER,
            classify(source = TxSource.WECHAT, rawType = "微信红包", direction = TxDirection.INCOME).excludeReason,
        )
    }
}

class RuleLearnerTest {

    @Test
    fun `a correction becomes a rule that outranks the preset library`() {
        val learned = RuleLearner.learnFrom("老王小卖部", CategoryKeys.SHOPPING)
        assertNotNull(learned)
        assertTrue(learned!!.priority < PresetRules.PRESET_PRIORITY_BASE)
    }

    @Test
    fun `generic payees are refused rather than poisoning the rule table`() {
        assertNull(RuleLearner.learnFrom("商户", CategoryKeys.FOOD))
        assertNull(RuleLearner.learnFrom("个人", CategoryKeys.FOOD))
        assertNull(RuleLearner.learnFrom("", CategoryKeys.FOOD))
        assertNull(RuleLearner.learnFrom(null, CategoryKeys.FOOD))
    }

    @Test
    fun `a learned rule actually changes the classification`() {
        val before = Categorizer(PresetRules.seedRules())
            .classify(ClassifyInput(TxSource.MANUAL, TxDirection.EXPENSE, counterpartyRaw = "老王小卖部"))
        assertEquals(CategoryKeys.UNCLASSIFIED, before.categoryKey)

        val learned = RuleLearner.learnFrom("老王小卖部", CategoryKeys.SHOPPING)!!
        val after = Categorizer(PresetRules.seedRules() + learned)
            .classify(ClassifyInput(TxSource.MANUAL, TxDirection.EXPENSE, counterpartyRaw = "老王小卖部"))
        assertEquals(CategoryKeys.SHOPPING, after.categoryKey)
    }
}
