package com.github.markdoc.jetbrains.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.BoundSearchableConfigurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.dsl.builder.*

/**
 * Settings → Languages & Frameworks → Markdoc の設定画面。
 *
 * 上から、言語サーバーの選択（[MarkdocServerComboBox]）、設定ファイルのパス、設定ファイルの中身（[MarkdocConfigEditor]）を並べる。
 * 設定ファイルがなければ、中身の欄には作成時の雛形を読み取り専用で（灰色で）出し、作成ボタンを出す。
 * 設定は言語サーバーの起動時に読まれるため、変更を適用したら IDE の再起動を勧める。
 */
class MarkdocConfigurable(private val project: Project) :
    BoundSearchableConfigurable("Markdoc", "com.github.markdoc.jetbrains.settings") {

    private val settings = MarkdocSettings.getInstance(project)
    private val localSettings = MarkdocLocalSettings.getInstance(project)

    /** 画面を開いている間だけある。[createPanel] で作り、[disposeUIResources] で捨てる。 */
    private var configEditor: MarkdocConfigEditor? = null

    /** 設定ファイルのパスの入力欄。画面を開いている間だけある。 */
    private var configPathField: TextFieldWithBrowseButton? = null

    /** 設定ファイルがないときだけ見せる、作成ボタンの行。画面を開いている間だけある。 */
    private var createRow: Row? = null

    /** 設定画面のパネルを作る。 */
    override fun createPanel(): DialogPanel {
        val editor = MarkdocConfigEditor(project) { exists -> createRow?.visible(!exists) }
            .also { configEditor = it }
        val serverCombo = MarkdocServerComboBox(project)
        return panel {
            row("Language server:") {
                cell(serverCombo)
                    .align(AlignX.FILL)
                    .bind(
                        { it.path },
                        { combo, path -> combo.path = path },
                        localSettings::customServerPath.toMutableProperty(),
                    )
            }
            row("Config file:") {
                textFieldWithBrowseButton(
                    configFileDescriptor(),
                    project,
                    ::relativeToProjectRoot,
                ).bindText(settings::configPath)
                    .align(AlignX.FILL)
                    .also { configPathField = it.component }
            }
            row {
                label("Configuration:").resizableColumn()
                browserLink("See available options", LANGUAGE_SERVER_URL).align(AlignX.RIGHT)
            }
            createRow = row {
                button("Create Config File") { createConfigFile() }
            }.visible(!editor.exists)
            row {
                cell(editor.component).align(Align.FILL)
            }.resizableRow()
        }
    }

    /** 入力欄かエディタのどちらかが変わっているか。 */
    override fun isModified(): Boolean = super.isModified() || configEditor?.isModified == true

    /** 入力欄とエディタを保存済みの内容に戻す。 */
    override fun reset() {
        super.reset()
        configEditor?.reset()
    }

    /**
     * 設定を保存し、エディタの内容が変わっていれば設定ファイルに書き込む。
     * 何かが変わったときは、再起動の確認を出す。
     */
    override fun apply() {
        val editor = configEditor
        // 設定を保存してから JSON の誤りで失敗しないよう、先に確かめる
        if (editor?.isModified == true) {
            editor.validate()
            // 別のファイルから読み込んだ内容で、すでにあるファイルを上書きしないよう、設定を保存する前に確かめる
            editor.checkTarget(configPathField?.text ?: settings.configPath)
        }

        val before = localSettings.customServerPath to settings.configPath
        super.apply()
        // 空白だけのパスは既定値として保存されるので、入力欄にも反映する
        configPathField?.text = settings.configPath
        // パスの変更を保存したあとで書くので、新しいパスのファイルに書き込まれる
        val written = editor?.save(settings.configPath) == true

        if (written || before != localSettings.customServerPath to settings.configPath) askRestart()
    }

    /**
     * 入力欄のパスに雛形の中身で設定ファイルを作り、エディタで編集できるようにする。
     * 雛形はプロジェクトに合わせて書き換える前提なので、ここでは再起動を勧めず、編集して Apply したときに勧める。
     */
    private fun createConfigFile() {
        val editor = configEditor ?: return
        try {
            editor.create(configPathField?.text ?: settings.configPath)
        } catch (e: ConfigurationException) {
            Messages.showErrorDialog(project, e.messageHtml.toString(), e.title)
        }
    }

    /** 画面を閉じるとき、保持している部品を捨てる。 */
    override fun disposeUIResources() {
        configEditor = null
        configPathField = null
        createRow = null
        super.disposeUIResources()
    }

    /** IDE の再起動を勧める確認を出し、承諾されたら再起動する。 */
    private fun askRestart() {
        val restart = Messages.showYesNoDialog(
            project,
            "Restart the IDE to apply the changes.",
            "Markdoc Settings Changed",
            "Restart",
            "Later",
            Messages.getQuestionIcon(),
        ) == Messages.YES
        if (restart) ApplicationManager.getApplication().restart()
    }

    /** 設定ファイルの選択ダイアログ。パスはプロジェクトルートからの相対で持つので、ルートの外は選ばせない。 */
    private fun configFileDescriptor() =
        FileChooserDescriptorFactory.createSingleFileDescriptor("json")
            .withTitle("Select Config File")
            .apply { project.guessProjectDir()?.let { withRoots(it) } }

    /** 選んだファイルを、プロジェクトルートからの相対パスにする。 */
    private fun relativeToProjectRoot(file: VirtualFile): String =
        project.guessProjectDir()?.let { VfsUtilCore.getRelativePath(file, it) } ?: file.path

    private companion object {
        /** 設定ファイルで指定できる項目の説明がある、上流の言語サーバーのリポジトリ。 */
        const val LANGUAGE_SERVER_URL = "https://github.com/markdoc/language-server"
    }
}
