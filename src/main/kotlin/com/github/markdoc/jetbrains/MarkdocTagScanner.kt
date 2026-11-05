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

    /** フェンスの情報文字列にある `{% process=false %}`。フェンス内のタグを処理しない指定。 */
    private val PROCESS_FALSE = Regex("""\{%[^%]*\bprocess\s*=\s*false\b""")

    /**
     * [text] 内のタグを先頭から走査し、トークンを返す。トークンは重ならず、出現順に並ぶ。
     *
     * Markdoc と同じく、インラインコード（`` `{% tag %}` ``）の中のタグは除く。フェンスコードブロックの中の
     * タグは、情報文字列に `{% process=false %}` がなければ処理する（Markdoc の既定）。
     */
    fun scan(text: CharSequence): List<Token> {
        val tokens = ArrayList<Token>()
        // 直前のタグが行をまたいで読み進めた位置。次の範囲はここより前から読まない
        var i = 0
        // インラインコードを探す範囲（段落など）の先頭。空行とフェンスで区切る
        var inlineStart = 0
        var fence: Fence? = null
        var lineStart = 0
        while (lineStart < text.length) {
            val lineEnd = text.indexOf('\n', lineStart).let { if (it < 0) text.length else it }
            val next = minOf(lineEnd + 1, text.length)
            val open = fence
            if (open != null) {
                if (open.isClosedBy(text, lineStart, lineEnd)) {
                    fence = null
                    inlineStart = next
                } else if (open.process) {
                    // フェンスの中はコードなので、バッククォートはインラインコードにならない
                    i = scanRange(text, maxOf(i, lineStart), next, tokens, codeSpans = false)
                }
            } else {
                val opened = Fence.openedAt(text, lineStart, lineEnd)
                if (opened != null || text.isBlank(lineStart, lineEnd)) {
                    i = scanRange(text, maxOf(i, inlineStart), lineStart, tokens, codeSpans = true)
                    inlineStart = next
                }
                if (opened != null) {
                    fence = opened
                    // 情報文字列の `{% process=false %}` などを色付けする
                    i = scanRange(text, maxOf(i, lineStart), next, tokens, codeSpans = false)
                }
            }
            lineStart = next
        }
        if (fence == null) scanRange(text, maxOf(i, inlineStart), text.length, tokens, codeSpans = true)
        return tokens
    }

    /**
     * [from] から [to] の手前までにあるタグを走査し、最後に読んだ位置を返す（タグが [to] を越えて続けば [to] より後ろ）。
     *
     * [codeSpans] が真なら、インラインコードを読み飛ばす。Markdoc のインライン規則と同じく先に始まった方を優先するため、
     * タグの中のバッククォートはインラインコードにならず、インラインコードの中の `{%` はタグにならない。
     */
    private fun scanRange(text: CharSequence, from: Int, to: Int, tokens: MutableList<Token>, codeSpans: Boolean): Int {
        var i = from
        while (i < to) {
            val c = text[i]
            i = when {
                c == '{' && text.at(i + 1) == '%' -> scanTag(text, i, tokens)
                !codeSpans -> i + 1
                // エスケープしたバッククォートはインラインコードを始めない
                c == '\\' && text.at(i + 1) == '`' -> i + 2
                c == '`' -> skipCodeSpan(text, i, to)
                else -> i + 1
            }
        }
        return maxOf(i, from)
    }

    /**
     * [open] から始まるバッククォートの並びが開くインラインコードを読み飛ばし、その直後の位置を返す。
     * [to] より前に同じ長さの並びがなければインラインコードではないので、並びの直後を返す。
     */
    private fun skipCodeSpan(text: CharSequence, open: Int, to: Int): Int {
        val length = run(text, open, to, '`')
        var i = open + length
        while (i < to) {
            if (text[i] != '`') {
                i++
                continue
            }
            val run = run(text, i, to, '`')
            if (run == length) return i + run
            i += run
        }
        return open + length
    }

    /** [from] から [to] の手前までで、先頭から続く [marker] の数。 */
    private fun run(text: CharSequence, from: Int, to: Int, marker: Char): Int {
        var i = from
        while (i < to && text[i] == marker) i++
        return i - from
    }

    /** [start] から [end] の手前までが空白だけかどうか。 */
    private fun CharSequence.isBlank(start: Int, end: Int): Boolean = (start until end).all { this[it].isWhitespace() }

    /**
     * 開いているフェンスコードブロック。
     *
     * @property marker フェンスの文字（`` ` `` か `~`）。
     * @property length 開きフェンスの文字数。閉じフェンスはこれ以上の長さが必要。
     * @property process 中のタグを処理するかどうか（`{% process=false %}` がなければ真）。
     */
    private class Fence(val marker: Char, val length: Int, val process: Boolean) {
        /** [start] から [end] の手前までの行がこのフェンスを閉じるかどうか。 */
        fun isClosedBy(text: CharSequence, start: Int, end: Int): Boolean {
            val i = skipSpaces(text, start)
            val run = run(text, i, end, marker)
            return run >= length && text.isBlank(i + run, end)
        }

        companion object {
            /**
             * [start] から [end] の手前までの行がフェンスを開くなら、そのフェンスを返す。
             *
             * Markdoc はインデントによるコードブロックを無効にしているため、リストの中などで深く字下げした
             * フェンスも認める。
             */
            fun openedAt(text: CharSequence, start: Int, end: Int): Fence? {
                val i = skipSpaces(text, start)
                val marker = text.at(i).takeIf { it == '`' || it == '~' } ?: return null
                val length = run(text, i, end, marker)
                if (length < 3) return null
                val info = text.subSequence(i + length, end)
                // バッククォートのフェンスの情報文字列にはバッククォートを書けない
                if (marker == '`' && info.contains('`')) return null
                return Fence(marker, length, process = !PROCESS_FALSE.containsMatchIn(info))
            }
        }
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

    /** [index] の文字。範囲外なら ` `。 */
    private fun CharSequence.at(index: Int): Char = if (index < length) this[index] else '\u0000'

    /** 識別子の先頭に置ける文字かどうか。 */
    private fun isIdentStart(c: Char) = c.isLetter() || c == '_'

    /** 識別子の 2 文字目以降に置ける文字かどうか（`-` を含む）。 */
    private fun isIdentPart(c: Char) = c.isLetterOrDigit() || c == '_' || c == '-'

    /** [from] から続く識別子を読み飛ばし、その直後の位置を返す。 */
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

    /** [from] から続く空白とタブを読み飛ばし、その直後の位置を返す。 */
    private fun skipSpaces(text: CharSequence, from: Int): Int {
        var i = from
        while (i < text.length && (text[i] == ' ' || text[i] == '\t')) i++
        return i
    }
}
