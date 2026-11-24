'use strict';

/**
 * Capacitor (Android) implementation of the platform API.
 *
 * Mirrors the method names and result shapes of js/main/preload.js +
 * js/main/main.js so the rest of the renderer can stay host-agnostic.
 * Binary payloads cross the JS<->Java bridge as base64 strings.
 */

import { registerPlugin } from '@capacitor/core';
import { version as upstreamVersion } from '../../package.json';

// Android releases are tagged v<upstream version>-<revision>; CI passes the revision
// as VITE_APP_REVISION so the app reports e.g. 9.1.2-1.
const appRevision = import.meta.env.VITE_APP_REVISION;
const appVersion = appRevision ? `${upstreamVersion}-${appRevision}` : upstreamVersion;

const Serial = registerPlugin('InavSerial');
const Tcp = registerPlugin('InavTcp');
const Udp = registerPlugin('InavUdp');
const Files = registerPlugin('InavFile');

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function toBytes(data) {
    if (data instanceof Uint8Array) {
        return data;
    }
    if (data instanceof ArrayBuffer) {
        return new Uint8Array(data);
    }
    if (ArrayBuffer.isView(data)) {
        return new Uint8Array(data.buffer, data.byteOffset, data.byteLength);
    }
    if (Array.isArray(data)) {
        return Uint8Array.from(data);
    }
    if (typeof data === 'string') {
        return new TextEncoder().encode(data);
    }
    throw new TypeError('Unsupported data type: ' + Object.prototype.toString.call(data));
}

function bytesToBase64(data) {
    const bytes = toBytes(data);
    let binary = '';
    const CHUNK = 0x8000;
    for (let i = 0; i < bytes.length; i += CHUNK) {
        binary += String.fromCharCode.apply(null, bytes.subarray(i, i + CHUNK));
    }
    return btoa(binary);
}

function base64ToBytes(b64) {
    const binary = atob(b64 || '');
    const bytes = new Uint8Array(binary.length);
    for (let i = 0; i < binary.length; i++) {
        bytes[i] = binary.charCodeAt(i);
    }
    return bytes;
}

function errorMessage(err) {
    if (!err) {
        return 'Unknown error';
    }
    return err.message || err.errorMessage || String(err);
}

/**
 * Fan a single native plugin event out to any number of renderer listeners,
 * matching the onX()/offX() handler pattern of the Electron preload.
 */
function nativeEvent(plugin, eventName, transform = (ev) => ev) {
    const listeners = new Set();
    let registered = false;

    return {
        on(callback) {
            if (!registered) {
                registered = true;
                plugin.addListener(eventName, (event) => {
                    const value = transform(event);
                    listeners.forEach((listener) => listener(value));
                });
            }
            listeners.add(callback);
            return callback;
        },
        off(handler) {
            listeners.delete(handler);
        },
    };
}

const dataTransform = (event) => base64ToBytes(event.data);
const errorTransform = (event) => new Error(event.message || 'Unknown error');

// ---------------------------------------------------------------------------
// Documents picked through the Android Storage Access Framework.
//
// SAF hands out content:// URIs whose last path segment is not the file name,
// but callers derive names and extensions from the path (e.g. mission_control
// picks the parser by extension). We therefore hand out a pseudo path ending
// in the real display name and map it back to the URI on read/write.
// ---------------------------------------------------------------------------

const documents = new Map();
let documentSeq = 0;

function registerDocument(uri, name) {
    const safeName = (name || 'file').replace(/[\\/]/g, '_');
    const pseudoPath = `saf://${++documentSeq}/${safeName}`;
    documents.set(pseudoPath, uri);
    return pseudoPath;
}

function resolvePath(path) {
    return documents.get(path) || path;
}

function basename(path) {
    return String(path || '').split('/').pop().split('\\').pop();
}

function mimeTypeFor(name) {
    const ext = basename(name).split('.').pop().toLowerCase();
    switch (ext) {
        case 'txt':
        case 'cli':
        case 'log':
        case 'hex':
        case 'mcm':
            return 'text/plain';
        case 'csv':
            return 'text/csv';
        case 'json':
            return 'application/json';
        case 'xml':
        case 'mission':
        case 'kml':
        case 'gpx':
            return 'application/xml';
        default:
            return 'application/octet-stream';
    }
}

