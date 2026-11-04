package com.github.markdoc.jetbrains.paths

import com.github.markdoc.jetbrains.MarkdocFiles
import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager

/**
 * 属性値のファイルパスから、そのファイルへ移動する（Ctrl+B、Ctrl+クリック）。
 *
 * ほかの言語のパスと同じく区切りごとに移動先が変わり、`partials/footer.mdoc` の `partials` の上ではディレクトリへ移動する。
 */
class MarkdocPathGotoDeclarationHandler : GotoDeclarationHandler {
    override fun getGotoDeclarationTargets(sourceElement: PsiElement?, offset: Int, editor: Editor): Array<PsiElement>? {
        val psiFile = sourceElement?.containingFile ?: return null
        val file = psiFile.virtualFile ?: return null
        if (!MarkdocFiles.isMarkdoc(file)) return null

        val value = MarkdocPathValue.at(editor.document.charsSequence, offset) ?: return null
        val target = MarkdocPathResolver.resolveAt(psiFile.project, file, value, offset) ?: return null

        val psiManager = PsiManager.getInstance(psiFile.project)
        val element = if (target.isDirectory) psiManager.findDirectory(target) else psiManager.findFile(target)
        return element?.let { arrayOf(it) }
    }
}
