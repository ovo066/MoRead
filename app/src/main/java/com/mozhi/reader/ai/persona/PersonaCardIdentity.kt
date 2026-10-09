package com.mozhi.reader.ai.persona

/** 角色卡判重：名字、人设与开场白规范化后都相同才算同一张卡，改过一个字就当作新角色。 */
object PersonaCardIdentity {
    private val whitespace = Regex("""\s+""")

    fun same(name: String, personality: String, greeting: String, otherName: String, otherPersonality: String, otherGreeting: String): Boolean =
        normalize(name) == normalize(otherName) && normalize(personality) == normalize(otherPersonality) &&
            normalize(greeting) == normalize(otherGreeting) && normalize(name).isNotEmpty()

    private fun normalize(text: String): String = text.replace(whitespace, "").lowercase()
}
