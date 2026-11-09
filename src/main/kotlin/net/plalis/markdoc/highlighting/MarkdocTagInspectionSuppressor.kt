package net.plalis.markdoc.highlighting

import com.intellij.codeInspection.InspectionSuppressor
import com.intellij.codeInspection.SuppressQuickFix
import com.intellij.psi.PsiElement

/**
 * Markdoc のタグの中（`{%` から `%}` まで）では、Markdown プラグインのインスペクションを止める。
 *
 * `$a["key"]` や `$items[0]["id"]` の `[…][…]` を Markdown が参照リンクとして読み、「Cannot resolve link label」
 * （`MarkdownUnresolvedLinkLabel`）などを報告するのを防ぐ。Markdoc はタグの中をリンクとして読まない。
 * ID が `Markdown` で始まらないインスペクションには影響しない。
 */
class MarkdocTagInspectionSuppressor : InspectionSuppressor {
    override fun isSuppressedFor(element: PsiElement, toolId: String): Boolean {
        if (!toolId.startsWith(MARKDOWN_TOOL_PREFIX)) return false
        val file = element.containingFile ?: return false
        val range = element.textRange ?: return false
        return MarkdocTagTokens.isInTag(file, range.startOffset, range.endOffset)
    }

    /** 抑制はタグの中であることだけで決まるので、抑制用のコメントを挿入する操作は出さない。 */
    override fun getSuppressActions(element: PsiElement?, toolId: String): Array<SuppressQuickFix> =
        SuppressQuickFix.EMPTY_ARRAY

    private companion object {
        /** Markdown プラグインのインスペクションの ID の接頭辞。 */
        const val MARKDOWN_TOOL_PREFIX = "Markdown"
    }
}
