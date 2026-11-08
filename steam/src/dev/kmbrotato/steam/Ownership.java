package dev.kmbrotato.steam;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.util.concurrent.TimeUnit;

import in.dragonbra.javasteam.enums.EResult;
import in.dragonbra.javasteam.steam.handlers.steamapps.SteamApps;
import in.dragonbra.javasteam.steam.handlers.steamapps.callback.AppOwnershipTicketCallback;

// Which of the game's apps the signed-in account owns, asked once per launch sync and cached.
// Steam hands out an ownership ticket only for apps the account has a license for. Only definite
// answers replace the cache, so a network hiccup never takes a DLC away.
final class Ownership {
    static final int BASE = 1942280, ABYSSAL_TERRORS = 2868390, PRIMAL_DREAD = 4498220;
    static final int[] APPS = { BASE, ABYSSAL_TERRORS, PRIMAL_DREAD };

    // Must be called before the session connects (handlers cannot be added mid-stream).
    static void prepare(SteamSession s) {
        if (s.client.getHandler(SteamApps.class) == null) s.client.addHandler(new SteamApps());
    }

    static void refresh(Context c, SteamSession s) {
        SteamApps apps = s.client.getHandler(SteamApps.class);
        if (apps == null) return;
        SharedPreferences.Editor e = TokenStore.prefs(c).edit();
        StringBuilder log = new StringBuilder();
        for (int app : APPS) {
            try {
                AppOwnershipTicketCallback cb = apps.getAppOwnershipTicket(app).toFuture().get(20, TimeUnit.SECONDS);
                EResult r = cb.getResult();
                if (r == EResult.OK) e.putBoolean("owned_" + app, true);
                else if (r == EResult.AccessDenied || r == EResult.NoMatch || r == EResult.Fail) e.putBoolean("owned_" + app, false);
                log.append(app).append('=').append(r).append(' ');
            } catch (Throwable t) {
                log.append(app).append("=? (").append(t.getClass().getSimpleName()).append(") ");
            }
        }
        e.apply();
        Log.i(SteamPlugin.TAG, "ownership: " + log.toString().trim());
    }

    static boolean owns(Context c, int app, boolean unknown) {
        SharedPreferences p = TokenStore.prefs(c);
        return p.contains("owned_" + app) ? p.getBoolean("owned_" + app, unknown) : unknown;
    }

    private Ownership() { }
}
