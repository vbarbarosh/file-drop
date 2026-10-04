// A name sent by a client becomes one safe file name in the drop folder: no
// path, no control characters, no leading dot, at most 200 bytes. Returns ''
// when nothing usable is left.
function file_name_sanitize(name)
{
    const base = String(name ?? '').split(/[\\/]/).pop();
    const printable = base.replace(/[\u0000-\u001f\u007f<>:"|?*]/g, '_').trim();
    const visible = printable.replace(/^\.+/, '');
    if (visible === '') {
        return '';
    }
    return name_limit_bytes(visible, 200);
}

// Cuts the stem, never the extension, so photo.jpg stays a .jpg.
function name_limit_bytes(name, max_bytes)
{
    if (Buffer.byteLength(name) <= max_bytes) {
        return name;
    }
    const dot = name.lastIndexOf('.');
    const ext = ((dot > 0) && ((name.length - dot) <= 10)) ? name.slice(dot) : '';
    let stem = name.slice(0, name.length - ext.length);
    while ((stem !== '') && (Buffer.byteLength(`${stem}${ext}`) > max_bytes)) {
        stem = Array.from(stem).slice(0, -1).join('');
    }
    return `${stem}${ext}`;
}

module.exports = file_name_sanitize;
