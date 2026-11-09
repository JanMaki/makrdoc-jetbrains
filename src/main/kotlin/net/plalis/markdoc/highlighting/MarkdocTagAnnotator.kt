package net.plalis.markdoc.highlighting

import net.plalis.markdoc.MarkdocFiles
import net.plalis.markdoc.MarkdocTagScanner
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement

/**
 * Markdoc ファイル（Markdown ファイルタイプ）の中のタグに色を付ける。
 *
 * Markdown の PSI はタグを知らないので、ファイル全体のテキストを [MarkdocTagScanner] で走査した結果
 * （[MarkdocTagTokens] がキャッシュする）から、葉の要素ごとにその範囲に重なる部分だけを色付けする
 * （注釈は要素の範囲内に収める必要がある）。
 */
class MarkdocTagAnnotator : Annotator, DumbAware {
    /** 葉の要素 [element] の範囲に重なるタグのトークンに色を付ける。 */
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        if (element.firstChild != null) return
        val range = element.textRange ?: return
        if (range.isEmpty) return
        val file = element.containingFile ?: return
        val virtualFile = file.virtualFile ?: return
        if (!MarkdocFiles.isMarkdoc(virtualFile)) return

        val tokens = MarkdocTagTokens.of(file)
        var index = MarkdocTagScanner.firstEndingAfter(tokens, range.startOffset)
        while (index < tokens.size) {
            val token = tokens[index++]
            if (token.start >= range.endOffset) break
            val clipped = TextRange(maxOf(token.start, range.startOffset), minOf(token.end, range.endOffset))
            if (clipped.isEmpty) continue
            holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
                .range(clipped)
                .textAttributes(MarkdocHighlighterColors.of(token.kind))
                .create()
        }
    }
}
