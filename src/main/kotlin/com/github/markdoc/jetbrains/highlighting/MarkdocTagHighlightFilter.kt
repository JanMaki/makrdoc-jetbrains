package com.github.markdoc.jetbrains.highlighting

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInsight.daemon.impl.HighlightInfoFilter
import com.intellij.psi.PsiFile

/**
 * Markdoc のタグの中（`{%` から `%}` まで）に Markdown プラグインが付けた色を取り除く。
 *
 * Markdown の構文解析はタグを知らないため、`$a["key"]` や `$items[0]["id"]` の `[…][…]` を参照リンクとして読み、
 * リンクの色と下線を付ける。Markdoc はタグの中をリンクとして読まないので、Markdown の色（外部名が `MARKDOWN_` で
 * 始まる色キーのもの）を除く。言語サーバーの診断やこのプラグインの色はそのまま残す。
 *
 * Markdown のインスペクションの警告はこのフィルターを通らないため、[MarkdocTagInspectionSuppressor] で止める。
 */
class MarkdocTagHighlightFilter : HighlightInfoFilter {
    override fun accept(highlightInfo: HighlightInfo, file: PsiFile?): Boolean {
        // すべてのファイルのすべての強調表示に呼ばれるので、安い判定（色キーの名前）を先にし、
        // Markdown の色だけについてタグの中かどうかを調べる
        val markdownColor = highlightInfo.forcedTextAttributesKey?.externalName?.startsWith(MARKDOWN_PREFIX) == true
        return file == null || !markdownColor ||
                !MarkdocTagTokens.isInTag(file, highlightInfo.startOffset, highlightInfo.endOffset)
    }

    private companion object {
        /** Markdown プラグインの色キー（`MarkdownHighlighterColors`）の外部名の接頭辞。 */
        const val MARKDOWN_PREFIX = "MARKDOWN_"
    }
}
