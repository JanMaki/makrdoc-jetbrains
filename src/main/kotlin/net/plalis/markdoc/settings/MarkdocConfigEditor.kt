package net.plalis.markdoc.settings

import net.plalis.markdoc.MarkdocConfigFile
import net.plalis.markdoc.MarkdocConfigReader
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.ex.util.EmptyEditorHighlighter
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.ui.ColorUtil
import com.intellij.ui.EditorTextField
import com.intellij.util.ui.UIUtil
import java.awt.Font
import java.io.IOException

/**
 * `markdoc.config.json` の中身を編集する JSON エディタ。
 *
 * ファイルの内容をそのまま表示し、[save] で書き戻す。ファイルがなければ作成時の雛形（[MarkdocConfigFile.TEMPLATE_TEXT]）を
 * 読み取り専用で表示する。編集できないとわかるよう、色分けを外した薄い文字、パネルの背景色、無効な枠にする。
 * [create] で雛形の中身のファイルを作ると、通常の表示に戻って編集できるようになる。
 *
 * @param onExistenceChanged ファイルの有無（[exists]）を読み直したときに、その値で呼ばれる。作成ボタンの表示の切り替えに使う。
 */
class MarkdocConfigEditor(
    private val project: Project,
    private val onExistenceChanged: (Boolean) -> Unit = {},
) {
    /** 読み込んだファイルの改行が CRLF だったか。書き戻すときに元の改行へ戻すために覚えておく。 */
    private var crlf = false

    /** エディタの内容を最後に読み込んだ（書き込んだ）設定ファイルの、プロジェクトルートからの相対パス。 */
    private var loadedPath = ""

    /**
     * 最後に読み込んだときに設定ファイルがあったか。なければエディタは読み取り専用。
     * [loadText] が値を設定するので、[loadedText] より前に宣言する（後ろに置くと、読み込んだ値が初期値で上書きされる）。
     */
    var exists = false
        private set

    /** エディタに最後に読み込んだ（書き込んだ）内容。LF の改行だけを持つ（ドキュメントは CRLF を扱えないため）。 */
    private var loadedText = loadText()

    /** JSON のファイルタイプ。エディタの作成と、ファイルがあるときの色分けに使う。 */
    private val jsonType = FileTypeManager.getInstance().getFileTypeByExtension("json")

    /** 画面に置くエディタ。 */
    val component: EditorTextField = EditorTextField(
        EditorFactory.getInstance().createDocument(loadedText),
        project,
        jsonType,
        false,
        false,
    ).apply {
        setFontInheritedFromLAF(false)
        addSettingsProvider {
            it.settings.isLineNumbersShown = false
            it.settings.isFoldingOutlineShown = false
            it.setVerticalScrollbarVisible(true)
            it.setHorizontalScrollbarVisible(true)
            // 中のエディタは画面に置かれたとき（や作り直されたとき）に作られるので、そのたびに見た目を合わせる
            applyAppearance(it)
        }
        isViewer = !exists
    }

    /** エディタの内容が、最後に読み込んだ（書き込んだ）内容から変わっているか。 */
    val isModified: Boolean
        get() = component.text != loadedText

    /** ファイルの現在の内容をエディタに読み直す。 */
    fun reset() {
        loadedText = loadText()
        component.text = loadedText
        updateExistence()
    }

    /**
     * プロジェクトルートからの [relativePath] に雛形の中身で設定ファイルを作り、エディタに読み込んで編集できるようにする。
     *
     * @throws ConfigurationException ルートの外を指す場合、すでにファイルがある場合、書き込みに失敗した場合。
     */
    fun create(relativePath: String) {
        val file = MarkdocConfigFile.create(project, relativePath)
        loadedPath = MarkdocSettings.normalizePath(relativePath)
        crlf = false
        loadedText = StringUtil.convertLineSeparators(VfsUtilCore.loadText(file))
        exists = true
        component.text = loadedText
        updateExistence()
    }

    /** ファイルの有無（[exists]）に合わせて、エディタの読み取り専用と見た目を切り替え、[onExistenceChanged] を呼ぶ。 */
    private fun updateExistence() {
        component.isViewer = !exists
        component.editor?.let { applyAppearance(it as EditorEx) }
        onExistenceChanged(exists)
    }

    /**
     * [editor] の見た目をファイルの有無に合わせる。
     *
     * ファイルがなければ、色分けを外して文字を薄くし、背景をパネルの色にし、枠を無効にし、カーソル行を強調しない。
     * ファイルがあれば、JSON の色分けと既定の背景・枠に戻す。
     */
    private fun applyAppearance(editor: EditorEx) {
        // 有効・無効の切り替えでエディタが作り直されてここへ戻ってきても繰り返さないよう、変わるときだけ設定する
        if (component.isEnabled != exists) component.isEnabled = exists
        editor.settings.isCaretRowShown = exists
        if (exists) {
            editor.highlighter = EditorHighlighterFactory.getInstance().createEditorHighlighter(project, jsonType)
            editor.backgroundColor = editor.colorsScheme.defaultBackground
        } else {
            val background = UIUtil.getPanelBackground()
            val foreground = ColorUtil.mix(UIUtil.getLabelDisabledForeground(), background, DISABLED_TEXT_FADE)
            editor.highlighter = EmptyEditorHighlighter(TextAttributes(foreground, null, null, null, Font.PLAIN))
            editor.backgroundColor = background
        }
    }

    /**
     * エディタの内容が JSON として読めるか確かめる。
     *
     * @throws ConfigurationException 読めない場合。メッセージは設定画面にそのまま表示される。
     */
    fun validate() {
        val element = try {
            JsonParser.parseString(component.text)
        } catch (e: JsonParseException) {
            throw ConfigurationException(e.cause?.message ?: e.message ?: "Malformed JSON", "Invalid Configuration")
        }
        // 読み込み側（MarkdocConfigReader.read）が受け付ける形と同じかどうか
        if (MarkdocConfigReader.firstEntry(element) == null) {
            throw ConfigurationException(MarkdocConfigReader.SHAPE_ERROR, "Invalid Configuration")
        }
    }

    /**
     * 内容が変わっているとき、[relativePath] に書き込んでよいかを確かめる。
     * 別のファイルから読み込んだ内容で、すでにある別のファイルを上書きしてしまう場合は書き込めない。
     *
     * @throws ConfigurationException プロジェクトルートの外を指す場合や、既存の別のファイルを上書きしてしまう場合。
     */
    fun checkTarget(relativePath: String) {
        if (!isModified) return
        val path = MarkdocSettings.normalizePath(relativePath)
        // 読み込んだときと同じパスでも、ルートの外へは書き込まないよう先に確かめる
        MarkdocConfigFile.checkInsideRoot(project, path)
        if (path == loadedPath) return
        val occupied = project.guessProjectDir()?.findFileByRelativePath(path) != null
        if (occupied) {
            throw ConfigurationException(
                "$path already exists. The editor holds the content of $loadedPath, so it would be overwritten.",
                "Config File Already Exists",
            )
        }
    }

    /**
     * 内容が変わっていれば、プロジェクトルートからの [relativePath] に書き込む。
     * ファイルや親ディレクトリがなければ作る。
     *
     * @return 書き込んだかどうか。
     * @throws ConfigurationException 既存の別のファイルを上書きしてしまう場合（[checkTarget]）や、書き込みに失敗した場合。
     */
    fun save(relativePath: String): Boolean {
        if (!isModified) return false
        checkTarget(relativePath)
        val root = project.guessProjectDir() ?: return false
        val text = component.text
        val path = MarkdocSettings.normalizePath(relativePath)
        try {
            MarkdocConfigFile.write(root, path, if (crlf) StringUtil.convertLineSeparators(text, "\r\n") else text)
        } catch (e: IOException) {
            throw ConfigurationException("Failed to write $path: ${e.message}", "Cannot Save Configuration")
        }
        loadedText = text
        loadedPath = path
        return true
    }

    /**
     * 設定ファイルの中身を LF の改行で返す。ファイルがなければ作成時の雛形。
     * 読み込んだパスは [loadedPath] に、ファイルの有無は [exists] に覚える。
     */
    private fun loadText(): String {
        loadedPath = MarkdocSettings.getInstance(project).configPath
        val raw = MarkdocConfigReader.findConfigFile(project)?.let { VfsUtilCore.loadText(it) }
        exists = raw != null
        crlf = raw?.contains("\r\n") == true
        return if (raw == null) MarkdocConfigFile.TEMPLATE_TEXT else StringUtil.convertLineSeparators(raw)
    }

    private companion object {
        /**
         * ファイルがないときの文字色を、無効なラベルの色から背景の色へ寄せる割合（0 で無効なラベルの色、1 で背景の色）。
         * 無効なラベルの色だけでは通常の文字と見分けにくいので、さらに薄くする。
         */
        const val DISABLED_TEXT_FADE = 0.45
    }
}
