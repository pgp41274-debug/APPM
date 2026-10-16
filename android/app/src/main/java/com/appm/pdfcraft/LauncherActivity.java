package com.appm.pdfcraft;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/**
 * The launcher icon. When PdfCraft is already open, Android brings its task to the front without
 * starting this at all, so a picker or print dialog open there stays open; only a fresh start
 * comes here, and goes on to {@link MainActivity}.
 */
public final class LauncherActivity extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }
}
