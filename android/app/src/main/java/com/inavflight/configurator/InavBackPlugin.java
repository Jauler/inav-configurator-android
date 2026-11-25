package com.inavflight.configurator;

import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/**
 * Lets the renderer (js/mobileNav.js) decide when MainActivity intercepts the back
 * button, and run the default back action after it has asked the user.
 */
@CapacitorPlugin(name = "InavBack")
public class InavBackPlugin extends Plugin {

    @PluginMethod
    public void setIntercept(PluginCall call) {
        boolean enabled = Boolean.TRUE.equals(call.getBoolean("enabled", false));
        MainActivity activity = mainActivity();
        if (activity != null) {
            activity.runOnUiThread(() -> activity.setBackIntercept(enabled));
        }
        call.resolve();
    }

    @PluginMethod
    public void performDefaultBack(PluginCall call) {
        MainActivity activity = mainActivity();
        if (activity != null) {
            activity.runOnUiThread(activity::performDefaultBack);
        }
        call.resolve();
    }

    private MainActivity mainActivity() {
        return getActivity() instanceof MainActivity ? (MainActivity) getActivity() : null;
    }
}
