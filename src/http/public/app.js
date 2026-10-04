const version = 1;

const file_input = document.getElementById('file_input');
const text_form = document.getElementById('text_form');
const text_input = document.getElementById('text_input');
const theme_toggle = document.getElementById('theme_toggle');
const uploads = document.getElementById('uploads');

const queue = [];
let sending = false;

main();

function main()
{
    // theme: stamped by index.html before the first paint; the toggle saves the choice
    theme_toggle.addEventListener('click', function () {
        const next_theme = (document.documentElement.dataset.theme === 'dark') ? 'light' : 'dark';
        document.documentElement.dataset.theme = next_theme;
        try {
            localStorage.setItem('theme', next_theme);
        }
        catch {
            report('theme-save-failed', next_theme);
        }
    });

    file_input.addEventListener('change', function () {
        for (const file of file_input.files) {
            queue_add(file, file.name);
        }
        file_input.value = '';
    });

    text_form.addEventListener('submit', function (event) {
        event.preventDefault();
        const text = text_input.value.trim();
        if (text === '') {
            return;
        }
        queue_add(new Blob([text], {type: 'text/plain'}), `note_${format_time_stamp(new Date())}.txt`);
        text_input.value = '';
    });

    refresh_info();
    report('page-loaded', `v${version} ${navigator.userAgent}`);
}

async function refresh_info()
{
    const res = await fetch('info');
    const info = await res.json();
    document.getElementById('host').textContent = `→ ${info.host}`;
    document.getElementById('apk_link').hidden = (info.apk_version === 0);
}

// uploads run one at a time, in the order they were chosen

function queue_add(blob, name)
{
    const row = document.createElement('li');
    const label = document.createElement('span');
    const progress = document.createElement('progress');
    label.textContent = name;
    progress.max = 1;
    progress.value = 0;
    row.append(label, progress);
    uploads.prepend(row);
    queue.push({blob, name, row, progress});
    queue_run();
}

async function queue_run()
{
    if (sending) {
        return;
    }
    sending = true;
    while (queue.length > 0) {
        const item = queue.shift();
        try {
            const answer = await upload_send(item);
            item.row.classList.add('done');
            item.row.firstChild.textContent = answer.saved;
        }
        catch (error) {
            item.row.classList.add('failed');
            item.row.firstChild.textContent = `${item.name}: ${error.message}`;
            report('upload-failed', `${item.name} ${error.message}`);
        }
    }
    sending = false;
}

// XHR, not fetch: only XHR reports upload progress in every browser.
function upload_send(item)
{
    return new Promise(function (resolve, reject) {
        const xhr = new XMLHttpRequest();
        const now = new Date();
        xhr.open('POST', `upload/${upload_id()}`);
        xhr.setRequestHeader('x-file-day', format_day(now));
        xhr.setRequestHeader('x-file-mtime', String(item.blob.lastModified ?? now.getTime()));
        xhr.setRequestHeader('x-file-path', encodeURIComponent(item.name));
        xhr.setRequestHeader('x-file-size', String(item.blob.size));
        xhr.setRequestHeader('x-upload-offset', '0');
        xhr.upload.addEventListener('progress', function (event) {
            item.progress.value = event.lengthComputable ? (event.loaded/event.total) : 0;
        });
        xhr.addEventListener('load', function () {
            if (xhr.status !== 200) {
                reject(new Error(`http ${xhr.status}`));
                return;
            }
            item.progress.value = 1;
            resolve(JSON.parse(xhr.responseText));
        });
        xhr.addEventListener('error', () => reject(new Error('connection lost')));
        xhr.send(item.blob);
    });
}

// crypto.randomUUID needs a secure context; a page on a LAN address is not one.
function upload_id()
{
    const bytes = crypto.getRandomValues(new Uint8Array(12));
    return Array.from(bytes, v => v.toString(16).padStart(2, '0')).join('');
}

// diagnostics: every event lands in the server console on the laptop

function report(event, detail)
{
    fetch('client-log', {
        body: JSON.stringify({version: `page-${version}`, event, detail}),
        headers: {'content-type': 'application/json'},
        method: 'POST',
    }).catch(ignore);
}

function format_day(date)
{
    return `${date.getFullYear()}-${pad2(date.getMonth() + 1)}-${pad2(date.getDate())}`;
}

function format_time_stamp(date)
{
    return `${format_day(date)}_${pad2(date.getHours())}-${pad2(date.getMinutes())}-${pad2(date.getSeconds())}`;
}

function pad2(value)
{
    return String(value).padStart(2, '0');
}

function ignore()
{
}
