const path = require("node:path");
const { Services } = require("@markdoc/language-server");

/**
 * パスの表記を vscode-uri の `fsPath` に合わせた Scanner。
 *
 * 上流の Scanner は Windows で次の 2 点が食い違い、partial が見つからないというエラーになる。
 * - 基準パスは `root` から作るためドライブ文字が大文字（`D:\`）だが、検証時に `fsPath` から得るパスは
 *   小文字（`d:\`）になり、前方一致で比較すると現在のファイルが見つからない。
 * - ファイルを OS ネイティブの相対パス（`partials\footer.mdoc`）で登録する一方、partial の照合には
 *   タグに書かれた `/` 区切りの値（`partials/footer.mdoc`）を使う。
 */
class FsPathScanner extends Services.Scanner {
  constructor(config) {
    super(config);
    this.path = lowerDriveLetter(this.path);
  }

  relativePath(file) {
    const relative = super.relativePath(file);
    // POSIX では区切り文字がもともと `/` なので変換しない
    return path.sep === "/" ? relative : relative.split(path.sep).join("/");
  }
}

/** vscode-uri の `fsPath` と同じく、Windows のドライブ文字を小文字にする。 */
function lowerDriveLetter(fsPath) {
  return /^[A-Z]:/.test(fsPath) ? fsPath[0].toLowerCase() + fsPath.slice(1) : fsPath;
}

module.exports = { FsPathScanner };
