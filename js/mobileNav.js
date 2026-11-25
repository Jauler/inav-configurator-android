'use strict';

/**
 * Compact (phone) layout support for the Android app.
 *
 * When the window is narrow or short, src/css/mobile.css turns the tab list into an
 * off-canvas drawer opened by #nav-toggle. This module only toggles state classes on
 * <html>; when the compact layout applies is decided by the stylesheet alone.
 */

import { isAndroid } from './platform';

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

// The toggle is only displayed in the compact layout (see mobile.css).
function isCompactLayout() {
    const toggle = document.getElementById('nav-toggle');
    return !!toggle && window.getComputedStyle(toggle).display !== 'none';
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

        // Picking a tab closes the drawer. Group headers and the expand-all toggle are
        // not tab links, so the drawer stays open while groups are being browsed.
        $('#tabs').on('click', 'li[class*="tab_"] > a', function () {
            setOpen(false);
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
        setOpen(false);
    },
};

export default mobileNav;
