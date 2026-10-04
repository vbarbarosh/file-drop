const cli = require('@vbarbarosh/node-helpers/src/cli');
const dgram = require('dgram');
const file_path_sanitize = require('../helpers/file_path_sanitize');
const format_log_value = require('../helpers/format_log_value');
const format_seconds = require('../helpers/format_seconds');
const fs = require('fs');
const fs_exists = require('@vbarbarosh/node-helpers/src/fs_exists');
const fs_mkdirp = require('@vbarbarosh/node-helpers/src/fs_mkdirp');
const fs_path_dirname = require('@vbarbarosh/node-helpers/src/fs_path_dirname');
const fs_path_extname = require('@vbarbarosh/node-helpers/src/fs_path_extname');
const fs_path_join = require('@vbarbarosh/node-helpers/src/fs_path_join');
const fs_path_resolve = require('@vbarbarosh/node-helpers/src/fs_path_resolve');
const fs_read_buffer = require('@vbarbarosh/node-helpers/src/fs_read_buffer');
const fs_read_utf8 = require('@vbarbarosh/node-helpers/src/fs_read_utf8');
const fs_readdir = require('@vbarbarosh/node-helpers/src/fs_readdir');
const fs_rename = require('@vbarbarosh/node-helpers/src/fs_rename');
const fs_rmf = require('@vbarbarosh/node-helpers/src/fs_rmf');
const fs_sha256 = require('@vbarbarosh/node-helpers/src/fs_sha256');
const fs_size_enoent = require('@vbarbarosh/node-helpers/src/fs_size_enoent');
const fs_stat = require('@vbarbarosh/node-helpers/src/fs_stat');
const fs_write = require('@vbarbarosh/node-helpers/src/fs_write');
const fs_write_stream = require('@vbarbarosh/node-helpers/src/fs_write_stream');
const http = require('http');
const log = require('../helpers/log');
const log_group_spawn = require('../helpers/log_group_spawn');
const os = require('os');
const path = require('path');
const stream_promises = require('stream/promises');

const public_dir = fs_path_join(__dirname, 'public');
const data_dir = (process.argv[2] === undefined) ? fs_path_resolve(__dirname, '../../data') : fs_path_resolve(process.argv[2]);
const uploads_dir = fs_path_join(data_dir, '.uploads');
const port = Number(process.env.PORT ?? 8080);
const discovery_question = 'file-drop?';
const part_max_age_ms = 7*24*3600*1000;
const done_max_age_ms = 30*24*3600*1000;

const mime_by_ext = {
    '.apk': 'application/vnd.android.package-archive',
    '.css': 'text/css',
    '.html': 'text/html; charset=utf-8',
    '.js': 'text/javascript',
    '.svg': 'image/svg+xml',
    '.txt': 'text/plain',
    '.webmanifest': 'application/manifest+json',
};

const busy_ids = new Set();
let group_uid = null;
let place_queue = Promise.resolve();

cli(main);

async function main()
{
    group_uid = log_group_spawn();

    await fs_mkdirp(uploads_dir);
    await uploads_remove_old();

    const server = http.createServer(function (req, res) {
        const upload_match = req.url.match(/^\/upload\/([A-Za-z0-9_-]{8,64})$/);
        if ((req.method === 'GET') && upload_match) {
            upload_get(req, res, upload_match[1]);
            return;
        }
        if ((req.method === 'POST') && upload_match) {
            upload_post(req, res, upload_match[1]);
            return;
        }
        if ((req.method === 'GET') && (req.url === '/info')) {
            info_get(req, res);
            return;
        }
        if ((req.method === 'POST') && (req.url === '/client-log')) {
            client_log_post(req, res);
            return;
        }
        static_get(req, res);
    });

    // A video over wifi takes minutes: no limit on a whole request. A phone
    // that walked out of the wifi sends nothing more: its socket is closed
    // after a minute of silence, and the upload is free to resume.
    server.requestTimeout = 0;
    server.timeout = 60000;

    server.listen(port, '0.0.0.0', function () {
        log(group_uid, 'server_listen', `port=${port} dir=${format_log_value(data_dir)}`);
        // Open on the phone, on the same wifi.
        for (const url of list_lan_urls()) {
            log(group_uid, 'server_lan_url', url);
        }
    });

    discovery_listen();
}

// GET /upload/:id
// How much of the upload the server holds, where it went once complete, and
// whether a connection still holds it: a phone that lost the wifi mid-file
// comes back before the server gives up on the old connection.
async function upload_get(req, res, id)
{
    upload_answer(res, 200, {offset: await fs_size_enoent(part_path_of(id)), saved: await done_read(id), busy: busy_ids.has(id)});
}

