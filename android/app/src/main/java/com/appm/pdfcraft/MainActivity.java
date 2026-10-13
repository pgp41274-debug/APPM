package com.appm.pdfcraft;

import android.annotation.SuppressLint;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Message;
import android.os.Process;
import android.print.PrintManager;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.ConsoleMessage;
import android.webkit.JsResult;
import android.webkit.MimeTypeMap;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.core.content.IntentCompat;
import androidx.core.graphics.Insets;
import androidx.core.os.BundleCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.webkit.WebSettingsCompat;
import androidx.webkit.WebViewAssetLoader;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * PdfCraft for Android: a WebView running PdfCraft's own browser build (wasm) from the APK's
 * assets, with Android's file pickers, "Open with", share sheet, saving and printing wired to
 * PdfCraft's host bridge through the shell page in assets/app/shell/.
 */
public final class MainActivity extends ComponentActivity {
    private static final String TAG = "PdfCraft";
    static final String HOST = "appassets.androidplatform.net";
    private static final String START = "https://" + HOST + "/app/shell/index.html";
    // Instance state: saves waiting on (or queued behind) the "Save as" picker.
    private static final String STATE_ASKING = "asking";
    private static final String STATE_SAVES = "saves";

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ArrayDeque<FileStore.SaveJob> saves = new ArrayDeque<>();

    private FileStore files;
    private Printer printer;
    private WebViewAssetLoader loader;
    private FrameLayout root;
    private WebView web;
    private ValueCallback<Uri[]> chooser;
    /**
     * The save waiting on the "Save as" picker, if one is open. Kept in the instance state: if
     * Android ends the process while the picker is open, the answer comes to a new activity.
     */
    private FileStore.SaveJob asking;
    private volatile boolean dirty;

