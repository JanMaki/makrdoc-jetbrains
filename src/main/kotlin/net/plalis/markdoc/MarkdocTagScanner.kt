package net.plalis.markdoc

import net.plalis.markdoc.MarkdocTagScanner.scan


/**
 * テキストから Markdoc のタグ（`{% … %}`）を探し、タグの中を種類ごとのトークンに切り分ける。
 *
 * タグの色分け（[net.plalis.markdoc.highlighting.MarkdocTagAnnotator]）と、属性値のファイルパスの
 * 特定（[net.plalis.markdoc.paths.MarkdocPathValue]）で使う。
 *
 * 構文の検証はしない（言語サーバーが行う）。書きかけのタグでも、それらしい部分を切り出せればよい。
 * 色を付けない部分（`=`、丸括弧、区切りの `,` や `:` など）はトークンにしない。
 *
 * ## コメント
 *
 * 設定で Markdoc の `allowComments` を有効にしていれば、`<!-- … -->` をコメントとして扱う（[scan] の
 * `allowComments`）。コメントの中のタグはトークンにせず、コメント全体を 1 つの [Kind.COMMENT] のトークンにする。
 * 範囲は Markdoc のコメントの規則に合わせる。
 *
 * - ブロックのコメント：行（字下げとリストの印の後）が `<!--` で始まれば、空行をまたいで最初の `-->` まで。
 *   Markdoc は `-->` と同じ行の残りを捨てるので、残りにタグがあってもトークンにしない
 * - インラインのコメント：行の途中の `<!--` から、同じ段落（空行かフェンスまで）の中の最初の `-->` まで
 * - 閉じていない `<!--` はコメントにしない（Markdoc 0.5 と同じ。同梱サーバーの Markdoc 0.3 は扱いが違うが、
 *   `language-server/src/comments.js` で 0.5 と同じ扱いに差し替えている）
 * - フェンスコードブロックとインラインコードの中、`\<!--` はコメントにしない
 */
object MarkdocTagScanner {
    /** トークンの種類。 */
    enum class Kind {
        /** `{%`、`%}`、`/%}`。 */
        DELIMITER,

        /** タグの最初の語。閉じタグの `/` を含む（`/callout`）。 */
        TAG_NAME,

        /** `=` が続く語と、短縮記法の `.class`・`#id`。オブジェクトのキー（`{id: 1}` の `id`）は [KEY] にする。 */
        ATTRIBUTE,

        /**
         * 引用符を含む文字列。閉じていなければ行末まで。変数の添字の中身（`$a["key"]` の `"key"`）を含む。
         * オブジェクトのキーにした文字列（`{"id": 1}` の `"id"`）は [KEY] にする。
         */
        STRING,

        /** 数値。変数の添字の中身（`$items[0]` の `0`）を含む。 */
        NUMBER,

        /** `true`、`false`、`null`。 */
        KEYWORD,

        /**
         * `$` から始まる変数参照（`$page.title`、`$items[0].name`）。添字の中身は [STRING] か [NUMBER] として分けるため、
         * 添字を含む変数は複数のトークンになる（`$items[`、`0`、`].name`）。
         */
        VARIABLE,

        /** `(` が続く語。 */
        FUNCTION,

        /** 配列の角括弧 `[`、`]`。変数の添字の角括弧（`$items[0]` の `[`、`]`）は [VARIABLE] に含める。 */
        BRACKET,

        /** オブジェクトの波括弧 `{`、`}`。区切り（`{%`、`%}`、`/%}`）の波括弧は [DELIMITER] に含める。 */
        BRACE,

        /**
         * オブジェクトのキー。直後に `:` が続く語か文字列（`{id: 1}` の `id`、`{"id": 1}` の `"id"`）。
         * Markdoc はキーと `:` の間に空白を認めないので、`{id : 1}` の `id` はキーにしない。
         */
        KEY,

        /**
         * コメント（`<!-- … -->`）全体。タグの中には現れない。コメントを有効にして走査したときだけ作る。
         * Markdown の色分けは複数行のコメントをコメントの色にしないため、このトークンで色を付ける。
         */
        COMMENT,
    }

    /** [start] から [end] の手前までが [kind] の部分。 */
    data class Token(val start: Int, val end: Int, val kind: Kind)

    private val KEYWORDS = setOf("true", "false", "null")

    /** コメントの開き。 */
    private const val COMMENT_OPEN = "<!--"

    /** コメントの閉じ。 */
    private const val COMMENT_CLOSE = "-->"

