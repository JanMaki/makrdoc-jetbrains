package net.plalis.markdoc.lsp

import com.github.markdoc.jetbrains.MarkdocConfigReader
import com.github.markdoc.jetbrains.MarkdocFiles
import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.javascript.nodejs.interpreter.NodeJsInterpreterManager
import com.intellij.javascript.nodejs.interpreter.local.NodeJsLocalInterpreter
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspIntegrationProvider

private val LOG = logger<MarkdocLspIntegrationProvider>()

/**
 * Markdoc ファイルが開かれたときに、条件を満たせば言語サーバーを起動する。
 *
 * 起動条件は次のすべて。
 * - 開いたファイルが Markdoc ファイルである
 * - プロジェクトが信頼済みである
 * - ローカルの Node.js インタープリターが設定されている
 * - 同梱のサーバーが見つかる
 * - 設定ファイルがないか、正しく読める
 */
class MarkdocLspIntegrationProvider : LspIntegrationProvider {
    override fun fileOpened(
        project: Project,
        file: VirtualFile,
        clientStarter: LspIntegrationProvider.LspClientStarter
    ) {
        if (!MarkdocFiles.isMarkdoc(file)) return

        // サーバーはスキーマ読み込み時にプロジェクトの JS を実行するため、信頼されていないプロジェクトでは起動しない
        if (!TrustedProjects.isProjectTrusted(project)) return

        // WSL やリモートのインタープリターではサーバーとプロジェクトのパス表記が合わないため、ローカルのものに限る
        val interpreter = NodeJsInterpreterManager.getInstance(project).interpreter as? NodeJsLocalInterpreter ?: return

        val serverPath = MarkdocServerResolver.resolve(project) ?: run {
            LOG.warn("Markdoc language server is missing")
            return
        }

        val config = when (val result = MarkdocConfigReader.read(project)) {
            is MarkdocConfigReader.Result.Found -> result.config
            MarkdocConfigReader.Result.NotFound -> MarkdocConfigReader.defaultConfig(project)
            // 壊れた設定で起動すると誤ったスキーマで検証されるため、起動しない
            is MarkdocConfigReader.Result.Invalid -> {
                LOG.warn("Invalid ${result.file.path}: ${result.message}")
                return
            }
        }

        // プロジェクトで起動済みなら何もしない。未起動なら記述子に従って起動する
        clientStarter.ensureClientStarted(MarkdocLspClientDescriptor(project, interpreter, serverPath, config))
    }
}
