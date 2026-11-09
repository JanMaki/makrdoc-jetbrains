package net.plalis.markdoc.config

import com.github.markdoc.jetbrains.MarkdocConfigReader
import com.github.markdoc.jetbrains.MarkdocFiles
import com.github.markdoc.jetbrains.settings.MarkdocConfigurable
import com.github.markdoc.jetbrains.settings.MarkdocSettings
import com.intellij.ide.util.PropertiesComponent
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile

/**
 * 設定ファイル（`markdoc.config.json`）がないときに、作成を勧める通知を出す。
 *
 * 通知を出すのは次の場合。
 * - Markdoc ファイル（`.mdoc` など）を開いたとき
 * - `.md` ファイルに Markdoc のタグ（`{% … %}`）があるとき（開いたときと、編集してタグが現れたとき）
 *
 * 通知はプロジェクトごとに 1 度だけ出す。出したことを [PropertiesComponent]（`.idea/workspace.xml`）に覚え、
 * IDE を再起動しても出さない。出した後は監視も外す。
 *
 * プロジェクトを開いたときに [MarkdocConfigStartup] から [start] を呼ぶ。
 */
@Service(Service.Level.PROJECT)
class MarkdocConfigNotifier(private val project: Project) : Disposable {
    /** すでに通知を出したか。入力のたびに [PropertiesComponent] を引かないよう、ここにも持つ。 */
    private var notified = PropertiesComponent.getInstance(project).getBoolean(SHOWN_KEY)

    /** 監視（リスナー）の破棄先。通知を出したら破棄して監視をやめる。サービスの破棄でも破棄される。 */
    private val listeners = Disposer.newDisposable(this, "MarkdocConfigNotifier listeners")

    /** 開いたファイルと編集の監視を始める。すでに通知を出していれば何もしない。EDT で呼ぶ。 */
    fun start() {
        if (notified) return
        project.messageBus.connect(listeners).subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun fileOpened(source: FileEditorManager, file: VirtualFile) = onOpened(file)
            },
        )
        // ドキュメントの変更はアプリ全体のイベントなので、このプロジェクトで開いているファイルかどうかを [onChanged] で見る
        EditorFactory.getInstance().eventMulticaster.addDocumentListener(
            object : DocumentListener {
                override fun documentChanged(event: DocumentEvent) = onChanged(event)
            },
            listeners,
        )
        // 監視を始める前に開かれていたファイル（前回から開いたままのファイル）も見る
        FileEditorManager.getInstance(project).openFiles.forEach(::onOpened)
    }

    /** ファイルを開いたとき。Markdoc ファイルか、タグを含む `.md` ファイルなら通知する。 */
    private fun onOpened(file: VirtualFile) {
        if (notified || !file.isValid) return
        when {
            MarkdocFiles.isMarkdoc(file) -> notifyMissing(MARKDOC_FILE_MESSAGE)
            isMarkdown(file) -> {
                val document = FileDocumentManager.getInstance().getDocument(file) ?: return
                if (TAG.containsMatchIn(document.immutableCharSequence)) notifyMissing(MARKDOWN_FILE_MESSAGE)
            }
        }
    }

    /** ドキュメントが変わったとき。このプロジェクトで開いている `.md` ファイルの、変わった行にタグが現れたら通知する。 */
    private fun onChanged(event: DocumentEvent) {
        if (notified) return
        val document = event.document
        val file = FileDocumentManager.getInstance().getFile(document) ?: return
        if (!isMarkdown(file) || !FileEditorManager.getInstance(project).isFileOpen(file)) return
        if (TAG.containsMatchIn(changedLines(document, event))) notifyMissing(MARKDOWN_FILE_MESSAGE)
    }

    /** [event] で変わった部分を含む行のテキスト。ファイル全体を毎回走査しないよう、変わった行だけを見る。 */
    private fun changedLines(document: Document, event: DocumentEvent): CharSequence {
        val length = document.textLength
        val startLine = document.getLineNumber(minOf(event.offset, length))
        val endLine = document.getLineNumber(minOf(event.offset + event.newLength, length))
        return document.immutableCharSequence.subSequence(
            document.getLineStartOffset(startLine),
            document.getLineEndOffset(endLine),
        )
    }

    /**
     * 設定ファイルがなければ、[message] を本文にして作成を勧める通知を出す。
     * 出したらそのことを覚え、監視を外す（二度と出さない）。
     */
    private fun notifyMissing(message: String) {
        if (notified || !MarkdocConfigReader.isMissing(project)) return
        notified = true
        PropertiesComponent.getInstance(project).setValue(SHOWN_KEY, true)
        Disposer.dispose(listeners)

        val path = MarkdocSettings.getInstance(project).configPath
        NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification("$path not found", message, NotificationType.INFORMATION)
            // サジェスト型にすると、最初のアクション（作成）がボタンになり、残りはリンクのまま
            .setSuggestionType(true)
            .addAction(NotificationAction.createSimpleExpiring("Create $path") { createConfigFileAndOpen(project) })
            .addAction(NotificationAction.createSimple("Open settings") {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, MarkdocConfigurable::class.java)
            })
            .notify(project)
    }

    /** 監視は [listeners] に登録してあり、このサービスの破棄で一緒に破棄されるので、ここでは何もしない。 */
    override fun dispose() = Unit

    companion object {
        /** plugin.xml で登録した通知グループの ID。 */
        private const val NOTIFICATION_GROUP = "Markdoc"

        /** 通知を出したことを [PropertiesComponent] に覚えるキー。 */
        private const val SHOWN_KEY = "com.github.markdoc.jetbrains.configNotificationShown"

        /** Markdoc のタグ（`{% … %}`）。1 行の中で閉じているものだけを見る。 */
        private val TAG = Regex("""\{%.*?%}""")

        /** Markdoc ファイルを開いたときの本文。 */
        private const val MARKDOC_FILE_MESSAGE =
            "Creating a config file is recommended to set up your schema and content path."

        /** タグを含む `.md` ファイルのときの本文。`.md` は Markdoc として扱わないので、拡張子のことも伝える。 */
        private const val MARKDOWN_FILE_MESSAGE =
            "This file contains Markdoc tags. Creating a config file and using the .mdoc extension is recommended."

        /** [project] の通知を出すサービスを返す。 */
        fun getInstance(project: Project): MarkdocConfigNotifier = project.service()

        /** [file] が Markdoc ファイルではない `.md` ファイルかどうか（`*.markdoc.md` は Markdoc ファイル）。 */
        private fun isMarkdown(file: VirtualFile): Boolean =
            !file.isDirectory && file.extension.equals("md", ignoreCase = true) && !MarkdocFiles.isMarkdoc(file)
    }
}