let backupDirPromise = null;
function backupDir() {
    if (!backupDirPromise) {
        backupDirPromise = Files.getBackupDir().then((result) => result.path);
    }
    return backupDirPromise;
}

async function writeNative(filename, data, append) {
    const payload = { path: resolvePath(filename), append: !!append };
    payload.data = bytesToBase64(typeof data === 'string' ? new TextEncoder().encode(data) : data);
    await Files.writeFile(payload);
}

// ---------------------------------------------------------------------------
// Settings store (electron-store equivalent)
// ---------------------------------------------------------------------------

const STORE_PREFIX = 'inav.store.';

function storeGet(key, defaultValue) {
    try {
        const raw = window.localStorage.getItem(STORE_PREFIX + key);
        if (raw === null) {
            return defaultValue;
        }
        return JSON.parse(raw);
    } catch (e) {
        console.warn('storeGet failed for', key, e);
        return defaultValue;
    }
}

function storeSet(key, value) {
    try {
        if (value === undefined) {
            window.localStorage.removeItem(STORE_PREFIX + key);
        } else {
            window.localStorage.setItem(STORE_PREFIX + key, JSON.stringify(value));
        }
    } catch (e) {
        console.warn('storeSet failed for', key, e);
    }
}

function storeDelete(key) {
    try {
        window.localStorage.removeItem(STORE_PREFIX + key);
    } catch (e) {
        console.warn('storeDelete failed for', key, e);
    }
}

// ---------------------------------------------------------------------------
// Transports
// ---------------------------------------------------------------------------

const serialData = nativeEvent(Serial, 'data', dataTransform);
const serialClose = nativeEvent(Serial, 'close', () => undefined);
const serialError = nativeEvent(Serial, 'error', errorTransform);

const tcpData = nativeEvent(Tcp, 'data', dataTransform);
const tcpEnd = nativeEvent(Tcp, 'end', () => undefined);
const tcpError = nativeEvent(Tcp, 'error', errorTransform);

const udpMessage = nativeEvent(Udp, 'message', dataTransform);
const udpError = nativeEvent(Udp, 'error', errorTransform);

let connectionSeq = 1;

async function transportConnect(promiseFactory) {
    try {
        await promiseFactory();
        return { error: false, id: connectionSeq++ };
    } catch (err) {
        const msg = errorMessage(err);
        return { error: true, msg: msg, errorMsg: msg };
    }
}

async function transportSend(plugin, data) {
    try {
        const result = await plugin.write({ data: bytesToBase64(data) });
        return { error: false, bytesWritten: result.bytesWritten };
    } catch (err) {
        return { error: true, msg: errorMessage(err) };
    }
}

async function transportClose(plugin) {
    try {
        await plugin.close();
        return { error: false };
    } catch (err) {
        return { error: true, msg: errorMessage(err) };
    }
}

const unsupported = (name) => () => {
    console.warn(`${name} is not supported on this platform`);
};

// ---------------------------------------------------------------------------
// Public API (same surface as js/main/preload.js)
// ---------------------------------------------------------------------------

