package dev.kmbrotato.steam;

import java.io.Closeable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import in.dragonbra.javasteam.enums.EResult;
import in.dragonbra.javasteam.steam.handlers.steamuser.LogOnDetails;
import in.dragonbra.javasteam.steam.handlers.steamuser.SteamUser;
import in.dragonbra.javasteam.steam.handlers.steamuser.callback.LoggedOnCallback;
import in.dragonbra.javasteam.steam.steamclient.SteamClient;
import in.dragonbra.javasteam.steam.steamclient.callbackmgr.CallbackManager;
import in.dragonbra.javasteam.steam.steamclient.callbackmgr.CallbackMsg;
import in.dragonbra.javasteam.util.compat.Consumer;
import in.dragonbra.javasteam.steam.steamclient.callbacks.ConnectedCallback;
import in.dragonbra.javasteam.steam.steamclient.callbacks.DisconnectedCallback;

// One connection to Steam, with its callback pump on a daemon thread.
//
// WebSocket (port 443) only, as the Isaac port found: JavaSteam's default also tries the connection
// servers' TCP ports by bare IP, and through Clash (TUN mode on the Thor) those connections dropped
// or hung ("Steam 断开了连接", "连接 Steam 超时") -- a bare IP only meets the proxy's catch-all rule,
// while a WebSocket server is reached by host name (cmpN-xxx.steamserver.net), which its Steam rules
// match. One configuration for every session, so the server list (failed servers are marked) is
// shared; once init() has run it is also kept in files/steam-servers.bin, so a sign-in does not
// first fetch it from the Steam directory web API.
final class SteamSession implements Closeable {
    private static volatile in.dragonbra.javasteam.steam.steamclient.configuration.SteamConfiguration config;

    static synchronized void init(android.content.Context c) {
        if (config != null) return;
        java.io.File list = new java.io.File(c.getFilesDir(), "steam-servers.bin");
        config = in.dragonbra.javasteam.steam.steamclient.configuration.SteamConfiguration.create(b -> b
                .withProtocolTypes(in.dragonbra.javasteam.networking.steam3.ProtocolTypes.WEB_SOCKET)
                .withConnectionTimeout(8_000L)
                .withServerListProvider(new in.dragonbra.javasteam.steam.discovery.FileServerListProvider(list)));
    }

    private static synchronized in.dragonbra.javasteam.steam.steamclient.configuration.SteamConfiguration config() {
        if (config == null) {
            config = in.dragonbra.javasteam.steam.steamclient.configuration.SteamConfiguration.create(b -> b
                    .withProtocolTypes(in.dragonbra.javasteam.networking.steam3.ProtocolTypes.WEB_SOCKET)
                    .withConnectionTimeout(8_000L));
        }
        return config;
    }

    final SteamClient client;
    private final CallbackManager callbacks;
    private final AtomicBoolean running = new AtomicBoolean(true);
    long steamId64;

    SteamSession() {
        SteamCrypto.install();
        client = new SteamClient(config());
        callbacks = new CallbackManager(client);
        Thread pump = new Thread(() -> {
            while (running.get()) {
                try {
                    callbacks.runWaitCallbacks(100L);
                } catch (Throwable t) {
                    if (!running.get()) break;
                }
            }
        }, "BroSteam-pump");
        pump.setDaemon(true);
        pump.start();
    }

    <T extends CallbackMsg> Closeable on(Class<T> type, Consumer<T> handler) {
        return callbacks.subscribe(type, handler);
    }

    // A server that does not answer is given up after 8 s and the connection retried (3 tries), rather
    // than one 25 s wait: through a proxy (Clash on the Thor) a connection can hang without failing.
    void connect() throws Exception {
        Exception last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            CountDownLatch done = new CountDownLatch(1);
            AtomicBoolean ok = new AtomicBoolean(false);
            Closeable a = callbacks.subscribe(ConnectedCallback.class, cb -> { ok.set(true); done.countDown(); });
            Closeable b = callbacks.subscribe(DisconnectedCallback.class, cb -> done.countDown());
            try {
                client.connect();
                if (!done.await(10, TimeUnit.SECONDS)) last = new Exception("连接 Steam 超时");
                else if (!ok.get()) last = new Exception("Steam 断开了连接");
                else return;
            } finally {
                a.close();
                b.close();
            }
            android.util.Log.w(SteamPlugin.TAG, "connect attempt " + attempt + ": " + last.getMessage());
            try {
                client.disconnect();
            } catch (Throwable ignored) {
            }
            Thread.sleep(300);
        }
        throw last;
    }

    void logOn(String account, String refreshToken, int loginId) throws Exception {
        for (int attempt = 1; ; attempt++) {
            connect();
            SteamUser user = client.getHandler(SteamUser.class);
            CountDownLatch done = new CountDownLatch(1);
            AtomicReference<LoggedOnCallback> result = new AtomicReference<>();
            Closeable s = callbacks.subscribe(LoggedOnCallback.class, cb -> { result.set(cb); done.countDown(); });
            try {
                LogOnDetails d = new LogOnDetails();
                d.setUsername(account);
                d.setAccessToken(refreshToken);
                d.setShouldRememberPassword(true);
                d.setLoginID(loginId);
                user.logOn(d);
                if (!done.await(25, TimeUnit.SECONDS)) throw new Exception("登录 Steam 超时");
            } finally {
                s.close();
            }
            EResult r = result.get().getResult();
            if (r == EResult.OK) {
                steamId64 = result.get().getClientSteamID().convertToUInt64();
                return;
            }
            if (r != EResult.TryAnotherCM || attempt >= 3) throw new LogOnRejected(r);
            client.disconnect();
            Thread.sleep(500L * attempt);
        }
    }

    static final class LogOnRejected extends Exception {
        final EResult result;

        LogOnRejected(EResult r) {
            super("Steam 拒绝登录：" + r);
            result = r;
        }
    }

    @Override
    public void close() {
        running.set(false);
        try {
            client.disconnect();
        } catch (Throwable ignored) {
        }
    }
}
