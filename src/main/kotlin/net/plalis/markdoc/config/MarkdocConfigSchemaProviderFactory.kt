package net.plalis.markdoc.config

import com.github.markdoc.jetbrains.MarkdocConfigReader
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.jetbrains.jsonSchema.extension.JsonSchemaFileProvider
import com.jetbrains.jsonSchema.extension.JsonSchemaProviderFactory
import com.jetbrains.jsonSchema.extension.SchemaType

/**
 * `markdoc.config.json` に JSON スキーマを割り当て、キーの補完、説明の表示、値の検査を効かせる。
 *
 * 対象は設定（[com.github.markdoc.jetbrains.settings.MarkdocSettings.configPath]）で指定された設定ファイル。
 * JSON プラグインがない IDE でも動くように、`json.xml` で任意の依存として登録する。
 */
class MarkdocConfigSchemaProviderFactory : JsonSchemaProviderFactory {
    override fun getProviders(project: Project): List<JsonSchemaFileProvider> = listOf(Provider(project))

    private class Provider(private val project: Project) : JsonSchemaFileProvider {
        override fun isAvailable(file: VirtualFile): Boolean =
            !project.isDisposed && file == MarkdocConfigReader.findConfigFile(project)

        override fun getName(): String = "Markdoc config"

        override fun getSchemaFile(): VirtualFile? =
            JsonSchemaProviderFactory.getResourceFile(MarkdocConfigSchemaProviderFactory::class.java, SCHEMA_PATH)

        override fun getSchemaType(): SchemaType = SchemaType.embeddedSchema
    }

    private companion object {
        const val SCHEMA_PATH = "/schemas/markdoc.config.schema.json"
    }
}
