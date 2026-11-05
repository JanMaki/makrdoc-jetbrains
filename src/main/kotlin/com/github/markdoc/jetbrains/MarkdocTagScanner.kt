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

    /**
     * [text] 内のタグを先頭から走査し、トークンを返す。トークンは重ならず、出現順に並ぶ。
     *
     * Markdoc と同じく、インラインコードの中のタグは除き、フェンスコードブロックの中のタグは情報文字列に
     * `{% process=false %}` がなければ処理する。フェンスはコンテナ（リスト項目の内容。近似的に追跡する）から
     * 3 桁までの字下げだけを認める。
     */
    fun scan(text: CharSequence): List<Token> = scan(text, null)

    /**
     * [text] の [offset] がインラインコードかフェンスコードブロックの中身かどうか。
     * フェンスは `process=false` の有無を問わず、開きフェンスの行は含めない。
     */
    fun isInCode(text: CharSequence, offset: Int): Boolean {
        val code = ArrayList<IntRange>()
        scan(text, code)
        return code.any { offset in it }
    }

    /** [scan] の本体。[code] があれば、コードの中身の範囲（カーソルが置ける位置、両端を含む）を加える。 */
    private fun scan(text: CharSequence, code: MutableList<IntRange>?): List<Token> {
        val tokens = ArrayList<Token>()
        // 読み終えた位置（タグは行をまたぐことがある）
        var i = 0
        // インラインコードを探す範囲の先頭（空行とフェンスで区切る）
        var inlineStart = 0
        var fence: Fence? = null
        var bodyStart = 0
        // 開いているリスト項目の内容の字下げ幅（外側から順に）
        val lists = ArrayList<Int>()
        var previousBlank = false
        var lineStart = 0
        while (lineStart < text.length) {
            val lineEnd = text.indexOf('\n', lineStart).let { if (it < 0) text.length else it }
            val next = minOf(lineEnd + 1, text.length)
            val open = fence
            if (open != null) {
                if (!open.isEndedBy(text, lineStart, lineEnd)) {
                    if (open.isClosedBy(text, lineStart, lineEnd)) {
                        code?.add(bodyStart until lineStart)
                        fence = null
                        inlineStart = next
                        previousBlank = false
                    } else if (open.process) {
                        // フェンスの中ではバッククォートはインラインコードにならない
                        i = scanRange(text, maxOf(i, lineStart), next, tokens, codeSpans = false)
                    }
                    lineStart = next
                    continue
                }
                // リスト項目が終わり、フェンスも閉じずに終わった。この行は通常の行として読む
                code?.add(bodyStart until lineStart)
                fence = null
                inlineStart = lineStart
                previousBlank = false
            }
            val blank = text.isBlank(lineStart, lineEnd)
            var opened: Fence? = null
            if (!blank) {
                val (width, pos) = indentOf(text, lineStart, lineEnd)
                val listContent = listContentIndent(text, pos, lineEnd, width)
                // 浅い行は、空行の後かブロックの始まりならリスト項目を終える（それ以外は遅延継続行）
                val startsBlock = listContent != null || Fence.markerLength(text, pos, lineEnd) >= 3
                while (lists.isNotEmpty() && width < lists.last() && (previousBlank || startsBlock)) lists.removeAt(lists.size - 1)
                val container = lists.lastOrNull() ?: 0
                // コンテナから 4 桁以上字下げした行はフェンスもリストも始めない（markdown-it）
                if (width - container < 4) {
                    opened = Fence.openedAt(text, pos, lineEnd, container)
                    if (opened == null && listContent != null) lists += listContent
                }
            }
            if (opened != null || blank) {
                i = scanRange(text, maxOf(i, inlineStart), lineStart, tokens, codeSpans = true, code)
                inlineStart = next
            }
            if (opened != null) {
                fence = opened
                bodyStart = next
                // 情報文字列の `{% process=false %}` などを色付けする
                i = scanRange(text, maxOf(i, lineStart), next, tokens, codeSpans = false)
            }
            previousBlank = blank
            lineStart = next
        }
        if (fence == null) {
            scanRange(text, maxOf(i, inlineStart), text.length, tokens, codeSpans = true, code)
        } else if (bodyStart < text.length || text.endsWith('\n')) {
            // 閉じていないフェンスは末尾まで続く（開きフェンスが最終行なら中身なし）
            code?.add(bodyStart..text.length)
        }
        return tokens
    }

    /**
     * [from] から [to] の手前までのタグを走査し、読み終えた位置を返す（タグが続けば [to] を越える）。
     *
     * [codeSpans] が真ならインラインコードを読み飛ばし、[code] にその中身を加える。タグとインラインコードは
     * 先に始まった方を優先する（Markdoc と同じ）。
     */
    private fun scanRange(
        text: CharSequence,
        from: Int,
        to: Int,
        tokens: MutableList<Token>,
        codeSpans: Boolean,
        code: MutableList<IntRange>? = null,
    ): Int {
        var i = from
        while (i < to) {
            val c = text[i]
            i = when {
                c == '{' && text.at(i + 1) == '%' -> scanTag(text, i, tokens)
                !codeSpans -> i + 1
                // エスケープしたバッククォートはインラインコードを始めない
                c == '\\' && text.at(i + 1) == '`' -> i + 2
                c == '`' -> skipCodeSpan(text, i, to, code)
                else -> i + 1
            }
        }
        return maxOf(i, from)
    }

    /**
     * [open] のバッククォートの並びが開くインラインコードを読み飛ばし、直後の位置を返す。
     * [to] までに同じ長さの並びがなければ、開きの並びの直後を返す。[code] には中身（両端を含む）を加える。
     */
    private fun skipCodeSpan(text: CharSequence, open: Int, to: Int, code: MutableList<IntRange>?): Int {
        val length = run(text, open, to, '`')
        var i = open + length
        while (i < to) {
            if (text[i] != '`') {
                i++
                continue
            }
            val run = run(text, i, to, '`')
            if (run == length) {
                code?.add(open + length..i)
                return i + run
            }
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
     * @property length 開きフェンスの文字数（閉じフェンスはこれ以上）。
     * @property container 含むリスト項目の内容の字下げ幅（リストの外なら 0）。
     * @property process 中のタグを処理するかどうか。
     */
    private class Fence(val marker: Char, val length: Int, val container: Int, val process: Boolean) {
        /** [start] から [end] の手前までの行がリスト項目を終え、フェンスを閉じずに終えるかどうか。 */
        fun isEndedBy(text: CharSequence, start: Int, end: Int): Boolean =
            !text.isBlank(start, end) && indentOf(text, start, end).first < container

        /** [start] から [end] の手前までの行がこのフェンスを閉じるかどうか。字下げはコンテナから 3 桁まで。 */
        fun isClosedBy(text: CharSequence, start: Int, end: Int): Boolean {
            val (width, i) = indentOf(text, start, end)
            if (width - container >= 4) return false
            val run = run(text, i, end, marker)
            return run >= length && text.isBlank(i + run, end)
        }

        companion object {
            /** [from] から始まるフェンス文字（`` ` `` か `~`）の並びの長さ。フェンス文字でなければ 0。 */
            fun markerLength(text: CharSequence, from: Int, end: Int): Int {
                val marker = text.at(from).takeIf { it == '`' || it == '~' } ?: return 0
                return run(text, from, end, marker)
            }

            /** 字下げの直後の [from] からの行がフェンスを開くなら返す。字下げの幅は呼び出し側で確かめる。 */
            fun openedAt(text: CharSequence, from: Int, end: Int, container: Int): Fence? {
                val length = markerLength(text, from, end)
                if (length < 3) return null
                val marker = text[from]
                val info = text.subSequence(from + length, end)
                // バッククォートのフェンスの情報文字列にはバッククォートを書けない
                if (marker == '`' && info.contains('`')) return null
                return Fence(marker, length, container, process = !isProcessFalse(info))
            }

            /** 情報文字列 [info] のタグに `process=false` があるかどうか。引用符の中は数えない。 */
            private fun isProcessFalse(info: CharSequence): Boolean {
                val tokens = ArrayList<Token>()
                scanRange(info, 0, info.length, tokens, codeSpans = false)
                fun Token.text() = info.subSequence(start, end).toString()
                // `=` はトークンにならないので、属性名の次のトークンが値
                return tokens.zipWithNext().any { (name, value) ->
                    name.kind == Kind.ATTRIBUTE && name.text() == "process" &&
                        value.kind == Kind.KEYWORD && value.text() == "false"
                }
            }
        }
    }

    /** [start] から [end] の手前までの行の字下げの幅（タブは 4 の倍数の桁まで進める）と、字下げの直後の位置。 */
    private fun indentOf(text: CharSequence, start: Int, end: Int): Pair<Int, Int> {
        var width = 0
        var i = start
        while (i < end) {
            when (text[i]) {
                ' ' -> width++
                '\t' -> width += 4 - width % 4
                else -> break
            }
            i++
        }
        return width to i
    }

    /**
     * 字下げ（幅 [width]）の直後の [from] がリストの印（`-`、`*`、`+`、`1.`、`1)`）なら、内容の字下げ幅を返す。
     * 印の後の空白が 5 桁以上か内容がなければ、印の 1 桁後を内容の始まりとする。
     */
    private fun listContentIndent(text: CharSequence, from: Int, end: Int, width: Int): Int? {
        var i = from
        val c = text.at(i)
        if (c == '-' || c == '*' || c == '+') {
            i++
        } else {
            while (i < end && i - from < 9 && text[i].isDigit()) i++
            if (i == from || (text.at(i) != '.' && text.at(i) != ')')) return null
            i++
        }
        if (i < end && text[i] != ' ' && text[i] != '\t') return null
        val marker = width + (i - from)
        val (spaces, _) = indentOf(text, i, end)
        return if (spaces in 1..4 && !text.isBlank(i, end)) marker + spaces else marker + 1
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
