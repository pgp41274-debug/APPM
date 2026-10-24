package com.appm.pdfcraft;

import android.annotation.SuppressLint;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.content.res.Configuration;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Message;
import android.os.Process;
import android.print.PrintJob;
import android.print.PrintManager;
import android.provider.DocumentsContract;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
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
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.core.content.IntentCompat;
import androidx.core.graphics.Insets;
import androidx.core.os.BundleCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.lifecycle.Lifecycle;
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
    private static final String STATE_INCOMING = "incoming";

    /** Writes saves, one at a time and in order. */
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    /** Builds print PDFs, which can take minutes: never in front of a save. */
    private final ExecutorService printing = Executors.newSingleThreadExecutor();
    private final ArrayDeque<FileStore.SaveJob> saves = new ArrayDeque<>();

    private FileStore files;
    private Printer printer;
    private WebViewAssetLoader loader;
    private FrameLayout root;
    private WebView web;
    private ValueCallback<Uri[]> chooser;
    /** The page died while PdfCraft was out of sight: start it again when it is back. */
    private boolean restartWhenShown;
    /** Times the page died since PdfCraft last started; reset by {@link #ready}. */
    private int deathsBeforeReady;
    /**
     * The save waiting on the "Save as" picker, if one is open. Kept in the instance state: if
     * Android ends the process while the picker is open, the answer comes to a new activity.
     */
    private FileStore.SaveJob asking;
    /** A save is being written: the next job waits, as it may depend on where this one goes. */
    private boolean writing;
    private volatile boolean dirty;
    /** PdfCraft shows the page alone (its Full screen mode): the system bars are hidden. */
    private boolean fullScreen;

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
            // In Full screen mode the page takes the whole screen, cutouts too; bars swiped in
            // for a moment lie over it.
            Insets i = insets.getInsets(fullScreen ? WindowInsetsCompat.Type.ime()
                    : WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout()
                    | WindowInsetsCompat.Type.ime());
            v.setPadding(i.left, i.top, i.right, i.bottom);
            return WindowInsetsCompat.CONSUMED;
        });
        setContentView(root);

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (web == null) {
                    leave();
                    return;
                }
                // The page closes what is open first (a menu, a tool strip, full screen, a
                // panel); only when nothing is does Back leave PdfCraft.
                web.evaluateJavascript("(function(){var s=window.shell;if(s&&s.back)return s.back();"
                        + "if(s&&" + fullScreen + "){s.leaveFullScreen();return true;}return false;})()", result -> {
                    if (!"true".equals(result)) leave();
                });
            }

            /** Back leaves PdfCraft running with its documents open, as Home does: finishing
             *  the activity would throw away every open tab. */
            private void leave() {
                if (dirty) toast(getString(R.string.kept_running));
                moveTaskToBack(true);
            }
        });

        // The intent that started the activity, unless it was handled before: Android hands a
        // recreated activity the intent its task began with, whatever setIntent() said since.
        if (state == null && (getIntent().getFlags() & Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) == 0) {
            handle(getIntent());
        }
        startPdfCraft();
        nextSave();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (restartWhenShown) {
            restartWhenShown = false;
            startPdfCraft();
            toast(getString(R.string.restarted));
        }
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration config) {
        super.onConfigurationChanged(config);
        Log.i(TAG, "configuration changed: " + config);
        // Changes are handled here rather than by recreating the activity (that would lose every
        // open document): follow a switch to or from Dark theme.
        int surface = ContextCompat.getColor(this, R.color.surface);
        root.setBackgroundColor(surface);
        if (web != null) web.setBackgroundColor(surface);
        boolean night = (config.uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        WindowInsetsControllerCompat bars = WindowCompat.getInsetsController(getWindow(), root);
        bars.setAppearanceLightStatusBars(!night);
        bars.setAppearanceLightNavigationBars(!night);
        setBarColors(surface);
    }

    @SuppressWarnings("deprecation") // The bars' colours still show before Android 15.
    private void setBarColors(int color) {
        getWindow().setStatusBarColor(color);
        getWindow().setNavigationBarColor(color);
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle out) {
        super.onSaveInstanceState(out);
        if (asking != null) out.putBundle(STATE_ASKING, asking.toBundle());
        ArrayList<Bundle> queued = new ArrayList<>();
        for (FileStore.SaveJob job : saves) {
            if (!job.isClose()) queued.add(job.toBundle());
        }
        out.putParcelableArrayList(STATE_SAVES, queued);
        // Files handed in that the page hasn't opened yet: their intents won't come again.
        out.putParcelableArrayList(STATE_INCOMING, files.waiting());
    }

    /**
     * Take back the saves an ended process left waiting on the "Save as" picker, and delete the
     * spool files of any others.
     */
    private void restoreSaves(Bundle state) {
        List<File> keep = new ArrayList<>();
        // Writes an ended process was in the middle of come first: they were made before any
        // save still queued.
        for (FileStore.SaveJob job : files.interruptedWrites()) {
            saves.add(job);
            keep.add(job.file);
        }
        if (state != null) {
            asking = FileStore.SaveJob.fromBundle(state.getBundle(STATE_ASKING));
            if (asking != null) keep.add(asking.file);
            ArrayList<Bundle> queued = BundleCompat.getParcelableArrayList(state, STATE_SAVES, Bundle.class);
            if (queued != null) {
                for (Bundle b : queued) {
                    FileStore.SaveJob job = FileStore.SaveJob.fromBundle(b);
                    if (job == null || keep.contains(job.file)) continue;
                    saves.add(job);
                    keep.add(job.file);
                }
            }
        }
        files.sweepSpools(keep);
        if (state != null) {
            ArrayList<Bundle> waiting = BundleCompat.getParcelableArrayList(state, STATE_INCOMING, Bundle.class);
            if (waiting != null) files.restoreWaiting(waiting);
        }
        // Bytes no save could write, kept in their own tabs until saved or closed.
        files.reopenAll();
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
        printing.shutdown();
        super.onDestroy();
    }

    // ---- WebView ----

    /** Load PdfCraft into a new WebView, or say why it can't run. */
    private void startPdfCraft() {
        root.removeAllViews();
        try {
            web = createWebView();
        } catch (RuntimeException e) {
            // No WebView to run in: none installed, turned off, or being updated right now.
            Log.e(TAG, "can't create a WebView", e);
            showProblem(getString(R.string.no_webview));
            return;
        }
        root.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        web.loadUrl(START);
    }

    /** Instead of PdfCraft: what went wrong, with a way to update the WebView and to try again. */
    private void showProblem(String message) {
        web = null;
        root.removeAllViews();
        int pad = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 24, getResources().getDisplayMetrics()));
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(pad, pad, pad, pad);
        TextView text = new TextView(this);
        text.setText(message);
        text.setGravity(Gravity.CENTER);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        box.addView(text);
        Button update = new Button(this);
        update.setText(R.string.update_webview);
        update.setOnClickListener(v -> updateWebViewLater());
        box.addView(update);
        Button retry = new Button(this);
        retry.setText(R.string.try_again);
        retry.setOnClickListener(v -> {
            deathsBeforeReady = 0;
            startPdfCraft();
        });
        box.addView(retry);
        root.addView(box, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    @SuppressLint("SetJavaScriptEnabled")
    private WebView createWebView() {
        WebView view = new WebView(this);
        view.setBackgroundColor(ContextCompat.getColor(this, R.color.surface));
        view.setOverScrollMode(View.OVER_SCROLL_NEVER);

        WebSettings s = view.getSettings();
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

        view.setWebViewClient(new WebViewClient() {
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
                if (view != web) return true;
                root.removeView(web);
                web.destroy();
                web = null;
                dirty = false;
                showFullScreen(false);
                // What the dead page was sending can't be finished, and a picker it opened must
                // hand its files to the next page instead.
                files.abortAll();
                printer.abortAll();
                files.reopenAll();
                if (chooser != null) {
                    chooser.onReceiveValue(null);
                    chooser = null;
                }
                if (++deathsBeforeReady > 2) {
                    // It dies before it is even ready: starting it again won't help.
                    showProblem(getString(R.string.keeps_stopping));
                } else if (getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.STARTED)) {
                    startPdfCraft();
                    toast(getString(R.string.restarted));
                } else {
                    // In the background, under the memory pressure that likely ended it: wait.
                    restartWhenShown = true;
                }
                return true;
            }
        });

        view.setWebChromeClient(new WebChromeClient() {
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

        view.addJavascriptInterface(new ShellBridge(this, files, printer), "AndroidShell");
        return view;
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
        for (Uri uri : uris) {
            if (uri == null) continue;
            // A file:// path would be read with PdfCraft's own rights: other apps' files are
            // out of reach under scoped storage, and PdfCraft's private files must stay private.
            if (!"content".equals(uri.getScheme())) {
                toast(getString(R.string.not_shared, String.valueOf(uri.getLastPathSegment())));
                continue;
            }
            Log.i(TAG, "incoming " + files.addIncoming(uri, canWrite) + ": " + uri);
        }
        // Handled: don't open the files again if the activity is recreated in this process.
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

    /**
     * Keep read (and, where offered, write) access to a picked file, so a save can still reach
     * it after Android ends the app mid-write; true if a Save may overwrite it.
     */
    private boolean keepAccess(Uri uri) {
        int rw = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
        try {
            getContentResolver().takePersistableUriPermission(uri, rw);
        } catch (SecurityException e) {
            try {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (SecurityException ignored) {
                // Readable for now; that's enough to open it.
            }
        }
        // The picker grants write access to every file it returns, read-only ones included:
        // ask the file's provider too.
        return checkUriPermission(uri, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                == PackageManager.PERMISSION_GRANTED && supportsWrite(uri);
    }

    private boolean supportsWrite(Uri uri) {
        // Only these providers set FLAG_SUPPORTS_WRITE exactly when a file can be written: the
        // media provider's Documents and Recent entries never set it, yet write fine. Elsewhere
        // try the write; a failed one is safe (FileStore.replace) and saves a copy instead.
        String authority = uri.getAuthority();
        if (!"com.android.externalstorage.documents".equals(authority)
                && !"com.android.providers.downloads.documents".equals(authority)) return true;
        if (!DocumentsContract.isDocumentUri(this, uri)) return true;
        try (Cursor c = getContentResolver().query(uri, new String[] {DocumentsContract.Document.COLUMN_FLAGS}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) {
                return (c.getInt(0) & DocumentsContract.Document.FLAG_SUPPORTS_WRITE) != 0;
            }
        } catch (RuntimeException ignored) {
            // Unknown: try, and fall back to a copy if the write fails.
        }
        return true;
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

    /** PdfCraft handed over a file, or closed a document (from the bridge thread). */
    void onSave(FileStore.SaveJob job) {
        runOnUiThread(() -> {
            saves.add(job);
            nextSave();
        });
    }

    /**
     * Take the next job, once the one before is done: a save goes where the saves before it left
     * its document bound, and a close lets go of that only after them.
     */
    private void nextSave() {
        // A destroyed activity's last write can finish after its successor took over the queue
        // (from the saved state); this one's executor and pickers are gone.
        if (isDestroyed() || asking != null || writing) return;
        FileStore.SaveJob job = saves.poll();
        if (job == null) return;
        if (job.isClose()) {
            files.forget(job.key);
            files.settled(job.key);
            nextSave();
            return;
        }
        if (!job.saveAs && !job.isExport()) {
            Uri target = job.resume != null ? job.resume : files.targetFor(job.key, job.name);
            writing = true;
            io.execute(() -> {
                String message = write(job, target);
                runOnUiThread(() -> {
                    writing = false;
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
            // don't leave the empty file the picker made. A file chosen to be replaced stays.
            if (uri != null) {
                io.execute(() -> files.deleteIfEmpty(uri));
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
        if (uri != null) {
            try {
                getContentResolver().takePersistableUriPermission(uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } catch (SecurityException ignored) {
                // Writable for now; a write cut short by a restart then goes to Downloads.
            }
        }
        // PdfCraft already counts the document as saved, so a cancelled Save As still keeps
        // a copy (in Downloads/PdfCraft) rather than losing the work.
        if (uri != null && !job.isExport()) files.bind(job.key, uri);
        writing = true;
        io.execute(() -> {
            String message = write(job, uri);
            if (uri == null) message = getString(R.string.save_as_cancelled, message);
            String shown = message;
            runOnUiThread(() -> {
                writing = false;
                toast(shown);
                nextSave();
            });
        });
    }

    /** Write a save to {@code target}, or to Downloads/PdfCraft when there's none or it fails. */
    private String write(FileStore.SaveJob job, Uri target) {
        if (job.restore) return undo(job, target);
        String message = writeOrCopy(job, target);
        return job.resume != null ? getString(R.string.save_resumed, message) : message;
    }

    /** A write an earlier process didn't finish, whose new bytes are gone: put the file back. */
    private String undo(FileStore.SaveJob job, Uri target) {
        try {
            if (files.restore(target, job)) return getString(R.string.save_undone, files.displayName(target));
            return getString(R.string.save_undone_copy, job.name, FileStore.DOWNLOADS_FOLDER + "/" + FileStore.beforeSaving(job.name));
        } catch (IOException | RuntimeException e) {
            Log.e(TAG, "can't put back " + target, e);
            return getString(R.string.save_undone_later, job.name);
        }
    }

    private String writeOrCopy(FileStore.SaveJob job, Uri target) {
        boolean keep = false;
        try {
            if (target != null) {
                try {
                    // Never leaves the file half-written: on failure it holds what it held.
                    files.replace(target, job);
                    files.settled(job.key);
                    return getString(R.string.saved, files.displayName(target));
                } catch (IOException | RuntimeException e) {
                    Log.w(TAG, "can't write " + target, e);
                }
            }
            Uri copy = files.saveToDownloads(job.name, job.mime, job.file);
            // Later saves of the document update this copy. A cancelled Save As leaves them
            // going where they went before, if anywhere.
            if (!job.isExport() && job.key != null) {
                if (target == null && job.saveAs) files.bindIfUnbound(job.key, copy);
                else files.bind(job.key, copy);
                files.settled(job.key);
            }
            String where = FileStore.DOWNLOADS_FOLDER + "/" + files.displayName(copy);
            return target != null ? getString(R.string.saved_instead, where) : getString(R.string.saved_to, where);
        } catch (IOException | RuntimeException e) {
            Log.e(TAG, "save failed", e);
            String reason = String.valueOf(e.getMessage());
            if (job.isExport()) return getString(R.string.save_failed, job.name, reason);
            // PdfCraft counts the document as saved (and may have closed it already): keep the
            // bytes that couldn't be written, open in a new tab until saved or closed.
            try {
                files.settled(job.key); // Older bytes kept for this document are superseded.
                files.reopen(job);
            } catch (IOException | RuntimeException again) {
                Log.e(TAG, "can't keep the unsaved bytes", again);
                keep = true; // In the cache at least, until the next start.
                return getString(R.string.save_failed, job.name, reason);
            }
            runOnUiThread(() -> {
                if (web != null) web.evaluateJavascript("window.shell && shell.incoming()", null);
            });
            return getString(R.string.save_failed_reopened, job.name, reason);
        } finally {
            //noinspection ResultOfMethodCallIgnored
            if (!keep) job.file.delete(); // Gone already if it was kept (reopen moved it).
        }
    }

    // ---- printing ----

    /** All sheets of print job {@code id} arrived (from the bridge thread). */
    void print(Printer printer, String id) {
        printing.execute(() -> {
            try {
                Printer.Document document = printer.build(id);
                runOnUiThread(() -> showPrintDialog(document));
            } catch (IOException | RuntimeException e) {
                Log.e(TAG, "print failed", e);
                toast(getString(R.string.print_failed, String.valueOf(e.getMessage())));
            }
        });
    }

    private void showPrintDialog(Printer.Document document) {
        // Building can take a while: the activity may be on its way out by now.
        if (isFinishing() || isDestroyed()) {
            Printer.deleteTree(document.dir);
            return;
        }
        PrintManager manager = (PrintManager) getSystemService(PRINT_SERVICE);
        PrintJob job = null;
        try {
            if (manager != null) job = manager.print(document.name, Printer.adapter(document), null);
        } catch (RuntimeException e) {
            Log.e(TAG, "can't start printing", e);
        }
        if (job == null) {
            // No print service on this device (print() then returns null), or it refused.
            Printer.deleteTree(document.dir);
            toast(getString(R.string.no_printing));
        }
    }

    // ---- small things ----

    void setDirty(boolean dirty) {
        this.dirty = dirty;
    }

    /** PdfCraft entered or left Full screen mode (from the bridge thread). */
    void setFullScreen(boolean on) {
        runOnUiThread(() -> showFullScreen(on));
    }

    /**
     * Hide the status and navigation bars while PdfCraft shows the page alone. A swipe from an
     * edge shows them for a moment; Back leaves Full screen mode. The browser build can't do this
     * itself: eframe ignores its full-screen request on the web.
     */
    private void showFullScreen(boolean on) {
        if (fullScreen == on || isDestroyed()) return;
        fullScreen = on;
        WindowManager.LayoutParams attrs = getWindow().getAttributes();
        attrs.layoutInDisplayCutoutMode = !on ? WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
                : Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ? WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                : WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        getWindow().setAttributes(attrs);
        WindowInsetsControllerCompat bars = WindowCompat.getInsetsController(getWindow(), root);
        if (on) {
            bars.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            bars.hide(WindowInsetsCompat.Type.systemBars());
        } else {
            bars.show(WindowInsetsCompat.Type.systemBars());
        }
        Log.i(TAG, "full screen " + on);
        ViewCompat.requestApplyInsets(root);
    }

    /** PdfCraft started in the page and is ready for files (from the bridge thread). */
    void ready() {
        Log.i(TAG, "PdfCraft ready");
        runOnUiThread(() -> deathsBeforeReady = 0);
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

    /**
     * Offer {@code job}'s bytes to other apps through the share sheet: a read-only copy in the
     * cache (an earlier share's copy is deleted), named as the document is.
     */
    void share(FileStore.SaveJob job) {
        runOnUiThread(() -> {
            File dir = new File(getCacheDir(), "shared");
            File[] old = dir.listFiles();
            if (old != null) {
                for (File f : old) {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
            }
            File copy = new File(dir, job.name.toLowerCase(Locale.ROOT).endsWith(".pdf") ? job.name : job.name + ".pdf");
            if ((!dir.isDirectory() && !dir.mkdirs()) || !job.file.renameTo(copy)) {
                //noinspection ResultOfMethodCallIgnored
                job.file.delete();
                toast("Couldn't share " + job.name);
                return;
            }
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".share", copy);
            Intent send = new Intent(Intent.ACTION_SEND)
                    .setType("application/pdf")
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            send.setClipData(ClipData.newRawUri(copy.getName(), uri));
            try {
                startActivity(Intent.createChooser(send, null));
            } catch (ActivityNotFoundException e) {
                toast("No app to share " + copy.getName() + " with");
            }
        });
    }

    void copyText(String text) {
        runOnUiThread(() -> {
            ClipboardManager clipboard = getSystemService(ClipboardManager.class);
            if (clipboard == null) return;
            clipboard.setPrimaryClip(ClipData.newPlainText("PDF text", text));
            // Android 13 and later show what was copied themselves.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                Toast.makeText(getApplicationContext(), "Copied", Toast.LENGTH_SHORT).show();
            }
        });
    }

    void haptic() {
        // The view's own setting: no buzz where the user turned touch feedback off.
        runOnUiThread(() -> {
            if (web != null) web.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        });
    }

    void toast(String message) {
        Log.i(TAG, "toast: " + message);
        // The application's context: a save can finish after this activity is destroyed.
        runOnUiThread(() -> Toast.makeText(getApplicationContext(), message, Toast.LENGTH_LONG).show());
    }
}
