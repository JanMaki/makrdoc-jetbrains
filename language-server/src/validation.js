const fs = require("node:fs");
const path = require("node:path");
const { Plugins } = require("@markdoc/language-server");

/**
 * partial の存在確認を、編集中の文書とディスク上のファイルで行う ValidationProvider。
 *
 * 上流は検証に使う `config.partials` を Scanner が持つ「保存済みファイルの partial 一覧」から作る。
 * Scanner は起動時のスキャンとファイル監視（動的登録が必要で、JetBrains では届かない）でしか更新されないため、
 * 起動後に書いた `{% partial %}` は常に「見つからない」と報告される。
 */
class MarkdocValidationProvider extends Plugins.ValidationProvider {
  configuration(uri) {
    const config = super.configuration(uri);
    const { Documents, Scanner } = this.services;
    const ast = Documents.ast(uri);
    if (!ast) return config;

    const partials = { ...config.partials };
    for (const node of ast.walk()) {
      if (node.type !== "tag" || node.tag !== "partial") continue;
      const file = node.attributes?.file;
      if (typeof file !== "string" || !file || partials[file]) continue;
      const relative = toContentRelative(file);
      if (!relative) continue;
      if (Scanner.has(relative) || isFileInside(Scanner.path, relative)) partials[file] = true;
    }
    return { ...config, partials };
  }
}

/**
 * `file` をコンテンツルートからの相対パスにする。IDE の補完・ジャンプと同じく先頭の `/` はコンテンツルートを指す。
 * UNC パスやドライブ付きの絶対パスはコンテンツルートの外を指すので `null` を返す。
 */
function toContentRelative(file) {
  if (/^[\\/]{2}/.test(file)) return null;
  const relative = file.replace(/^[\\/]/, "");
  if (!relative || path.isAbsolute(relative) || path.win32.isAbsolute(relative)) return null;
  return relative;
}

/** `root` 基準の `relative` が、シンボリックリンクを解決した後も `root` の内側にある通常ファイルかどうか。 */
function isFileInside(root, relative) {
  try {
    const realRoot = fs.realpathSync(root);
    const realTarget = fs.realpathSync(path.resolve(root, relative));
    const inner = path.relative(realRoot, realTarget);
    if (!inner || inner === ".." || inner.startsWith(`..${path.sep}`) || path.isAbsolute(inner)) return false;
    return fs.statSync(realTarget).isFile();
  } catch {
    return false;
  }
}

module.exports = { MarkdocValidationProvider };
