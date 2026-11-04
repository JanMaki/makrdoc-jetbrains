# Markdoc Language Service（JetBrains プラグイン）

公式の [`@markdoc/language-server`](https://github.com/markdoc/language-server) を JetBrains の LSP API で動かし、WebStorm などで Markdoc の補完・検証を提供する非公式プラグイン。

- 対象 IDE：2026.1.4 以降で、Node.js サポートを持つ JetBrains IDE
- 対象ファイル：`.mdoc`、`.markdoc`、`*.markdoc.md`（Markdown ファイルとして扱う）
- 実行時に必要なもの：Node.js（プロジェクトの Node.js インタープリター設定を使う）

## 機能

### 言語サーバー由来

| 機能 | 内容 |
|---|---|
| 検証 | スキーマに基づく検証と構文エラーを波線で表示する（未定義のタグ、許可されていない属性値、必須属性の欠落、存在しない partial など） |
| タグ名の補完 | `{%`、`{% `、`{% /` の直後から候補を出す。選ぶと閉じタグや必須属性まで入る |
| 属性名の補完 | `{% callout ` のようにタグ名の後で候補を出す。入力済みの属性は除き、必須の属性を先に並べる |
| 属性値の補完 | `type=` や `type="` の直後から、スキーマの `matches` と Boolean の値を出す。属性名を選んだ直後にも自動で出る |
| リンクの補完 | `[text](/` の後で、フロントマターのルートを出す |
| 定義ジャンプ・参照 | `{% partial file="…" /%}` から partial のファイルへ移動し、partial の使用箇所を探す |
| その他 | フォーマット、コード折りたたみ、ドキュメントリンク、Quick Fix |

### プラグイン独自

| 機能 | 内容 |
|---|---|
| ファイルの関連付け | Markdoc の拡張子を標準の Markdown ファイルタイプに関連付ける |
| 設定の受け渡し | プロジェクトルートの `markdoc.config.json` を読み、先頭のエントリを初期化オプションでサーバーに渡す。ファイルがなければスキーマなしで起動し、壊れていれば起動しない |
| 起動条件 | 信頼済みのプロジェクトで、ローカルの Node.js が設定されているときだけ起動する（サーバーはスキーマの JS を実行するため） |
| `/%}` の自動入力 | タグ名の後で `/` を打つと `/%}` まで入れる。閉じタグの書き始め、引用符の中、後ろに `%}` がある場合は補わない |

## 上流の言語サーバーとの違い

同梱する言語サーバーは、上流の `server()` に次の差し替えを加えて起動する（`language-server/src/`）。

| ファイル | 差し替える理由 |
|---|---|
| `capabilities.js` | 上流は補完などの機能を初期化後に動的に登録するが、JetBrains の LSP クライアントは動的登録を使わない。同じ機能を初期化時に宣言する |
| `completion.js` | 上流の補完は出る場面が少なく、挿入テキストを resolve で補う（JetBrains は resolve の結果から説明文しか使わない）。補完を広げ、挿入テキストを最初から返す |
| `scanner.js` | Windows でドライブ文字の大文字・小文字と区切り文字が食い違い、partial が見つからない。パスの表記をそろえる |

プラグイン側でも、サーバーが定義ジャンプなどの行き先を URI ではなくファイルパスで返す問題に対応している（`MarkdocLspClientDescriptor.findFileByUri`）。

## 構成

```
src/main/kotlin/com/github/markdoc/jetbrains/
  MarkdocFiles.kt                    Markdoc ファイルの判定
  MarkdocConfigReader.kt             markdoc.config.json の読み込みと初期化オプションの組み立て
  MarkdocTypedHandler.kt             `/` 入力時の `/%}` の自動入力
  lsp/MarkdocLspIntegrationProvider.kt  起動条件の判定
  lsp/MarkdocLspClientDescriptor.kt     サーバーの起動、URI の解決、初期化オプション
  lsp/MarkdocCompletionSupport.kt       属性名の挿入後に補完を開き直す
  lsp/MarkdocServerResolver.kt          同梱サーバーの場所の解決
src/main/resources/META-INF/
  plugin.xml                         必須の拡張（ファイルタイプ、入力補助）
  lsp.xml                            LSP API がある IDE でだけ読み込む拡張
language-server/
  src/                               同梱サーバーのエントリーポイントと差し替え
  build.mjs                          esbuild で 1 ファイルにまとめる
samples/basic/                       動作確認用の Markdoc プロジェクト
```

## 開発

```bash
./gradlew buildPlugin      # build/distributions/ に配布 ZIP を作る
./gradlew verifyPlugin     # Plugin Verifier で 2026.1.4 との互換性を検査する
./gradlew runIde           # samples/basic を開いたサンドボックスの WebStorm を起動する
./gradlew runIde -PlspTrace  # LSP のメッセージを log_runIde/language-services/ に記録する
```

言語サーバーは `prepareSandbox` の前に `language-server/` で `npm ci` と esbuild が走り、配布ディレクトリの `server/` に置かれる。

## 未実装

仕様書の Stage 2 以降の機能（タグの色分け、アイコン、設定画面、Node.js 未検出時の通知、設定ファイルの変更監視、言語サーバーの選択、`.md` の扱いの切り替えなど）はまだない。設定ファイルを変更した場合は IDE の再起動が必要。
