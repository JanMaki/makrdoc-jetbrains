const test = require("node:test");
const assert = require("node:assert");
const {ANNOTATION_PATTERN} = require("../src/completion");

test("入力済みの属性と入力中の名前を読む", () => {
    const m = ANNOTATION_PATTERN.exec('# 見出し {% id="a" class=b cl');
    assert.ok(m);
    assert.strictEqual(m[1], "# 見出し ");
    assert.strictEqual(m[2], ' id="a" class=b');
    assert.strictEqual(m[4], "cl");
});

test("行頭が {% の行は注釈ではない", () => {
    assert.strictEqual(ANNOTATION_PATTERN.exec("{% callout "), null);
});

test("= が長く続く行でも、一致しない場合に指数時間にならない", () => {
    const line = "a {%a" + "=a".repeat(5000) + "b%";
    const start = process.hrtime.bigint();
    assert.strictEqual(ANNOTATION_PATTERN.exec(line), null);
    const ms = Number(process.hrtime.bigint() - start) / 1e6;
    assert.ok(ms < 1000, `照合に ${ms}ms かかった`);
});
