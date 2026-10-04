const assert = require('assert');
const file_path_sanitize = require('./file_path_sanitize');
const test = require('node:test');

test('a folder tree is kept', function () {
    assert.strictEqual(file_path_sanitize('Camera/2026/IMG_1.jpg'), 'Camera/2026/IMG_1.jpg');
    assert.strictEqual(file_path_sanitize('IMG_1.jpg'), 'IMG_1.jpg');
});

test('dot parts and empty parts are dropped', function () {
    assert.strictEqual(file_path_sanitize('../../etc/passwd'), 'etc/passwd');
    assert.strictEqual(file_path_sanitize('/a//b/./c.txt'), 'a/b/c.txt');
    assert.strictEqual(file_path_sanitize('a\\b\\c.txt'), 'a/b/c.txt');
});

test('nothing usable gives an empty path', function () {
    assert.strictEqual(file_path_sanitize('../..'), '');
    assert.strictEqual(file_path_sanitize(undefined), '');
});

test('a deep tree keeps its last 16 levels', function () {
    const deep = Array.from({length: 20}, (v, i) => `d${i}`).join('/');
    assert.strictEqual(file_path_sanitize(`${deep}/f.txt`).split('/').length, 16);
});
