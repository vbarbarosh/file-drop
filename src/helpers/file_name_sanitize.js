const name_limit_bytes = require('./name_limit_bytes');

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

module.exports = file_name_sanitize;
