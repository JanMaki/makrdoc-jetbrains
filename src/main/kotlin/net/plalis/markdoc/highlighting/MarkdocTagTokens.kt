package net.plalis.markdoc.highlighting

import net.plalis.markdoc.MarkdocConfigReader
import net.plalis.markdoc.MarkdocFiles
import net.plalis.markdoc.MarkdocTagScanner
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiFile
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager

/**
 * Markdoc ファイルのタグのトークンを、ファイルごとにキャッシュして提供する。
 *
 * 色分け（[MarkdocTagAnnotator]）と、タグの中の Markdown の色と警告を除く処理（[MarkdocTagHighlightFilter]、
 * [MarkdocTagInspectionSuppressor]）で同じ走査結果を使う。
 */
object MarkdocTagTokens {
    /**
     * [file] 全体を走査したトークン。設定でコメントが有効なら、コメントの中のタグを除き、コメントのトークンを含む。
     *
     * ファイルか設定ファイルが変わるまでキャッシュする。設定ファイルの追加・削除・名前の変更でも読み直せるように、
     * ファイルの構造の変更（[VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS]）でも捨てる。
     */
    fun of(file: PsiFile): List<MarkdocTagScanner.Token> =
        CachedValuesManager.getCachedValue(file) {
            val project = file.project
            val allowComments = MarkdocConfigReader.allowComments(project)
            val dependencies = listOfNotNull(
                file,
                MarkdocConfigReader.findConfigFile(project),
                VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS,
            )
            CachedValueProvider.Result.create(
                MarkdocTagScanner.scan(file.viewProvider.contents, allowComments),
                *dependencies.toTypedArray(),
            )
        }

    /**
     * Markdoc ファイル [file] の [start] から [end] の手前までが 1 つのタグの中に収まるかどうか。
     * Markdoc ファイルでなければ（Markdown ファイルなら）偽で、トークンの走査もしない。
     */
    fun isInTag(file: PsiFile, start: Int, end: Int): Boolean {
        val viewProvider = file.viewProvider
        return MarkdocFiles.isMarkdoc(viewProvider.virtualFile) &&
                MarkdocTagScanner.isInTag(viewProvider.contents, of(file), start, end)
    }
}
