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
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VFileProperty
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.EditorTextField
import java.io.IOException
import java.nio.file.InvalidPathException
import java.nio.file.Path

/**
 * `markdoc.config.json` の中身を編集する JSON エディタ。
 *
 * ファイルの内容をそのまま表示し、[save] で書き戻す。ファイルがなければ雛形を表示し、
 * 内容を変えて [save] したときだけファイルを作る。
 */
class MarkdocConfigEditor(private val project: Project) {
    /** 読み込んだファイルの改行が CRLF だったか。書き戻すときに元の改行へ戻すために覚えておく。 */
    private var crlf = false

    /** エディタの内容を最後に読み込んだ（書き込んだ）設定ファイルの、プロジェクトルートからの相対パス。 */
    private var loadedPath = ""

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
        checkInsideRoot(path)
        if (path == loadedPath) return
        val exists = project.guessProjectDir()?.findFileByRelativePath(path) != null
        if (exists) {
            throw ConfigurationException(
                "$path already exists. The editor holds the content of $loadedPath, so it would be overwritten.",
                "Config File Already Exists",
            )
        }
    }

    /**
     * [path] がプロジェクトルートの中のファイルを指すか確かめる。`..` や絶対パスでルートの外に出る場合は書き込めない。
     *
     * @throws ConfigurationException ルートの外（またはルートそのもの）を指す場合。
     */
    private fun checkInsideRoot(path: String) {
        val root = project.guessProjectDir() ?: return
        val rootPath = Path.of(root.path).normalize()
        val target = try {
            rootPath.resolve(path).normalize()
        } catch (_: InvalidPathException) {
            null
        }
        if (target == null || target == rootPath || !target.startsWith(rootPath)) {
            throw ConfigurationException(
                "$path is outside the project root.",
                "Invalid Config File Path",
            )
        }
        // 字句上はルートの中でも、途中のディレクトリやファイルがシンボリックリンクだとルートの外へ書き込めてしまう。
        // すでにある部分を VFS でたどり、シンボリックリンクが含まれていれば書き込まない。
        var current: VirtualFile = root
        for (name in rootPath.relativize(target).map { it.toString() }) {
            current = current.findChild(name) ?: break
            if (current.`is`(VFileProperty.SYMLINK)) {
                throw ConfigurationException(
                    "$path goes through a symbolic link, which may point outside the project root.",
                    "Invalid Config File Path",
                )
            }
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
            write(root, path, text)
        } catch (e: IOException) {
            throw ConfigurationException("Failed to write $path: ${e.message}", "Cannot Save Configuration")
        }
        loadedText = text
        loadedPath = path
        return true
    }

    /** [root] からの [path] に [text] を書き込む。ファイルや親ディレクトリがなければ作る。 */
    private fun write(root: VirtualFile, path: String, text: String) {
        runWriteAction {
            val file = root.findFileByRelativePath(path) ?: run {
                val dir = VfsUtil.createDirectoryIfMissing(root, path.substringBeforeLast('/', ""))
                    ?: return@runWriteAction
                dir.createChildData(this, path.substringAfterLast('/'))
            }
            VfsUtil.saveText(file, if (crlf) StringUtil.convertLineSeparators(text, "\r\n") else text)
        }
    }

    /** 設定ファイルの中身を LF の改行で返す。ファイルがなければ雛形。読み込んだパスは [loadedPath] に覚える。 */
    private fun loadText(): String {
        loadedPath = MarkdocSettings.getInstance(project).configPath
        val raw = MarkdocConfigReader.findConfigFile(project)?.let { VfsUtilCore.loadText(it) }
        crlf = raw?.contains("\r\n") == true
        return if (raw == null) TEMPLATE else StringUtil.convertLineSeparators(raw)
    }

    private companion object {
        /** 設定ファイルがないときにエディタに出す雛形。 */
        val TEMPLATE = listOf("[", "  {", "    \"path\": \"\"", "  }", "]", "").joinToString("\n")
    }
}
