package dev.kmbrotato.steam;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;
import android.view.View;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import org.godotengine.godot.Godot;
import org.godotengine.godot.plugin.GodotPlugin;

// The engine singleton "Steam" for the Android build. On PC it is GodotSteam, compiled into
// Brotato's engine; here res://singletons/platforms/steam.gd (overlay) talks to this instead.
// Every method answers from cached state and returns at once: they run on the game's render thread.
// Steam itself is reached by LauncherActivity (sign-in, cloud download, ownership), SteamPlay
// (playtime, achievements) and CloudBridge (cloud upload), all on their own threads.
// JNI in Godot 3 passes ints as 32-bit, so the SteamID64 travels as a string.
public final class SteamPlugin extends GodotPlugin {
    static final String TAG = "BroSteam";
    // Set while GodotApp exists in this process: the launcher then goes straight back to the game
    // instead of syncing saves the running game has open.
    static volatile boolean gameRunning;
    private Context app;

    public SteamPlugin(Godot godot) {
        super(godot);
    }

    @Override
    public String getPluginName() {
        return "Steam";
    }

    @Override
    public List<String> getPluginMethods() {
        return Arrays.asList("isSignedIn", "getSteamIDString", "isSubscribed", "isDLCOwned", "getGameLanguage",
                "isAchieved", "setAchievement", "loggedOn", "openUrl", "getSubscribedItemsString", "waitForSaves", "notifyAchievement");
    }

    @Override
    public View onMainCreate(Activity activity) {
        app = activity.getApplicationContext();
        gameRunning = true;
        SteamCrypto.install();
        SteamSession.init(app);
        // user://logs: on PC Godot's file logging creates it. Without it the mod loader fails on
        // every line it logs ("File must be opened before use") and the crash reporter has
        // nothing to collect. Runs before the engine starts the game's autoloads.
        new java.io.File(app.getFilesDir(), "logs").mkdirs();
        // Checking the achievement notice's look: adb shell am broadcast -a dev.kmbrotato.TEST_ACHIEVEMENT
        // [--es name chal_x --es title T --es desc D --es locale zh]. Only in debug builds, or while the
        // file Android/data/dev.kmbrotato.brotato/files/achievement-test exists (put there over USB).
        java.io.File ext = app.getExternalFilesDir(null);
        boolean testable = (app.getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
                || (ext != null && new java.io.File(ext, "achievement-test").isFile());
        if (testable) {
            android.content.BroadcastReceiver r = new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent i) {
                    String n = i.getStringExtra("name"), loc = i.getStringExtra("locale");
                    activity.runOnUiThread(() -> AchievementToast.show(activity, n == null ? "chal_farmer" : n,
                            i.getStringExtra("title"), i.getStringExtra("desc"), loc == null ? "zh" : loc));
                }
            };
            android.content.IntentFilter f = new android.content.IntentFilter("dev.kmbrotato.TEST_ACHIEVEMENT");
            if (android.os.Build.VERSION.SDK_INT >= 33) app.registerReceiver(r, f, Context.RECEIVER_EXPORTED);
            else app.registerReceiver(r, f);
        }
        boolean signedIn = isSignedIn();
        Log.i(TAG, "plugin up, signed in " + signedIn + (signedIn ? " as " + TokenStore.steamId(app) : ""));
        if (signedIn) {
            // Here, before the game activity starts, so SteamPlay counts its onStart (it decides
            // what is visible). Its sign-in waits for a routine launch's cloud phase (SteamPlay.run).
            SteamPlay.start(app);
            CloudBridge.watch(app);
        }
        return null;
    }

    @Override
    public void onMainDestroy() {
        gameRunning = false;
    }

    @Override
    public void onMainPause() {
        if (app != null) CloudBridge.flush(app);
    }

    private Context ctx() {
        return app != null ? app : getActivity().getApplicationContext();
    }

    public boolean isSignedIn() {
        return TokenStore.load(ctx()) != null && !TokenStore.steamId(ctx()).isEmpty();
    }

    public String getSteamIDString() {
        return isSignedIn() ? TokenStore.steamId(ctx()) : "";
    }

    // The base game is never denied: an unanswered or failed check must not lock the player out.
    public boolean isSubscribed() {
        return Ownership.owns(ctx(), Ownership.BASE, true);
    }

    // DLC is owned only when Steam said so (cached from the last launch sync).
    public boolean isDLCOwned(int appId) {
        return isSignedIn() && Ownership.owns(ctx(), appId, false);
    }

    // The Steam language name for the system language (what getCurrentGameLanguage returns on PC).
    public String getGameLanguage() {
        Locale l = Locale.getDefault();
        String lang = l.getLanguage(), country = l.getCountry(), script = l.getScript();
        switch (lang) {
            case "zh":
                return "Hant".equals(script) || "TW".equals(country) || "HK".equals(country) || "MO".equals(country) ? "tchinese" : "schinese";
            case "ja": return "japanese";
            case "ko": return "koreana";
            case "ru": return "russian";
            case "pl": return "polish";
            case "es": return "spanish";
            case "pt": return "brazilian";
            case "de": return "german";
            case "tr": return "turkish";
            case "it": return "italian";
            case "fr": return "french";
            default: return "english";
        }
    }

    public boolean isAchieved(String name) {
        return SteamPlay.isAchieved(ctx(), name);
    }

    // A Steam-style "achievement unlocked" notice, for a challenge completed while playing (steam.gd
    // calls it after setAchievement, never for the start-up re-sync). title/desc are the game's own
    // texts, used when the Steam schema has not been cached yet; locale is the game's language.
    public void notifyAchievement(String name, String title, String desc, String locale) {
        Activity a = getActivity();
        if (a == null || !isSignedIn() || SteamPlay.onSteam(ctx(), name)) return;
        a.runOnUiThread(() -> AchievementToast.show(a, name, title, desc, locale));
    }

    public boolean setAchievement(String name) {
        if (!isSignedIn()) return false;
        SteamPlay.unlock(ctx(), new String[] { name });
        return true;
    }

    // Called by assets/android/boot.gd's _ready, right before the game reads its saves: blocks
    // until a routine launch's cloud phase is over (at most ms). true = the saves are in place.
    public boolean waitForSaves(int ms) {
        long t0 = android.os.SystemClock.elapsedRealtime();
        boolean done = CloudSync.Gate.await(ms);
        Log.i(TAG, "waitForSaves: " + (done ? "ready" : "gave up") + " after " + (android.os.SystemClock.elapsedRealtime() - t0) + " ms");
        return done;
    }

    public boolean loggedOn() {
        return SteamPlay.isUp();
    }

    // Workshop items that are subscribed and fully downloaded to user://workshop/<id>/ as of the
    // last launch sync, comma-separated (ids pass 2^31, so not as ints).
    public String getSubscribedItemsString() {
        return isSignedIn() ? TokenStore.prefs(ctx()).getString(Workshop.PREF_IDS, "") : "";
    }

    // Stands in for the Steam overlay's web browser.
    public void openUrl(String url) {
        Activity a = getActivity();
        if (a == null || url == null || url.isEmpty()) return;
        runOnUiThread(() -> {
            try {
                a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Throwable t) {
                Log.w(TAG, "cannot open " + url + ": " + t);
            }
        });
    }
}
