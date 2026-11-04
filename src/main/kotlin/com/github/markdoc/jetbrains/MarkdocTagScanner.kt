package com.github.markdoc.jetbrains

/**
 * テキストから Markdoc のタグ（`{% … %}`）を探し、タグの中を種類ごとのトークンに切り分ける。
 *
 * タグの色分け（[com.github.markdoc.jetbrains.highlighting.MarkdocTagAnnotator]）と、属性値のファイルパスの
 * 特定（[com.github.markdoc.jetbrains.paths.MarkdocPathValue]）で使う。
 *
 * 構文の検証はしない（言語サーバーが行う）。書きかけのタグでも、それらしい部分を切り出せればよい。
 * 色を付けない部分（`=`、括弧、区切りの `,` など）はトークンにしない。
 */
object MarkdocTagScanner {
    /** トークンの種類。 */
    enum class Kind {
        /** `{%`、`%}`、`/%}`。 */
        DELIMITER,

        /** タグの最初の語。閉じタグの `/` を含む（`/callout`）。 */
        TAG_NAME,

        /** `=` が続く語と、短縮記法の `.class`・`#id`。 */
        ATTRIBUTE,

        /** 引用符を含む文字列。閉じていなければ行末まで。 */
        STRING,
        NUMBER,

        /** `true`、`false`、`null`。 */
        KEYWORD,

        /** `$` から始まる変数参照（`$page.title`）。 */
        VARIABLE,

        /** `(` が続く語。 */
        FUNCTION,
    }

    /** [start] から [end] の手前までが [kind] の部分。 */
    data class Token(val start: Int, val end: Int, val kind: Kind)

    private val KEYWORDS = setOf("true", "false", "null")

    /** [text] 内のタグを先頭から走査し、トークンを返す。トークンは重ならず、出現順に並ぶ。 */
    fun scan(text: CharSequence): List<Token> {
        val tokens = ArrayList<Token>()
        var i = 0
        while (true) {
            val open = text.indexOf("{%", i)
            if (open < 0) break
            i = scanTag(text, open, tokens)
        }
        return tokens
    }

    /** [open] の `{%` から始まるタグを走査し、タグの直後の位置を返す。`%}` がなければテキストの末尾まで読む。 */
    private fun scanTag(text: CharSequence, open: Int, tokens: MutableList<Token>): Int {
        tokens += Token(open, open + 2, Kind.DELIMITER)
        var i = open + 2
        // タグ名（`{% /callout` の `/` を含む）は最初の語だけ
        var first = true
        while (i < text.length) {
            val c = text[i]
            val start = i
            when {
                c.isWhitespace() -> {
                    i++
                    continue
                }
                c == '%' && text.at(i + 1) == '}' -> {
                    tokens += Token(i, i + 2, Kind.DELIMITER)
                    return i + 2
                }
                c == '/' && text.at(i + 1) == '%' && text.at(i + 2) == '}' -> {
                    tokens += Token(i, i + 3, Kind.DELIMITER)
                    return i + 3
                }
                // 次のタグが始まった（このタグは閉じていない）
                c == '{' && text.at(i + 1) == '%' -> return i
                c == '/' && first && isIdentStart(text.at(i + 1)) -> {
                    i = skipIdent(text, i + 1)
                    tokens += Token(start, i, Kind.TAG_NAME)
                }
                c == '"' -> {
                    i = skipString(text, i)
                    tokens += Token(start, i, Kind.STRING)
                }
                c == '$' && isIdentStart(text.at(i + 1)) -> {
                    i = skipVariable(text, i + 1)
                    tokens += Token(start, i, Kind.VARIABLE)
                }
                c.isDigit() || (c == '-' && text.at(i + 1).isDigit()) -> {
                    i++
                    while (i < text.length && (text[i].isDigit() || text[i] == '.')) i++
                    tokens += Token(start, i, Kind.NUMBER)
                }
                // 属性の短縮記法（`.class`、`#id`）
                (c == '.' || c == '#') && isIdentStart(text.at(i + 1)) -> {
                    i = skipIdent(text, i + 1)
                    tokens += Token(start, i, Kind.ATTRIBUTE)
                }
                isIdentStart(c) -> {
                    i = skipIdent(text, i)
                    val word = text.subSequence(start, i).toString()
                    val kind = when (text.at(skipSpaces(text, i))) {
                        '=' -> Kind.ATTRIBUTE
                        '(' -> Kind.FUNCTION
                        else -> when {
                            word in KEYWORDS -> Kind.KEYWORD
                            first -> Kind.TAG_NAME
                            else -> null
                        }
                    }
                    if (kind != null) tokens += Token(start, i, kind)
                }
                else -> i++
            }
            first = false
        }
        return i
    }

    private fun CharSequence.at(index: Int): Char = if (index < length) this[index] else '\u0000'

    private fun isIdentStart(c: Char) = c.isLetter() || c == '_'

    private fun isIdentPart(c: Char) = c.isLetterOrDigit() || c == '_' || c == '-'

    private fun skipIdent(text: CharSequence, from: Int): Int {
        var i = from
        while (i < text.length && isIdentPart(text[i])) i++
        return i
    }

    /** `$page.title` や `$items[0]` のような変数参照を読み飛ばす。 */
    private fun skipVariable(text: CharSequence, from: Int): Int {
        var i = from
        while (i < text.length && (isIdentPart(text[i]) || text[i] == '.')) i++
        return i
    }

    /** 閉じ引用符まで読み飛ばす。バックスラッシュによるエスケープを考慮する。 */
    private fun skipString(text: CharSequence, from: Int): Int {
        var i = from + 1
        while (i < text.length) {
            when (text[i]) {
                '\\' -> i += 2
                '"' -> return i + 1
                '\n' -> return i
                else -> i++
            }
        }
        return minOf(i, text.length)
    }

    private fun skipSpaces(text: CharSequence, from: Int): Int {
        var i = from
        while (i < text.length && (text[i] == ' ' || text[i] == '\t')) i++
        return i
    }
}
