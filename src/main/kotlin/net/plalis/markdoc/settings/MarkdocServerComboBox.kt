package net.plalis.markdoc.settings

import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.io.FileUtil
import com.intellij.ui.ColoredListCellRenderer
import javax.swing.DefaultComboBoxModel
import javax.swing.JList

/**
 * 言語サーバーの選択肢を出すプルダウン。
 *
 * 選択肢は次の 3 種類で、末尾は常に `Select...`。
 * - `Bundled`: プラグインに同梱したサーバー（常に先頭）
 * - 利用者が選んだ JS ファイル: パスを表示する
 * - `Select...`: ファイル選択ダイアログを開く。選んだファイルは選択肢に加わって選択される
 *
 * 保存する値は [path] で、同梱のサーバーは空文字で表す。
 */
class MarkdocServerComboBox(private val project: Project) : ComboBox<MarkdocServerComboBox.Item>() {

    /** プルダウンの選択肢。 */
    sealed interface Item {
        /** 同梱のサーバー。 */
        data object Bundled : Item

        /** 利用者が選んだサーバーの JS ファイル。 */
        data class Custom(val path: String) : Item

        /** ファイル選択ダイアログを開く項目。選択肢としては残らず、選ぶとファイルを選ばせる。 */
        data object Select : Item
    }

    private val itemModel = DefaultComboBoxModel<Item>().apply {
        addElement(Item.Bundled)
        addElement(Item.Select)
    }

    /** 直前に確定した選択。`Select...` をキャンセルしたときに戻す。 */
    private var previous: Item = Item.Bundled

    /** プログラムから選択を変えている間は、選択の変更に反応しない。 */
    private var updating = false

    /**
     * 選択中のサーバーの JS ファイルのパス。同梱のサーバーなら空文字。
     * 代入すると、その値に対応する選択肢を選ぶ（なければ追加する）。
     */
    var path: String
        get() = (selectedItem as? Item.Custom)?.path.orEmpty()
        set(value) {
            select(if (value.isEmpty()) Item.Bundled else Item.Custom(value))
        }

    init {
        model = itemModel
        renderer = ItemRenderer()
        addActionListener { onSelectionChanged() }
    }

    /** [item] を選ぶ。選択肢になければ、`Select...` の手前に加える。 */
    private fun select(item: Item) {
        updating = true
        try {
            if ((0 until itemModel.size).none { itemModel.getElementAt(it) == item }) {
                itemModel.insertElementAt(item, itemModel.size - 1)
            }
            selectedItem = item
            previous = item
        } finally {
            updating = false
        }
    }

    /** `Select...` が選ばれたらファイルを選ばせる。キャンセルされたら直前の選択に戻す。 */
    private fun onSelectionChanged() {
        if (updating) return
        val item = selectedItem as? Item ?: return
        if (item != Item.Select) {
            previous = item
            return
        }
        val file = FileChooser.chooseFile(FileChooserDescriptorFactory.createSingleFileDescriptor("js"), project, null)
        if (file == null) {
            updating = true
            try {
                selectedItem = previous
            } finally {
                updating = false
            }
        } else {
            select(Item.Custom(file.path))
        }
    }

    /** 選択肢の表示。同梱のサーバーは名前だけ、選んだファイルはパスを出す。 */
    private class ItemRenderer : ColoredListCellRenderer<Item>() {
        override fun customizeCellRenderer(
            list: JList<out Item>, value: Item?, index: Int, selected: Boolean, hasFocus: Boolean,
        ) {
            when (value) {
                Item.Bundled -> append("Bundled")
                is Item.Custom -> append(shorten(value.path))
                Item.Select -> append("Select...")
                null -> {}
            }
        }

        /** 表示用に、ホームディレクトリ以下のパスを `~` に縮める。 */
        private fun shorten(path: String): String =
            FileUtil.getLocationRelativeToUserHome(path, !SystemInfo.isWindows)
    }
}
