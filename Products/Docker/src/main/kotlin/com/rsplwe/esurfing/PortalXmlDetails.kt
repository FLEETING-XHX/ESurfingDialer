package com.rsplwe.esurfing

import java.util.Locale

/** White-listed classifications only. Parser messages are inspected in memory and never retained. */
enum class PortalXmlIssue {
    NONE, ENTITY_REFERENCE, UNDECLARED_ENTITY, DUPLICATE_ATTRIBUTE, ATTRIBUTE_LT,
    ATTRIBUTE_QUOTE, ATTRIBUTE_SYNTAX, TAG_MISMATCH, INVALID_CHARACTER, COMMENT_SYNTAX,
    DOCTYPE_REJECTED, DEPTH_LIMIT, OTHER_XML_SYNTAX;

    companion object {
        fun fromMessage(message: String?): PortalXmlIssue {
            val text = message.orEmpty().lowercase(Locale.ROOT)
            return when {
                text.startsWith("the reference to entity ") || text.startsWith("the entity name must immediately follow ") ||
                    text.startsWith("对实体 \"") && text.endsWith("的引用必须以 ';' 分隔符结尾。") -> ENTITY_REFERENCE
                text.startsWith("the entity ") && text.endsWith("was referenced, but not declared.") ||
                    text.startsWith("引用了实体 \"") && text.endsWith(", 但未声明它。") -> UNDECLARED_ENTITY
                text.startsWith("attribute ") && text.contains(" was already specified for element ") ||
                    text.startsWith("已经为元素 \"") && text.contains(" 指定属性 \"") -> DUPLICATE_ATTRIBUTE
                text.startsWith("the value of attribute ") && text.endsWith("must not contain the '<' character.") ||
                    text.startsWith("与元素类型 \"") && text.endsWith("属性值不能包含 '<' 字符。") -> ATTRIBUTE_LT
                text.startsWith("open quote is expected for attribute ") || text.startsWith("the value of attribute ") && text.contains(" must not contain ") ||
                    text.startsWith("与元素类型 \"") && text.endsWith("应有左引号。") -> ATTRIBUTE_QUOTE
                text.startsWith("attribute name ") || text.startsWith("element type ") && text.endsWith("must be followed by either attribute specifications, \">\" or \"/>\".") -> ATTRIBUTE_SYNTAX
                text.startsWith("the element type ") && text.contains(" must be terminated by the matching end-tag ") ||
                    text.startsWith("元素类型 \"") && text.contains(" 必须由匹配的结束标记 \"") -> TAG_MISMATCH
                text.startsWith("an invalid xml character ") || text.startsWith("character reference ") && text.contains("invalid xml character") -> INVALID_CHARACTER
                text.startsWith("the string \"--\" is not permitted within comments.") -> COMMENT_SYNTAX
                text.startsWith("doctype is disallowed ") -> DOCTYPE_REJECTED
                text.startsWith("jaxp") && (text.contains("depth") && text.contains("limit") || text.contains("深度") && text.contains("限制")) -> DEPTH_LIMIT
                else -> OTHER_XML_SYNTAX
            }
        }
    }
}

/** The context at the parser's reporting position, not a source excerpt or a claimed root cause. */
enum class PortalXmlContext { UNKNOWN, TEXT, START_TAG, ATTRIBUTE, END_TAG, CDATA, COMMENT, PROCESSING_INSTRUCTION, DECLARATION }

internal object PortalXmlDetails {
    fun context(xml: String, line: Int, column: Int): PortalXmlContext {
        if (line < 1 || column < 1) return PortalXmlContext.UNKNOWN
        var row = 1
        var col = 1
        var offset = 0
        while (offset < xml.length && (row < line || col < column)) {
            when (xml[offset++]) {
                '\r' -> { if (xml.getOrNull(offset) == '\n') offset++; row++; col = 1 }
                '\n' -> { row++; col = 1 }
                else -> col++
            }
            if (row > line) return PortalXmlContext.UNKNOWN
        }
        if (row != line || col != column) return PortalXmlContext.UNKNOWN
        // SAX positions often point immediately after the offending character.
        val limit = (offset - 1).coerceAtLeast(0)
        var at = 0
        var state = PortalXmlContext.TEXT
        var quote: Char? = null
        while (at < limit) {
            when (state) {
                PortalXmlContext.TEXT -> when {
                    xml.startsWith("<![CDATA[", at) -> { state = PortalXmlContext.CDATA; at += 9 }
                    xml.startsWith("<!--", at) -> { state = PortalXmlContext.COMMENT; at += 4 }
                    xml.startsWith("<?", at) -> { state = PortalXmlContext.PROCESSING_INSTRUCTION; at += 2 }
                    xml.startsWith("<!", at) -> { state = PortalXmlContext.DECLARATION; at += 2 }
                    xml.startsWith("</", at) -> { state = PortalXmlContext.END_TAG; at += 2 }
                    xml[at] == '<' -> { state = PortalXmlContext.START_TAG; at++ }
                    else -> at++
                }
                PortalXmlContext.START_TAG -> when (xml[at++]) {
                    '\'', '"' -> { quote = xml[at - 1]; state = PortalXmlContext.ATTRIBUTE }
                    '>' -> state = PortalXmlContext.TEXT
                }
                PortalXmlContext.ATTRIBUTE -> { if (xml[at++] == quote) { quote = null; state = PortalXmlContext.START_TAG } }
                PortalXmlContext.END_TAG, PortalXmlContext.DECLARATION -> { if (xml[at++] == '>') state = PortalXmlContext.TEXT }
                PortalXmlContext.CDATA -> { if (xml.startsWith("]]>", at)) { at += 3; state = PortalXmlContext.TEXT } else at++ }
                PortalXmlContext.COMMENT -> { if (xml.startsWith("-->", at)) { at += 3; state = PortalXmlContext.TEXT } else at++ }
                PortalXmlContext.PROCESSING_INSTRUCTION -> { if (xml.startsWith("?>", at)) { at += 2; state = PortalXmlContext.TEXT } else at++ }
                PortalXmlContext.UNKNOWN -> return state
            }
        }
        return state
    }
}
