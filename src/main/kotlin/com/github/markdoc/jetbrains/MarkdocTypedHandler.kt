package com.github.markdoc.jetbrains

import com.intellij.codeInsight.editorActions.TypedHandlerDelegate
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile

/**
 * Markdoc のタグの中で `/` を入力したときに、続けて `%}` を補って自己終了タグ（`/%}`）を閉じる。
 *
 * 次の場合は補わない。
 * - タグ名より前の `/`（`{% /callout` のような閉じタグの書き始め）
 * - 引用符の中（属性値のパスなど）
 * - カーソルの後ろに `%}` が既にある
 */
class MarkdocTypedHandler : TypedHandlerDelegate() {
    override fun charTyped(c: Char, project: Project, editor: Editor, file: PsiFile): Result {
        if (c != '/') return Result.CONTINUE
        val virtualFile = file.virtualFile ?: return Result.CONTINUE
        if (!MarkdocFiles.isMarkdoc(virtualFile)) return Result.CONTINUE

        val document = editor.document
        val caret = editor.caretModel.offset
        val line = document.getLineNumber(caret)
        val lineStart = document.getLineStartOffset(line)
        val lineEnd = document.getLineEndOffset(line)
        val chars = document.charsSequence
        // 入力した `/` より前と、カーソルより後ろ（同じ行の中だけを見る）
        val before = chars.subSequence(lineStart, caret - 1).toString()
        val after = chars.subSequence(caret, lineEnd).toString()

        if (!shouldCloseTag(before, after)) return Result.CONTINUE

        document.insertString(caret, "%}")
        editor.caretModel.moveToOffset(caret + 2)
        return Result.STOP
    }

    companion object {
        /** `{%` の後に空白を挟んでタグ名が続く。 */
        private val TAG_NAME = Regex("""^\s*[\w-]+""")

        /** カーソルの直後に（空白を挟んで）`%}` がある。 */
        private val CLOSED_AFTER = Regex("""^\s*%}""")

        /**
         * `/` を入力した位置で `%}` を補うかどうか。
         *
         * @param before 同じ行の、入力した `/` より前の文字列。
         * @param after 同じ行の、カーソルより後ろの文字列。
         */
        fun shouldCloseTag(before: String, after: String): Boolean {
            val open = before.lastIndexOf("{%")
            if (open < 0) return false
            val inside = before.substring(open + 2)
            // 最後の `{%` が既に閉じていれば、タグの外にいる
            if ("%}" in inside) return false
            if (!TAG_NAME.containsMatchIn(inside)) return false
            // 引用符が奇数個なら、属性値の中にいる
            if (inside.count { it == '"' } % 2 == 1) return false
            return !CLOSED_AFTER.containsMatchIn(after)
        }
    }
}
