package com.xiaozhangben.ledger.domain.categorize

import com.xiaozhangben.ledger.domain.model.CategoryKeys
import com.xiaozhangben.ledger.domain.model.CategoryKind
import com.xiaozhangben.ledger.domain.model.ExcludeReason
import com.xiaozhangben.ledger.domain.model.RuleMatch
import com.xiaozhangben.ledger.domain.model.RuleOrigin
import com.xiaozhangben.ledger.domain.model.RuleTarget

/**
 * One classification rule. Domain-level so the engine stays unit-testable without
 * a database.
 *
 * `categoryKey` is nullable because a rule may exist purely to exclude rows
 * (transfers, top-ups) rather than to classify them.
 */
data class RuleDef(
    val id: Long,
    val priority: Int,
    val pattern: String,
    val target: RuleTarget = RuleTarget.ANY,
    val match: RuleMatch = RuleMatch.CONTAINS,
    val categoryKey: String? = null,
    val excludeReason: ExcludeReason? = null,
    val kind: CategoryKind = CategoryKind.VARIABLE,
    val origin: RuleOrigin = RuleOrigin.PRESET,
    val label: String = pattern,
    val enabled: Boolean = true,
)

/**
 * Built-in merchant keyword library.
 *
 * Ordering is the priority: the table is emitted in list order and the engine takes
 * the first match, so **specific patterns must precede generic ones**.
 * That is why "美团单车"/"美团买菜"/"美团酒店" sit in their own blocks ahead of the
 * catch-all "美团" → 餐饮.
 */
object PresetRules {

    /**
     * Preset rules occupy the 1000+ priority band. Learned rules use the reserved
     * 500 band (see [RuleLearner]) so a correction always outranks the shipped
     * library without the user reordering hundreds of rows by hand.
     */
    const val PRESET_PRIORITY_BASE = 1000

    private data class Block(
        val categoryKey: String,
        val kind: CategoryKind,
        val keywords: List<String>,
    )