// POST /upload/:id (body, header:x-upload-offset, header:x-file-size,
//                   header:x-file-path, header:x-file-mtime, header:x-file-day)
// The body is the file from x-upload-offset on; it is appended to a part
// file, so a connection lost mid-file continues where it stopped. The last
// byte moves the part to data/<day>/<path>, and the id is remembered as done:
// repeating a finished upload changes nothing.
async function upload_post(req, res, id)
{
    const time0 = Date.now();
    const offset = Number(req.headers['x-upload-offset'] ?? 0);
    const size = Number(req.headers['x-file-size']);
    const relative = upload_relative_path(req, id);
    const part_path = part_path_of(id);

    const saved = await done_read(id);
    if (saved !== null) {
        upload_refuse(req, res, 200, {offset: size, saved});
        return;
    }
    if (!Number.isSafeInteger(size) || (size < 0)) {
        upload_refuse(req, res, 400, {error: 'x-file-size is required'});
        return;
    }
    if (busy_ids.has(id)) {
        upload_refuse(req, res, 409, {offset: await fs_size_enoent(part_path), error: 'busy'});
        return;
    }
    const part_size = await fs_size_enoent(part_path);
    if (offset !== part_size) {
        upload_refuse(req, res, 409, {offset: part_size, error: 'offset mismatch'});
        return;
    }

    busy_ids.add(id);
    try {
        await stream_promises.pipeline(req, fs_write_stream(part_path, {flags: 'a'}));
    }
    catch (error) {
        const kept = await fs_size_enoent(part_path);
        log(group_uid, 'upload_interrupted', `file=${format_log_value(relative)} offset=${kept} size=${size} error=${format_log_value(error.message)}`);
        upload_answer(res, 400, {offset: kept, error: 'interrupted'});
        return;
    }
    finally {
        busy_ids.delete(id);
    }

    const received = await fs_size_enoent(part_path);
    if (received < size) {
        upload_answer(res, 200, {offset: received, saved: null});
        return;
    }
    if (received > size) {
        await fs_rmf(part_path);
        log(group_uid, 'upload_oversize', `file=${format_log_value(relative)} received=${received} size=${size}`);
        upload_answer(res, 400, {offset: 0, error: 'more bytes than x-file-size'});
        return;
    }

    const placed = await place_serial(() => part_place(part_path, relative));
    await done_write(id, placed.saved);
    if (!placed.duplicate) {
        await mtime_restore(fs_path_join(data_dir, placed.saved), req.headers['x-file-mtime']);
    }
    const sender = placed.duplicate ? 'upload_duplicate' : 'upload_saved';
    log(group_uid, sender, `file=${format_log_value(placed.saved)} bytes=${size} ${format_seconds(Date.now() - time0)}`);
    upload_answer(res, 200, {offset: size, saved: placed.saved});
}

// GET /info
// The app's check: who answers, and which apk it serves.
async function info_get(req, res)
{
    json_send(res, 200, {name: 'file-drop', host: os.hostname(), apk_version: await apk_version_read()});
}

// POST /client-log (body)
// A phone event, echoed to the console.
function client_log_post(req, res)
{
    const chunks = [];
    req.on('data', v => chunks.push(v));
    req.on('end', function () {
        log(group_uid, 'phone_report', format_phone_report(Buffer.concat(chunks).toString()));
        res.writeHead(204);
        res.end();
    });
}

// GET /<path>
async function static_get(req, res)
{
    const url_path = req.url.split('?')[0];
    const relative = (url_path === '/') ? 'index.html' : url_path.slice(1);
    const file_path = fs_path_join(public_dir, relative);

    if (!file_path.startsWith(`${public_dir}${path.sep}`) || !(await fs_exists(file_path))) {
        res.writeHead(404, {'content-type': 'text/plain'});
        res.end('not found');
        return;
    }

    res.writeHead(200, {
        'cache-control': 'no-store',
        'content-type': mime_by_ext[fs_path_extname(file_path)] ?? 'application/octet-stream',
    });
    res.end(await fs_read_buffer(file_path));
}

// The app asks the whole wifi "file-drop?" on this port, over UDP; the
// answer's source address is the laptop, so no address is ever typed.
function discovery_listen()
{
    const socket = dgram.createSocket('udp4');
    socket.on('message', function (message, remote) {
        if (message.toString() !== discovery_question) {
            return;
        }
        const answer = JSON.stringify({name: 'file-drop', host: os.hostname(), port});
        socket.send(answer, remote.port, remote.address);
        log(group_uid, 'discovery_answer', `to=${remote.address}`);
    });
    socket.on('error', function (error) {
        log(group_uid, 'discovery_error', `error=${format_log_value(error.message)}`);
        socket.close();
    });
    socket.bind(port, '0.0.0.0');
}

// Places run one at a time, so two uploads of one name never take one place.
function place_serial(fn)
{
    const out = place_queue.then(fn);
    place_queue = out.catch(ignore);
    return out;
}

