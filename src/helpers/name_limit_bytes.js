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

module.exports = name_limit_bytes;
