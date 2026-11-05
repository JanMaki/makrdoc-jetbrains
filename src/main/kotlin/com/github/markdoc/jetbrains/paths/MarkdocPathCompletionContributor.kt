package com.github.markdoc.jetbrains.paths

import com.github.markdoc.jetbrains.MarkdocFiles
import com.github.markdoc.jetbrains.MarkdocTagScanner
import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.PlatformIcons

/**
 * 属性値のファイルパスを補完する。
 *
 * 最後の `/` より後ろを入力中の名前とみなし、その手前のパスが指すディレクトリの中身を候補に出す。
 * ディレクトリを選ぶと `/` を付けて、続けて中身の候補を出す。
 * `partial` の `file` では Markdoc ファイルとディレクトリだけを出す。
 */
class MarkdocPathCompletionContributor : CompletionContributor() {
    /** カーソルがファイルパスの属性値の中にあれば、入力済みのディレクトリにあるファイルとディレクトリを候補に出す。 */
    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        val psiFile = parameters.originalFile
        val file = psiFile.virtualFile ?: return
        if (!MarkdocFiles.isMarkdoc(file)) return

        val offset = parameters.offset
        val text = parameters.editor.document.charsSequence
        if (MarkdocTagScanner.isInCode(text, offset)) return
        val value = MarkdocPathValue.at(text, offset) ?: return
        val typed = value.text.substring(0, offset - value.start)
        val slash = typed.lastIndexOf('/')
        val prefix = typed.substring(slash + 1)

        val base = MarkdocPathResolver.baseDirectory(psiFile.project, file, value) ?: return
        val directoryPath = typed.substring(0, slash + 1).trim('/')
        val directory = if (directoryPath.isEmpty()) base else base.findFileByRelativePath(directoryPath) ?: return
        if (!directory.isDirectory) return

        val paths = result.withPrefixMatcher(prefix)
        for (child in directory.children) {
            if (child.name.startsWith(".")) continue
            if (child == file) continue
            if (value.isPartial && !child.isDirectory && !MarkdocFiles.isMarkdoc(child)) continue
            paths.addElement(lookupElement(child))
        }
        // パスの中では、言語サーバーなどほかの補完の候補は役に立たない
        paths.stopHere()
    }

    /** [child] の補完候補。ディレクトリは末尾に `/` を付ける。 */
    private fun lookupElement(child: VirtualFile): LookupElementBuilder =
        if (child.isDirectory) {
            LookupElementBuilder.create(child, child.name)
                .withIcon(PlatformIcons.FOLDER_ICON)
                .withTailText("/", true)
                .withInsertHandler { context, _ ->
                    val editor = context.editor
                    val caret = editor.caretModel.offset
                    if (editor.document.charsSequence.getOrNull(caret) != '/') {
                        editor.document.insertString(caret, "/")
                    }
                    editor.caretModel.moveToOffset(caret + 1)
                    AutoPopupController.getInstance(context.project).scheduleAutoPopup(editor)
                }
        } else {
            LookupElementBuilder.create(child, child.name).withIcon(child.fileType.icon)
        }
}
