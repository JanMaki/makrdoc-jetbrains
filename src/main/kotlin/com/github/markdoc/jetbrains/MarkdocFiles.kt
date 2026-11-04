package com.github.markdoc.jetbrains

import com.intellij.openapi.vfs.VirtualFile

/**
 * Markdoc として扱うファイルの判定。
 *
 * ファイルタイプの関連付け、言語サーバーの対象判定など、ファイルの種類を見る処理はすべてここを通す。
 */
object MarkdocFiles {
    /** Markdoc ファイルの拡張子。公式 VS Code 拡張が認識するものと同じ。 */
    private val SUFFIXES = listOf(".mdoc", ".markdoc", ".markdoc.md")

    /**
     * [file] が Markdoc ファイルかどうかを返す。
     *
     * 判定はファイル名の末尾だけで行う。`.md` は Markdoc ファイルとみなさない。
     */
    fun isMarkdoc(file: VirtualFile): Boolean {
        if (file.isDirectory) return false
        // 大文字の拡張子（例: `Guide.MDOC`）も対象にする
        val name = file.name.lowercase()
        return SUFFIXES.any { name.endsWith(it) }
    }
}
