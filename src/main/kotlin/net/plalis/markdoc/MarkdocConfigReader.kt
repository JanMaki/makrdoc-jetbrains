package net.plalis.markdoc

import net.plalis.markdoc.settings.MarkdocSettings
import com.google.gson.*
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * `markdoc.config.json` を読み、言語サーバーに渡す設定を作る。
 *
 * 設定ファイルの形式は `@markdoc/language-server` の README に従う。
 * ファイルはサイトごとの設定オブジェクトの配列で、各オブジェクトはサーバーの `Config` 型
 * （`path`、`schema`、`routing` など）に対応する。
 */
object MarkdocConfigReader {
    /** [read] の結果。 */
    sealed interface Result {
        /** 設定ファイルがない。 */
        data object NotFound : Result

        /**
         * 設定ファイルはあるが、JSON として読めないか、形式が違う。
         *
         * @property file 読み込もうとした設定ファイル。
         * @property message 利用者に見せる原因の説明。
         */
        data class Invalid(val file: VirtualFile, val message: String) : Result

        /**
         * 設定ファイルを読めた。
         *
         * @property file 読み込んだ設定ファイル。
         * @property config サーバーに渡す設定エントリ。`root` はプロジェクトルートの絶対パスに置き換え済み。
         */
        data class Found(val file: VirtualFile, val config: JsonObject) : Result
    }

    /** 設定（[MarkdocSettings.configPath]）で指定された、プロジェクトルートからの設定ファイルを返す。なければ `null`。 */
    fun findConfigFile(project: Project): VirtualFile? {
        val root = project.guessProjectDir() ?: return null
        val file = root.findFileByRelativePath(MarkdocSettings.getInstance(project).configPath) ?: return null
        // ディレクトリや、`..` でルートの外に出たファイルは設定ファイルとして扱わない
        return file.takeIf { !it.isDirectory && VfsUtilCore.isAncestor(root, it, true) }
    }

    /**
     * [project] の設定ファイルがないかどうか。作成を勧める通知や New メニューの項目を出すかの判定に使う。
     * プロジェクトルートがない場合は作れないので偽を返す。
     */
    fun isMissing(project: Project): Boolean = projectRoot(project) != null && findConfigFile(project) == null

    /** 形式が違うときのエラーメッセージ。 */
    const val SHAPE_ERROR = "Expected an array of configuration objects"

    /** 設定ファイルの [element] から使うエントリ（配列なら先頭、オブジェクトならそれ自身）を返す。形式が違えば `null`。 */
    fun firstEntry(element: JsonElement): JsonObject? = when (element) {
        is JsonArray -> element.firstOrNull()
        else -> element
    } as? JsonObject

    /**
     * [project] の設定ファイルを読み、サーバーに渡す設定エントリを返す。
     *
     * 配列に複数のエントリがある場合は先頭のエントリだけを使う。
     * 配列ではなく単一のオブジェクトが書かれている場合は、それを 1 件のエントリとして扱う。
     */
    fun read(project: Project): Result {
        val root = projectRoot(project) ?: return Result.NotFound
        val file = findConfigFile(project) ?: return Result.NotFound
        val element = try {
            JsonParser.parseString(loadText(file))
        } catch (e: JsonParseException) {
            // Gson は行・列を含む詳細を cause 側に持つことが多い
            return Result.Invalid(file, e.cause?.message ?: e.message ?: "Malformed JSON")
        }
        val entry = firstEntry(element)
            ?: return Result.Invalid(file, SHAPE_ERROR)
        // 読み込んだ JSON を書き換えないように複製してから root を埋める
        val config = entry.deepCopy()
        config.addProperty("root", root)
        // サーバーは root と path を結合してコンテンツの場所を決めるので、省略時はルート直下とする
        if (!config.has("path")) config.addProperty("path", "")
        return Result.Found(file, config)
    }

    /**
     * [project] の設定でコメント（`<!-- … -->`）が有効かどうか。設定ファイルがないか読めなければ偽。
     *
     * サーバーは設定エントリの `markdoc` を Markdoc の `Tokenizer` にそのまま渡すので、`markdoc.allowComments` を見る。
     */
    fun allowComments(project: Project): Boolean {
        val config = (read(project) as? Result.Found)?.config ?: return false
        val markdoc = config.get("markdoc") as? JsonObject ?: return false
        return isTruthy(markdoc.get("allowComments"))
    }

    /**
     * 設定ファイルがない場合にサーバーへ渡す最小の設定。
     *
     * サーバーは初期化オプションの `config` がないと起動時に例外で終了するため、
     * スキーマなしでも構文エラーの検出などが動くように `root` と `path` だけを渡す。
     * 作成時の雛形（[MarkdocConfigFile.TEMPLATE_TEXT]）は例のパスを含むので、ここでは使わない。
     */
    fun defaultConfig(project: Project): JsonObject = JsonObject().apply {
        addProperty("root", projectRoot(project) ?: "")
        addProperty("path", "")
    }

    /**
     * 設定エントリから LSP の初期化オプションを作る。
     *
     * 形式は README のスタンドアロン構成 `{ root, path, config: { root, path, ... } }` に従う。
     * サーバーが実際に参照するのは `config` だけだが、README の形に揃えておく。
     */
    fun initializationOptions(config: JsonObject): JsonObject = JsonObject().apply {
        add("root", config.get("root"))
        add("path", config.get("path"))
        add("config", config)
    }

    /**
     * プロジェクトルートを OS ネイティブの形式で返す。
     *
     * サーバーは `URI.fsPath`（Windows では `\` 区切り）と `root` の文字数でパスを切り出すため、
     * IntelliJ の `/` 区切りではなくネイティブの区切り文字で渡す必要がある。
     */
    fun projectRoot(project: Project): String? =
        project.guessProjectDir()?.path?.let { Path.of(it).toString() }

    /**
     * 設定ファイルの中身をディスクから読む。
     *
     * VFS の内容は前回の IDE の終了時のまま残り、起動直後（サーバーを起動する時点）にはまだ更新されていないことがある。
     * そのまま読むと、IDE を閉じている間に書き換えた設定がサーバーに渡らない。ローカルのファイルでなければ VFS から読む。
     * 文字コードは VFS から読むときと同じく、ファイルの文字コード（[VirtualFile.getCharset]）で解釈する。
     */
    private fun loadText(file: VirtualFile): String =
        try {
            String(Files.readAllBytes(file.toNioPath()), file.charset)
        } catch (_: UnsupportedOperationException) {
            // ローカルのファイルシステムにない（toNioPath が使えない）
            VfsUtilCore.loadText(file)
        } catch (_: IOException) {
            // VFS にはあるがディスクから読めない（消された直後など）
            VfsUtilCore.loadText(file)
        }

    /**
     * [element] が JavaScript で真とみなされるかどうか。Markdoc は `if (config.allowComments)` で判定するので、
     * `true` だけでなく 0 以外の数、空でない文字列、オブジェクト、配列も真とする。
     */
    private fun isTruthy(element: JsonElement?): Boolean {
        if (element == null || element.isJsonNull) return false
        if (!element.isJsonPrimitive) return true
        val primitive = element.asJsonPrimitive
        return when {
            primitive.isBoolean -> primitive.asBoolean
            primitive.isNumber -> primitive.asDouble.let { it != 0.0 && !it.isNaN() }
            else -> primitive.asString.isNotEmpty()
        }
    }
}
