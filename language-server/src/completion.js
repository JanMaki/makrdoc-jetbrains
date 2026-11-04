const { Plugins } = require("@markdoc/language-server");

/** LSP の CompletionItemKind（vscode-languageserver の値と同じ）。 */
const CompletionItemKind = { Function: 3, Property: 10, Value: 12, EnumMember: 20 };

/** LSP の InsertTextFormat.Snippet。 */
const SNIPPET = 2;

/** 引用符なしで書く値の型。 */
const NON_STRING_TYPES = [Number, Boolean, Array, Object, "Number", "Boolean", "Array", "Object"];

/**
 * 補完のタイミングと挿入内容を JetBrains IDE 向けに整えた CompletionProvider。
 *
 * 上流の補完は「タグ名を 1 文字以上入力した後」と「属性値を 1 文字以上入力した後」だけで、
 * 属性名の補完はない。ここでは次の場面で候補を出す。
 * - `{%` や `{% /` の直後（タグ名）
 * - `{% callout ` や `{% callout t` のようにタグ名の後（属性名）
 * - `{% callout type=` や `{% callout type="` の直後（属性値）
 *
 * 挿入するテキストは候補を返す時点で決める。上流はタグのスニペットを completionItem/resolve で補うが、
 * JetBrains の LSP クライアントは resolve の結果から documentation しか使わない。
 */
class MarkdocCompletionProvider extends Plugins.CompletionProvider {
  constructor(config, connection, services) {
    super(config, connection, services);
    const [, , upstreamRoute] = this.completions;

    // 各パターンは行頭からカーソルまでの文字列に対して照合し、最初に一致したものを使う
    this.completions = [
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
   * タグ名の候補。
   *
   * 上流のスニペットには、自己終了タグの前に空白が入らない、`{% ` の後では閉じタグまで 1 行に
   * まとめてしまう、文字列の属性に引用符が付かない、といった問題があるため、[tagSnippet] で組み立て直す。
   */
  completeTagNames(params, [typed, spacing, closing]) {
    const tags = this.schema(params)?.tags;
    if (!tags) return [];

    const { character } = params.position;
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

    return Object.entries(tags).map(([name, config]) => ({
      label: name,
      kind: CompletionItemKind.Function,
      documentation: config.description ?? "",
      insertText: closing ? `${name} %}` : tagSnippet(name, config, context),
      insertTextFormat: SNIPPET,
    }));
  }

  /** 属性名の候補。入力済みの属性は除き、必須の属性を先に並べる。 */
  completeAttributeNames(params, [, tagName, written]) {
    const attributes = this.schema(params)?.tags?.[tagName]?.attributes;
    if (!attributes) return [];

    const used = new Set(Array.from(written.matchAll(/([\w-]+)=?/g), ([, name]) => name));
    return Object.entries(attributes)
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
        data: { triggerSuggest: true },
      }));
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
    const { character } = params.position;
    const closingQuote = quote && this.lineText(params)[character] !== '"' ? '"' : "";

    return options.map((option) => ({
      label: `${option}`,
      kind: typeof option === "string" ? CompletionItemKind.EnumMember : CompletionItemKind.Value,
      // `=` の直後で引用符がまだなければ、文字列の値は引用符ごと挿入する
      insertText: typeof option === "string" && !quote ? `"${option}"` : `${option}${closingQuote}`,
    }));
  }

  /** 補完を求められた文書に適用されるスキーマ。読み込めていなければ `undefined`。 */
  schema(params) {
    return this.services.Schema.get(params.textDocument.uri);
  }

  /** カーソルがある行の全文（改行を除く）。 */
  lineText({ textDocument: { uri }, position: { line } }) {
    const doc = this.services.Documents.get(uri);
    const text = doc?.getText({ start: { line, character: 0 }, end: { line: line + 1, character: 0 } }) ?? "";
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
function tagSnippet(name, config, { leading, block, alreadyClosed }) {
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

module.exports = { MarkdocCompletionProvider };
