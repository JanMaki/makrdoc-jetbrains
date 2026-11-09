package net.plalis.markdoc

import net.plalis.markdoc.paths.MarkdocPathResolver
import net.plalis.markdoc.paths.MarkdocPathValue
import com.intellij.openapi.actionSystem.*

/**
 * Markdoc ファイルで、移動できるファイルパスの上にカーソルがあるときだけ、Ctrl+B を「太字の切り替え」ではなく
 * 「宣言へ移動」にする。それ以外の場所では Markdown の太字の切り替えのまま。
 *
 * Markdown プラグインの `MarkdownActionPromoter` は、Markdown ファイルタイプのファイルで太字などの書式アクションを
 * 常に優先する（無効にする拡張ポイントは Markdown 以外のファイルにしか効かない）。プロモーターは登録順に適用され、
 * 後のものほど優先されるため、`order="last"` で登録して「宣言へ移動」を先頭に戻す。
 */
class MarkdocActionPromoter : ActionPromoter {
    /** カーソルが移動できるファイルパスの上にあれば「宣言へ移動」だけを返す。それ以外は並びを変えない（空を返す）。 */
    override fun promote(actions: List<AnAction>, context: DataContext): List<AnAction> {
        val editor = context.getData(CommonDataKeys.EDITOR) ?: return emptyList()
        val project = context.getData(CommonDataKeys.PROJECT) ?: return emptyList()
        val file = context.getData(CommonDataKeys.VIRTUAL_FILE) ?: return emptyList()
        if (!MarkdocFiles.isMarkdoc(file)) return emptyList()

        val offset = editor.caretModel.offset
        val value = MarkdocPathValue.at(editor.document.charsSequence, offset) ?: return emptyList()
        // 存在しないパスでは移動先がないので、太字の切り替えのままにする
        MarkdocPathResolver.resolveAt(project, file, value, offset) ?: return emptyList()

        val manager = ActionManager.getInstance()
        return actions.filter { manager.getId(it) == IdeActions.ACTION_GOTO_DECLARATION }
    }
}
