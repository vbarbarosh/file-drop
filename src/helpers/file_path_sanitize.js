const file_name_sanitize = require('./file_name_sanitize');

// A relative path sent by a client, like "Camera/2026/IMG_1.jpg", becomes a
// safe path inside the drop folder: each part sanitized, empty and dot parts
// dropped, at most 16 levels. Returns '' when no file name is left.
function file_path_sanitize(relative)
{
    const parts = String(relative ?? '').split(/[\\/]/).map(file_name_sanitize).filter(v => v !== '');
    return parts.slice(-16).join('/');
}

module.exports = file_path_sanitize;
