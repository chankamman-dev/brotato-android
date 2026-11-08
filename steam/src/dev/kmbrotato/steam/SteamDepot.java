package dev.kmbrotato.steam;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import in.dragonbra.javasteam.enums.EResult;
import in.dragonbra.javasteam.steam.cdn.Client;
import in.dragonbra.javasteam.steam.cdn.Server;
import in.dragonbra.javasteam.steam.handlers.steamapps.PICSProductInfo;
import in.dragonbra.javasteam.steam.handlers.steamapps.PICSRequest;
import in.dragonbra.javasteam.steam.handlers.steamapps.SteamApps;
import in.dragonbra.javasteam.steam.handlers.steamapps.callback.DepotKeyCallback;
import in.dragonbra.javasteam.steam.handlers.steamapps.callback.PICSProductInfoCallback;
import in.dragonbra.javasteam.steam.handlers.steamapps.callback.PICSTokensCallback;
import in.dragonbra.javasteam.steam.handlers.steamcontent.SteamContent;
import in.dragonbra.javasteam.types.ChunkData;
import in.dragonbra.javasteam.types.DepotManifest;
import in.dragonbra.javasteam.types.KeyValue;
import kotlinx.coroutines.Deferred;
import kotlinx.coroutines.GlobalScope;
import kotlinx.coroutines.future.FutureKt;

// Reads one depot of an app from the Steam content network, the way DepotDownloader does: app info
// (PICS) for the manifest id, the depot key, a manifest request code, then the manifest and its chunks
// from a CDN server. Adapted from the Magical Princess port. Needs a logged-on session whose account
// owns the app, with the SteamApps handler added before connecting (Ownership.prepare).
// chunk() may be called from several threads at once.
final class SteamDepot implements Closeable {
    final int appId, depotId;
    private final SteamApps apps;
    private final SteamContent content;
    private final Client cdn;
    private final List<Server> servers = new ArrayList<>();
    private final AtomicInteger next = new AtomicInteger();
    private volatile byte[] depotKey;

    SteamDepot(SteamSession session, int appId, int depotId) {
        this.appId = appId;
        this.depotId = depotId;
        this.apps = session.client.getHandler(SteamApps.class);
        this.content = session.client.getHandler(SteamContent.class);
        this.cdn = new Client(session.client);
    }

    // The manifest id of the depot on a branch ("public"), from the app's PICS info.
    long branchManifest(String branch) throws Exception {
        if (apps == null) throw new IOException("SteamApps handler missing");
        PICSTokensCallback tokens = get(apps.picsGetAccessTokens(appId, null).toFuture());
        Long token = tokens.getAppTokens().get(appId);
        PICSRequest req = new PICSRequest(appId, token == null ? 0L : token);
        List<PICSProductInfoCallback> res = get(apps.picsGetProductInfo(req, null).toFuture()).getResults();
        for (PICSProductInfoCallback cb : res) {
            PICSProductInfo info = cb.getApps().get(appId);
            if (info == null) continue;
            KeyValue m = info.getKeyValues().get("depots").get(String.valueOf(depotId)).get("manifests").get(branch);
            String gid = m.get("gid").getValue();
            if (gid == null) gid = m.getValue();
            if (gid != null) return Long.parseUnsignedLong(gid);
        }
        throw new IOException("Steam 没有返回游戏的版本信息（账号可能没有这个游戏）");
    }

    private synchronized void prepare() throws Exception {
        if (depotKey == null) {
            DepotKeyCallback k = get(apps.getDepotDecryptionKey(depotId, appId).toFuture());
            if (k.getResult() != EResult.OK) throw new IOException("拿不到游戏内容密钥（" + k.getResult() + "），请确认账号拥有这个游戏");
            depotKey = k.getDepotKey();
        }
        if (servers.isEmpty()) {
            Deferred<List<Server>> d = content.getServersForSteamPipe(null, null, GlobalScope.INSTANCE);
            List<Server> all = get(FutureKt.asCompletableFuture(d));
            for (Server s : all) {
                String t = s.getType();
                if (!"SteamCache".equals(t) && !"CDN".equals(t)) continue;
                if (s.getUseAsProxy()) continue; // needs the proxy URL template, not a plain content server
                int[] allowed = s.getAllowedAppIds();
                if (allowed != null && allowed.length > 0 && Arrays.stream(allowed).noneMatch(x -> x == appId)) continue;
                servers.add(s);
            }
            servers.sort(Comparator.comparingDouble(Server::getWeightedLoad));
            if (servers.isEmpty()) throw new IOException("没有可用的 Steam 下载服务器");
        }
    }

    private Server server(int attempt) {
        synchronized (servers) {
            return servers.get((next.get() + attempt) % servers.size());
        }
    }

    private void blame(Server s) {
        // Move on to the next server for everyone.
        synchronized (servers) {
            if (servers.size() > 1 && servers.get(next.get() % servers.size()) == s) next.incrementAndGet();
        }
    }

    DepotManifest manifest(long gid) throws Exception {
        prepare();
        Exception last = null;
        for (int attempt = 0; attempt < 6; attempt++) {
            Server s = server(attempt);
            try {
                Deferred<Long> code = content.getManifestRequestCode(depotId, appId, gid, "public", null, GlobalScope.INSTANCE);
                long rc = get(FutureKt.asCompletableFuture(code));
                DepotManifest m = get(cdn.downloadManifestFuture(depotId, gid, rc, s, depotKey));
                if (m.getFilenamesEncrypted() && !m.decryptFilenames(depotKey)) throw new IOException("文件名解密失败");
                return m;
            } catch (Exception e) {
                last = e;
                blame(s);
            }
        }
        throw new IOException("下载版本清单失败：" + rootMessage(last), last);
    }

    // One chunk, decrypted and unpacked: the first getUncompressedLength() bytes of the result.
    // JavaSteam downloads into the same buffer, so it must also hold the compressed form, which is
    // the longer one for data that does not compress (Brotato's pck is mostly PNG and Ogg): a buffer
    // of just the unpacked size fails with "The destination buffer must be longer than the chunk
    // CompressedLength".
    byte[] chunk(ChunkData c) throws Exception {
        prepare();
        byte[] buf = new byte[Math.max(c.getUncompressedLength(), c.getCompressedLength())];
        Exception last = null;
        for (int attempt = 0; attempt < 8; attempt++) {
            Server s = server(attempt);
            try {
                int n = get(cdn.downloadDepotChunkFuture(depotId, c, s, buf, depotKey));
                if (n != c.getUncompressedLength()) throw new IOException("数据块长度不对");
                return buf;
            } catch (IllegalArgumentException e) {
                throw e; // our mistake, not the server's: retrying cannot help
            } catch (Exception e) {
                last = e;
                blame(s);
                if (attempt >= 3) Thread.sleep(500L * attempt);
            }
        }
        throw new IOException("下载失败：" + rootMessage(last), last);
    }

    private static <T> T get(CompletableFuture<T> f) throws Exception {
        try {
            return f.get(60, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            Throwable c = e.getCause();
            if (c instanceof Exception) throw (Exception) c;
            throw e;
        }
    }

    static String rootMessage(Throwable t) {
        if (t == null) return "未知错误";
        while (t.getCause() != null && t.getCause() != t) t = t.getCause();
        String m = t.getMessage();
        return m == null || m.isEmpty() ? t.getClass().getSimpleName() : m;
    }

    @Override
    public void close() {
        try {
            cdn.close();
        } catch (Throwable ignored) {
        }
    }
}
