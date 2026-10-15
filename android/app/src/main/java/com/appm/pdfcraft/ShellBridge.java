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
        activity.ready();
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

    /** All slices sent: write the file where it belongs, or share it (kind "share"). */
    @JavascriptInterface
    public boolean endSave(String id) {
        try {
            FileStore.SaveJob job = files.end(id);
            if ("share".equals(job.kind)) {
                activity.share(job);
            } else {
                activity.onSave(job);
            }
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

    /**
     * PdfCraft closed the document {@code id} or else {@code doc} names (as in {@link #beginSave}),
     * or file {@code id} never became a document. Once the saves sent before are written, its
     * file is free: opened again, it saves back there.
     */
    @JavascriptInterface
    public void closed(String id, String doc) {
        activity.onSave(files.closed(id, doc));
    }

    /** Put text on the clipboard (Copy text on a selection). */
    @JavascriptInterface
    public void copyText(String text) {
        activity.copyText(text);
    }

    /** PdfCraft has (or no longer has) unsaved work. */
    @JavascriptInterface
    public void setDirty(boolean dirty) {
        activity.setDirty(dirty);
    }

    /** PdfCraft entered or left Full screen mode: hide or show the system bars. */
    @JavascriptInterface
    public void setFullScreen(boolean on) {
        activity.setFullScreen(on);
    }

    /** Open a web link in the browser. */
    @JavascriptInterface
    public void openExternal(String url) {
        activity.openExternalLater(url);
    }

    /** Start a print job; PdfCraft's print sheets follow, one {@link #beginSheet} at a time. */
    @JavascriptInterface
    public String beginPrint(String jobName) {
        try {
            return printer.begin(jobName);
        } catch (IOException e) {
            activity.toast("Couldn't print: " + e.getMessage());
            return "";
        }
    }

    /** The next sheet: its size in points and whether its picture lies turned. */
    @JavascriptInterface
    public boolean beginSheet(String id, double widthPt, double heightPt, boolean turned) {
        try {
            printer.beginSheet(id, widthPt, heightPt, turned);
            return true;
        } catch (IOException | IllegalArgumentException e) {
            return printFailed(id, e);
        }
    }

    /** One base64 slice (a multiple of 4 characters) of the sheet's PNG. */
    @JavascriptInterface
    public boolean appendSheet(String id, String base64) {
        try {
            printer.appendSheet(id, base64);
            return true;
        } catch (IOException | IllegalArgumentException e) {
            return printFailed(id, e);
        }
    }

    @JavascriptInterface
    public boolean endSheet(String id) {
        try {
            printer.endSheet(id);
            return true;
        } catch (IOException e) {
            return printFailed(id, e);
        }
    }

    private boolean printFailed(String id, Exception e) {
        printer.abort(id);
        activity.toast("Couldn't print: " + e.getMessage());
        return false;
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
