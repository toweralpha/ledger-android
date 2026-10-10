package com.xiaozhangben.ledger.data.local

import com.xiaozhangben.ledger.domain.categorize.PresetRules
import com.xiaozhangben.ledger.domain.categorize.RuleDef
import com.xiaozhangben.ledger.domain.model.CategoryKeys
import com.xiaozhangben.ledger.domain.model.CategoryKind
import com.xiaozhangben.ledger.domain.model.ExcludeReason
import com.xiaozhangben.ledger.domain.model.RuleMatch
import com.xiaozhangben.ledger.domain.model.RuleOrigin
import com.xiaozhangben.ledger.domain.model.RuleTarget

/**
 * First-run data: a starter category set plus the merchant keyword library.
 *
 * Seeding is idempotent (guarded by a row count) and is invoked by the container
 * rather than by a Room callback, so the order of database creation, seeding and
 * first UI emission is deterministic and easy to reason about.
 */
object Seed {

    data class CategorySeed(
        val key: String,
        val name: String,
        val iconKey: String,
        val colorHex: String,
        val kind: CategoryKind,
        val builtIn: Boolean = true,
    )

    val CATEGORIES: List<CategorySeed> = listOf(
        CategorySeed(CategoryKeys.FOOD, "餐饮", "restaurant", "#E8590C", CategoryKind.VARIABLE),
        CategorySeed(CategoryKeys.TRANSPORT, "交通", "directions_bus", "#1C7ED6", CategoryKind.VARIABLE),
        CategorySeed(CategoryKeys.SHOPPING, "购物", "shopping_bag", "#C2255C", CategoryKind.VARIABLE),
        CategorySeed(CategoryKeys.HOUSING, "居住", "home", "#5F3DC4", CategoryKind.FIXED),
        CategorySeed(CategoryKeys.TELECOM, "通讯", "smartphone", "#0B7285", CategoryKind.FIXED),
        CategorySeed(CategoryKeys.ENTERTAINMENT, "娱乐", "movie", "#D6336C", CategoryKind.VARIABLE),
        CategorySeed(CategoryKeys.HEALTH, "医疗健康", "medical_services", "#2B8A3E", CategoryKind.VARIABLE),
        CategorySeed(CategoryKeys.SOCIAL, "人情往来", "card_giftcard", "#E67700", CategoryKind.VARIABLE),
        CategorySeed(CategoryKeys.EDUCATION, "学习成长", "school", "#1971C2", CategoryKind.VARIABLE),
        CategorySeed(CategoryKeys.SERVICES, "生活服务", "build", "#495057", CategoryKind.VARIABLE),
        CategorySeed(CategoryKeys.TRAVEL, "旅行住宿", "flight", "#0CA678", CategoryKind.VARIABLE),
        CategorySeed(CategoryKeys.DIGITAL, "数码订阅", "subscriptions", "#7048E8", CategoryKind.VARIABLE),
        CategorySeed(CategoryKeys.OTHER, "其他", "more_horiz", "#868E96", CategoryKind.VARIABLE),
        // System buckets. Not user-editable, and deliberately not the same thing as
        // "其他": rows land here only because we could not explain them.
        CategorySeed(CategoryKeys.UNCLASSIFIED, "待归类", "help_outline", "#F08C00", CategoryKind.VARIABLE),
        CategorySeed(CategoryKeys.REFUND_POOL, "退款", "undo", "#37B24D", CategoryKind.VARIABLE),
    )

    fun categories(now: Long): List<CategoryEntity> = CATEGORIES.mapIndexed { index, seed ->
        CategoryEntity(
            key = seed.key,
            name = seed.name,
            iconKey = seed.iconKey,
            colorHex = seed.colorHex,
            kind = seed.kind.name,
            sortOrder = index,
            archived = false,
            builtIn = true,
            updatedAt = now,
        )
    }

    fun rules(now: Long): List<CategoryRuleEntity> = PresetRules.seedRules().map { it.toEntity(now) }

    /**
     * Default daily slots.
     *
     * Seeded once, on first run. All three bind 餐饮 and are separated by their time
     * window — which is the only signal that can tell 午饭 from 晚饭, since a payment
     * notification carries an amount and nothing else. See [DailySlotAssigner].
     *
     * Ids are fixed rather than random, and the insert ignores conflicts: `bootstrap()`
     * runs from the Application, the boot receiver and the periodic worker, so two
     * concurrent first-runs used to seed twice and the user saw two "早饭" cards.
     * Stable ids also keep the built-in slots identifiable across a backup/restore.
     *
     * The amounts are deliberately modest starting points: they are editable, and a
     * wrong default the user notices is better than an empty page they never configure.
     */
    fun pockets(now: Long): List<DailyPocketEntity> = listOf(
        SlotSeed("slot-breakfast", "早饭", 1_000L, 5 * 60, 10 * 60),
        SlotSeed("slot-lunch", "午饭", 2_000L, 10 * 60, 15 * 60),
        SlotSeed("slot-dinner", "晚饭", 2_500L, 15 * 60, 24 * 60),
    ).mapIndexed { index, seed ->
        DailyPocketEntity(
            id = seed.id,
            name = seed.name,
            dailyAmountCents = seed.amountCents,
            categoryKey = CategoryKeys.FOOD,
            weekdaysMask = 0b1111111,
            startMinute = seed.startMinute,
            endMinute = seed.endMinute,
            builtIn = true,
            sortOrder = index,
            updatedAt = now,
        )
    }

    private data class SlotSeed(
        val id: String,
        val name: String,
        val amountCents: Long,
        val startMinute: Int,
        val endMinute: Int,
    )
}

fun RuleDef.toEntity(now: Long): CategoryRuleEntity = CategoryRuleEntity(
    id = id,
    priority = priority,
    pattern = pattern,
    target = target.name,
    matchType = match.name,
    categoryKey = categoryKey,
    excludeReason = excludeReason?.name,
    kind = kind.name,
    origin = origin.name,
    label = label,
    hitCount = 0,
    enabled = enabled,
    updatedAt = now,
)

fun CategoryRuleEntity.toDomain(): RuleDef = RuleDef(
    id = id,
    priority = priority,
    pattern = pattern,
    target = runCatching { RuleTarget.valueOf(target) }.getOrDefault(RuleTarget.ANY),
    match = runCatching { RuleMatch.valueOf(matchType) }.getOrDefault(RuleMatch.CONTAINS),
    categoryKey = categoryKey,
    excludeReason = excludeReason?.let { runCatching { ExcludeReason.valueOf(it) }.getOrNull() },
    kind = runCatching { CategoryKind.valueOf(kind) }.getOrDefault(CategoryKind.VARIABLE),
    origin = runCatching { RuleOrigin.valueOf(origin) }.getOrDefault(RuleOrigin.PRESET),
    label = label,
    enabled = enabled,
)
