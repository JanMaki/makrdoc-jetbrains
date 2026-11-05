package com.github.markdoc.jetbrains.highlighting

import com.github.markdoc.jetbrains.MarkdocFiles
import com.github.markdoc.jetbrains.MarkdocTagScanner
import com.intellij.psi.PsiFile
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager

/**
 * Markdoc ファイルのタグのトークンを、ファイルごとにキャッシュして提供する。
 *
 * 色分け（[MarkdocTagAnnotator]）と、タグの中の Markdown の色と警告を除く処理（[MarkdocTagHighlightFilter]、
 * [MarkdocTagInspectionSuppressor]）で同じ走査結果を使う。
 */
object MarkdocTagTokens {
    /** [file] 全体を走査したトークン。ファイルが変わるまでキャッシュする。 */
    fun of(file: PsiFile): List<MarkdocTagScanner.Token> =
        CachedValuesManager.getCachedValue(file) {
            CachedValueProvider.Result.create(MarkdocTagScanner.scan(file.viewProvider.contents), file)
        }

    /**
     * Markdoc ファイル [file] の [start] から [end] の手前までが 1 つのタグの中に収まるかどうか。
     * Markdoc ファイルでなければ（Markdown ファイルなら）偽で、トークンの走査もしない。
     */
    fun isInTag(file: PsiFile, start: Int, end: Int): Boolean {
        val viewProvider = file.viewProvider
        return MarkdocFiles.isMarkdoc(viewProvider.virtualFile) &&
                MarkdocTagScanner.isInTag(viewProvider.contents, of(file), start, end)
    }
}