    private final ActivityResultLauncher<String[]> openOne = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(), uri -> chosen(uri == null ? null : new Uri[] {uri}));
    private final ActivityResultLauncher<String[]> openMany = registerForActivityResult(
            new ActivityResultContracts.OpenMultipleDocuments(),
            uris -> chosen(uris == null || uris.isEmpty() ? null : uris.toArray(new Uri[0])));
    private final ActivityResultLauncher<Intent> createDocument = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> created(result.getResultCode() == RESULT_OK && result.getData() != null ? result.getData().getData() : null));

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        files = new FileStore(this);
        printer = new Printer(this);
        restoreSaves(state);
        loader = new WebViewAssetLoader.Builder()
                .setDomain(HOST)
                .addPathHandler("/app/", new AssetHandler(getAssets()))
                .addPathHandler("/incoming/", this::incoming)
                .build();

        // Draw edge to edge and keep PdfCraft clear of the system bars, cutouts and keyboard.
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        root = new FrameLayout(this);
        root.setBackgroundColor(ContextCompat.getColor(this, R.color.surface));
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets i = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout()
                    | WindowInsetsCompat.Type.ime());
            v.setPadding(i.left, i.top, i.right, i.bottom);
            return WindowInsetsCompat.CONSUMED;
        });
        setContentView(root);
        createWebView();

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                // Back leaves PdfCraft running with its documents open, as Home does: finishing
                // the activity would throw away every open tab.
                if (dirty) toast(getString(R.string.kept_running));
                moveTaskToBack(true);
            }
        });

        handle(getIntent());
        web.loadUrl(START);
        nextSave();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle out) {
        super.onSaveInstanceState(out);
        if (asking != null) out.putBundle(STATE_ASKING, asking.toBundle());
        ArrayList<Bundle> queued = new ArrayList<>();
        for (FileStore.SaveJob job : saves) queued.add(job.toBundle());
        out.putParcelableArrayList(STATE_SAVES, queued);
    }

    /**
     * Take back the saves an ended process left waiting on the "Save as" picker, and delete the
     * spool files of any others.
     */
    private void restoreSaves(Bundle state) {
        List<File> keep = new ArrayList<>();
        if (state != null) {
            asking = FileStore.SaveJob.fromBundle(state.getBundle(STATE_ASKING));
            if (asking != null) keep.add(asking.file);
            ArrayList<Bundle> queued = BundleCompat.getParcelableArrayList(state, STATE_SAVES, Bundle.class);
            if (queued != null) {
                for (Bundle b : queued) {
                    FileStore.SaveJob job = FileStore.SaveJob.fromBundle(b);
                    if (job == null) continue;
                    saves.add(job);
                    keep.add(job.file);
                }
            }
        }
        files.sweepSpools(keep);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handle(intent);
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            root.removeView(web);
            web.destroy();
            web = null;
        }
        io.shutdown();
        super.onDestroy();
    }

    // ---- WebView ----

    @SuppressLint("SetJavaScriptEnabled")
    private void createWebView() {
        web = new WebView(this);
        root.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        web.setBackgroundColor(ContextCompat.getColor(this, R.color.surface));
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true); // PdfCraft keeps its preferences in localStorage.
        s.setAllowFileAccess(false);
        s.setSupportZoom(false); // PdfCraft zooms pages itself (pinch included).
        s.setBuiltInZoomControls(false);
        s.setTextZoom(100);
        s.setSupportMultipleWindows(true);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(s, false); // PdfCraft has its own dark theme.
        }

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri url = request.getUrl();
                // An exception thrown here would end the app; fail just the request instead.
                try {
                    if (HOST.equals(url.getHost())) return loader.shouldInterceptRequest(url);
                    // The app is offline. A link that would navigate a frame away from PdfCraft
                    // opens in the browser; "204 No Content" leaves the frame where it is.
                    if (isNavigation(request)) {
                        runOnUiThread(() -> openExternal(url));
                        return empty(204, "No Content");
                    }
                    return empty(403, "Offline");
                } catch (RuntimeException e) {
                    Log.w(TAG, "can't answer " + url, e);
                    return empty(500, "Internal Server Error");
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri url = request.getUrl();
                if ("https".equals(url.getScheme()) && HOST.equals(url.getHost())) return false;
                openExternal(url);
                return true;
            }

            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                // The page's process died (usually the system reclaiming memory). Start PdfCraft
                // again instead of letting the whole app crash.
                Log.w(TAG, "WebView renderer gone; crashed=" + detail.didCrash());
                if (view == web) {
                    root.removeView(web);
                    web.destroy();
                    dirty = false;
                    createWebView();
                    web.loadUrl(START);
                    toast(getString(R.string.restarted));
                }
                return true;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (chooser != null) chooser.onReceiveValue(null);
                chooser = callback;
                String[] types = mimeTypes(params.getAcceptTypes());
                try {
                    if (params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) openMany.launch(types);
                    else openOne.launch(types);
                } catch (ActivityNotFoundException e) {
                    chooser = null;
                    callback.onReceiveValue(null);
                    toast(getString(R.string.no_picker));
                }
                return true;
            }

            @Override
            public boolean onCreateWindow(WebView view, boolean dialog, boolean userGesture, Message resultMsg) {
                // window.open() that the shell didn't catch: take the URL from a throwaway view.
                WebView popup = new WebView(MainActivity.this);
                popup.setWebViewClient(new WebViewClient() {
                    @Override
                    public boolean shouldOverrideUrlLoading(WebView w, WebResourceRequest r) {
                        openExternal(r.getUrl());
                        w.destroy();
                        return true;
                    }
                });
                ((WebView.WebViewTransport) resultMsg.obj).setWebView(popup);
                resultMsg.sendToTarget();
                return true;
            }

            @Override
            public boolean onJsBeforeUnload(WebView view, String url, String message, JsResult result) {
                result.confirm(); // Only a renderer restart reloads the page.
                return true;
            }

            @Override
            public boolean onConsoleMessage(ConsoleMessage m) {
                int level = m.messageLevel() == ConsoleMessage.MessageLevel.ERROR ? Log.ERROR
                        : m.messageLevel() == ConsoleMessage.MessageLevel.WARNING ? Log.WARN : Log.DEBUG;
                Log.println(level, TAG, m.message() + " (" + m.sourceId() + ":" + m.lineNumber() + ")");
                return true;
            }
        });

        web.addJavascriptInterface(new ShellBridge(this, files, printer), "AndroidShell");
    }

    private static boolean isNavigation(WebResourceRequest request) {
        if (request.isForMainFrame()) return true;
        Map<String, String> headers = request.getRequestHeaders();
        String accept = headers == null ? null : headers.get("Accept");
        return accept != null && accept.contains("text/html");
    }

    private static WebResourceResponse empty(int status, String reason) {
        return new WebResourceResponse("text/plain", "utf-8", status, reason, Collections.emptyMap(),
                new ByteArrayInputStream(new byte[0]));
    }

    /** {@code /incoming/<id>}: the bytes of a file handed to PdfCraft by another app or a picker. */
    private WebResourceResponse incoming(String id) {
        try {
            return new WebResourceResponse("application/pdf", null, files.openIncoming(id));
        } catch (IOException | RuntimeException e) {
            // Any app can send us a URI, and its provider may throw anything (SecurityException,
            // IllegalArgumentException, ...): the shell then says the file couldn't be opened.
            Log.w(TAG, "can't read incoming file " + id, e);
            byte[] message = String.valueOf(e.getMessage()).getBytes(StandardCharsets.UTF_8);
            return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found", Collections.emptyMap(),
                    new ByteArrayInputStream(message));
        }
    }

    /** Serves {@code /app/...} from the APK's assets/app/, with the right MIME types for wasm. */
    private static final class AssetHandler implements WebViewAssetLoader.PathHandler {
        private final AssetManager assets;

        AssetHandler(AssetManager assets) {
            this.assets = assets;
        }

        @Override
        public WebResourceResponse handle(String path) {
            String file = path.isEmpty() || path.endsWith("/") ? path + "index.html" : path;
            if (file.contains("..")) return empty(404, "Not Found");
            try {
                InputStream in = assets.open("app/" + file, AssetManager.ACCESS_STREAMING);
                String mime = mime(file);
                return new WebResourceResponse(mime, mime.startsWith("text/") ? "utf-8" : null, in);
            } catch (IOException e) {
                return empty(404, "Not Found");
            }
        }

        private static String mime(String file) {
            String ext = file.substring(file.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
            switch (ext) {
                case "wasm": return "application/wasm";
                case "js": case "mjs": return "text/javascript";
                case "html": return "text/html";
                case "css": return "text/css";
                case "json": case "webmanifest": return "application/json";
                case "svg": return "image/svg+xml";
                case "png": return "image/png";
                case "ttf": return "font/ttf";
                case "otf": return "font/otf";
                case "woff2": return "font/woff2";
                default:
                    String guess = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
                    return guess != null ? guess : "application/octet-stream";
            }
        }
    }

    // ---- opening ----

    private void handle(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        List<Uri> uris = new ArrayList<>();
        if (Intent.ACTION_VIEW.equals(action) && intent.getData() != null) {
            uris.add(intent.getData());
        } else if (Intent.ACTION_SEND.equals(action)) {
            Uri uri = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri.class);
            if (uri != null) uris.add(uri);
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            ArrayList<Uri> list = IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri.class);
            if (list != null) uris.addAll(list);
        }
        if (uris.isEmpty()) return;
        boolean canWrite = (intent.getFlags() & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0;
        for (Uri uri : uris) files.addIncoming(uri, canWrite && "content".equals(uri.getScheme()));
        // Handled: don't open the files again if the activity is recreated.
        setIntent(new Intent(Intent.ACTION_MAIN));
        if (web != null) web.evaluateJavascript("window.shell && shell.incoming()", null);
    }

    /** The file picker behind PdfCraft's Open (an {@code <input type=file>}) answered. */
    private void chosen(Uri[] uris) {
        ValueCallback<Uri[]> callback = chooser;
        chooser = null;
        if (callback != null) {
            if (uris != null) {
                for (Uri uri : uris) files.addPicked(uri, keepAccess(uri));
            }
            callback.onReceiveValue(uris);
            return;
        }
        // Nobody is waiting for the files: Android ended the app while the picker was open.
        // Open them as if they were shared, once PdfCraft is ready.
        if (uris == null) return;
        for (Uri uri : uris) files.addIncoming(uri, keepAccess(uri));
        if (web != null) web.evaluateJavascript("window.shell && shell.incoming()", null);
    }

    /** Keep read (and, where offered, write) access to a picked file; true if it is writable. */
    private boolean keepAccess(Uri uri) {
        int rw = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
        try {
            getContentResolver().takePersistableUriPermission(uri, rw);
            return true;
        } catch (SecurityException e) {
            try {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (SecurityException ignored) {
                // Readable for now; that's enough to open it.
            }
        }
        return checkUriPermission(uri, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** The MIME types for an {@code <input accept>} list such as ".pdf" or "image/png,.jpg". */
    static String[] mimeTypes(String[] accept) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (accept != null) {
            for (String group : accept) {
                if (group == null) continue;
                for (String raw : group.split(",")) {
                    String t = raw.trim().toLowerCase(Locale.ROOT);
                    if (t.isEmpty()) continue;
                    if (t.contains("/")) {
                        out.add(t);
                        continue;
                    }
                    String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(t.startsWith(".") ? t.substring(1) : t);
                    // An extension Android doesn't know: let the user pick any file.
                    if (mime == null) return new String[] {"*/*"};
                    out.add(mime);
                }
            }
        }
        return out.isEmpty() ? new String[] {"*/*"} : out.toArray(new String[0]);
    }

    // ---- saving ----

    /** PdfCraft handed over a file (from the bridge thread). */
    void onSave(FileStore.SaveJob job) {
        runOnUiThread(() -> {
            saves.add(job);
            nextSave();
        });
    }

    private void nextSave() {
        if (asking != null) return;
        FileStore.SaveJob job = saves.poll();
        if (job == null) return;
        if (!job.saveAs && !job.isExport()) {
            Uri target = files.targetFor(job.key, job.name);
            io.execute(() -> {
                String message = write(job, target);
                runOnUiThread(() -> {
                    toast(message);
                    nextSave();
                });
            });
            return;
        }
        asking = job;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType(job.mime)
                .putExtra(Intent.EXTRA_TITLE, job.name);
        try {
            createDocument.launch(intent);
        } catch (ActivityNotFoundException e) {
            created(null);
        }
    }

    /** The "Save as" picker answered: {@code uri} is where to write, or null if cancelled. */
    private void created(Uri uri) {
        FileStore.SaveJob job = asking;
        asking = null;
        if (job == null) {
            // Android ended the app while the picker was open, and the save didn't survive:
            // don't leave the empty file the picker made.
            if (uri != null) {
                io.execute(() -> files.deleteCreated(uri));
                toast(getString(R.string.save_lost));
            }
            nextSave();
            return;
        }
        if (uri == null && job.isExport()) {
            //noinspection ResultOfMethodCallIgnored
            job.file.delete();
            toast(getString(R.string.export_cancelled));
            nextSave();
            return;
        }
        // PdfCraft already counts the document as saved, so a cancelled Save As still keeps
        // a copy (in Downloads/PdfCraft) rather than losing the work.
        if (uri != null && !job.isExport()) files.bind(job.key, uri);
        io.execute(() -> {
            String message = write(job, uri);
            if (uri == null) message = getString(R.string.save_as_cancelled, message);
            String shown = message;
            runOnUiThread(() -> {
                toast(shown);
                nextSave();
            });
        });
    }

    /** Write a save to {@code target}, or to Downloads/PdfCraft when there's none or it fails. */
    private String write(FileStore.SaveJob job, Uri target) {
        try {
            if (target != null) {
                try {
                    files.writeTo(target, job.file);
                    return getString(R.string.saved, files.displayName(target));
                } catch (IOException | SecurityException | IllegalArgumentException e) {
                    Log.w(TAG, "can't write " + target, e);
                }
            }
            Uri copy = files.saveToDownloads(job.name, job.mime, job.file);
            // Later saves of the document update this copy. A cancelled Save As leaves them
            // going where they went before, if anywhere.
            if (!job.isExport()) {
                if (target == null && job.saveAs) files.bindIfUnbound(job.key, copy);
                else files.bind(job.key, copy);
            }
            String where = FileStore.DOWNLOADS_FOLDER + "/" + files.displayName(copy);
            return target != null ? getString(R.string.saved_instead, where) : getString(R.string.saved_to, where);
        } catch (IOException | RuntimeException e) {
            Log.e(TAG, "save failed", e);
            return getString(R.string.save_failed, job.name, String.valueOf(e.getMessage()));
        } finally {
            //noinspection ResultOfMethodCallIgnored
            job.file.delete();
        }
    }

    // ---- printing ----

    /** All sheets of print job {@code id} arrived (from the bridge thread). */
    void print(Printer printer, String id) {
        io.execute(() -> {
            try {
                Printer.Document document = printer.build(id);
                runOnUiThread(() -> {
                    PrintManager manager = (PrintManager) getSystemService(PRINT_SERVICE);
                    if (manager == null) {
                        Printer.deleteTree(document.dir);
                        toast(getString(R.string.no_printing));
                        return;
                    }
                    manager.print(document.name, Printer.adapter(document), null);
                });
            } catch (IOException | RuntimeException e) {
                Log.e(TAG, "print failed", e);
                toast(getString(R.string.print_failed, String.valueOf(e.getMessage())));
            }
        });
    }

    // ---- small things ----

    void setDirty(boolean dirty) {
        this.dirty = dirty;
    }

    void openExternalLater(String url) {
        runOnUiThread(() -> openExternal(Uri.parse(url)));
    }

    void openExternal(Uri url) {
        String scheme = url.getScheme() == null ? "" : url.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https") && !scheme.equals("mailto")) return;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, url).addCategory(Intent.CATEGORY_BROWSABLE));
        } catch (ActivityNotFoundException e) {
            toast(getString(R.string.no_browser, url.toString()));
        }
    }

    void updateWebViewLater() {
        runOnUiThread(() -> {
            PackageInfo info = WebViewCompat.getCurrentWebViewPackage(this);
            String pkg = info != null ? info.packageName : "com.google.android.webview";
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + pkg)));
            } catch (ActivityNotFoundException e) {
                openExternal(Uri.parse("https://play.google.com/store/apps/details?id=" + pkg));
            }
        });
    }

    void toast(String message) {
        runOnUiThread(() -> Toast.makeText(this, message, Toast.LENGTH_LONG).show());
    }
}