// photo.jpg, photo_2.jpg, photo_3.jpg...: the first free name wins. A name
// already holding the same bytes means the file came before: the part goes.
async function part_place(part_path, relative)
{
    const digest = await fs_sha256(part_path);
    const part_size = await fs_size_enoent(part_path);
    let counter = 1;
    while (true) {
        const candidate = name_numbered(relative, counter);
        const candidate_path = fs_path_join(data_dir, candidate);
        if (!(await fs_exists(candidate_path))) {
            await fs_mkdirp(fs_path_dirname(candidate_path));
            await fs_rename(part_path, candidate_path);
            return {saved: candidate, duplicate: false};
        }
        const stat = await fs_stat(candidate_path);
        if (stat.isFile() && (stat.size === part_size) && ((await fs_sha256(candidate_path)) === digest)) {
            await fs_rmf(part_path);
            return {saved: candidate, duplicate: true};
        }
        counter += 1;
    }
}

// The phone's own time of the file, so the folder sorts by when a photo was taken.
async function mtime_restore(file_path, header)
{
    const mtime = Number(header);
    if (!Number.isFinite(mtime) || (mtime <= 0)) {
        return;
    }
    await fs.promises.utimes(file_path, new Date(), new Date(mtime));
}

// A part waits a week for its phone to come back; a done id is kept a month,
// longer than any phone retries.
async function uploads_remove_old()
{
    const now = Date.now();
    for (const name of await fs_readdir(uploads_dir)) {
        const file_path = fs_path_join(uploads_dir, name);
        const stat = await fs_stat(file_path);
        const max_age_ms = name.endsWith('.part') ? part_max_age_ms : done_max_age_ms;
        if ((now - stat.mtimeMs) > max_age_ms) {
            await fs_rmf(file_path);
            log(group_uid, 'upload_forgotten', `file=${format_log_value(name)}`);
        }
    }
}

// data/<day>/<path>: the day is the phone's, the day the file was added to
// its outbox, so a file sent late still sits with its day.
function upload_relative_path(req, id)
{
    const header_day = req.headers['x-file-day'] ?? '';
    const day = /^\d{4}-\d\d-\d\d$/.test(header_day) ? header_day : format_today();
    let decoded = req.headers['x-file-path'] ?? '';
    try {
        decoded = decodeURIComponent(decoded);
    }
    catch {
        // a raw name with a stray %, kept as it came
    }
    const relative = file_path_sanitize(decoded);
    return `${day}/${(relative === '') ? `file_${id}` : relative}`;
}

function name_numbered(relative, counter)
{
    if (counter === 1) {
        return relative;
    }
    const ext = fs_path_extname(relative);
    return `${relative.slice(0, relative.length - ext.length)}_${counter}${ext}`;
}

function part_path_of(id)
{
    return fs_path_join(uploads_dir, `${id}.part`);
}

async function done_read(id)
{
    try {
        return await fs_read_utf8(fs_path_join(uploads_dir, `${id}.done`));
    }
    catch {
        return null;
    }
}

async function done_write(id, saved)
{
    await fs_write(fs_path_join(uploads_dir, `${id}.done`), saved);
}

async function apk_version_read()
{
    try {
        return Number((await fs_read_utf8(fs_path_join(public_dir, 'apk-version.txt'))).trim());
    }
    catch {
        return 0;
    }
}

// An answer before the body is read: the body is not wanted, a whole video
// would be drained for nothing, so the connection closes after the answer.
function upload_refuse(req, res, status, body)
{
    res.setHeader('connection', 'close');
    res.on('finish', () => req.destroy());
    upload_answer(res, status, body);
}

// The state goes twice: as headers for the app, which reads no JSON, and as
// JSON for the page.
function upload_answer(res, status, body)
{
    if (res.headersSent) {
        return;
    }
    const headers = {'content-type': 'application/json', 'x-upload-offset': String(body.offset ?? 0)};
    if (body.saved) {
        headers['x-file-saved'] = encodeURIComponent(body.saved);
    }
    if (body.busy) {
        headers['x-upload-busy'] = '1';
    }
    res.writeHead(status, headers);
    res.end(JSON.stringify(body));
}

function json_send(res, status, body)
{
    res.writeHead(status, {'content-type': 'application/json'});
    res.end(JSON.stringify(body));
}

function format_phone_report(body)
{
    let report;
    try {
        report = JSON.parse(body);
    }
    catch {
        return `invalid=${format_log_value(body.slice(0, 200))}`;
    }
    return `version=${format_log_value(report.version)} event=${format_log_value(report.event)} detail=${format_log_value(report.detail)}`;
}

function format_today()
{
    const now = new Date();
    return `${now.getFullYear()}-${pad2(now.getMonth() + 1)}-${pad2(now.getDate())}`;
}

function pad2(value)
{
    return String(value).padStart(2, '0');
}

function list_lan_urls()
{
    const out = [];
    for (const addresses of Object.values(os.networkInterfaces())) {
        for (const address of addresses) {
            if ((address.family === 'IPv4') && !address.internal) {
                out.push(`http://${address.address}:${port}/`);
            }
        }
    }
    return out;
}

function ignore()
{
}