    private val BLOCKS: List<Block> = listOf(
        // ---- Disambiguating exceptions first ------------------------------------
        Block(CategoryKeys.TRANSPORT, CategoryKind.VARIABLE, listOf(
            "美团单车", "青桔", "哈啰", "共享单车", "共享电单车", "松果出行",
            "美团打车", "高德打车", "花小猪", "曹操出行", "t3出行", "首汽约车", "享道出行",
            "滴滴出行", "滴滴", "地铁", "轨道交通", "公交", "公共自行车", "轮渡",
            "12306", "铁路", "火车票", "高铁", "航空", "机票", "航班", "机场",
            "加油", "中石化", "中石油", "壳牌", "bp", "充电桩", "特来电", "星星充电",
            "停车", "etc通行", "高速", "过路费", "月卡停车",
        )),

        // ---- 餐饮 ----------------------------------------------------------------
        Block(CategoryKeys.FOOD, CategoryKind.VARIABLE, listOf(
            "美团外卖", "饿了么", "外卖", "美团跑腿",
            "肯德基", "kfc", "麦当劳", "金拱门", "汉堡王", "德克士", "华莱士", "塔斯汀",
            "星巴克", "瑞幸", "库迪", "cotti", "manner", "tims", "太平洋咖啡", "costa",
            "蜜雪冰城", "喜茶", "奈雪", "霸王茶姬", "古茗", "沪上阿姨", "茶百道", "书亦",
            "益禾堂", "coco", "一点点", "茶颜悦色", "冰雪时光",
            "海底捞", "西贝", "呷哺", "九毛九", "外婆家", "绿茶餐厅", "太二", "和府捞面",
            "老娘舅", "老乡鸡", "真功夫", "乡村基", "南城香", "吉野家", "食其家",
            "沙县", "兰州拉面", "拉面", "米线", "米粉", "麻辣烫", "冒菜", "螺蛳粉",
            "烧烤", "火锅", "串串", "烤肉", "小龙虾", "快餐", "食堂", "小吃", "早餐",
            "烘焙", "面包", "蛋糕", "甜品", "冰淇淋", "奶茶", "咖啡", "餐饮", "饭店",
            "餐厅", "酒楼", "食府", "食品", "美团",
        )),

        // ---- 出行/交通补贴到卡 -------------------------------------------------
        Block(CategoryKeys.TRANSPORT, CategoryKind.VARIABLE, listOf(
            "滴滴快车", "滴滴专车", "代驾", "顺风车", "出租车", "打车",
        )),

        // ---- 购物 ----------------------------------------------------------------
        Block(CategoryKeys.SHOPPING, CategoryKind.VARIABLE, listOf(
            "淘宝", "天猫", "聚划算", "京东", "京东商城", "拼多多", "唯品会", "得物",
            "闲鱼", "苏宁", "国美", "抖音商城", "快手小店", "小红书", "网易严选",
            "美团买菜", "叮咚买菜", "朴朴", "盒马", "山姆", "沃尔玛", "大润发", "永辉",
            "华润万家", "物美", "家乐福", "罗森", "全家", "便利蜂", "711", "7-11",
            "美宜佳", "天福", "十足", "超市", "便利店", "生鲜", "菜市场", "水果",
            "优衣库", "无印良品", "名创优品", "屈臣氏", "丝芙兰", "波司登", "安踏",
            "李宁", "耐克", "nike", "阿迪达斯", "adidas", "lululemon",
            "小米", "华为商城", "apple", "苹果", "vivo", "oppo", "荣耀", "三星",
        )),

        // ---- 居住 ----------------------------------------------------------------
        Block(CategoryKeys.HOUSING, CategoryKind.FIXED, listOf(
            "房租", "租金", "房贷", "物业", "物业管理", "水费", "电费", "燃气", "天然气",
            "暖气", "供暖", "链家", "贝壳", "自如", "蛋壳", "我爱我家", "中介费", "家政",
            "保洁", "维修费", "宽带", "有线电视",
        )),

        // ---- 通讯（固定支出） ---------------------------------------------------
        Block(CategoryKeys.TELECOM, CategoryKind.FIXED, listOf(
            "中国移动", "中国联通", "中国电信", "话费", "手机充值", "流量包", "流量充值",
            "宽带费", "移动通信", "联通", "电信",
        )),

        // ---- 娱乐 ----------------------------------------------------------------
        Block(CategoryKeys.ENTERTAINMENT, CategoryKind.VARIABLE, listOf(
            "腾讯视频", "爱奇艺", "优酷", "芒果tv", "哔哩哔哩", "bilibili", "b站",
            "网易云音乐", "qq音乐", "酷狗", "spotify", "喜马拉雅",
            "steam", "taptap", "epic", "playstation", "xbox", "任天堂", "nintendo",
            "王者荣耀", "和平精英", "原神", "崩坏", "英雄联盟", "游戏", "点卡",
            "电影", "影院", "cgv", "万达影城", "猫眼", "淘票票", "剧本杀", "密室",
            "ktv", "酒吧", "livehouse", "演唱会", "网吧", "台球", "桌游", "棋牌",
            "乐园", "游乐场", "动物园", "水族馆", "景区", "门票",
        )),

        // ---- 医疗健康 ------------------------------------------------------------
        Block(CategoryKeys.HEALTH, CategoryKind.VARIABLE, listOf(
            "医院", "药店", "大药房", "药房", "医药", "诊所", "门诊", "挂号", "急诊",
            "体检", "口腔", "牙科", "眼科", "中医", "理疗", "康复", "疫苗", "血站",
            "健身", "健身房", "游泳", "球馆", "羽毛球", "篮球馆", "keep", "乐刻",
            "超级猩猩", "瑜伽", "普拉提", "按摩", "spa", "足疗",
        )),

        // ---- 人情往来 ------------------------------------------------------------
        Block(CategoryKeys.SOCIAL, CategoryKind.VARIABLE, listOf(
            "红包", "礼金", "随礼", "份子钱", "花店", "鲜花", "婚庆", "礼品", "礼物",
            "蛋糕店", "祝福",
        )),

        // ---- 学习成长 ------------------------------------------------------------
        Block(CategoryKeys.EDUCATION, CategoryKind.VARIABLE, listOf(
            "书店", "当当", "图书", "文具", "晨光", "课程", "培训", "教育", "学费",
            "考试", "报名费", "知网", "得到", "极客时间", "慕课", "网课", "驾校",
            "钢琴", "乐器", "美术", "书法", "自习室",
        )),

        // ---- 生活服务 ------------------------------------------------------------
        Block(CategoryKeys.SERVICES, CategoryKind.VARIABLE, listOf(
            "理发", "美发", "美容", "美甲", "美睫", "洗衣", "干洗", "洗车", "快递",
            "顺丰", "菜鸟", "驿站", "中通", "圆通", "申通", "韵达", "极兔", "邮政",
            "搬家", "开锁", "疏通", "家电维修", "宠物", "猫粮", "狗粮", "宠物医院",
            "照相", "打印", "复印", "公证", "证件照",
        )),

        // ---- 旅行住宿 ------------------------------------------------------------
        Block(CategoryKeys.TRAVEL, CategoryKind.VARIABLE, listOf(
            "携程", "去哪儿", "飞猪", "同程", "艺龙", "马蜂窝", "booking", "agoda",
            "airbnb", "爱彼迎", "酒店", "民宿", "青旅", "客栈", "度假", "旅行社",
            "导游", "签证", "出境", "免税店",
        )),

        // ---- 数码订阅 ------------------------------------------------------------
        Block(CategoryKeys.DIGITAL, CategoryKind.VARIABLE, listOf(
            "icloud", "云服务", "阿里云", "腾讯云", "华为云", "会员", "订阅",
            "microsoft", "office365", "adobe", "域名", "服务器", "vps", "主机",
            "百度网盘", "夸克", "坚果云", "openai", "chatgpt", "github",
        )),
    )