const capacitorPlatform = {
    // Serial ---------------------------------------------------------------
    listSerialDevices: async () => {
        try {
            const result = await Serial.getDevices();
            return (result.devices || []).map((device) => device.path);
        } catch (err) {
            console.warn('listSerialDevices failed:', err);
            return [];
        }
    },
    serialConnect: (path, options) => transportConnect(() =>
        Serial.connect({ path: path, baudRate: (options && options.bitrate) || 115200 })),
    serialSend: (data) => transportSend(Serial, data),
    serialClose: () => transportClose(Serial),
    onSerialData: serialData.on,
    offSerialData: serialData.off,
    onSerialClose: serialClose.on,
    offSerialClose: serialClose.off,
    onSerialError: serialError.on,
    offSerialError: serialError.off,

    // TCP ------------------------------------------------------------------
    tcpConnect: (host, port) => transportConnect(() => Tcp.connect({ host: host, port: port })),
    tcpSend: (data) => transportSend(Tcp, data),
    tcpClose: () => { transportClose(Tcp); },
    onTcpData: tcpData.on,
    offTcpData: tcpData.off,
    onTcpEnd: tcpEnd.on,
    offTcpEnd: tcpEnd.off,
    onTcpError: tcpError.on,
    offTcpError: tcpError.off,

    // UDP ------------------------------------------------------------------
    udpConnect: (ip, port) => transportConnect(() => Udp.connect({ host: ip, port: port })),
    udpSend: (data) => transportSend(Udp, data),
    udpClose: () => transportClose(Udp),
    onUdpMessage: udpMessage.on,
    offUdpMessage: udpMessage.off,
    onUdpError: udpError.on,
    offUdpError: udpError.off,

    // Settings / app info --------------------------------------------------
    storeGet: storeGet,
    storeSet: storeSet,
    storeDelete: storeDelete,
    appGetPath: () => '',
    appGetVersion: () => appVersion,
    appGetLocale: () => navigator.language || 'en',

    // Dialogs --------------------------------------------------------------
    showOpenDialog: async (options) => {
        try {
            const result = await Files.openDocument({ mimeTypes: ['*/*'] });
            if (result.canceled) {
                return { canceled: true, filePaths: [] };
            }
            return { canceled: false, filePaths: [registerDocument(result.uri, result.name)] };
        } catch (err) {
            console.error('showOpenDialog failed:', err);
            return { canceled: true, filePaths: [] };
        }
    },
    showSaveDialog: async (options) => {
        const name = basename((options && options.defaultPath) || '') || 'untitled.txt';
        try {
            const result = await Files.createDocument({ name: name, mimeType: mimeTypeFor(name) });
            if (result.canceled) {
                return { canceled: true };
            }
            return { canceled: false, filePath: registerDocument(result.uri, result.name || name) };
        } catch (err) {
            console.error('showSaveDialog failed:', err);
            return { canceled: true };
        }
    },
    alertDialog: (message) => window.alert(message),
    confirmDialog: async (message) => window.confirm(message),

    // Files ----------------------------------------------------------------
    writeFile: async (filename, data) => {
        try {
            await writeNative(filename, data, false);
            return false;
        } catch (err) {
            return errorMessage(err);
        }
    },
    appendFile: async (filename, data) => {
        await writeNative(filename, data, true);
        return false;
    },
    readFile: async (filename, encoding = 'utf8') => {
        try {
            const result = await Files.readFile({ path: resolvePath(filename) });
            const bytes = base64ToBytes(result.data);
            const data = encoding ? new TextDecoder(encoding === 'utf8' ? 'utf-8' : encoding).decode(bytes) : bytes;
            return { error: false, data: data };
        } catch (err) {
            return { error: errorMessage(err) };
        }
    },
    rm: async (path) => {
        try {
            await Files.rm({ path: resolvePath(path) });
            return false;
        } catch (err) {
            return errorMessage(err);
        }
    },
    chmod: async () => 'chmod is not supported on Android',
    getBackupDir: () => backupDir(),
    openBackupDir: async () => {
        const dir = await backupDir();
        window.alert('Backups are stored in:\n' + dir);
        return dir;
    },
    listBackups: async () => {
        try {
            const result = await Files.listDir({ path: await backupDir() });
            return (result.files || []).filter((f) => f.endsWith('.txt') || f.endsWith('.cli'));
        } catch (err) {
            console.warn('listBackups failed:', err);
            return [];
        }
    },

    // SITL (local child processes) is not available on Android -------------
    startChildProcess: unsupported('startChildProcess'),
    killChildProcess: () => {},
    onChildProcessStdout: (callback) => callback,
    offChildProcessStdout: () => {},
    onChildProcessStderr: (callback) => callback,
    offChildProcessStderr: () => {},
    onChildProcessError: (callback) => callback,
    offChildProcessError: () => {},
};

export default capacitorPlatform;
