import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.tasks.PrepareSandboxTask
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask

plugins {
    id("org.jetbrains.intellij.platform") version "2.19.0"
    kotlin("jvm") version "2.4.21"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

kotlin { jvmToolchain(21) }

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

dependencies {
    intellijPlatform {
        webstorm(providers.gradleProperty("platformVersion"))
        // LSP はプラットフォームのモジュールなので bundledPlugins には書かない
        bundledPlugins("JavaScript", "NodeJS", "org.intellij.plugins.markdown", "com.intellij.modules.json")
    }
}

intellijPlatform {
    pluginConfiguration {
        version = providers.gradleProperty("pluginVersion")
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = provider { null }
        }
    }
    pluginVerification {
        // README の「対応 IDE」に挙げた IDE を、サポートする最小の版で検査する
        ides {
            val minVersion = "2026.1.4"
            listOf(
                IntelliJPlatformType.WebStorm,
                IntelliJPlatformType.IntellijIdea,
                IntelliJPlatformType.PyCharm,
                IntelliJPlatformType.PhpStorm,
                IntelliJPlatformType.GoLand,
                IntelliJPlatformType.RubyMine,
                IntelliJPlatformType.Rider,
                IntelliJPlatformType.CLion,
            ).forEach { create(it, minVersion) }
        }
        failureLevel = listOf(
            VerifyPluginTask.FailureLevel.COMPATIBILITY_PROBLEMS,
            VerifyPluginTask.FailureLevel.INTERNAL_API_USAGES,
            VerifyPluginTask.FailureLevel.OVERRIDE_ONLY_API_USAGES,
            VerifyPluginTask.FailureLevel.INVALID_PLUGIN,
            VerifyPluginTask.FailureLevel.MISSING_DEPENDENCIES,
        )
    }
}

// ---- 同梱する言語サーバー ----

val languageServerDir = layout.projectDirectory.dir("language-server")
val serverOutputDir = layout.buildDirectory.dir("server")

// package-lock.json どおりに esbuild と @markdoc/language-server を入れる
val npmInstallServer = tasks.register<Exec>("npmInstallServer") {
    workingDir = languageServerDir.asFile
    commandLine(npmCommand(), "ci", "--no-fund", "--no-audit")
    inputs.file(languageServerDir.file("package.json"))
    inputs.file(languageServerDir.file("package-lock.json"))
    outputs.dir(languageServerDir.dir("node_modules"))
}

// language-server/src を build/server/markdoc-lsp-server.js の 1 ファイルにまとめ、ライセンス表記を添える
val bundleServer = tasks.register<Exec>("bundleServer") {
    dependsOn(npmInstallServer)
    workingDir = languageServerDir.asFile
    commandLine(nodeCommand(), "build.mjs", serverOutputDir.get().asFile.absolutePath)
    inputs.file(languageServerDir.file("build.mjs"))
    inputs.dir(languageServerDir.dir("src"))
    inputs.file(languageServerDir.file("package-lock.json"))
    outputs.dir(serverOutputDir)
}

// JAR ではなくプラグインの配布ディレクトリ直下の server/ に置く。配布 ZIP もサンドボックスから作られる。
// runIde や verifyPlugin などはそれぞれ専用の prepareSandbox_* タスクでサンドボックスを作るため、すべてに設定する
tasks.withType<PrepareSandboxTask>().configureEach {
    dependsOn(bundleServer)
    from(serverOutputDir) {
        into(intellijPlatform.projectName.map { "$it/server" })
    }
}

// ---- 開発用のサンドボックス IDE ----

val sampleProjectPath = layout.projectDirectory.dir("samples/basic").asFile.absolutePath
val lspTrace = providers.gradleProperty("lspTrace").isPresent

tasks.runIde {
    // 動作確認用のサンプルプロジェクトを開いた状態で起動する。
    // スクリプトの変数をラムダで直接参照するとスクリプト自体を捕まえて configuration cache に保存できないため、ローカルに写す
    val projectPath = sampleProjectPath
    argumentProviders += CommandLineArgumentProvider { listOf(projectPath) }
    // -PlspTrace を付けると、LSP のメッセージを log_runIde/language-services/ に記録する
    if (lspTrace) jvmArgs("-Didea.log.trace.categories=#com.intellij.platform.lsp")
}

fun isWindows() = System.getProperty("os.name").lowercase().contains("windows")

// Exec は PATHEXT を解決しないため、Windows では拡張子付きで指定する
fun npmCommand() = if (isWindows()) "npm.cmd" else "npm"
fun nodeCommand() = if (isWindows()) "node.exe" else "node"
