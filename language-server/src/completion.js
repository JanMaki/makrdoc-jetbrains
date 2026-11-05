const {Plugins} = require("@markdoc/language-server");
const {frontmatterOf, withMarkdocVariables} = require("./variables");

/** LSP の CompletionItemKind（vscode-languageserver の値と同じ）。 */
const CompletionItemKind = {Function: 3, Variable: 6, Property: 10, Value: 12, EnumMember: 20};

/** Markdoc の組み込み関数。スキーマを読み込めていないときも候補に出す。 */
const BUILTIN_FUNCTIONS = ["equals", "and", "or", "not", "default", "debug"];

/**
 * タグの中（`{%` の後で `%}` より前）で、文字列の外にある位置まで読む部分。
 * 文字列は閉じたものだけを読むので、これに続く位置は文字列の中にならない。
 * 照合するのはカーソルがある行の、カーソルより前だけなので、最短一致の繰り返しでも重くならない。
 */
const TAG_BODY = String.raw`\{%(?:(?!%\})[^"]|"(?:[^"\\]|\\.)*")*?`;

/**
 * 変数参照の入力位置。`$` の前は識別子の文字でないこと（`a$b` の `$` は変数を始めない）。
 * 1 つ目のグループは `.` までの入力済みの階層（`a.b[0].`）、2 つ目は入力中の名前。
 * 添字は数値か文字列（`[0]`、`["key"]`）だけを認める（Markdoc の文法と同じ）。
 */
const VARIABLE_PATTERN = new RegExp(
    String.raw`${TAG_BODY}(?<![\w$-])\$((?:[\w-]+(?:\[(?:\d+|"(?:[^"\\]|\\.)*")\])*\.)*)([\w-]*)$`,
);

/** 関数の引数の入力位置（`(` か `,` の後）。グループは入力中の名前。 */
const ARGUMENT_PATTERN = new RegExp(String.raw`${TAG_BODY}[(,][ ]*([\w-]*)$`);

/**
 * 入力済みの階層（`a.b[0]["key"].`）を 1 段ずつ切り出す。
 * グループは順に、名前、数値の添字、文字列の添字の中身（エスケープを含む）。
 */
const PATH_SEGMENT = /([\w-]+)|\[(\d+)\]|\["((?:[^"\\]|\\.)*)"\]/g;

/** LSP の InsertTextFormat.Snippet。 */
const SNIPPET = 2;

/** 引用符なしで書く値の型。 */
const NON_STRING_TYPES = [Number, Boolean, Array, Object, "Number", "Boolean", "Array", "Object"];

/**
 * 補完のタイミングと挿入内容を JetBrains IDE 向けに整えた CompletionProvider。
 *
 * 上流の補完は「タグ名を 1 文字以上入力した後」と「属性値を 1 文字以上入力した後」だけで、
 * 属性名の補完はない。ここでは次の場面で候補を出す。
 * - `{%` や `{% /` の直後（タグ名。開きタグの位置ではタグの後に関数名も）
 * - `{% callout ` や `{% callout t` のようにタグ名の後（属性名）
 * - `{% callout type=` や `{% callout type="` の直後（属性値。引用符がなければ関数名も）
 * - `{% $`、`type=$`、`$page.` の後（変数名と、その次の階層のキー）
 * - `equals(` や `,` の後（関数名）と、`{% if ` のように主属性を持つタグ名の後（関数名）
 *
 * 挿入するテキストは候補を返す時点で決める。上流はタグのスニペットを completionItem/resolve で補うが、
 * JetBrains の LSP クライアントは resolve の結果から documentation しか使わない。
 */
