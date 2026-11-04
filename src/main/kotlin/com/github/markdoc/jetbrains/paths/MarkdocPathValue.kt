package com.github.markdoc.jetbrains.paths

import com.github.markdoc.jetbrains.MarkdocTagScanner
import com.github.markdoc.jetbrains.MarkdocTagScanner.Kind

/**
 * タグの属性値に書かれたファイルパス（`{% partial file="partials/footer.mdoc" /%}` の `partials/footer.mdoc`）。
 *
 * @property tagName 属性を持つタグの名前。
 * @property attributeName 属性名。
 * @property start 値の先頭（開き引用符の直後）のオフセット。
 * @property end 値の末尾のオフセット（閉じ引用符の位置。閉じていなければ最初の空白か `%` の位置）。
 * @property text 値の文字列。
 */
data class MarkdocPathValue(
    val tagName: String,
    val attributeName: String,
    val start: Int,
    val end: Int,
    val text: String,
) {
    /** `partial` の `file` かどうか。パスの基準のディレクトリと、補完に出すファイルの種類が変わる。 */
    val isPartial: Boolean get() = tagName == "partial" && attributeName == "file"

    companion object {
        /** 値をファイルパスとして扱う属性名。タグの種類は問わない。 */
        private val PATH_ATTRIBUTES = setOf("file", "src", "href", "path")

        /**
         * [text] の [offset] がファイルパスの属性値の中（両端を含む）にあれば、その値を返す。
         *
         * 書きかけで閉じ引用符がない値も対象にする。URL（`https://…`）、アンカー（`#…`）、`mailto:` は除く。
         */
        fun at(text: CharSequence, offset: Int): MarkdocPathValue? {
            val tokens = MarkdocTagScanner.scan(text)
            val index = tokens.indexOfFirst { it.kind == Kind.STRING && it.start < offset && offset <= it.end }
            if (index < 1) return null
            val string = tokens[index]
            val attribute = tokens[index - 1].takeIf { it.kind == Kind.ATTRIBUTE } ?: return null
            // 属性名と値の間は `=`（前後の空白を含む）だけでなければならない
            if (text.substring(attribute.end, string.start).trim() != "=") return null
            val attributeName = text.substring(attribute.start, attribute.end)
            if (attributeName !in PATH_ATTRIBUTES) return null
            val tagName = tagNameBefore(tokens, index - 1, text) ?: return null

            val start = string.start + 1
            val closed = string.end - string.start >= 2 && text[string.end - 1] == '"'
            // 閉じていない文字列は行末まで続くため、そのままでは後ろの `/%}` まで値に含んでしまう
            val end = if (closed) {
                string.end - 1
            } else {
                (start until string.end).firstOrNull { text[it].isWhitespace() || text[it] == '%' } ?: string.end
            }
            // 閉じ引用符より後ろは値の外
            if (offset > end) return null

            val value = text.substring(start, end)
            if (value.contains("://") || value.startsWith("#") || value.startsWith("mailto:")) return null
            return MarkdocPathValue(tagName, attributeName, start, end, value)
        }

        /** [index] のトークンが属するタグの名前。`{%` の直後のトークンがタグ名でなければ `null`。 */
        private fun tagNameBefore(tokens: List<MarkdocTagScanner.Token>, index: Int, text: CharSequence): String? {
            for (i in index downTo 0) {
                val token = tokens[i]
                if (token.kind == Kind.DELIMITER && text.substring(token.start, token.end) == "{%") {
                    val name = tokens.getOrNull(i + 1)?.takeIf { it.kind == Kind.TAG_NAME } ?: return null
                    return text.substring(name.start, name.end)
                }
            }
            return null
        }
    }
}
