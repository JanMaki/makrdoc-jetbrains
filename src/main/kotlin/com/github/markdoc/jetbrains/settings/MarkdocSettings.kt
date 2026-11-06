package com.github.markdoc.jetbrains.settings

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
     * プロジェクトルートからの設定ファイルの相対パス。`/` 区切りで返す。
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

        /** [path] を前後の空白を除いた `/` 区切りにする。空なら [DEFAULT_CONFIG_PATH]。 */
        fun normalizePath(path: String): String =
            FileUtil.toSystemIndependentName(path.trim()).ifEmpty { DEFAULT_CONFIG_PATH }
    }
}
