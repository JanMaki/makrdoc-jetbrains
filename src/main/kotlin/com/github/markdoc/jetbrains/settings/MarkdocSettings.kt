package com.github.markdoc.jetbrains.settings

import com.intellij.openapi.components.*
import com.intellij.openapi.project.Project

/**
 * プロジェクトごとのプラグイン設定。`.idea/markdoc.xml` に保存する。
 *
 * 設定は言語サーバーの起動時に読まれるため、変更の反映にはサーバーの再起動（IDE の再起動）が必要。
 * 値の編集は [MarkdocConfigurable] が行う。
 */
@Service(Service.Level.PROJECT)
@State(name = "MarkdocSettings", storages = [Storage("markdoc.xml")])
class MarkdocSettings : SimplePersistentStateComponent<MarkdocSettings.State>(State()) {

    /** 永続化する値。XML への保存は [BaseState] の仕組みに任せる。 */
    class State : BaseState() {
        /** プロジェクトルートからの設定ファイルの相対パス。 */
        var configPath by string(DEFAULT_CONFIG_PATH)

        /** 同梱の代わりに使う言語サーバーの JS ファイル（絶対パス）。空なら同梱のものを使う。 */
        var customServerPath by string("")
    }

    /**
     * プロジェクトルートからの設定ファイルの相対パス。
     * 空白だけの値は未設定として扱い、[DEFAULT_CONFIG_PATH] を返す。
     */
    var configPath: String
        get() = state.configPath?.trim().orEmpty().ifEmpty { DEFAULT_CONFIG_PATH }
        set(value) {
            state.configPath = value.trim()
        }

    /** 同梱の代わりに使う言語サーバーの JS ファイル（絶対パス）。空なら同梱のサーバーを使う。 */
    var customServerPath: String
        get() = state.customServerPath?.trim().orEmpty()
        set(value) {
            state.customServerPath = value.trim()
        }

    companion object {
        /** プロジェクトルートからの設定ファイルの既定の相対パス。 */
        const val DEFAULT_CONFIG_PATH = "markdoc.config.json"

        /** [project] の設定を返す。 */
        fun getInstance(project: Project): MarkdocSettings = project.service()
    }
}
