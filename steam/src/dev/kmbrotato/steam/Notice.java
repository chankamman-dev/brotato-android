package dev.kmbrotato.steam;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

// A toast from background work (the routine sync runs while the game is in front).
final class Notice {
    static void show(Context c, String message) {
        Context app = c.getApplicationContext();
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                Toast.makeText(app, message, Toast.LENGTH_LONG).show();
            } catch (Throwable ignored) {
            }
        });
    }

    private Notice() { }
}
