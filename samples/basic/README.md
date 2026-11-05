# 動作確認用のサンプル

`./gradlew runIde` で開く Markdoc プロジェクト。`docs/index.mdoc` には意図的なエラーを入れてある。

`docs/variables.mdoc` は変数と関数の確認用。`schema.js` の `variables`・`functions`
を使い、添字付きの変数の色分けと、変数名・次の階層のキー・関数名の補完を試せる。

`docs/collections.mdoc` は配列とオブジェクトの属性値の確認用。括弧（`[ ]`、`{ }`）とオブジェクトのキーの色分けと、
タグを閉じる区切り（`%}`、`/%}`）の `}` が括弧の色にならないことを試せる。
