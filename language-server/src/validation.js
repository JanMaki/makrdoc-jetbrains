const fs = require("node:fs");
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
      if (Scanner.has(file) || isFile(Scanner.fullPath(file))) partials[file] = true;
    }
    return { ...config, partials };
  }
}

function isFile(path) {
  try {
    return fs.statSync(path).isFile();
  } catch {
    return false;
  }
}

module.exports = { MarkdocValidationProvider };
