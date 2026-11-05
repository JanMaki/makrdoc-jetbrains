package com.github.markdoc.jetbrains.lsp

import com.github.markdoc.jetbrains.MarkdocTagScanner
import com.google.gson.JsonObject
import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementDecorator
import com.intellij.platform.lsp.api.customization.LspCompletionSupport
import org.eclipse.lsp4j.CompletionItem

/**
 * Markdoc 言語サーバーの補完候補を IDE の候補に変換する。
 *
 * サーバーが `data.triggerSuggest` を付けた候補（属性名）は、挿入後に補完ポップアップを開き直す。
 * `type=""` を挿入した直後は文字を入力していないため、そのままでは属性値の候補が出ない。
 *
 * コード（インラインコードとフェンスコードブロックの中身）の中では、サーバーに補完を求めない。
 */
class MarkdocCompletionSupport : LspCompletionSupport() {
    override fun shouldRunCodeCompletion(parameters: CompletionParameters): Boolean =
        !MarkdocTagScanner.isInCode(parameters.editor.document.charsSequence, parameters.offset) &&
                super.shouldRunCodeCompletion(parameters)

    override fun createLookupElement(parameters: CompletionParameters, item: CompletionItem): LookupElement? {
        val element = super.createLookupElement(parameters, item) ?: return null
        if (!item.triggersSuggest()) return element

        return LookupElementDecorator.withInsertHandler(element) { context, decorator ->
            // LSP の挿入処理（スニペットの展開を含む）を済ませてから、カーソル位置で補完を開く
            decorator.delegate.handleInsert(context)
            AutoPopupController.getInstance(context.project).scheduleAutoPopup(context.editor)
        }
    }

    /** サーバーが挿入後の補完を求めているかどうか。`data` は Gson の [JsonObject] で届く。 */
    private fun CompletionItem.triggersSuggest(): Boolean =
        (data as? JsonObject)?.get("triggerSuggest")?.takeIf { it.isJsonPrimitive }?.asBoolean == true
}
