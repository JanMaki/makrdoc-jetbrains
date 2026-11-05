package com.github.markdoc.jetbrains.highlighting

import com.github.markdoc.jetbrains.MarkdocTagScanner.Kind
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors as Default

/**
 * Markdoc のタグの色。Settings | Editor | Color Scheme | Markdoc で変更できる。
 *
 * 既定の色はテーマの標準の色から引き継ぐ。`MARKUP_TAG`、`MARKUP_ATTRIBUTE`、`FUNCTION_CALL` は多くのテーマで
 * 地の文とほぼ同じ色になり区別がつかないため、使わない。`GLOBAL_VARIABLE` も Islands Dark や IntelliJ Light では
 * 定義がなく地の文と同じ色になるため、変数にはグローバル変数によく使われる見た目（紫の斜体）の `STATIC_FIELD` を使う。
 */
object MarkdocHighlighterColors {
    val DELIMITER = key("MARKDOC_DELIMITER", Default.METADATA)
    val TAG_NAME = key("MARKDOC_TAG_NAME", Default.KEYWORD)
    val ATTRIBUTE = key("MARKDOC_ATTRIBUTE", Default.INSTANCE_FIELD)
    val STRING = key("MARKDOC_STRING", Default.STRING)
    val NUMBER = key("MARKDOC_NUMBER", Default.NUMBER)
    val KEYWORD = key("MARKDOC_KEYWORD", Default.KEYWORD)
    val VARIABLE = key("MARKDOC_VARIABLE", Default.STATIC_FIELD)
    val FUNCTION = key("MARKDOC_FUNCTION", Default.FUNCTION_DECLARATION)

    /** [kind] のトークンに付ける色。 */
    fun of(kind: Kind): TextAttributesKey = when (kind) {
        Kind.DELIMITER -> DELIMITER
        Kind.TAG_NAME -> TAG_NAME
        Kind.ATTRIBUTE -> ATTRIBUTE
        Kind.STRING -> STRING
        Kind.NUMBER -> NUMBER
        Kind.KEYWORD -> KEYWORD
        Kind.VARIABLE -> VARIABLE
        Kind.FUNCTION -> FUNCTION
    }

    /** [name] の色キーを、既定の色を [fallback] から継承して作る。 */
    private fun key(name: String, fallback: TextAttributesKey) =
        TextAttributesKey.createTextAttributesKey(name, fallback)
}
