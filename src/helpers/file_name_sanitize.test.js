const assert = require('assert');
const file_name_sanitize = require('./file_name_sanitize');
const test = require('node:test');

test('a plain name stays as it is', function () {
    assert.strictEqual(file_name_sanitize('IMG_20261004_123456.jpg'), 'IMG_20261004_123456.jpg');
    assert.strictEqual(file_name_sanitize('отчёт за май.pdf'), 'отчёт за май.pdf');
});

test('a path keeps only its last part', function () {
    assert.strictEqual(file_name_sanitize('../../etc/passwd'), 'passwd');
    assert.strictEqual(file_name_sanitize('C:\\Users\\me\\photo.png'), 'photo.png');
    assert.strictEqual(file_name_sanitize('dir/'), '');
});

test('hidden and empty names are refused', function () {
    assert.strictEqual(file_name_sanitize('.bashrc'), 'bashrc');
    assert.strictEqual(file_name_sanitize('..'), '');
    assert.strictEqual(file_name_sanitize('   '), '');
    assert.strictEqual(file_name_sanitize(null), '');
});

test('control and reserved characters become underscores', function () {
    assert.strictEqual(file_name_sanitize('a\nb\u0000c.txt'), 'a_b_c.txt');
    assert.strictEqual(file_name_sanitize('what? "now".txt'), 'what_ _now_.txt');
});

test('a long name is cut in its stem and keeps the extension', function () {
    const out = file_name_sanitize(`${'я'.repeat(300)}.jpeg`);
    assert.ok(Buffer.byteLength(out) <= 200);
    assert.ok(out.endsWith('я.jpeg'));
});
