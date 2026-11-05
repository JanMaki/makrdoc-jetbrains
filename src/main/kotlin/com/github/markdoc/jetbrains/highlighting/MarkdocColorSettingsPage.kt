package com.github.markdoc.jetbrains.highlighting

import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.PlainSyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.options.colors.AttributesDescriptor
import com.intellij.openapi.options.colors.ColorDescriptor
import com.intellij.openapi.options.colors.ColorSettingsPage
import javax.swing.Icon

/** Settings | Editor | Color Scheme | Markdoc。タグの色を変更できるようにする。 */
class MarkdocColorSettingsPage : ColorSettingsPage {
    private val tags = mapOf(
        "d" to MarkdocHighlighterColors.DELIMITER,
        "t" to MarkdocHighlighterColors.TAG_NAME,
        "a" to MarkdocHighlighterColors.ATTRIBUTE,
        "s" to MarkdocHighlighterColors.STRING,
        "n" to MarkdocHighlighterColors.NUMBER,
        "k" to MarkdocHighlighterColors.KEYWORD,
        "v" to MarkdocHighlighterColors.VARIABLE,
        "f" to MarkdocHighlighterColors.FUNCTION,
    )

    /** 設定画面に表示する言語名。 */
    override fun getDisplayName(): String = "Markdoc"

    /** 設定画面のアイコン（なし）。 */
    override fun getIcon(): Icon? = null

    /** プレビューの下地のハイライター。タグの色はタグ付きのデモテキストで付ける。 */
    override fun getHighlighter(): SyntaxHighlighter = PlainSyntaxHighlighter()

    /** プレビューに表示するテキスト。`<name>` の部分に対応する色が付く。 */
    override fun getDemoText(): String = """
        # Getting started

        <d>{%</d> <t>callout</t> <a>type</a>=<s>"warning"</s> <a>title</a>=<v>${'$'}page.title</v> <a>.wide</a> <d>%}</d>
        Read this first.
        <d>{%</d> <t>/callout</t> <d>%}</d>

        <d>{%</d> <t>if</t> <f>equals</f>(<v>${'$'}version</v>, <n>2</n>) <d>%}</d>
        <d>{%</d> <t>partial</t> <a>file</a>=<s>"footer.mdoc"</s> <a>variables</a>={visible: <k>true</k>} <d>/%}</d>
        <d>{%</d> <t>/if</t> <d>%}</d>
    """.trimIndent()

    /** デモテキストのタグ名と色の対応。 */
    override fun getAdditionalHighlightingTagToDescriptorMap(): Map<String, TextAttributesKey> = tags

    /** 設定画面で変更できる色の一覧。 */
    override fun getAttributeDescriptors(): Array<AttributesDescriptor> = arrayOf(
        AttributesDescriptor("Tag delimiter", MarkdocHighlighterColors.DELIMITER),
        AttributesDescriptor("Tag name", MarkdocHighlighterColors.TAG_NAME),
        AttributesDescriptor("Attribute name", MarkdocHighlighterColors.ATTRIBUTE),
        AttributesDescriptor("String", MarkdocHighlighterColors.STRING),
        AttributesDescriptor("Number", MarkdocHighlighterColors.NUMBER),
        AttributesDescriptor("Keyword (true, false, null)", MarkdocHighlighterColors.KEYWORD),
        AttributesDescriptor("Variable", MarkdocHighlighterColors.VARIABLE),
        AttributesDescriptor("Function", MarkdocHighlighterColors.FUNCTION),
    )

    /** 前景・背景以外の色設定（なし）。 */
    override fun getColorDescriptors(): Array<ColorDescriptor> = ColorDescriptor.EMPTY_ARRAY
}
