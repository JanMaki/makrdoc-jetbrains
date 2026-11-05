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
  /**
   * 上流の検証設定のうち `partials` を、編集中の文書にある `{% partial %}` ごとにディスク上の実ファイルで判定し直す。
   *
   * Scanner のキャッシュはファイル削除を検知できないことがあるため、キャッシュにあるかどうかでは判定しない。
   */
  configuration(uri) {
    const config = super.configuration(uri);
    const { Documents, Scanner } = this.services;
    const ast = Documents.ast(uri);
    if (!ast) return config;

    const partials = { ...config.partials };
    for (const node of ast.walk()) {
      if (node.type !== "tag" || node.tag !== "partial") continue;
      const file = node.attributes?.file;
      if (typeof file !== "string" || !file) continue;
      const relative = toContentRelative(file);
      if (relative && isFileInside(Scanner.path, relative)) partials[file] = true;
      else delete partials[file];
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

/** シンボリックリンクを辿る回数の上限。循環するリンクで止まらなくなるのを防ぐ。 */
const MAX_LINK_HOPS = 40;

/**
 * `root` 基準の `relative` が、シンボリックリンクを解決した後も `root` の内側にある通常ファイルかどうか。
 *
 * `realpathSync` で一度に解決すると、リンク先が UNC パス（`\\server\share`）でもアクセスしてしまう。
 * そこでパスを 1 要素ずつ辿り、シンボリックリンクやジャンクションはリンク先がコンテンツルートの内側に
 * あることを確かめてから、リンク先と残りの要素を続けて辿る。
 */
function isFileInside(root, relative) {
  try {
    const realRoot = fs.realpathSync(root);
    const pending = relative.split(/[\\/]+/).filter(Boolean);
    let current = realRoot;
    let hops = 0;
    while (pending.length) {
      const part = pending.shift();
      if (part === ".") continue;
      const next = part === ".." ? path.dirname(current) : path.join(current, part);
      if (!isInside(realRoot, next)) return false;
      if (!fs.lstatSync(next).isSymbolicLink()) {
        current = next;
        continue;
      }
      if (++hops > MAX_LINK_HOPS) return false;
      const target = fs.readlinkSync(next);
      if (/^[\\/]{2}/.test(target)) return false;
      const resolved = path.resolve(current, target);
      if (!isInside(realRoot, resolved)) return false;
      // リンク先もリンクを含みうるので、ルートから辿り直す
      pending.unshift(...path.relative(realRoot, resolved).split(path.sep).filter(Boolean));
      current = realRoot;
    }
    return current !== realRoot && fs.statSync(current).isFile();
  } catch {
    return false;
  }
}

/** `target` が `root` 自身か、その内側を指すかどうか（どちらも絶対パス）。 */
function isInside(root, target) {
  const inner = path.relative(root, target);
  return inner === "" || (inner !== ".." && !inner.startsWith(`..${path.sep}`) && !path.isAbsolute(inner));
}

module.exports = { MarkdocValidationProvider };
