package net.plalis.markdoc.lsp

import net.plalis.markdoc.MarkdocConfigReader
import net.plalis.markdoc.MarkdocFiles
import com.google.gson.JsonObject
import com.intellij.execution.process.BaseProcessHandler
import com.intellij.javascript.nodejs.execution.NodeTargetRun
import com.intellij.javascript.nodejs.execution.NodeTargetRunOptions
import com.intellij.javascript.nodejs.interpreter.local.NodeJsLocalInterpreter
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.ProjectWideLspClientDescriptor
import com.intellij.platform.lsp.api.customization.LspCompletionCustomizer
import com.intellij.platform.lsp.api.customization.LspCustomization
import java.nio.file.Path

/**
 * プロジェクトに 1 つ起動する Markdoc 言語サーバーの記述子。
 *
 * サーバーは `node <serverPath> --stdio` で起動し、STDIO 上の JSON-RPC で通信する。
 *
 * @param interpreter サーバーを実行する Node.js。
 * @param serverPath サーバーの JS ファイル。
 * @param config サーバーに渡す設定エントリ（[MarkdocConfigReader] が作ったもの）。
 */
class MarkdocLspClientDescriptor(
    project: Project,
    private val interpreter: NodeJsLocalInterpreter,
    private val serverPath: Path,
    private val config: JsonObject,
) : ProjectWideLspClientDescriptor(project, "Markdoc") {

    override fun isSupportedFile(file: VirtualFile): Boolean = MarkdocFiles.isMarkdoc(file)

    /**
     * Node.js の実行設定（実行ファイル、環境変数など）を IDE のインタープリター設定から引き継いでサーバーを起動する。
     */
    override fun startServerProcess(): BaseProcessHandler<*> {
        // デバッガーは付けず、ANSI カラーも使わない（出力は JSON-RPC だけ）
        val targetRun = NodeTargetRun(interpreter, project, null, NodeTargetRunOptions.of(false))
        targetRun.commandLineBuilder.apply {
            // スキーマのパスなどはプロジェクトルートからの相対パスで書かれるため、作業ディレクトリをそこに合わせる
            config.get("root")?.asString?.takeIf { it.isNotEmpty() }?.let {
                setWorkingDirectory(targetRun.path(it))
            }
            addParameter(targetRun.path(serverPath))
            addParameter("--stdio")
            charset = Charsets.UTF_8
        }
        return targetRun.startProcessEx().processHandler
    }

    /**
     * サーバーが返した URI に対応するファイルを探す。
     *
     * サーバーは定義ジャンプ・参照・ドキュメントリンクの対象を URI ではなくファイルパス
     * （例: `D:\docs\partials\footer.mdoc`）で返すため、スキームのない値はローカルのパスとして扱う。
     */
    override fun findFileByUri(fileUri: String): VirtualFile? {
        if (!fileUri.contains("://")) {
            return findLocalFileByPath(FileUtil.toSystemIndependentName(fileUri))
        }
        return super.findFileByUri(fileUri)
    }

    /** サーバーは言語 ID で処理を分けない（documentSelector が null）ので、識別用の値を送る。 */
    override fun getLanguageId(file: VirtualFile): String = "markdoc"

    override fun createInitializationOptions(): Any = MarkdocConfigReader.initializationOptions(config)

    /** 補完候補の変換だけを差し替え、ほかの LSP 機能は IDE 標準の動作を使う。 */
    override val lspCustomization: LspCustomization = object : LspCustomization() {
        override val completionCustomizer: LspCompletionCustomizer = MarkdocCompletionSupport()
    }
}
