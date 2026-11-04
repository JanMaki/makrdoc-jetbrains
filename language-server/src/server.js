// プラグインに同梱する言語サーバーのエントリーポイント。
// 上流の server() をそのまま起動し、JetBrains の LSP クライアントと Windows で問題になる部分だけを差し替える。
const { server, Services, Plugins } = require("@markdoc/language-server");
const { capabilities } = require("./capabilities");
const { MarkdocCompletionProvider } = require("./completion");
const { FsPathScanner } = require("./scanner");

server(
  { ...Services, Scanner: FsPathScanner },
  { ...Plugins, CompletionProvider: MarkdocCompletionProvider },
  capabilities,
);
