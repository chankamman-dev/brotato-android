package dev.kmbrotato.steam;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Random;

// The Steam refresh token, the account it belongs to and what Steam told us about it (SteamID64,
// which apps the account owns). Kept in the app's private preferences; the game reads the cached
// values through SteamPlugin without ever waiting for the network.
final class TokenStore {
    static final String PREFS = "brotato_steam";

    final String account;
    final String refreshToken;

    private TokenStore(String account, String refreshToken) {
        this.account = account;
        this.refreshToken = refreshToken;
    }

    static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static TokenStore load(Context c) {
        SharedPreferences p = prefs(c);
        String a = p.getString("account", null), t = p.getString("token", null);
        return a == null || t == null ? null : new TokenStore(a, t);
    }

    static void save(Context c, String account, String token) {
        prefs(c).edit().putString("account", account).putString("token", token).apply();
    }

    // Signing out forgets the identity too: the game then saves under files/user again.
    // Set when a background (routine) sign-in was rejected: the next launch shows the launcher.
    static boolean relogin(Context c) {
        return prefs(c).getBoolean("relogin", false);
    }

    static void setRelogin(Context c, boolean v) {
        if (relogin(c) != v) prefs(c).edit().putBoolean("relogin", v).apply();
    }

    static void clear(Context c) {
        SharedPreferences.Editor e = prefs(c).edit().remove("account").remove("token").remove("steam_id").remove(Workshop.PREF_IDS).remove("relogin");
        for (int app : Ownership.APPS) e.remove("owned_" + app);
        e.apply();
    }

    static void saveSteamId(Context c, long id) {
        prefs(c).edit().putString("steam_id", Long.toString(id)).apply();
    }

    // "" until the first successful logon.
    static String steamId(Context c) {
        return prefs(c).getString("steam_id", "");
    }

    // A stable login id of our own, so this session never replaces the desktop client's.
    static int loginId(Context c) {
        SharedPreferences p = prefs(c);
        int id = p.getInt("login_id", 0);
        if (id == 0) {
            id = 0x42520000 | (new Random().nextInt(0xFFFF) + 1);
            p.edit().putInt("login_id", id).apply();
        }
        return id;
    }
}
