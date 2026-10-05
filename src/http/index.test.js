const assert = require('assert');
const child_process = require('child_process');
const crypto = require('crypto');
const dgram = require('dgram');
const fs = require('fs');
const fs_path_join = require('@vbarbarosh/node-helpers/src/fs_path_join');
const http = require('http');
const os = require('os');
const test = require('node:test');

const port = 20000 + Math.floor(Math.random()*20000);
const base_url = `http://127.0.0.1:${port}`;

let drop_dir = null;
let uploads_dir = null;
let server = null;

test.before(async function () {
    drop_dir = fs.mkdtempSync(fs_path_join(os.tmpdir(), 'file-drop-test-'));
    // The state on another disk than the drop folder, as with docker's two
    // mounts: a finished part cannot be renamed into place, it is copied.
    const state_root = fs.existsSync('/dev/shm') ? '/dev/shm' : os.tmpdir();
    uploads_dir = fs.mkdtempSync(fs_path_join(state_root, 'file-drop-test-uploads-'));
    const week_ago = new Date(Date.now() - 8*24*3600*1000);
    fs.writeFileSync(fs_path_join(uploads_dir, 'old-upload-id.part'), 'abandoned');
    fs.utimesSync(fs_path_join(uploads_dir, 'old-upload-id.part'), week_ago, week_ago);
    fs.writeFileSync(fs_path_join(uploads_dir, 'new-upload-id.part'), 'resumable');
    server = child_process.spawn(process.execPath, [fs_path_join(__dirname, 'index.js'), drop_dir, uploads_dir], {env: {...process.env, PORT: String(port)}});
    await server_wait_listen();
});

test.after(function () {
    server.kill();
    fs.rmSync(drop_dir, {force: true, recursive: true});
    fs.rmSync(uploads_dir, {force: true, recursive: true});
});

test('a part older than a week is forgotten, a fresh one waits', function () {
    assert.deepStrictEqual(fs.readdirSync(uploads_dir), ['new-upload-id.part']);
});

test('an upload lands in the drop folder under its own name with the phone time', async function () {
    const mtime = Date.parse('2026-10-04T12:34:56Z');
    const answer = await upload_whole(upload_id(), 'IMG_0001.jpg', 'first photo', {'x-file-mtime': String(mtime)});
    assert.deepStrictEqual(answer, {offset: 11, saved: 'IMG_0001.jpg'});
    const file_path = fs_path_join(drop_dir, 'IMG_0001.jpg');
    assert.strictEqual(fs.readFileSync(file_path, 'utf8'), 'first photo');
    assert.strictEqual(fs.statSync(file_path).mtimeMs, mtime);
});

test('a finished upload sent again changes nothing', async function () {
    const id = upload_id();
    await upload_whole(id, 'note.txt', 'hello');
    const again = await upload_whole(id, 'note.txt', 'hello');
    assert.deepStrictEqual(again, {offset: 5, saved: 'note.txt'});
    assert.ok(!fs.existsSync(fs_path_join(drop_dir, 'note_1.txt')));
    assert.deepStrictEqual(await upload_state(id), {offset: 0, saved: 'note.txt', busy: false});
});

test('the same file under a new id is stored once', async function () {
    const answer = await upload_whole(upload_id(), 'IMG_0001.jpg', 'first photo');
    assert.strictEqual(answer.saved, 'IMG_0001.jpg');
    assert.ok(!fs.existsSync(fs_path_join(drop_dir, 'IMG_0001_1.jpg')));
});

test('other bytes under a taken name get _1, then _2', async function () {
    const answer = await upload_whole(upload_id(), 'IMG_0001.jpg', 'second photo');
    assert.strictEqual(answer.saved, 'IMG_0001_1.jpg');
    const third = await upload_whole(upload_id(), 'IMG_0001.jpg', 'third photo');
    assert.strictEqual(third.saved, 'IMG_0001_2.jpg');
    assert.strictEqual(fs.readFileSync(fs_path_join(drop_dir, 'IMG_0001.jpg'), 'utf8'), 'first photo');
});

test('a folder keeps its tree', async function () {
    const answer = await upload_whole(upload_id(), 'Trip/day 1/a.jpg', 'a');
    assert.strictEqual(answer.saved, 'Trip/day 1/a.jpg');
    assert.ok(fs.existsSync(fs_path_join(drop_dir, 'Trip', 'day 1', 'a.jpg')));
});

test('a path cannot leave the drop folder', async function () {
    const answer = await upload_whole(upload_id(), '../../escape.txt', 'no');
    assert.strictEqual(answer.saved, 'escape.txt');
});

test('a name outside ascii arrives intact', async function () {
    const answer = await upload_whole(upload_id(), 'отчёт за май.pdf', 'pdf');
    assert.strictEqual(answer.saved, 'отчёт за май.pdf');
});

test('an upload sent in two parts continues from the offset', async function () {
    const id = upload_id();
    const body = crypto.randomBytes(100000);
    const first = await upload_part(id, 'video.mp4', body, 0, 40000);
    assert.deepStrictEqual(first, {offset: 40000, saved: null});
    assert.deepStrictEqual(await upload_state(id), {offset: 40000, saved: null, busy: false});
    const second = await upload_part(id, 'video.mp4', body, 40000, body.length);
    assert.strictEqual(second.saved, 'video.mp4');
    assert.ok(fs.readFileSync(fs_path_join(drop_dir, 'video.mp4')).equals(body));
});

test('a wrong offset is refused with the right one', async function () {
    const id = upload_id();
    const body = Buffer.from('0123456789');
    await upload_part(id, 'digits.txt', body, 0, 4);
    const res = await fetch(`${base_url}/upload/${id}`, {
        body: body.subarray(6),
        headers: upload_headers('digits.txt', body.length, 6),
        method: 'POST',
    });
    assert.strictEqual(res.status, 409);
    assert.strictEqual(res.headers.get('x-upload-offset'), '4');
});

