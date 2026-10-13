package com.appm.pdfcraft;

import android.content.pm.PackageInfo;
import android.os.Build;
import android.webkit.JavascriptInterface;

import androidx.webkit.WebViewCompat;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;

/**
 * {@code window.AndroidShell}: what the shell page (assets/app/shell/shell.js) calls. Methods run
 * on the WebView's JavaBridge thread, not the UI thread.
 */
final class ShellBridge {
    private final MainActivity activity;
    private final FileStore files;
    private final Printer printer;

    ShellBridge(MainActivity activity, FileStore files, Printer printer) {
        this.activity = activity;
        this.files = files;
        this.printer = printer;
    }

    /** Files waiting to be opened, as JSON {@code [{id, name}]}; each is fetched from /incoming/id. */
    @JavascriptInterface
    public String pending() {
        return files.drainPending();
    }

    /** PdfCraft (re)started in the page: the documents of the one before are gone. */
    @JavascriptInterface
    public void appReady() {
        files.newPage();
    }

    /**
     * Start a save; returns its id, or "" if it can't be spooled. A document save names the
     * document by {@code id}, the id it was opened with (see {@link #pending}), or else by
     * {@code doc}, PdfCraft's number for it; "" when there is none.
     */
    @JavascriptInterface
    public String beginSave(String name, String mime, boolean saveAs, String kind, String id, String doc) {
        try {
            return files.begin(name, mime, saveAs, kind, id, doc);
        } catch (IOException e) {
            activity.toast("Couldn't save " + name + ": " + e.getMessage());
            return "";
        }
    }

    /** One base64 slice (a multiple of 4 characters) of the save {@code id}. */
    @JavascriptInterface
    public boolean appendChunk(String id, String base64) {
        try {
            files.append(id, base64);
            return true;
        } catch (IOException | IllegalArgumentException e) {
            files.abort(id);
            activity.toast("Couldn't save: " + e.getMessage());
            return false;
        }
    }

    /** All slices sent: write the file where it belongs. */
    @JavascriptInterface
    public boolean endSave(String id) {
        try {
            activity.onSave(files.end(id));
            return true;
        } catch (IOException e) {
            files.abort(id);
            activity.toast("Couldn't save: " + e.getMessage());
            return false;
        }
    }

    @JavascriptInterface
    public void abortSave(String id) {
        files.abort(id);
    }

    /** PdfCraft has (or no longer has) unsaved work. */
    @JavascriptInterface
    public void setDirty(boolean dirty) {
        activity.setDirty(dirty);
    }

    /** Open a web link in the browser. */
    @JavascriptInterface
    public void openExternal(String url) {
        activity.openExternalLater(url);
    }

    /** Start a print job; PdfCraft's print sheets follow one {@link #printPage} at a time. */
    @JavascriptInterface
    public String beginPrint(String jobName) {
        try {
            return printer.begin(jobName);
        } catch (IOException e) {
            activity.toast("Couldn't print: " + e.getMessage());
            return "";
        }
    }

    /** One sheet: its size in points, whether its picture lies turned, and the PNG as base64. */
    @JavascriptInterface
    public boolean printPage(String id, double widthPt, double heightPt, boolean turned, String base64Png) {
        try {
            printer.addPage(id, widthPt, heightPt, turned, base64Png);
            return true;
        } catch (IOException | IllegalArgumentException e) {
            printer.abort(id);
            activity.toast("Couldn't print: " + e.getMessage());
            return false;
        }
    }

    @JavascriptInterface
    public void abortPrint(String id) {
        printer.abort(id);
    }

    /** All sheets sent: open Android's print dialog. */
    @JavascriptInterface
    public void endPrint(String id) {
        activity.print(printer, id);
    }

    @JavascriptInterface
    public void toast(String message) {
        activity.toast(message);
    }

    /** Versions for the shell's error screen. */
    @JavascriptInterface
    public String info() {
        JSONObject o = new JSONObject();
        try {
            o.put("app", BuildConfig.VERSION_NAME);
            o.put("android", Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
            o.put("device", Build.MANUFACTURER + " " + Build.MODEL);
            PackageInfo webView = WebViewCompat.getCurrentWebViewPackage(activity);
            o.put("webview", webView == null ? "unknown" : webView.packageName + " " + webView.versionName);
        } catch (JSONException ignored) {
            // put() with String keys never throws here.
        }
        return o.toString();
    }

    /** Open the store page of the device's WebView, to update it. */
    @JavascriptInterface
    public void updateWebView() {
        activity.updateWebViewLater();
    }
}
