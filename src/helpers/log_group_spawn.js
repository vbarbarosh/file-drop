const crypto = require('crypto');
const log = require('./log');

function log_group_spawn(parent_uid = null)
{
    const out = crypto.randomBytes(6).toString('hex');
    log(out, 'group_spawn', (parent_uid === null) ? '' : `parent=${parent_uid}`);
    return out;
}

module.exports = log_group_spawn;