test('a connection cut mid-file keeps what arrived', async function () {
    const id = upload_id();
    const body = crypto.randomBytes(300000);
    await upload_cut(id, 'cut.bin', body, 120000);
    const state = await upload_state_settled(id, 120000);
    assert.deepStrictEqual(state, {offset: 120000, saved: null, busy: false});
    const answer = await upload_part(id, 'cut.bin', body, 120000, body.length);
    assert.strictEqual(answer.saved, 'cut.bin');
    assert.ok(fs.readFileSync(fs_path_join(drop_dir, 'cut.bin')).equals(body));
});

test('an upload in progress shows as busy', async function () {
    const id = upload_id();
    const body = crypto.randomBytes(100000);
    const headers = {...upload_headers('slow.bin', body.length, 0), 'content-length': String(body.length)};
    const req = http.request(`${base_url}/upload/${id}`, {headers, method: 'POST'});
    req.on('error', ignore);
    req.write(body.subarray(0, 1000));
    await new Promise(v => setTimeout(v, 100));
    assert.strictEqual((await upload_state(id)).busy, true);
    req.end(body.subarray(1000));
    await upload_state_settled(id, body.length);
    assert.strictEqual((await upload_state(id)).saved, 'slow.bin');
});

test('an upload with no size is refused', async function () {
    const res = await fetch(`${base_url}/upload/${upload_id()}`, {body: 'x', method: 'POST'});
    assert.strictEqual(res.status, 400);
});

test('info names the server', async function () {
    const res = await fetch(`${base_url}/info`);
    const info = await res.json();
    assert.strictEqual(info.name, 'file-drop');
    assert.strictEqual(info.host, os.hostname());
    assert.strictEqual(typeof info.apk_version, 'number');
});

test('the page is served', async function () {
    const res = await fetch(`${base_url}/`);
    assert.strictEqual(res.status, 200);
    assert.match(await res.text(), /<title>File Drop<\/title>/);
});

test('a discovery question gets the port back', async function () {
    const answer = await discovery_ask();
    assert.deepStrictEqual(answer, {name: 'file-drop', host: os.hostname(), port});
});

test('the state really is on another disk', function () {
    if (fs.statSync(drop_dir).dev === fs.statSync(uploads_dir).dev) {
        return;
    }
    assert.notStrictEqual(fs.statSync(drop_dir).dev, fs.statSync(uploads_dir).dev);
});

test('no hidden copy is left in the drop folder', async function () {
    await upload_whole(upload_id(), 'moved.txt', 'across disks');
    assert.deepStrictEqual(fs.readdirSync(drop_dir).filter(v => v.startsWith('.')), []);
    assert.strictEqual(fs.readFileSync(fs_path_join(drop_dir, 'moved.txt'), 'utf8'), 'across disks');
});

function upload_id()
{
    return crypto.randomBytes(8).toString('hex');
}

function upload_headers(relative, size, offset)
{
    return {
        'x-file-path': encodeURIComponent(relative),
        'x-file-size': String(size),
        'x-upload-offset': String(offset),
    };
}

async function upload_whole(id, relative, body, headers = {})
{
    const res = await fetch(`${base_url}/upload/${id}`, {
        body,
        headers: {...upload_headers(relative, Buffer.byteLength(body), 0), ...headers},
        method: 'POST',
    });
    assert.strictEqual(res.status, 200);
    return res.json();
}

async function upload_part(id, relative, body, begin, end)
{
    const res = await fetch(`${base_url}/upload/${id}`, {
        body: body.subarray(begin, end),
        headers: upload_headers(relative, body.length, begin),
        method: 'POST',
    });
    assert.strictEqual(res.status, 200);
    return res.json();
}

async function upload_state(id)
{
    const res = await fetch(`${base_url}/upload/${id}`);
    return res.json();
}

// The server sees the cut a moment after the socket closes.
async function upload_state_settled(id, offset)
{
    for (let i = 0; i < 50; ++i) {
        const state = await upload_state(id);
        if ((state.offset === offset) && !state.busy) {
            return state;
        }
        await new Promise(v => setTimeout(v, 20));
    }
    return upload_state(id);
}

// Promises the whole body, sends part of it, and drops the connection.
function upload_cut(id, relative, body, sent)
{
    return new Promise(function (resolve) {
        const headers = {...upload_headers(relative, body.length, 0), 'content-length': String(body.length)};
        const req = http.request(`${base_url}/upload/${id}`, {headers, method: 'POST'});
        req.on('error', ignore);
        req.write(body.subarray(0, sent), function () {
            setTimeout(destroy, 100);
        });
        function destroy() {
            req.destroy();
            resolve();
        }
    });
}

function server_wait_listen()
{
    return new Promise(function (resolve, reject) {
        let output = '';
        server.stdout.on('data', function (chunk) {
            output += chunk;
            if (output.includes('[server_listen]')) {
                resolve();
            }
        });
        server.on('exit', v => reject(new Error(`server exited with ${v}: ${output}`)));
    });
}

function discovery_ask()
{
    return new Promise(function (resolve, reject) {
        const socket = dgram.createSocket('udp4');
        const timer = setTimeout(timeout, 2000);
        socket.on('message', function (message) {
            clearTimeout(timer);
            socket.close();
            resolve(JSON.parse(message.toString()));
        });
        socket.send('file-drop?', port, '127.0.0.1');
        function timeout() {
            socket.close();
            reject(new Error('no discovery answer'));
        }
    });
}

function ignore()
{
}