class MarkdocCompletionProvider extends Plugins.CompletionProvider {
    /** 上流の補完パターンを、タグ名・属性名・属性値の補完に差し替える（ルートの補完は上流のものを使う）。 */
    constructor(config, connection, services) {
        super(config, connection, services);
        const [, , upstreamRoute] = this.completions;

        // 各パターンは行頭からカーソルまでの文字列に対して照合し、最初に一致したものを使う
        this.completions = [
            {
                // 変数。属性値の `=$` もここで扱うため、属性値より先に照合する
                match: VARIABLE_PATTERN,
                complete: (params, matches) => this.completeVariables(params, matches),
            },
            {
                // 関数の引数の位置では関数名を出す。属性値（`type=equals(`）より先に照合する
                match: ARGUMENT_PATTERN,
                complete: (params) => this.functionItems(params),
            },
            {
                // タグ名。空の入力でも一致させる
                match: /\{%([ ]*)(\/)?[\w-]*$/,
                complete: (params, matches) => this.completeTagNames(params, matches),
            },
            {
                // 属性値。`=` の直後と `="` の直後の両方に対応する
                match: /\{%[ ]*([\w-]+)[^%]* ([\w-]+)=("?)[^ "]*$/,
                complete: (params, matches) => this.completeAttributeValues(params, matches),
            },
            {
                // 属性名。タグ名と入力済みの属性の後に空白があれば一致する
                match: /\{%[ ]*([\w-]+)((?: +[\w-]+(?:=(?:"[^"]*"|[^ "%]*))?)*) +[\w-]*$/,
                complete: (params, matches) => this.completeAttributeNames(params, matches),
            },
            // リンク先のルートは上流のまま
            upstreamRoute,
        ];
    }

    /**
     * タグ名の候補。開きタグの位置では、`{% equals($a, 1) %}` のように書ける関数名もタグの後に並べる。
     *
     * 上流のスニペットには、自己終了タグの前に空白が入らない、`{% ` の後では閉じタグまで 1 行に
     * まとめてしまう、文字列の属性に引用符が付かない、といった問題があるため、[tagSnippet] で組み立て直す。
     */
    completeTagNames(params, [typed, spacing, closing]) {
        const tags = this.schema(params)?.tags ?? {};

        const {character} = params.position;
        const lineText = this.lineText(params);
        const before = lineText.slice(0, character - typed.length);
        const after = lineText.slice(character);
        const context = {
            // `{%` の直後に空白がなければ補う
            leading: spacing.length > 0 ? "" : " ",
            // 行にタグだけを書いているときは、開きタグと閉じタグを別の行に置く
            block: before.trim() === "" && after.trim() === "",
            // カーソルの後ろに `%}` が既にあれば、タグ名の書き換えとみなして名前だけを入れる
            alreadyClosed: /^(?:(?!\{%).)*%\}/.test(after),
        };

        const tagItems = Object.entries(tags).map(([name, config]) => ({
            label: name,
            kind: CompletionItemKind.Function,
            documentation: config.description ?? "",
            sortText: `0${name}`,
            insertText: closing ? `${name} %}` : tagSnippet(name, config, context),
            insertTextFormat: SNIPPET,
        }));
        // 閉じタグには関数を書けない
        if (closing) return tagItems;

        // `{% equals($a, 1) %}` のように関数の結果も書ける。タグの後に並べるので、
        // 入力した名前に合うタグがなければ関数だけが残る
        const functionItems = this.functionItems(params).map((item) => ({
            ...item,
            insertText: `${context.leading}${item.label}($1)${context.alreadyClosed ? "" : " %}"}`,
        }));
        return [...tagItems, ...functionItems];
    }

    /** 属性名の候補。入力済みの属性は除き、必須の属性を先に並べる。 */
    completeAttributeNames(params, [, tagName, written]) {
        const attributes = this.schema(params)?.tags?.[tagName]?.attributes;
        if (!attributes) return [];

        const used = new Set(Array.from(written.matchAll(/([\w-]+)=?/g), ([, name]) => name));
        // `{% if ` のように主属性を値だけで書けるタグでは、最初の位置に関数も書ける
        const functions =
            attributes.primary && !isStringAttribute(attributes.primary) && written.trim() === ""
                ? this.functionItems(params)
                : [];
        const names = Object.entries(attributes)
            .filter(([name]) => !used.has(name))
            .map(([name, attribute]) => ({
                label: name,
                kind: CompletionItemKind.Property,
                detail: [typeName(attribute.type), attribute.required ? "required" : ""].filter(Boolean).join(", "),
                documentation: attribute.description ?? "",
                sortText: `${attribute.required ? 0 : 1}${name}`,
                // 文字列は引用符の中にカーソルを置き、それ以外は値を直接書けるようにする
                insertText: isStringAttribute(attribute) ? `${name}="$1"` : `${name}=$1`,
                insertTextFormat: SNIPPET,
                // 挿入後すぐに属性値の候補を出すよう、クライアント（MarkdocCompletionSupport）に知らせる
                data: {triggerSuggest: true},
            }));
        return [...names, ...functions];
    }

    /** 属性値の候補。`matches` に列挙された値と、Boolean 型の `true` / `false` を出す。 */
    completeAttributeValues(params, [, tagName, attrName, quote]) {
        const schema = this.schema(params);
        const attribute = schema?.tags?.[tagName]?.attributes?.[attrName];
        if (!attribute) return [];

        const accepts = typeof attribute.matches === "function" ? attribute.matches(schema) : attribute.matches;
        const options = Array.isArray(accepts)
            ? accepts.filter((option) => typeof option !== "object")
            : attribute.type === Boolean
                ? [true, false]
                : [];

        // 開き引用符の後で、カーソルの直後に閉じ引用符がなければ補う
        const {character} = params.position;
        const closingQuote = quote && this.lineText(params)[character] !== '"' ? '"' : "";

        const values = options.map((option) => ({
            label: `${option}`,
            kind: typeof option === "string" ? CompletionItemKind.EnumMember : CompletionItemKind.Value,
            sortText: `0${option}`,
            // `=` の直後で引用符がまだなければ、文字列の値は引用符ごと挿入する
            insertText: typeof option === "string" && !quote ? `"${option}"` : `${option}${closingQuote}`,
        }));
        // 引用符の外なら関数呼び出しも書ける。列挙された値の後に並べる
        return quote ? values : [...values, ...this.functionItems(params)];
    }

    /**
     * 変数名の候補。`$` の直後では設定の `variables` と組み込みの `$markdoc.frontmatter.*` の名前を、
     * `$a.` のように階層を入力した後では、その値が持つキーを出す。
     *
     * 候補は `textEdit` で入力中の名前だけを置き換える。クライアントが補完の接頭辞を単語の区切りで決めると、
     * `$` や `.` を含めるかどうかが定まらないため。
     *
     * @param written 入力済みの階層（`a.b[0].`。`$` の直後なら空）。
     * @param typed 入力中の名前。
     */
    completeVariables(params, [, written, typed]) {
        const frontmatter = frontmatterOf(this.services.Documents.ast(params.textDocument.uri), this.services.Scanner);
        const root = withMarkdocVariables(this.schema(params)?.variables, frontmatter);
        const {line, character} = params.position;
        const range = {start: {line, character: character - typed.length}, end: {line, character}};
        const item = (label, value) => ({
            label,
            kind: CompletionItemKind.Variable,
            detail: valueType(value),
            filterText: label,
            textEdit: {range, newText: label},
        });

        const path = Array.from(written.matchAll(PATH_SEGMENT), ([, name, index, key]) =>
            name ?? (index !== undefined ? Number(index) : unescapeKey(key)),
        );
        if (path.length > 0) return childItems(lookup(root, path), item);

        // フロントマターのキーは `markdoc.frontmatter.title` の形でも直接出す（設定の変数の後に並べる）
        const frontmatterItems = childItems(frontmatter, (key, value) => ({
            ...item(`markdoc.frontmatter.${key}`, value),
            sortText: `1${key}`,
        }));
        return [...childItems(root, item), ...frontmatterItems];
    }

    /**
     * 関数名の候補。スキーマの `functions` と組み込みの関数を出し、括弧の中にカーソルを置く。
     * タグ名や属性値の候補と混ぜて返すので、`sortText` でそれらの後に並べる。
     */
    functionItems(params) {
        const functions = this.schema(params)?.functions ?? {};
        const names = new Set([...Object.keys(functions), ...BUILTIN_FUNCTIONS]);
        return Array.from(names, (name) => ({
            label: name,
            kind: CompletionItemKind.Function,
            detail: "function",
            documentation: functions[name]?.description ?? "",
            sortText: `2${name}`,
            insertText: `${name}($1)`,
            insertTextFormat: SNIPPET,
        }));
    }

    /** 補完を求められた文書に適用されるスキーマ。読み込めていなければ `undefined`。 */
    schema(params) {
        return this.services.Schema.get(params.textDocument.uri);
    }

    /** カーソルがある行の全文（改行を除く）。 */
    lineText({textDocument: {uri}, position: {line}}) {
        const doc = this.services.Documents.get(uri);
        const text = doc?.getText({start: {line, character: 0}, end: {line: line + 1, character: 0}}) ?? "";
        return text.replace(/\r?\n$/, "");
    }
}

/**
 * タグを挿入するスニペットを組み立てる。
 *
 * 必須の属性は値の入力位置を順に並べ、任意の属性があればその後に追加用の入力位置を置く。
 * 自己終了タグは `/%}` で閉じ、それ以外は閉じタグまで入れて本文の位置（`$0`）にカーソルを置く。
 *
 * @param context.leading タグ名の前に入れる空白。
 * @param context.block 開きタグと閉じタグを別の行に置くかどうか。
 * @param context.alreadyClosed カーソルの後ろに `%}` が既にあるかどうか。
 */
function tagSnippet(name, config, {leading, block, alreadyClosed}) {
    if (alreadyClosed) return name;

    const attributes = Object.entries(config.attributes ?? {});
    const required = attributes.filter(([, attribute]) => attribute.required);
    let index = 1;
    let attributeText = required
        .map(([attrName, attribute]) =>
            isStringAttribute(attribute) ? ` ${attrName}="\${${index++}}"` : ` ${attrName}=\${${index++}}`,
        )
        .join("");
    if (required.length < attributes.length) attributeText += `\${${index++}}`;

    const open = `${leading}${name}${attributeText}`;
    if (config.selfClosing) return `${open} /%}`;
    return block ? `${open} %}\n$0\n{% /${name} %}` : `${open} %}$0{% /${name} %}`;
}

/**
 * 文字列の添字の中身（`["a\"b"]` の `a\"b`）からエスケープを外す。
 * JSON の文字列として読めないエスケープ（`\x` など）は、そのままの文字列をキーにする。
 */
function unescapeKey(key) {
    try {
        return JSON.parse(`"${key}"`);
    } catch {
        return key;
    }
}

/** `value` をキーの並び `path` で辿った先の値。辿れなければ `undefined`。 */
function lookup(value, path) {
    return path.reduce(
        (current, key) =>
            current !== null && typeof current === "object" && Object.prototype.hasOwnProperty.call(current, key)
                ? current[key]
                : undefined,
        value,
    );
}

/** `value` がオブジェクトなら、そのキーごとに `item(キー, 値)` で作った候補を返す（配列は添字で書くので除く）。 */
function childItems(value, item) {
    if (value === null || typeof value !== "object" || Array.isArray(value)) return [];
    return Object.entries(value)
        .filter(([key]) => /^[\w-]+$/.test(key))
        .map(([key, child]) => item(key, child));
}

/** 変数の値の型を表示用の名前にする。 */
function valueType(value) {
    if (value === null) return "null";
    if (Array.isArray(value)) return "array";
    if (value instanceof Date) return "date";
    return typeof value;
}

/** スキーマの属性の型を表示用の名前にする（`String` などのコンストラクターか文字列で書かれる）。 */
function typeName(type) {
    if (Array.isArray(type)) return type.map(typeName).join(" | ");
    if (typeof type === "function") return type.name;
    return typeof type === "string" ? type : "";
}

/**
 * 値を引用符で囲んで書く属性かどうか。
 *
 * 数値・真偽値・配列・オブジェクトだけを受け付ける属性以外は文字列として扱う。
 * 組み込みの `partial` の `file` のように、独自のクラスを型にした属性も値は文字列で書く。
 */
function isStringAttribute(attribute) {
    if (Array.isArray(attribute.matches) && attribute.matches.some((option) => typeof option === "string")) return true;
    const types = Array.isArray(attribute.type) ? attribute.type : [attribute.type];
    return !types.every((type) => NON_STRING_TYPES.includes(type));
}

module.exports = {MarkdocCompletionProvider};
