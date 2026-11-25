'use strict';

/**
 * Compact (phone) layout support and back-button handling for the Android app.
 *
 * When the window is narrow or short, src/css/mobile.css turns the tab list into an
 * off-canvas drawer opened by #nav-toggle. This module only toggles state classes on
 * <html>; when the compact layout applies is decided by the stylesheet alone.
 *
 * Back button: MainActivity only intercepts back while this module says there is
 * something for it to do (platform.setBackIntercept), so normal presses go straight
 * to Android (no JS round trip, predictive back keeps working). While intercepting,
 * the press is passed to window.inavHandleBackButton().
 */

import { isAndroid } from './platform';
import platform from './platform';
import GUI from './gui';
import CONFIGURATOR from './data_storage';
import i18n from './localization';

const root = document.documentElement;

// How often the back-intercept state is re-evaluated. Most of its inputs (connection,
// dialogs) change outside this module; the native side is only called on a change.
const BACK_STATE_POLL_MS = 250;

// Scopes src/css/mobile.css. Set at import time so the first paint is already correct.
if (isAndroid) {
    root.classList.add('platform-android');
}

function isOpen() {
    return root.classList.contains('nav-open');
}

function setOpen(open) {
    root.classList.toggle('nav-open', open);
    $('#nav-toggle').attr('aria-expanded', open ? 'true' : 'false');
    syncBackIntercept();
}

// The toggle is only displayed in the compact layout, and not while the ground station
// hides the tab list (see mobile.css).
function isCompactLayout() {
    const toggle = document.getElementById('nav-toggle');
    return !!toggle && window.getComputedStyle(toggle).display !== 'none';
}

function isDrawerOpen() {
    return isOpen() && isCompactLayout();
}

function isOptionsOpen() {
    return $('#options').hasClass('active');
}

// Native <dialog>s and jBox modals are mostly progress/confirmation windows with their
// own buttons; back must neither close them nor leave the app underneath them.
function isModalOpen() {
    return !!document.querySelector('dialog[open]') || $('.jBox-Modal:visible').length > 0;
}

function isConnected() {
    return !!(GUI.connected_to || CONFIGURATOR.connectionValid);
}

// ---- Back button -----------------------------------------------------------------

let backInterceptSent = null;
let leavePromptOpen = false;

function wantsBack() {
    return isDrawerOpen() || isOptionsOpen() || isModalOpen() || isConnected();
}

function syncBackIntercept() {
    if (!isAndroid) {
        return;
    }
    const want = wantsBack();
    if (want !== backInterceptSent) {
        backInterceptSent = want;
        platform.setBackIntercept(want);
    }
}

function confirmLeave() {
    if (leavePromptOpen) {
        return;
    }
    leavePromptOpen = true;
    // Deferred, so the native evaluateJavascript() call returns before the prompt opens.
    setTimeout(async () => {
        try {
            if (await platform.confirmDialog(i18n.getMessage('androidBackLeaveWhileConnected'))) {
                platform.performDefaultBack();
            }
        } finally {
            leavePromptOpen = false;
        }
    }, 0);
}

/**
 * Called by MainActivity while back is intercepted. Returns true when the press was
 * handled here; false lets the activity run the default back action.
 */
function handleBackButton() {
    if (isDrawerOpen()) {
        setOpen(false);
        return true;
    }
    if (isOptionsOpen()) {
        $('#options').trigger('click'); // toggles the options window closed
        return true;
    }
    if (isModalOpen()) {
        return true;
    }
    if (isConnected()) {
        confirmLeave();
        return true;
    }
    return false;
}

// ---- Tab switching -----------------------------------------------------------------

// The tab click handler in configurator_main.js runs before this delegated one and may
// refuse the switch (not connected, operation in progress, tab not supported, unsaved
// changes kept). Only close the drawer when the switch is actually going ahead, so a
// refusal stays visible next to the drawer and its log message.
function tabSwitchAccepted($li) {
    return GUI.tab_switch_in_progress
        || $li.hasClass('active')
        || CONFIGURATOR.cliActive; // leaving the CLI switches once it has exited
}

const mobileNav = {
    init() {
        $('#nav-toggle').on('click', function (e) {
            e.preventDefault();
            setOpen(!isOpen());
        });

        $('#nav-backdrop').on('click', function () {
            setOpen(false);
        });

        // Group headers and the expand-all toggle are not tab links, so the drawer
        // stays open while groups are being browsed.
        $('#tabs').on('click', 'li[class*="tab_"] > a', function () {
            if (tabSwitchAccepted($(this).parent())) {
                setOpen(false);
            }
        });

        $(document).on('keydown', function (e) {
            if (e.key === 'Escape') {
                setOpen(false);
            }
        });

        // Rotating a tablet can leave the compact layout; do not keep a stale open state.
        $(window).on('resize', function () {
            if (isOpen() && !isCompactLayout()) {
                setOpen(false);
            }
        });

        window.inavHandleBackButton = handleBackButton;

        if (isAndroid) {
            // Plain setInterval: the app's interval helper kills its timers on disconnect.
            window.setInterval(syncBackIntercept, BACK_STATE_POLL_MS);
            syncBackIntercept();
        }
    },

    close() {
        if (isOpen()) {
            setOpen(false);
        }
    },
};

export default mobileNav;
