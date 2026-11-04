// @markdoc/language-server を単一の JS ファイルにまとめ、ライセンス表記とバージョンを添える。
// 使い方: node build.mjs <出力ディレクトリ>
import { build } from "esbuild";
import { copyFileSync, existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { createRequire } from "node:module";
import path from "node:path";
import { fileURLToPath } from "node:url";

const outDir = path.resolve(process.argv[2] ?? "../build/server");
const require = createRequire(import.meta.url);
const pkgDir = path.dirname(require.resolve("@markdoc/language-server/package.json"));
const pkg = JSON.parse(readFileSync(path.join(pkgDir, "package.json"), "utf8"));

mkdirSync(outDir, { recursive: true });

await build({
  // 上流の dist/wrapper.js の代わりに、Windows 向けの修正を入れたエントリーポイントから起動する
  entryPoints: [fileURLToPath(new URL("./src/server.js", import.meta.url))],
  outfile: path.join(outDir, "markdoc-lsp-server.js"),
  bundle: true,
  platform: "node",
  target: "node18",
  format: "cjs",
  // 同梱した依存のライセンスコメントをファイル末尾に残す
  legalComments: "eof",
  logLevel: "info",
});

// npm パッケージには LICENSE が含まれないため、上流リポジトリの MIT 全文を代わりに添える
const license = path.join(pkgDir, "LICENSE");
const target = path.join(outDir, "LICENSE-markdoc-language-server.txt");
if (existsSync(license)) {
  copyFileSync(license, target);
} else {
  writeFileSync(target, `@markdoc/language-server ${pkg.version}\n${readFileSync(new URL("./LICENSE-upstream.txt", import.meta.url), "utf8")}`);
}
writeFileSync(path.join(outDir, "VERSION"), `${pkg.version}\n`);
