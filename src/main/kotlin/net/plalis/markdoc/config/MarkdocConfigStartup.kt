package net.plalis.markdoc.config

import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** プロジェクトを開いたときに、設定ファイルがないときの通知（[MarkdocConfigNotifier]）の監視を始める。 */
class MarkdocConfigStartup : ProjectActivity {
    override suspend fun execute(project: Project) {
        // 開いているファイルの一覧とドキュメントを扱うので EDT で始める
        withContext(Dispatchers.EDT) {
            if (!project.isDisposed) MarkdocConfigNotifier.getInstance(project).start()
        }
    }
}
