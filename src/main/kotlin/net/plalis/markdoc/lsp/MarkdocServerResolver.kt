package net.plalis.markdoc.lsp

import com.github.markdoc.jetbrains.settings.MarkdocLocalSettings
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.Project
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

/**
 * 起動する言語サーバーのエントリーポイントを解決する。
 *
 * 設定でサーバーが指定されていればそれを使い、なければ同梱のサーバーを使う。
 * 同梱サーバーは esbuild で 1 ファイルにまとめられ、プラグインの配布ディレクトリ直下の
 * `server/` に置かれている（JAR の中ではないので、Node.js からそのまま実行できる）。
 */
object MarkdocServerResolver {
    private const val PLUGIN_ID = "com.github.markdoc.jetbrains"

    private val LOG = logger<MarkdocServerResolver>()

    /** プラグインの配布ディレクトリからの、同梱サーバーの相対パス。 */
    private const val BUNDLED_SERVER = "server/markdoc-lsp-server.js"

    /**
     * 起動するサーバーの JS ファイルを返す。見つからなければ `null`。
     *
     * 設定でパスを指定したのに、そのファイルがない場合は同梱サーバーに切り替えず `null` を返す
     * （意図と違うサーバーで黙って動かさないため）。
     */
    fun resolve(project: Project): Path? {
        val custom = MarkdocLocalSettings.getInstance(project).customServerPath
        if (custom.isEmpty()) return bundledServer()
        val path = try {
            Path.of(custom).takeIf { Files.isRegularFile(it) }
        } catch (_: InvalidPathException) {
            null
        }
        if (path == null) LOG.warn("The custom Markdoc language server was not found: $custom")
        return path
    }

    /** プラグインに同梱したサーバーを返す。配布物が壊れていてファイルがなければ `null`。 */
    fun bundledServer(): Path? {
        val pluginPath = PluginManagerCore.getPlugin(PluginId.getId(PLUGIN_ID))?.pluginPath ?: return null
        return pluginPath.resolve(BUNDLED_SERVER).takeIf { Files.isRegularFile(it) }
    }
}