    /**
     * Structural exclusions are not keyword rules: they key off the bill's own
     * `交易类型` / `当前状态` columns, where the semantics are unambiguous.
     * Kept here so seeding writes them into the same editable rule table.
     */
    private val EXCLUDE_RULES: List<Pair<String, ExcludeReason>> = listOf(
        "转账" to ExcludeReason.TRANSFER,
        "群收款" to ExcludeReason.TRANSFER,
        "亲属卡" to ExcludeReason.TRANSFER,
        "aa收款" to ExcludeReason.TRANSFER,
        "资金归集" to ExcludeReason.TRANSFER,
        "零钱提现" to ExcludeReason.INVEST,
        "提现" to ExcludeReason.INVEST,
        "信用卡还款" to ExcludeReason.INVEST,
        "还款" to ExcludeReason.INVEST,
        "零钱通" to ExcludeReason.INVEST,
        "理财通" to ExcludeReason.INVEST,
        "余额宝" to ExcludeReason.INVEST,
        "基金" to ExcludeReason.INVEST,
        "黄金" to ExcludeReason.INVEST,
        "证券" to ExcludeReason.INVEST,
        "股票" to ExcludeReason.INVEST,
        "保险" to ExcludeReason.INVEST,
        "定期存款" to ExcludeReason.INVEST,
    )

    /** Seeds the database once, on first launch. */
    fun seedRules(): List<RuleDef> {
        val result = ArrayList<RuleDef>(512)
        var id = 1L
        // Preset rules occupy the 1000+ band. Rules learned from user corrections use
        // the reserved 500 band (see RuleLearner) so they always outrank the library
        // without the user having to reorder 400 rows by hand.
        var priority = PRESET_PRIORITY_BASE

        // Structural exclusions run first: a "转账" row must never reach a food rule.
        for ((pattern, reason) in EXCLUDE_RULES) {
            result += RuleDef(
                id = id++,
                priority = priority++,
                pattern = pattern,
                target = RuleTarget.ANY,
                match = RuleMatch.CONTAINS,
                categoryKey = null,
                excludeReason = reason,
                origin = RuleOrigin.PRESET,
                label = "排除：$pattern",
            )
        }

        for (block in BLOCKS) {
            for (keyword in block.keywords) {
                result += RuleDef(
                    id = id++,
                    priority = priority++,
                    pattern = keyword,
                    target = RuleTarget.ANY,
                    match = RuleMatch.CONTAINS,
                    categoryKey = block.categoryKey,
                    kind = block.kind,
                    origin = RuleOrigin.PRESET,
                    label = "$keyword → ${categoryLabel(block.categoryKey)}",
                )
            }
        }
        return result
    }

    private fun categoryLabel(key: String): String = when (key) {
        CategoryKeys.FOOD -> "餐饮"
        CategoryKeys.TRANSPORT -> "交通"
        CategoryKeys.SHOPPING -> "购物"
        CategoryKeys.HOUSING -> "居住"
        CategoryKeys.TELECOM -> "通讯"
        CategoryKeys.ENTERTAINMENT -> "娱乐"
        CategoryKeys.HEALTH -> "医疗健康"
        CategoryKeys.SOCIAL -> "人情往来"
        CategoryKeys.EDUCATION -> "学习成长"
        CategoryKeys.SERVICES -> "生活服务"
        CategoryKeys.TRAVEL -> "旅行住宿"
        CategoryKeys.DIGITAL -> "数码订阅"
        else -> key
    }
}