    /**
     * [text] 内のタグを先頭から走査し、トークンを返す。トークンは重ならず、出現順に並ぶ。
     *
     * Markdoc と同じく、インラインコードの中のタグは除き、フェンスコードブロックの中のタグは情報文字列に
     * `{% process=false %}` がなければ処理する。フェンスはコンテナ（リスト項目の内容。近似的に追跡する）から
     * 3 桁までの字下げだけを認める。
     *
     * @param allowComments 真なら `<!-- … -->` をコメントとして扱い、中のタグを除いてコメントのトークンを加える
     *   （クラスの説明の「コメント」を参照）。
     */
    fun scan(text: CharSequence, allowComments: Boolean = false): List<Token> = scan(text, null, allowComments)

    /**
     * [text] の [offset] がインラインコードかフェンスコードブロックの中身かどうか。
     * フェンスは `process=false` の有無を問わず、開きフェンスの行は含めない。
     */
    fun isInCode(text: CharSequence, offset: Int): Boolean {
        val code = ArrayList<IntRange>()
        scan(text, code, allowComments = false)
        return code.any { offset in it }
    }

    /**
     * [text] を走査した [tokens]（[scan] の結果）で、[start] から [end] の手前までが 1 つのタグの中に収まるかどうか。
     *
     * タグは開きの区切り（`{%`）から、次の閉じの区切り（`%}` か `/%}`）の終わりまで。閉じていないタグは、
     * 次の `{%` かコメントの手前にある最後のトークンの終わりまでとする。
     */
    fun isInTag(text: CharSequence, tokens: List<Token>, start: Int, end: Int): Boolean {
        // 区切りは `{%`、`%}`、`/%}` で、`{` から始まるのは開きの区切りだけ
        fun Token.isOpening() = kind == Kind.DELIMITER && text[this.start] == '{'

        // [start] 以前に始まる最後のトークンから戻り、直前に開いたタグを探す
        var index = firstEndingAfter(tokens, start)
        if (index == tokens.size || tokens[index].start > start) index--
        while (index >= 0 && !tokens[index].isOpening()) {
            // 先に閉じの区切りかコメントが見つかれば、範囲はタグの外から始まっている（コメントはタグの外にしかない）
            val kind = tokens[index].kind
            if (kind == Kind.COMMENT || (kind == Kind.DELIMITER && tokens[index].end <= start)) return false
            index--
        }
        if (index < 0) return false

        // タグの終わりを探す
        var tagEnd = tokens[index].end
        for (i in index + 1 until tokens.size) {
            val token = tokens[i]
            if (token.isOpening() || token.kind == Kind.COMMENT) break
            tagEnd = token.end
            if (token.kind == Kind.DELIMITER) break
        }
        return end <= tagEnd
    }

