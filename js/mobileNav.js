'use strict';

/**
 * Compact (phone) layout support for the Android app.
 *
 * When the window is narrow or short, src/css/mobile.css turns the tab list into an
 * off-canvas drawer opened by #nav-toggle. This module only toggles state classes on
 * <html>; when the compact layout applies is decided by the stylesheet alone.
 */

import { isAndroid } from './platform';
import GUI from './gui';
import CONFIGURATOR from './data_storage';

const root = document.documentElement;

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
}

// The toggle is only displayed in the compact layout, and not while the ground station
// hides the tab list (see mobile.css).
function isCompactLayout() {
    const toggle = document.getElementById('nav-toggle');
    return !!toggle && window.getComputedStyle(toggle).display !== 'none';
}

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

        // Called by MainActivity on the Android back button/gesture. Returns true when
        // the press was consumed here, so the activity skips its default handling.
        window.inavHandleBackButton = function () {
            if (isOpen() && isCompactLayout()) {
                setOpen(false);
                return true;
            }
            return false;
        };
    },

    close() {
        if (isOpen()) {
            setOpen(false);
        }
    },
};

export default mobileNav;
