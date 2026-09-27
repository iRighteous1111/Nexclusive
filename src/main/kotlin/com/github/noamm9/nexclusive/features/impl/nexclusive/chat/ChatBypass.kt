package com.github.noamm9.nexclusive.features.impl.nexclusive.chat

import com.github.noamm9.utils.location.LocationUtils

object ChatBypass {
    private val socialCommands = setOf("pc", "ac", "gc", "cc", "r", "msg", "w", "m", "message", "whisper", "tell", "pm")

    private val cyrillicMap = mapOf(
        'a' to 'а', 'A' to 'А', 'e' to 'е', 'E' to 'Е',
        'o' to 'о', 'O' to 'О', 'c' to 'с', 'C' to 'С',
        'p' to 'р', 'P' to 'Р', 'x' to 'х', 'X' to 'Х',
        'y' to 'у', 'Y' to 'У'
    )

    private val wideMap by lazy {
        val normal = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        val wide = "ａｂｃｄｅｆｇｈｉｊｋｌｍｎｏｐｑｒｓｔｕｖｗｘｙｚＡＢＣＤＥＦＧＨＩＪＫＬＭＮＯＰＱＲＳＴＵＶＷＸＹＺ０１２３４５６７８９"
        normal.zip(wide).toMap()
    }

    private val smallCapsHMap = mapOf('b' to 'ʙ', 'g' to 'ɢ', 'h' to 'ʜ', 'j' to 'ᴊ', 'q' to 'ǫ', 'z' to 'ᴢ', 'x' to 'x')
    private val smallCapsMap by lazy { "abcdefghijklmnopqrstuvwxyz".zip("ᴀʙᴄᴅᴇꜰɢʜɪᴊᴋʟᴍɴᴏᴘǫʀꜱᴛᴜᴠᴡxʏᴢ").toMap() }

    fun bypass(message: String, mode: Int): String {
        val isCmd = message.startsWith("/")
        val clean = if (isCmd) message.substring(1) else message
        val prefix = if (isCmd) "/" else ""

        val matchedSocial = socialCommands.firstOrNull {
            clean.equals(it, ignoreCase = true) || clean.startsWith("$it ", ignoreCase = true)
        }

        if (matchedSocial != null) {
            val isPm = socialCommands.drop(4).any { clean.startsWith(it, ignoreCase = true) } && ! clean.startsWith("r ", ignoreCase = true)
            val text = clean.substring(matchedSocial.length).trimStart()
            val target = if (isPm) text.split(" ").firstOrNull().orEmpty() else ""
            val content = if (isPm) text.removePrefix(target).trimStart() else text

            return buildString {
                append(prefix).append(matchedSocial)
                if (target.isNotEmpty()) append(" $target")
                if (content.isNotEmpty()) append(" ${transform(content, mode)}")
            }
        }

        if (isCmd) return message
        return transform(message, mode)
    }

    private fun transform(str: String, mode: Int): String = when (mode) {
        0 -> str.map { cyrillicMap[it] ?: it }.joinToString("")
        1 -> {
            val s = if (LocationUtils.onHypixel) str.lowercase() else str
            s.map { wideMap[it] ?: it }.joinToString("")
        }
        2 -> buildString {
            for (i in str.indices) {
                append(str[i])
                if (i < str.length - 1 && str[i] != ' ' && str[i + 1] != ' ') append('.')
            }
        }
        3 -> {
            val map = if (LocationUtils.onHypixel) smallCapsHMap else smallCapsMap
            str.lowercase().map { map[it] ?: it }.joinToString("")
        }
        else -> str
    }
}
