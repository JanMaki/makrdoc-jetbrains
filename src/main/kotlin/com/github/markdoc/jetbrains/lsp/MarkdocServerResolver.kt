package com.github.markdoc.jetbrains.lsp

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId
import java.nio.file.Files
import java.nio.file.Path

/**
 * 起動する言語サーバーのエントリーポイントを解決する。
 *
 * サーバーは esbuild で 1 ファイルにまとめられ、プラグインの配布ディレクトリ直下の
 * `server/` に置かれている（JAR の中ではないので、Node.js からそのまま実行できる）。
 */
object MarkdocServerResolver {
    private const val PLUGIN_ID = "com.github.markdoc.jetbrains"

    /** プラグインの配布ディレクトリからの、同梱サーバーの相対パス。 */
    private const val BUNDLED_SERVER = "server/markdoc-lsp-server.js"

    /** 起動するサーバーの JS ファイルを返す。見つからなければ `null`。 */
    fun resolve(): Path? = bundledServer()

    /** プラグインに同梱したサーバーを返す。配布物が壊れていてファイルがなければ `null`。 */
    private fun bundledServer(): Path? {
        val pluginPath = PluginManagerCore.getPlugin(PluginId.getId(PLUGIN_ID))?.pluginPath ?: return null
        return pluginPath.resolve(BUNDLED_SERVER).takeIf { Files.isRegularFile(it) }
    }
}