    /** 終わりが [offset] より後ろにある最初のトークンの位置。なければ `tokens.size`（トークンは重ならず昇順に並ぶ）。 */
    fun firstEndingAfter(tokens: List<Token>, offset: Int): Int {
        var low = 0
        var high = tokens.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (tokens[mid].end <= offset) low = mid + 1 else high = mid
        }
        return low
    }

    /**
     * [scan] の本体。[code] があれば、コードの中身の範囲（カーソルが置ける位置、両端を含む）を加える。
     * [allowComments] が真なら、コメントの中を読み飛ばしてコメントのトークンを加える。
     */
    private fun scan(text: CharSequence, code: MutableList<IntRange>?, allowComments: Boolean): List<Token> {
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
            // 行の内容の先頭（字下げとリストの印の後）。空行なら null
            var contentStart: Int? = null
            if (!blank) {
                val (width, pos) = indentOf(text, lineStart, lineEnd)
                val listMarker = ListMarker.at(text, pos, lineEnd, width)
                contentStart = listMarker?.contentStart ?: pos
                // 浅い行は、空行の後かブロックの始まりならリスト項目を終える（それ以外は遅延継続行）
                val startsBlock = listMarker != null || Fence.markerLength(text, pos, lineEnd) >= 3
                while (lists.isNotEmpty() && width < lists.last() && (previousBlank || startsBlock)) lists.removeAt(
                    lists.size - 1
                )
                val container = lists.lastOrNull() ?: 0
                // コンテナから 4 桁以上字下げした行はフェンスもリストも始めない（markdown-it）
                if (width - container < 4) {
                    opened = Fence.openedAt(text, pos, lineEnd, container)
                    if (opened == null && listMarker != null) lists += listMarker.contentIndent
                }
            }
            // ブロックのコメント。Markdoc の規則は字下げの深さを問わず、段落の途中でも始まる
            if (allowComments && opened == null && contentStart != null) {
                val commentEnd = findCommentEnd(text, contentStart, text.length)
                if (commentEnd >= 0) {
                    // コメントは段落を終えるので、ここまでの段落を先に走査する
                    i = scanRange(
                        text,
                        maxOf(i, inlineStart),
                        lineStart,
                        tokens,
                        codeSpans = true,
                        code,
                        comments = true
                    )
                    // 前の行から続くタグが `<!--` まで読んでいれば、コメントではない
                    if (i <= contentStart) {
                        tokens += Token(contentStart, commentEnd, Kind.COMMENT)
                        // Markdoc は `-->` と同じ行の残りを捨てるので、次の行から読む
                        val resume = nextLineStart(text, commentEnd)
                        i = resume
                        inlineStart = resume
                        previousBlank = false
                        lineStart = resume
                        continue
                    }
                }
            }
            if (opened != null || blank) {
                i = scanRange(text, maxOf(i, inlineStart), lineStart, tokens, codeSpans = true, code, allowComments)
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
            scanRange(text, maxOf(i, inlineStart), text.length, tokens, codeSpans = true, code, allowComments)
        } else if (bodyStart < text.length || text.endsWith('\n')) {
            // 閉じていないフェンスは末尾まで続く（開きフェンスが最終行なら中身なし）
            code?.add(bodyStart..text.length)
        }
        return tokens
    }

    /**
     * [from] から [to] の手前までのタグを走査し、読み終えた位置を返す（タグが続けば [to] を越える）。
     *
     * [codeSpans] が真ならインラインコードを読み飛ばし、[code] にその中身を加える。さらに [comments] が真なら
     * インラインのコメントを読み飛ばし、コメントのトークンを加える。タグとインラインコードとコメントは、
     * 先に始まったものを優先する（Markdoc と同じ）。
     */
    private fun scanRange(
        text: CharSequence,
        from: Int,
        to: Int,
        tokens: MutableList<Token>,
        codeSpans: Boolean,
        code: MutableList<IntRange>? = null,
        comments: Boolean = false,
    ): Int {
        var i = from
        while (i < to) {
            val c = text[i]
            i = when {
                c == '{' && text.at(i + 1) == '%' -> scanTag(text, i, tokens)
                !codeSpans -> i + 1
                // エスケープしたバッククォートと `<` は、インラインコードとコメントを始めない
                c == '\\' && (text.at(i + 1) == '`' || text.at(i + 1) == '<') -> i + 2
                c == '`' -> skipCodeSpan(text, i, to, code)
                comments && c == '<' -> skipComment(text, i, to, tokens)
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

    /**
     * [open] から始まるインラインのコメントを読み飛ばし、直後の位置を返す。[tokens] にはコメントのトークンを加える。
     * [open] が `<!--` でないか、[to] までに閉じていなければコメントにせず、[open] の次の位置を返す。
     */
    private fun skipComment(text: CharSequence, open: Int, to: Int, tokens: MutableList<Token>): Int {
        val end = findCommentEnd(text, open, to)
        if (end < 0) return open + 1
        tokens += Token(open, end, Kind.COMMENT)
        return end
    }

    /**
     * [open] から `<!--` で始まるコメントの終わり（`-->` の直後）を返す。`<!--` でないか、[to] までに `-->` が
     * なければ -1。Markdoc と同じく `<!--` の中から探すので、`<!-->` と `<!--->` もコメントになる。
     */
    private fun findCommentEnd(text: CharSequence, open: Int, to: Int): Int {
        if (!text.startsWith(COMMENT_OPEN, open)) return -1
        var i = open + 2
        while (i + COMMENT_CLOSE.length <= to) {
            if (text.startsWith(COMMENT_CLOSE, i)) return i + COMMENT_CLOSE.length
            i++
        }
        return -1
    }

    /** [from] を含む行の次の行の先頭。最終行ならテキストの末尾。 */
    private fun nextLineStart(text: CharSequence, from: Int): Int =
        text.indexOf('\n', from).let { if (it < 0) text.length else it + 1 }

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
     * 行頭のリストの印（`-`、`*`、`+`、`1.`、`1)`）。
     *
     * @property contentIndent 項目の内容の字下げ幅。続く行がこれ以上字下げしていれば項目の内容になる。
     * @property contentStart 印の後の空白の直後の位置（その行の内容の先頭）。
     */
    private class ListMarker(val contentIndent: Int, val contentStart: Int) {
        companion object {
            /**
             * 字下げ（幅 [width]）の直後の [from] がリストの印なら返す。[end] は行末。
             * 印の後の空白が 5 桁以上か内容がなければ、印の 1 桁後を内容の字下げ幅とする。
             */
            fun at(text: CharSequence, from: Int, end: Int, width: Int): ListMarker? {
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
                val (spaces, content) = indentOf(text, i, end)
                val indent = if (spaces in 1..4 && !text.isBlank(i, end)) marker + spaces else marker + 1
                return ListMarker(indent, content)
            }
        }
    }

    /**
     * [open] の `{%` から始まるタグを走査し、タグの直後の位置を返す。`%}` がなければテキストの末尾まで読む。
     *
     * 区切り（`%}`、`/%}`、次のタグの `{%`）は括弧より先に判定する。そのため `{a: {b: 1}}/%}` の `}` のうち、
     * 区切りの一部になるのは `/%}` の `}` だけで、残りは [Kind.BRACE] になる。
     */
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
                    // キーと `:` の間に空白は置けない（Markdoc の文法）
                    val kind = if (text.at(i) == ':') Kind.KEY else Kind.STRING
                    tokens += Token(start, i, kind)
                }

                c == '$' && isIdentStart(text.at(i + 1)) -> i = scanVariable(text, i, tokens)

                c.isDigit() || (c == '-' && text.at(i + 1).isDigit()) -> {
                    i = skipNumber(text, i)
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
                    // `=` と `(` の前には空白を置けるが、キーと `:` の間には置けない（Markdoc の文法）
                    val next = text.at(skipSpaces(text, i))
                    val kind = when {
                        text.at(i) == ':' -> Kind.KEY
                        next == '=' -> Kind.ATTRIBUTE
                        next == '(' -> Kind.FUNCTION
                        word in KEYWORDS -> Kind.KEYWORD
                        first -> Kind.TAG_NAME
                        else -> null
                    }
                    if (kind != null) tokens += Token(start, i, kind)
                }
                // 配列とオブジェクトの括弧（区切りの `{`、`}` は上で読み終えている）
                c == '[' || c == ']' || c == '{' || c == '}' -> {
                    i++
                    tokens += Token(start, i, if (c == '[' || c == ']') Kind.BRACKET else Kind.BRACE)
                }

                else -> i++
            }
            first = false
        }
        return i
    }

    /** [index] の文字。範囲外なら `\u0000`（どの判定にも当てはまらない文字）。 */
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

    /**
     * [start] の `$` から始まる変数参照（`$page.title`、`$items[0]`、`$a["key"]`）を走査してトークンを加え、
     * その直後の位置を返す。
     *
     * `.` の後の名前と、数値か文字列を書いた添字（`[0]`、`["key"]`）を続けて読む。入力中の `$a.` は `.` まで含める。
     * 閉じていない添字（`$items[0`）は変数に含めない。
     *
     * 添字の中身は [Kind.NUMBER] か [Kind.STRING]、それ以外（`$`、名前、`.`、`[`、`]`）は [Kind.VARIABLE] にする。
     * `$a["key"].b` は `$a[`、`"key"`、`].b` の 3 つのトークンになる。
     */
    private fun scanVariable(text: CharSequence, start: Int, tokens: MutableList<Token>): Int {
        // 変数の色にする部分の先頭（添字の中身の後で `]` から始め直す）
        var segment = start
        var i = skipIdent(text, start + 1)
        loop@ while (i < text.length) {
            when (text[i]) {
                '.' -> i = skipIdent(text, i + 1)
                '[' -> {
                    val (kind, valueEnd) = subscriptAt(text, i) ?: break@loop
                    tokens += Token(segment, i + 1, Kind.VARIABLE)
                    tokens += Token(i + 1, valueEnd, kind)
                    segment = valueEnd
                    i = valueEnd + 1
                }

                else -> break@loop
            }
        }
        tokens += Token(segment, i, Kind.VARIABLE)
        return i
    }

    /**
     * [open] の `[` から始まる添字（`[0]`、`[-1]`、`["key"]`）の中身の種類と、中身の終わり（`]` の位置）を返す。
     * 中身が数値か文字列でないか、直後に `]` がなければ `null`。
     */
    private fun subscriptAt(text: CharSequence, open: Int): Pair<Kind, Int>? {
        val c = text.at(open + 1)
        val (kind, end) = when {
            // 閉じていない文字列は行末かテキストの末尾で止まるので、続く `]` の確認で除かれる
            c == '"' -> Kind.STRING to skipString(text, open + 1)
            c.isDigit() || (c == '-' && text.at(open + 2).isDigit()) -> Kind.NUMBER to skipNumber(text, open + 1)
            else -> return null
        }
        return if (text.at(end) == ']') kind to end else null
    }

    /** [from] から始まる数値（`-` と小数点を含む）を読み飛ばし、その直後の位置を返す。 */
    private fun skipNumber(text: CharSequence, from: Int): Int {
        var i = from + 1
        while (i < text.length && (text[i].isDigit() || text[i] == '.')) i++
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
