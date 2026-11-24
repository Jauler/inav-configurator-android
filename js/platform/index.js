'use strict';

/**
 * Platform abstraction.
 *
 * Everything the renderer needs from the host OS (serial/TCP/UDP transports,
 * files, dialogs, persistent settings, app info) goes through this object.
 *
 * - Electron: the object exposed by js/main/preload.js as window.electronAPI.
 * - Android (Capacitor): js/platform/capacitor.js, backed by native plugins in
 *   android/app/src/main/java/com/inavflight/configurator/.
 *
 * Both implementations expose the same method names and result shapes, so
 * callers do not need to know which one they are talking to.
 */

import { Capacitor } from '@capacitor/core';
import capacitorPlatform from './capacitor';

export const isElectron = typeof window !== 'undefined' && !!window.electronAPI;
export const isCapacitor = !isElectron && Capacitor.isNativePlatform();
export const isAndroid = isCapacitor && Capacitor.getPlatform() === 'android';

const platform = isElectron ? window.electronAPI : capacitorPlatform;

export default platform;
