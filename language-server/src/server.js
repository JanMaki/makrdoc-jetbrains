// プラグインに同梱する言語サーバーのエントリーポイント。
// 上流の server() をそのまま起動し、JetBrains の LSP クライアントと Windows で問題になる部分だけを差し替える。
const {server, Services, Plugins} = require("@markdoc/language-server");
const {capabilities} = require("./capabilities");
const {CommentSafeDocuments, CommentSafeFormattingProvider} = require("./comments");
const {MarkdocCompletionProvider} = require("./completion");
const {FsPathScanner} = require("./scanner");
const {MarkdocValidationProvider} = require("./validation");

server(
    // TODO: Markdoc を更新して comments.js を消すときに、Documents と FormattingProvider を上流のものに戻す
    {...Services, Scanner: FsPathScanner, Documents: CommentSafeDocuments},
    {
        ...Plugins,
        CompletionProvider: MarkdocCompletionProvider,
        FormattingProvider: CommentSafeFormattingProvider,
        ValidationProvider: MarkdocValidationProvider,
    },
    capabilities,
);
