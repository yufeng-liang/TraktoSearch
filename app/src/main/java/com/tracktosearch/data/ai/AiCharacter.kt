package com.tracktosearch.data.ai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.Locale

/** AI 精灵的静态配置；服务端返回的可用状态可以覆盖本地默认值。 */
@Serializable
data class AiCharacter(
    val id: String,
    val name: String,
    val activationWord: String,
    val aliases: List<String> = emptyList(),
    val isAvailable: Boolean = false,
    val personalityPrompt: String = "",
    val auditionText: String = ""
)

@Serializable
data class AiCharacterDto(
    val id: String,
    val name: String,
    val activationWord: String,
    val aliases: List<String> = emptyList(),
    @SerialName("available") val isAvailable: Boolean = false,
    val personalityPrompt: String = "",
    val auditionText: String = ""
)

@Serializable
data class AiCharactersDto(val characters: List<AiCharacterDto> = emptyList())

/** 本地静态角色目录，资源和音色未就绪时默认保持不可用。 */
object AiCharacterCatalog {
    val all: List<AiCharacter> = listOf(
        AiCharacter("chiikawa", "吉伊", "吉伊", listOf("鸡伊", "雞伊", "jiyi", "ji yi"), personalityPrompt = "胆小但认真，遇到困难会努力坚持。", auditionText = "今天也一起找一部好看的电影吧。"),
        AiCharacter("hachiware", "小八", "小八", listOf("小巴", "xiaoba", "xiao ba"), personalityPrompt = "温柔乐观，善于鼓励和解释。", auditionText = "我发现了一点有意思的片单线索哦。"),
        AiCharacter("usagi", "乌萨奇", "乌萨奇", listOf("呜萨奇", "乌萨齐", "烏薩奇", "wusaqi", "wu sa qi"), personalityPrompt = "行动派、节奏跳脱，用短促有力的语气表达。", auditionText = "呀哈！你的片单有点东西。"),
        // 与服务端字符 ID 保持一致（服务端为 flying-squirrel）；不一致会导致远程可用态合并不上，角色永久"准备中"
        AiCharacter("flying-squirrel", "飞鼠", "飞鼠", listOf("飛鼠", "fei shu", "feishu"), personalityPrompt = "可爱又爱撒娇，偶尔带一点小任性。", auditionText = "让我看看，今天有什么值得你发光的电影。"),
        AiCharacter("shisa", "狮萨", "狮萨", listOf("獅薩", "师萨", "shisa"), personalityPrompt = "认真勤奋，语气朴实而有生活感。", auditionText = "欢迎回来，我帮你把片单整理得更清楚。"),
        AiCharacter("kurimanju", "栗子馒头", "栗子馒头", listOf("栗子饅頭", "栗子馒头", "lizi mantou"), personalityPrompt = "成熟淡定，喜欢用简短的话给出可靠建议。", auditionText = "先坐下来，慢慢看看你的观看口味。"),
        AiCharacter("rakko", "獭师", "獭师", listOf("獺師", "塔师", "tashi", "lashi"), personalityPrompt = "沉着温和，像前辈一样观察并点拨。", auditionText = "准备好了吗？我们来认真拆一拆这份片单。")
    )
}

object AiActivationNormalizer {
    private val punctuation = Regex("[\\p{P}\\p{S}\\s]+")

    /** 清除 ASR 常见标点并统一少量繁体字，不改变用户原始文本。 */
    fun normalize(value: String): String = value
        .trim()
        .lowercase(Locale.ROOT)
        .replace(punctuation, "")
        .replace('烏', '乌')
        .replace('薩', '萨')
        .replace('獺', '獭')
        .replace('獅', '狮')
        .replace('雞', '鸡')
        .replace('饅', '馒')
        .replace('頭', '头')

    /** 将有限的授权角色名同音写法归到角色标准名，避免把普通文本泛化成同音词。 */
    fun canonicalName(value: String): String {
        return when (normalize(value)) {
            "wusaqi", "乌萨齐", "呜萨奇" -> "乌萨奇"
            "jiyi", "鸡伊" -> "吉伊"
            "xiaoba", "小巴" -> "小八"
            "feishu" -> "飞鼠"
            "shisa", "师萨" -> "狮萨"
            "lizimantou" -> "栗子馒头"
            "tashi", "lashi", "塔师" -> "獭师"
            else -> normalize(value)
        }
    }

    fun matches(character: AiCharacter, spoken: String): Boolean {
        // 别名已由 canonicalName 归一：spoken 归一后等于角色标准名即命中，
        // 无需再遍历别名（原来的 .any 循环恒等于该判断，是死代码）。
        return canonicalName(spoken) == canonicalName(character.activationWord)
    }
}

fun AiCharacterDto.toDomain(): AiCharacter = AiCharacter(
    id = id,
    name = name,
    activationWord = activationWord,
    aliases = aliases,
    isAvailable = isAvailable,
    personalityPrompt = personalityPrompt,
    auditionText = auditionText
)
