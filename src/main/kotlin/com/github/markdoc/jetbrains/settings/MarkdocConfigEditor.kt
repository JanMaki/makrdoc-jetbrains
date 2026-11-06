package com.github.markdoc.jetbrains.settings

import com.github.markdoc.jetbrains.MarkdocConfigReader
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.ui.EditorTextField

/**
 * `markdoc.config.json` の中身を編集する JSON エディタ。
 *
 * ファイルの内容をそのまま表示し、[save] で書き戻す。ファイルがなければ雛形を表示し、
 * 内容を変えて [save] したときだけファイルを作る。
 */
class MarkdocConfigEditor(private val project: Project) {
    /** 読み込んだファイルの改行が CRLF だったか。書き戻すときに元の改行へ戻すために覚えておく。 */
    private var crlf = false

    /** エディタに最後に読み込んだ（書き込んだ）内容。LF の改行だけを持つ（ドキュメントは CRLF を扱えないため）。 */
    private var loadedText = loadText()

    /** 画面に置くエディタ。 */
    val component: EditorTextField = EditorTextField(
        EditorFactory.getInstance().createDocument(loadedText),
        project,
        FileTypeManager.getInstance().getFileTypeByExtension("json"),
        false,
        false,
    ).apply {
        setFontInheritedFromLAF(false)
        addSettingsProvider {
            it.settings.isLineNumbersShown = false
            it.settings.isFoldingOutlineShown = false
            it.setVerticalScrollbarVisible(true)
            it.setHorizontalScrollbarVisible(true)
        }
    }

    /** エディタの内容が、最後に読み込んだ（書き込んだ）内容から変わっているか。 */
    val isModified: Boolean
        get() = component.text != loadedText

    /** ファイルの現在の内容をエディタに読み直す。 */
    fun reset() {
        loadedText = loadText()
        component.text = loadedText
    }

    /**
     * エディタの内容が JSON として読めるか確かめる。
     *
     * @throws ConfigurationException 読めない場合。メッセージは設定画面にそのまま表示される。
     */
    fun validate() {
        try {
            JsonParser.parseString(component.text)
        } catch (e: JsonParseException) {
            throw ConfigurationException(e.cause?.message ?: e.message ?: "Malformed JSON", "Invalid Configuration")
        }
    }

    /**
     * 内容が変わっていれば、プロジェクトルートからの [relativePath] に書き込む。
     * ファイルや親ディレクトリがなければ作る。
     *
     * @return 書き込んだかどうか。
     */
    fun save(relativePath: String): Boolean {
        if (!isModified) return false
        val root = project.guessProjectDir() ?: return false
        val text = component.text
        val path = FileUtil.toSystemIndependentName(relativePath)
        runWriteAction {
            val file = root.findFileByRelativePath(path) ?: run {
                val dir = VfsUtil.createDirectoryIfMissing(root, path.substringBeforeLast('/', ""))
                    ?: return@runWriteAction
                dir.createChildData(this, path.substringAfterLast('/'))
            }
            VfsUtil.saveText(file, if (crlf) StringUtil.convertLineSeparators(text, "\r\n") else text)
        }
        loadedText = text
        return true
    }

    /** 設定ファイルの中身を LF の改行で返す。ファイルがなければ雛形。 */
    private fun loadText(): String {
        val raw = MarkdocConfigReader.findConfigFile(project)?.let { VfsUtilCore.loadText(it) }
        crlf = raw?.contains("\r\n") == true
        return if (raw == null) TEMPLATE else StringUtil.convertLineSeparators(raw)
    }

    private companion object {
        /** 設定ファイルがないときにエディタに出す雛形。 */
        val TEMPLATE = listOf("[", "  {", "    \"path\": \"\"", "  }", "]", "").joinToString("\n")
    }
}
