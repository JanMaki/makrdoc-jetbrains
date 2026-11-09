package net.plalis.markdoc.settings

import com.intellij.openapi.components.*
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil

/**
 * プロジェクトごとのプラグイン設定。`.idea/markdoc.xml` に保存する（チームで共有できる値だけを置く）。
 *
 * 設定は言語サーバーの起動時に読まれるため、変更の反映にはサーバーの再起動（IDE の再起動）が必要。
 * 値の編集は [MarkdocConfigurable] が行う。マシンごとに違う値は [MarkdocLocalSettings] に置く。
 */
@Service(Service.Level.PROJECT)
@State(name = "MarkdocSettings", storages = [Storage("markdoc.xml")])
class MarkdocSettings : SimplePersistentStateComponent<MarkdocSettings.State>(State()) {

    /** 永続化する値。XML への保存は [BaseState] の仕組みに任せる。 */
    class State : BaseState() {
        /** プロジェクトルートからの設定ファイルの相対パス。 */
        var configPath by string(DEFAULT_CONFIG_PATH)
    }

    /**
     * プロジェクトルートからの設定ファイルの相対パス。[normalizePath] で整えた `/` 区切りで返す。
     * 空白だけの値は未設定として扱い、[DEFAULT_CONFIG_PATH] を返す。
     */
    var configPath: String
        get() = normalizePath(state.configPath.orEmpty())
        set(value) {
            state.configPath = value.trim()
        }

    companion object {
        /** プロジェクトルートからの設定ファイルの既定の相対パス。 */
        const val DEFAULT_CONFIG_PATH = "markdoc.config.json"

        /** [project] の設定を返す。 */
        fun getInstance(project: Project): MarkdocSettings = project.service()

        /**
         * [path] を前後の空白を除いた `/` 区切りにし、`.` と `..` を字句の上で畳む（`./a.json` → `a.json`、
         * `a/../b.json` → `b.json`）。空なら [DEFAULT_CONFIG_PATH]。
         *
         * 設定の読み込み、ファイルの作成、エディタの保存先の比較がすべてこれを通るので、同じファイルは同じ表記になる。
         * ルートより上へ出る `..` は残す（ルートの外として書き込み前の検査で弾かれる）。畳んだ結果が空（ルートそのもの）
         * なら `.` を返す。絶対パスはそのまま返す（同じくルートの外として扱われる）。
         */
        fun normalizePath(path: String): String {
            val independent = FileUtil.toSystemIndependentName(path.trim())
            if (independent.isEmpty()) return DEFAULT_CONFIG_PATH
            if (independent.startsWith("/") || FileUtil.isAbsolute(independent)) return independent
            val names = ArrayDeque<String>()
            for (name in independent.split('/')) {
                when {
                    name.isEmpty() || name == "." -> Unit
                    name == ".." && names.isNotEmpty() && names.last() != ".." -> names.removeLast()
                    else -> names.addLast(name)
                }
            }
            return names.joinToString("/").ifEmpty { "." }
        }
    }
}
