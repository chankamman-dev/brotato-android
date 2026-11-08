package dev.kmbrotato.steam;

import android.content.Context;
import android.os.FileObserver;
import android.util.Log;

import java.io.File;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

// Uploads the game's saves while it runs. Brotato writes user://<SteamID64>/*.json itself, so instead
// of hooking its scripts this watches that folder: each finished write or delete of a synced file
// (re)starts a short timer, and the upload runs once the burst of writes has settled -- the game
// writes save_v3_0.json and run_v3_0.json together at the end of every wave. Leaving the game
// (onPause) uploads right away. Uploads never touch the local files (CloudSync localWrites=false).
final class CloudBridge {
    private static final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "BroSteam-upload");
        t.setDaemon(true);
        return t;
    });
    private static ScheduledFuture<?> pending;
    private static FileObserver observer;
    private static boolean dirty;

    static synchronized void watch(Context c) {
        Context app = c.getApplicationContext();
        File dir = CloudSync.saveDir(app);
        if (observer != null || dir == null || TokenStore.load(app) == null || !CloudSync.initialized(app)) {
            Log.i(SteamPlugin.TAG, "cloud upload off (" + (dir == null ? "not signed in" : !CloudSync.initialized(app) ? "never synced" : "already watching") + ")");
            return;
        }
        dir.mkdirs();
        int mask = FileObserver.CLOSE_WRITE | FileObserver.MOVED_TO | FileObserver.DELETE;
        observer = new FileObserver(dir.getPath(), mask) {
            @Override
            public void onEvent(int event, String path) {
                if (path != null && CloudSync.SYNCED.matcher(path).matches()) onSaved(app, path);
            }
        };
        observer.startWatching();
        Log.i(SteamPlugin.TAG, "watching " + dir + " for saves");
    }

    private static synchronized void onSaved(Context app, String name) {
        dirty = true;
        if (pending != null) pending.cancel(false);
        pending = worker.schedule(() -> upload(app, "save " + name), 4, TimeUnit.SECONDS);
    }

    // The game is leaving the screen: push what is waiting now rather than in a few seconds.
    static synchronized void flush(Context c) {
        if (!dirty) return;
        if (pending != null) pending.cancel(false);
        Context app = c.getApplicationContext();
        pending = worker.schedule(() -> upload(app, "pause"), 0, TimeUnit.SECONDS);
    }

    private static void upload(Context app, String why) {
        synchronized (CloudBridge.class) { dirty = false; }
        try {
            CloudSync.Result r = CloudSync.sync(app, CloudSync.Log.NONE, false);
            Log.i(SteamPlugin.TAG, "cloud upload (" + why + "): " + r);
        } catch (Throwable t) {
            synchronized (CloudBridge.class) { dirty = true; }
            Log.w(SteamPlugin.TAG, "cloud upload (" + why + ") failed: " + t);
        }
    }

    private CloudBridge() { }
}
