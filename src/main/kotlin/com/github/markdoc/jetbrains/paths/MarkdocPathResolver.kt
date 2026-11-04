package com.github.markdoc.jetbrains.paths

import com.github.markdoc.jetbrains.MarkdocConfigReader
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.VirtualFile

/**
 * 属性値のファイルパスを、プロジェクト内のファイルやディレクトリに結び付ける。
 *
 * 基準のディレクトリは次のとおり。
 * - `partial` の `file`：コンテンツのディレクトリ（`markdoc.config.json` の `path`。なければプロジェクトルート）
 * - それ以外で `/` から始まるパス：プロジェクトルート
 * - それ以外：パスを書いたファイルのディレクトリ
 */
object MarkdocPathResolver {
    /** [file] に書かれた [value] の基準のディレクトリ。 */
    fun baseDirectory(project: Project, file: VirtualFile, value: MarkdocPathValue): VirtualFile? = when {
        value.isPartial -> contentRoot(project)
        value.text.startsWith("/") -> project.guessProjectDir()
        else -> file.parent
    }

    /**
     * [value] のうち、[offset] がある区切りの末尾までのパスが指すファイルかディレクトリ。存在しなければ `null`。
     *
     * `partials/footer.mdoc` の `partials` の上ならディレクトリ `partials`、`footer.mdoc` の上ならファイルを返す。
     *
     * @param file パスを書いたファイル。
     * @param offset 文書内のオフセット（[value] の範囲内）。
     */
    fun resolveAt(project: Project, file: VirtualFile, value: MarkdocPathValue, offset: Int): VirtualFile? {
        val base = baseDirectory(project, file, value) ?: return null
        val segmentEnd = value.text.indexOf('/', offset - value.start).takeIf { it >= 0 } ?: value.text.length
        val path = value.text.substring(0, segmentEnd).trim('/')
        if (path.isEmpty()) return null
        return base.findFileByRelativePath(path)
    }

    /** コンテンツのディレクトリ。設定ファイルがないか読めなければプロジェクトルート。 */
    fun contentRoot(project: Project): VirtualFile? {
        val root = project.guessProjectDir() ?: return null
        val config = (MarkdocConfigReader.read(project) as? MarkdocConfigReader.Result.Found)?.config ?: return root
        val path = config.get("path")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty().trim('/', '\\')
        if (path.isEmpty()) return root
        return root.findFileByRelativePath(path.replace('\\', '/')) ?: root
    }
}
