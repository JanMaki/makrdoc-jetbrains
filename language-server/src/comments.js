// 同梱する Markdoc（@markdoc/language-server 0.0.14 が依存する 0.3 系）のコメントの規則の不具合を避けるための差し替え。
//
// TODO: 暫定の対処。次の 2 点が直った Markdoc に更新したら、このファイルと、server.js・scanner.js からの呼び出しを消す。
//  - 閉じていない `<!--` の扱い（Markdoc 0.5 で修正済み）
//  - リンクのテキストの中のコメント（Markdoc 0.5.11 でも未修正。止まりはしないが例外になる）

const {Services, Plugins} = require("@markdoc/language-server");

const OPEN = "<!--";
const CLOSE = "-->";

/**
 * Markdoc の Tokenizer に入ったコメントの規則を、不具合を直した規則に差し替える。
 *
 * コメントの規則は設定で `allowComments` を有効にしたときだけ入る。入っていなければ何もしない。
 *
 * Markdoc 0.3 のコメントの規則には次の不具合があり、言語サーバーが止まらなくなってメモリを使い切る。
 * - 閉じていない `<!--` で、`indexOf` の -1 を「見つかった」と扱う。インラインでは読む位置が戻って
 *   止まらなくなり、ブロックでは文書の末尾までをコメントにする
 * - 試しに読むだけの呼び出し（silent。リンクのテキストの範囲を探すときなど）で、読む位置を進めずに成功を返す。
 *   markdown-it はそこから先に進めなくなる
 *
 * 差し替え後は、閉じていない `<!--` をコメントにしない（Markdoc 0.5 と同じ）。それ以外の扱いは元の規則と同じ。
 *
 * @param tokenizer Markdoc の Tokenizer。`parser` に markdown-it のインスタンスを持つ。
 */
function patchCommentRules(tokenizer) {
    const parser = tokenizer?.parser;
    if (!parser) return;
    // `__find__` は markdown-it の Ruler の内部 API だが、規則の有無を調べる公開 API がない
    if (parser.block.ruler.__find__("comment") >= 0) {
        // 元の規則と同じく、段落の途中でもコメントを始められるようにする
        parser.block.ruler.at("comment", block, {alt: ["paragraph"]});
    }
    if (parser.inline.ruler.__find__("comment") >= 0) {
        parser.inline.ruler.at("comment", inline);
    }
}

/**
 * ブロックのコメントの規則。行頭（字下げの後）の `<!--` から最初の `-->` までを 1 つのコメントにする。
 * 元の規則と同じく、`-->` と同じ行の残りは読み飛ばす。
 *
 * @param state markdown-it のブロックの状態。
 * @param startLine 読み始める行。
 * @param endLine 読む範囲の終わりの行（使わない。`-->` は範囲の外まで探す）。
 * @param silent 真なら、コメントが始まるかどうかだけを調べる。
 * @returns コメントだったかどうか。
 */
function block(state, startLine, endLine, silent) {
    const start = state.bMarks[startLine] + state.tShift[startLine];
    if (!state.src.startsWith(OPEN, start)) return false;
    const close = state.src.indexOf(CLOSE, start);
    if (close === -1) return false;
    if (silent) return true;

    const content = state.src.slice(start + OPEN.length, close);
    const lines = content.split("\n").length;
    const token = state.push("comment", "", 0);
    token.content = content.trim();
    token.map = [startLine, startLine + lines];
    state.line += lines;
    return true;
}

/**
 * インラインのコメントの規則。行の途中の `<!--` から、同じインラインの範囲（段落など）の最初の `-->` までを
 * 1 つのコメントにする。
 *
 * @param state markdown-it のインラインの状態。
 * @param silent 真なら、トークンを作らずに読む位置だけを進める。
 * @returns コメントだったかどうか。
 */
function inline(state, silent) {
    if (!state.src.startsWith(OPEN, state.pos)) return false;
    const close = state.src.indexOf(CLOSE, state.pos);
    if (close === -1) return false;
    if (!silent) {
        const token = state.push("comment", "", 0);
        token.content = state.src.slice(state.pos + OPEN.length, close).trim();
    }
    // markdown-it の規則は、silent でも成功したら読む位置を進めなければならない
    state.pos = close + CLOSE.length;
    return true;
}

/** 文書の解析（検証や補完などで使う AST）に使う Tokenizer で、コメントの規則を差し替えた Documents。 */
class CommentSafeDocuments extends Services.Documents {
    constructor(...args) {
        super(...args);
        patchCommentRules(this.tokenizer);
    }
}

/**
 * フォーマットに使う Tokenizer でコメントの規則を差し替え、行の途中のコメントのあとで改行しないようにした
 * FormattingProvider。
 */
class CommentSafeFormattingProvider extends Plugins.FormattingProvider {
    constructor(...args) {
        super(...args);
        patchCommentRules(this.tokenizer);
    }

    /**
     * Markdoc の `format` はコメントのあとに必ず改行を書くので、行の途中のコメントのあとで段落が切れてしまう。
     * そこで行の途中のコメントは、いったん目印の文字列（テキスト）にしてから整形し、整形後に元のコメントに戻す。
     * 行頭から始まるコメント（ブロックのコメント）は今までどおり整形する。
     */
    formatRange(doc, range) {
        const comments = [];
        const tokenizer = this.tokenizer;
        const tokenize = tokenizer.tokenize;
        tokenizer.tokenize = (content) => hideInlineComments(tokenize.call(tokenizer, content), comments);
        try {
            return super.formatRange(doc, range).map((edit) => ({
                ...edit,
                newText: edit.newText.replace(PLACEHOLDER, (_, index) => `${OPEN} ${comments[index]} ${CLOSE}`),
            }));
        } finally {
            tokenizer.tokenize = tokenize;
        }
    }
}

/**
 * 行の途中のコメントの目印。Markdoc の `format` がエスケープする文字（`*`、`_`、`~` など）を含まない、
 * 私用領域の文字で番号を囲む。
 */
const PLACEHOLDER = /(\d+)/g;

/**
 * インラインのトークンの中のコメントを、目印のテキストに置き換える。
 *
 * @param tokens Markdoc の Tokenizer が返したトークン。
 * @param comments 置き換えたコメントの中身を、目印の番号の順に追加する配列。
 * @returns 置き換えた [tokens]。
 */
function hideInlineComments(tokens, comments) {
    for (const token of tokens) {
        for (const child of token.children ?? []) {
            if (child.type !== "comment") continue;
            comments.push(child.content);
            child.type = "text";
            child.content = `${comments.length - 1}`;
        }
    }
    return tokens;
}

module.exports = {patchCommentRules, CommentSafeDocuments, CommentSafeFormattingProvider};
