package com.inavflight.configurator.file;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.util.Base64;

import androidx.activity.result.ActivityResult;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * File access for the renderer.
 *
 * Paths are either content:// URIs returned by the system file picker (Storage
 * Access Framework) or plain filesystem paths inside the app's own storage (used
 * for the automatic backup directory). File contents cross the bridge as base64.
 */
@CapacitorPlugin(name = "InavFile")
public class InavFilePlugin extends Plugin {

    // ---- Pickers ---------------------------------------------------------------------

    @PluginMethod
    public void openDocument(PluginCall call) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(call, intent, "onDocumentPicked");
    }

    @PluginMethod
    public void createDocument(PluginCall call) {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(call.getString("mimeType", "application/octet-stream"));
        intent.putExtra(Intent.EXTRA_TITLE, call.getString("name", "untitled"));
        startActivityForResult(call, intent, "onDocumentPicked");
    }

    @ActivityCallback
    private void onDocumentPicked(PluginCall call, ActivityResult result) {
        if (call == null) {
            return;
        }
        JSObject response = new JSObject();
        Intent data = result.getData();
        if (result.getResultCode() != Activity.RESULT_OK || data == null || data.getData() == null) {
            response.put("canceled", true);
            call.resolve(response);
            return;
        }
        Uri uri = data.getData();
        response.put("canceled", false);
        response.put("uri", uri.toString());
        response.put("name", displayName(uri));
        call.resolve(response);
    }

    // ---- Read / write ----------------------------------------------------------------

    @PluginMethod
    public void readFile(PluginCall call) {
        String path = call.getString("path");
        if (path == null) {
            call.reject("path is required");
            return;
        }
        try (InputStream in = openInput(path)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = in.read(buffer)) != -1) {
                out.write(buffer, 0, n);
            }
            JSObject result = new JSObject();
            result.put("data", Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP));
            call.resolve(result);
        } catch (IOException | SecurityException e) {
            call.reject("Unable to read " + path + ": " + e.getMessage());
        }
    }

    @PluginMethod
    public void writeFile(PluginCall call) {
        String path = call.getString("path");
        if (path == null) {
            call.reject("path is required");
            return;
        }
        boolean append = call.getBoolean("append", false);
        byte[] bytes = Base64.decode(call.getString("data", ""), Base64.DEFAULT);
        try (OutputStream out = openOutput(path, append)) {
            out.write(bytes);
            call.resolve();
        } catch (IOException | SecurityException e) {
            call.reject("Unable to write " + path + ": " + e.getMessage());
        }
    }

    @PluginMethod
    public void rm(PluginCall call) {
        String path = call.getString("path");
        if (path == null) {
            call.reject("path is required");
            return;
        }
        try {
            boolean deleted = isContentUri(path)
                    ? DocumentsContract.deleteDocument(getContext().getContentResolver(), Uri.parse(path))
                    : new File(path).delete();
            if (deleted) {
                call.resolve();
            } else {
                call.reject("Unable to delete " + path);
            }
        } catch (IOException | SecurityException | IllegalArgumentException e) {
            call.reject("Unable to delete " + path + ": " + e.getMessage());
        }
    }

    @PluginMethod
    public void listDir(PluginCall call) {
        String path = call.getString("path");
        JSArray files = new JSArray();
        String[] names = path != null ? new File(path).list() : null;
        if (names != null) {
            for (String name : names) {
                files.put(name);
            }
        }
        JSObject result = new JSObject();
        result.put("files", files);
        call.resolve(result);
    }

    @PluginMethod
    public void getBackupDir(PluginCall call) {
        File base = getContext().getExternalFilesDir(null);
        if (base == null) {
            base = getContext().getFilesDir();
        }
        File dir = new File(base, "inav-backups");
        if (!dir.exists() && !dir.mkdirs()) {
            call.reject("Unable to create " + dir.getAbsolutePath());
            return;
        }
        JSObject result = new JSObject();
        result.put("path", dir.getAbsolutePath());
        call.resolve(result);
    }

    // ---- Helpers ---------------------------------------------------------------------

    private static boolean isContentUri(String path) {
        return path.startsWith("content://");
    }

    private InputStream openInput(String path) throws IOException {
        if (isContentUri(path)) {
            InputStream in = getContext().getContentResolver().openInputStream(Uri.parse(path));
            if (in == null) {
                throw new IOException("Provider returned no stream");
            }
            return in;
        }
        return new FileInputStream(path);
    }

    private OutputStream openOutput(String path, boolean append) throws IOException {
        if (isContentUri(path)) {
            ContentResolver resolver = getContext().getContentResolver();
            OutputStream out = resolver.openOutputStream(Uri.parse(path), append ? "wa" : "wt");
            if (out == null) {
                throw new IOException("Provider returned no stream");
            }
            return out;
        }
        File file = new File(path);
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        return new FileOutputStream(file, append);
    }

    private String displayName(Uri uri) {
        try (Cursor cursor = getContext().getContentResolver()
                .query(uri, new String[] { OpenableColumns.DISPLAY_NAME }, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                String name = cursor.getString(0);
                if (name != null) {
                    return name;
                }
            }
        } catch (Exception ignored) {
        }
        String last = uri.getLastPathSegment();
        return last != null ? last : "file";
    }
}
