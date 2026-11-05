const {defaultCapabilities} = require("@markdoc/language-server");

/**
 * 補完ポップアップを自動で開く文字。
 *
 * `{%` の直後（`%`）、タグ名や属性の後の空白、属性値の入力開始（`=` と `"`）、
 * 変数の入力開始と次の階層（`$` と `.`）、関数の引数の入力開始（`(`）で候補を出す。
 */
const TRIGGER_CHARACTERS = ["%", " ", "=", '"', "$", ".", "("];

/**
 * initialize の応答で返すサーバーの機能。
 *
 * 上流は textDocumentSync 以外の機能を初期化後に client/registerCapability で動的に登録するが、
 * JetBrains の LSP クライアントは補完などの dynamicRegistration に対応しておらず、登録を受け付けても使わない。
 * そのため、上流が動的に登録するのと同じ機能を静的にも宣言する。
 */
const capabilities = {
    ...defaultCapabilities,
    executeCommandProvider: {commands: []},
    codeActionProvider: {resolveProvider: true},
    completionProvider: {resolveProvider: true, triggerCharacters: TRIGGER_CHARACTERS},
    definitionProvider: true,
    referencesProvider: true,
    foldingRangeProvider: true,
    documentFormattingProvider: true,
    documentRangeFormattingProvider: true,
    documentLinkProvider: {},
    linkedEditingRangeProvider: true,
    selectionRangeProvider: true,
    documentSymbolProvider: true,
};

module.exports = {capabilities};
