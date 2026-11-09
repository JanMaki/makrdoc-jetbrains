package net.plalis.markdoc

import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.editorActions.TypedHandlerDelegate
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile

/**
 * Markdoc のタグの中での入力を補助する。
 *
 * - `/` を入力したら、続けて `%}` を補って自己終了タグ（`/%}`）を閉じる（[shouldCloseTag]）
 * - `/` か `"` を入力したら補完を開く。ファイルパスの属性値なら、その階層の候補が出る
 *   （[net.plalis.markdoc.paths.MarkdocPathCompletionContributor]）
 *
 * どちらも同じ行の中だけを見る。コード（インラインコードとフェンスコードブロックの中身）の中では何もしない。
 */
class MarkdocTypedHandler : TypedHandlerDelegate() {
    /** 入力した文字はまだ文書に入っていないので、カーソルより前がそのまま入力位置より前になる。 */
    override fun checkAutoPopup(charTyped: Char, project: Project, editor: Editor, file: PsiFile): Result {
        if (charTyped != '/' && charTyped != '"') return Result.CONTINUE
        if (!file.isMarkdoc()) return Result.CONTINUE

        val caret = editor.caretModel.offset
        if (tagContent(editor.lineTextBefore(caret)) != null && !editor.isInCode(caret)) {
            AutoPopupController.getInstance(project).scheduleAutoPopup(editor)
        }
        return Result.CONTINUE
    }

    /** 入力した文字は文書に入った後なので、カーソルの 1 文字前が入力した `/` になる。 */
    override fun charTyped(c: Char, project: Project, editor: Editor, file: PsiFile): Result {
        if (c != '/') return Result.CONTINUE
        if (!file.isMarkdoc()) return Result.CONTINUE

        val document = editor.document
        val caret = editor.caretModel.offset
        val lineEnd = document.getLineEndOffset(document.getLineNumber(caret))
        val before = editor.lineTextBefore(caret - 1)
        val after = document.charsSequence.substring(caret, lineEnd)
        if (!shouldCloseTag(before, after)) return Result.CONTINUE
        if (editor.isInCode(caret)) return Result.CONTINUE

        document.insertString(caret, "%}")
        editor.caretModel.moveToOffset(caret + 2)
        return Result.STOP
    }

    /** このファイルが Markdoc ファイルかどうか。 */
    private fun PsiFile.isMarkdoc(): Boolean = virtualFile?.let(MarkdocFiles::isMarkdoc) == true

    /** [offset] がコードの中かどうか。 */
    private fun Editor.isInCode(offset: Int): Boolean = MarkdocTagScanner.isInCode(document.charsSequence, offset)

    /** [offset] がある行の、行頭から [offset] までの文字列。 */
    private fun Editor.lineTextBefore(offset: Int): String {
        val lineStart = document.getLineStartOffset(document.getLineNumber(offset))
        return document.charsSequence.substring(lineStart, offset)
    }

    companion object {
        /** `{%` の後に空白を挟んでタグ名が続く。 */
        private val TAG_NAME = Regex("""^\s*[\w-]+""")

        /** カーソルの直後に（空白を挟んで）`%}` がある。 */
        private val CLOSED_AFTER = Regex("""^\s*%}""")

        /**
         * `/` を入力した位置で `%}` を補うかどうか。
         *
         * 次の場合は補わない。
         * - タグ名より前の `/`（`{% /callout` のような閉じタグの書き始め）
         * - 引用符の中（属性値のパスなど）
         * - カーソルの後ろに `%}` が既にある
         *
         * @param before 同じ行の、入力した `/` より前の文字列。
         * @param after 同じ行の、カーソルより後ろの文字列。
         */
        fun shouldCloseTag(before: String, after: String): Boolean {
            val inside = tagContent(before) ?: return false
            if (!TAG_NAME.containsMatchIn(inside)) return false
            // 引用符が奇数個なら、属性値の中にいる
            if (inside.count { it == '"' } % 2 == 1) return false
            return !CLOSED_AFTER.containsMatchIn(after)
        }

        /** [before] の末尾が閉じていないタグの中なら、最後の `{%` より後ろの文字列。タグの外なら `null`。 */
        private fun tagContent(before: String): String? {
            val open = before.lastIndexOf("{%")
            if (open < 0) return null
            val inside = before.substring(open + 2)
            return inside.takeUnless { "%}" in it }
        }
    }
}
