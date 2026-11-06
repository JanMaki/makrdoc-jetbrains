package com.github.markdoc.jetbrains.settings

import com.intellij.openapi.components.*
import com.intellij.openapi.project.Project

/**
 * プロジェクトごと、かつマシンごとのプラグイン設定。ワークスペースファイル（`.idea/workspace.xml`）に保存する。
 *
 * 絶対パスのようにマシンで異なる値は、共有される [MarkdocSettings] ではなくここに置く。
 */
@Service(Service.Level.PROJECT)
@State(name = "MarkdocLocalSettings", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class MarkdocLocalSettings : SimplePersistentStateComponent<MarkdocLocalSettings.State>(State()) {

    /** 永続化する値。 */
    class State : BaseState() {
        /** 同梱の代わりに使う言語サーバーの JS ファイル（絶対パス）。空なら同梱のものを使う。 */
        var customServerPath by string("")
    }

    /** 同梱の代わりに使う言語サーバーの JS ファイル（絶対パス）。空なら同梱のサーバーを使う。 */
    var customServerPath: String
        get() = state.customServerPath?.trim().orEmpty()
        set(value) {
            state.customServerPath = value.trim()
        }

    companion object {
        /** [project] の設定を返す。 */
        fun getInstance(project: Project): MarkdocLocalSettings = project.service()
    }
}
