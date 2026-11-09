package net.plalis.markdoc.config

import net.plalis.markdoc.MarkdocConfigFile
import net.plalis.markdoc.MarkdocConfigReader
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction

/**
 * New メニュー（プロジェクトビューの右クリック → New など）の「Markdoc Config File」。
 *
 * サーバーは設定のパス（既定はプロジェクトルートの `markdoc.config.json`）しか読まないので、選んでいるディレクトリに
 * かかわらず、設定のパスに雛形（[MarkdocConfigFile.TEMPLATE_TEXT]）の中身で作る。設定ファイルがすでにあれば項目を出さない。
 */
class NewMarkdocConfigAction : DumbAwareAction() {
    /** [update] はファイルの有無を見るだけなので、EDT を止めないようバックグラウンドで行う。 */
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    /** 設定ファイルがないときだけ項目を出す。 */
    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible = project != null && MarkdocConfigReader.isMissing(project)
    }

    /** 設定ファイルを作ってエディタで開く。 */
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        createConfigFileAndOpen(project)
    }
}
