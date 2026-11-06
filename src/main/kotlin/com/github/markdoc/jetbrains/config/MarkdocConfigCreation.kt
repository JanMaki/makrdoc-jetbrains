package com.github.markdoc.jetbrains.config

import com.github.markdoc.jetbrains.MarkdocConfigFile
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile

/**
 * 設定のパスに雛形（[MarkdocConfigFile.TEMPLATE_TEXT]）の中身で設定ファイルを作り、エディタで開く。
 * 作れなければ（すでにある、ルートの外を指すなど）エラーのダイアログを出す。
 *
 * New メニュー（[NewMarkdocConfigAction]）と通知（[MarkdocConfigNotifier]）の作成ボタンから、EDT で呼ぶ。
 * 雛形は書き換える前提なので、サーバーの再起動は勧めない（書き換えた後の再起動は利用者に任せる）。
 *
 * @return 作ったファイル。作れなければ `null`。
 */
fun createConfigFileAndOpen(project: Project): VirtualFile? {
    val file = try {
        MarkdocConfigFile.create(project)
    } catch (e: ConfigurationException) {
        Messages.showErrorDialog(project, e.messageHtml.toString(), e.title)
        return null
    }
    FileEditorManager.getInstance(project).openFile(file, true)
    return file
}
