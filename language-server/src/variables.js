/**
 * 補完（completion.js）と検証（validation.js）で共有する、文書から参照できる変数の組み立て。
 *
 * Markdoc の文書は、設定の `variables` に加えて、組み込みの `$markdoc.frontmatter` で自身のフロントマターを参照できる。
 */

/**
 * AST ごとのフロントマターの解析結果。
 *
 * 検証は文書が変わるたびに走り、補完も入力のたびに走るため、同じ AST の YAML を何度も読み直さないようにする。
 * 上流の Documents は変更のたびに AST を作り直すので、古い AST の結果は WeakMap から自然に消える。
 */
const frontmatterCache = new WeakMap();

/**
 * 文書の AST [ast] のフロントマター。なければ、または YAML として読めなければ空のオブジェクト。
 *
 * YAML の解釈は上流の Scanner に任せる（上流が同梱する yaml パッケージを使い、構文エラーは空のオブジェクトにする）。
 * 返す値はキャッシュと共有するので、呼び出し側で書き換えないこと。
 */
function frontmatterOf(ast, Scanner) {
    const source = ast?.attributes?.frontmatter;
    if (!source) return {};
    let value = frontmatterCache.get(ast);
    if (value === undefined) {
        const parsed = Scanner.frontmatter(source);
        value = parsed && typeof parsed === "object" ? parsed : {};
        frontmatterCache.set(ast, value);
    }
    return value;
}

/**
 * 設定の `variables` に、組み込みの `markdoc.frontmatter` を加えたオブジェクト（元のオブジェクトは変えない）。
 * 設定に `markdoc` という変数があれば、その中身は残して `frontmatter` だけを上書きする。
 */
function withMarkdocVariables(variables, frontmatter) {
    const base = variables && typeof variables === "object" ? variables : {};
    return {...base, markdoc: {...base.markdoc, frontmatter}};
}

module.exports = {frontmatterOf, withMarkdocVariables};
