package com.github.markdoc.jetbrains

import com.github.markdoc.jetbrains.settings.MarkdocSettings
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VFileProperty
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import java.io.IOException
import java.nio.file.InvalidPathException
import java.nio.file.Path

/**
 * `markdoc.config.json` の雛形、作成、書き込み。
 *
 * 作成（[create]）は、設定画面の作成ボタン、New メニュー、設定ファイルがないときの通知から使う。
 * 書き込み（[write]）とパスの検査（[checkInsideRoot]）は、設定画面のエディタの保存でも使う。
 * 読み込みは [MarkdocConfigReader] が行う。
 */
object MarkdocConfigFile {
    /**
     * 設定ファイルを作るときの中身（末尾は改行）。公式の言語サーバーの README（Configuration quickstart）にある例と同じ。
     * 設定画面でファイルがないときにも、これを読み取り専用で見せる。
     *
     * `path` やスキーマのパスは例なので、利用者のプロジェクトに合わせて書き換えてもらう前提。
     * そのため、ファイルがないときにサーバーへ渡す設定（[MarkdocConfigReader.defaultConfig]）にはこれを使わない。
     */
    val TEMPLATE_TEXT = """
        [
          {
            "id": "my-site",
            "path": "docs/content",
            "schema": {
              "path": "docs/dist/schema.js",
              "type": "node",
              "property": "default",
              "watch": true
            },
            "routing": {
              "frontmatter": "route"
            }
          }
        ]
    """.trimIndent() + "\n"

    /**
     * プロジェクトルートからの [relativePath]（既定は設定のパス）に、[TEMPLATE_TEXT] の中身で設定ファイルを作る。
     *
     * @return 作ったファイル。
     * @throws ConfigurationException 絶対パスやルートの外を指す場合、すでにファイルがある場合、書き込みに失敗した場合。
     */
    fun create(project: Project, relativePath: String = MarkdocSettings.getInstance(project).configPath): VirtualFile {
        val root = project.guessProjectDir()
            ?: throw ConfigurationException("The project has no root directory.", "Cannot Create Config File")
        val path = MarkdocSettings.normalizePath(relativePath)
        checkInsideRoot(project, path)
        if (root.findFileByRelativePath(path) != null) {
            throw ConfigurationException("$path already exists.", "Config File Already Exists")
        }
        try {
            return write(root, path, TEMPLATE_TEXT)
        } catch (e: IOException) {
            throw ConfigurationException("Failed to write $path: ${e.message}", "Cannot Create Config File")
        }
    }

    /**
     * [root] からの [path] に [text] をそのまま（改行を変えずに）書き込む。ファイルや親ディレクトリがなければ作る。
     * 書き込みアクションの中で実行するので、EDT から呼ぶ。
     *
     * [path] は [MarkdocSettings.normalizePath] で整え、[checkInsideRoot] で確かめたものを渡す
     * （`./` や `..` が残っていると、`.` や `..` という名前のディレクトリを作ろうとしてしまう）。
     *
     * @return 書き込んだファイル。
     * @throws IOException ファイルや親ディレクトリを作れない場合、書き込めない場合。
     */
    fun write(root: VirtualFile, path: String, text: String): VirtualFile = runWriteAction {
        val file = root.findFileByRelativePath(path) ?: run {
            val dir = VfsUtil.createDirectoryIfMissing(root, path.substringBeforeLast('/', ""))
                ?: throw IOException("Cannot create the parent directory")
            dir.createChildData(this, path.substringAfterLast('/'))
        }
        VfsUtil.saveText(file, text)
        file
    }

    /**
     * [path] がプロジェクトルートの中のファイルを指すか確かめる。`..` でルートの外に出る場合は書き込めない。
     * 絶対パスは、ルートの中を指していても書き込めない（[write] はルートからの相対パスとして扱うため、
     * `/home/user/project/x` がルートの下の `home/user/project/x` になってしまう）。
     * プロジェクトルートがなければ、絶対パスの検査だけ行う（書き込む側でルートがないことを扱う）。
     *
     * @throws ConfigurationException 絶対パスの場合、ルートの外（またはルートそのもの）を指す場合、シンボリックリンクを通る場合。
     */
    fun checkInsideRoot(project: Project, path: String) {
        if (path.startsWith("/") || FileUtil.isAbsolute(path)) {
            throw ConfigurationException(
                "$path is an absolute path. Specify a path relative to the project root.",
                "Invalid Config File Path",
            )
        }
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
}
