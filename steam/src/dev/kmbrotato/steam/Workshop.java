package dev.kmbrotato.steam;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import in.dragonbra.javasteam.base.ClientMsgProtobuf;
import in.dragonbra.javasteam.base.IPacketMsg;
import in.dragonbra.javasteam.enums.EDepotFileFlag;
import in.dragonbra.javasteam.enums.EMsg;
import in.dragonbra.javasteam.enums.EResult;
import in.dragonbra.javasteam.protobufs.steamclient.SteammessagesClientserverUcm.CMsgClientUCMEnumerateUserSubscribedFilesWithUpdates;
import in.dragonbra.javasteam.protobufs.steamclient.SteammessagesClientserverUcm.CMsgClientUCMEnumerateUserSubscribedFilesWithUpdatesResponse;
import in.dragonbra.javasteam.protobufs.steamclient.SteammessagesPublishedfileSteamclient.CPublishedFile_GetDetails_Request;
import in.dragonbra.javasteam.protobufs.steamclient.SteammessagesPublishedfileSteamclient.CPublishedFile_GetUserFiles_Request;
import in.dragonbra.javasteam.protobufs.steamclient.SteammessagesPublishedfileSteamclient.PublishedFileDetails;
import in.dragonbra.javasteam.rpc.service.PublishedFile;
import in.dragonbra.javasteam.steam.cdn.Client;
import in.dragonbra.javasteam.steam.cdn.Server;
import in.dragonbra.javasteam.steam.handlers.ClientMsgHandler;
import in.dragonbra.javasteam.steam.handlers.steamapps.SteamApps;
import in.dragonbra.javasteam.steam.handlers.steamapps.callback.DepotKeyCallback;
import in.dragonbra.javasteam.steam.handlers.steamcontent.SteamContent;
import in.dragonbra.javasteam.steam.handlers.steamunifiedmessages.SteamUnifiedMessages;
import in.dragonbra.javasteam.types.ChunkData;
import in.dragonbra.javasteam.types.DepotManifest;
import in.dragonbra.javasteam.types.FileData;
import in.dragonbra.javasteam.types.JobID;
import kotlinx.coroutines.GlobalScope;
import kotlinx.coroutines.future.FutureKt;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

// Steam Workshop for the Android build. Keeps <files>/workshop/<id>/ -- user://workshop, where the
// game's mod loader looks (overlay steam.tres) -- in step with the account's subscriptions, the way
// the desktop client keeps steamapps/workshop/content/1942280/<id>/. Runs in the launcher sync,
// before the game starts, on the session CloudSync opened.
//
// Subscriptions come from the message the desktop client itself uses
// (ClientUCMEnumerateUserSubscribedFilesWithUpdates), with PublishedFile.GetUserFiles as a fallback.
// Content is SteamPipe UGC: the item's hcontent_file is a manifest of the app's own depot (DepotDownloader's
// pubfile path), downloaded and decrypted chunk by chunk from the content servers.
// An item is swapped in only once fully downloaded and verified, so a failed update keeps the old
// version, and nothing is removed unless Steam actually answered with the subscription list.
// The game asks SteamPlugin.getSubscribedItemsString() for the ids, the prefs below.
final class Workshop {
    static final int APP = Ownership.BASE;
    static final String PREF_IDS = "workshop_ids";
    private static final String TAG = SteamPlugin.TAG;

    // Must be called before the session connects.
    static void prepare(SteamSession s) {
        if (s.client.getHandler(Ucm.class) == null) s.client.addHandler(new Ucm());
    }

    static File root(Context c) {
        return new File(c.getFilesDir(), "workshop");
    }

    private static File stateFile(Context c) {
        return new File(c.getFilesDir(), "workshop-state.json");
    }

    // Changes found while the game runs (staged): new item folders in files/workshop-next/ and the
    // resulting state in files/workshop-next.json, switched in by applyStaged() at the next launch.
    // The running game must not see its mod zips replaced: Godot reopens a zip by path for every
    // file it reads from it.
    private static File nextRoot(Context c) {
        return new File(c.getFilesDir(), "workshop-next");
    }

    private static File nextState(Context c) {
        return new File(c.getFilesDir(), "workshop-next.json");
    }

    static final String STAGED_READY = "下次启动时生效";

    static String sync(Context c, SteamSession s, GameFiles.Progress log) {
        return sync(c, s, log, false);
    }

    // Never throws: a Workshop failure must not stop the save sync or the game.
    static String sync(Context c, SteamSession s, GameFiles.Progress log, boolean staged) {
        try {
            return run(c, s, log, staged);
        } catch (Throwable t) {
            Log.w(TAG, "workshop sync failed, keeping the mods from last time", t);
            return "创意工坊同步失败，沿用上次的模组";
        }
    }

    private static final class Item {
        final long id;
        long manifest;
        long updated;
        String title = "";
        String fileUrl = "";
        String fileName = "";

        Item(long id) {
            this.id = id;
        }

        String label() {
            return title.isEmpty() ? Long.toString(id) : title;
        }
    }

    private static String run(Context c, SteamSession s, GameFiles.Progress log, boolean staged) throws Exception {
        log.line("正在读取创意工坊订阅…");
        Map<Long, Item> subs = subscriptions(s);
        if (!subs.isEmpty()) details(s, subs);

        JSONObject state = stateFile(c).isFile() ? new JSONObject(new String(Files.readAllBytes(stateFile(c).toPath()), "UTF-8")) : new JSONObject();
        if (state.optLong("steamId") != s.steamId64) state = new JSONObject();
        JSONObject known = state.optJSONObject("items");
        if (known == null) known = new JSONObject();

        File root = root(c);
        root.mkdirs();
        File staging = new File(c.getFilesDir(), "workshop-staging");
        deleteTree(staging);
        File dest = staged ? nextRoot(c) : root;
        if (staged) {
            deleteTree(dest);
            nextState(c).delete();
            dest.mkdirs();
        }

        Downloader dl = null;
        JSONObject next = new JSONObject();
        List<String> ready = new ArrayList<>();
        int fetched = 0, failed = 0, n = 0;
        try {
            for (Item it : subs.values()) {
                n++;
                File dir = new File(root, Long.toString(it.id));
                JSONObject k = known.optJSONObject(Long.toString(it.id));
                boolean have = dir.isDirectory() && k != null;
                boolean current = have && k.optString("manifest").equals(Long.toString(it.manifest)) && k.optLong("updated") == it.updated;
                if (current) {
                    next.put(Long.toString(it.id), k);
                    ready.add(Long.toString(it.id));
                    continue;
                }
                log.line("下载模组 " + n + "/" + subs.size() + "：" + it.label());
                File tmp = new File(staging, Long.toString(it.id));
                try {
                    if (it.manifest != 0) {
                        if (dl == null) dl = new Downloader(s);
                        dl.fetch(it.manifest, tmp);
                    } else if (!it.fileUrl.isEmpty()) {
                        httpGet(it.fileUrl, new File(tmp, safeName(new File(it.fileName.replace('\\', '/')).getName(), it.id + ".zip")));
                    } else {
                        throw new Exception("没有可下载的内容");
                    }
                    File into = new File(dest, Long.toString(it.id));
                    deleteTree(into);
                    if (!tmp.renameTo(into)) throw new Exception("无法放入 " + into);
                    next.put(Long.toString(it.id), new JSONObject().put("manifest", Long.toString(it.manifest)).put("updated", it.updated).put("title", it.title));
                    ready.add(Long.toString(it.id));
                    fetched++;
                    Log.i(TAG, "workshop: got " + it.id + " \"" + it.title + "\" manifest " + Long.toUnsignedString(it.manifest));
                } catch (Throwable t) {
                    failed++;
                    deleteTree(tmp);
                    Log.w(TAG, "workshop: " + it.id + " \"" + it.title + "\" failed", t);
                    if (have) {
                        next.put(Long.toString(it.id), k);
                        ready.add(Long.toString(it.id));
                    }
                }
            }
        } finally {
            if (dl != null) dl.close();
            deleteTree(staging);
        }

        // Unsubscribed (or left over from another account): remove. An empty list is not trusted
        // for this (a query can answer empty by mistake, as UCM did): nothing is loaded this time,
        // but the files stay, so a real subscription comes back next launch without a download.
        int removed = 0;
        JSONArray remove = new JSONArray();
        File[] dirs = subs.isEmpty() ? null : root.listFiles();
        if (dirs != null) for (File d : dirs) {
            if (ready.contains(d.getName())) continue;
            if (staged) remove.put(d.getName());
            else deleteTree(d);
            removed++;
        }

        state.put("steamId", s.steamId64);
        state.put("items", next);
        state.put("lastSync", System.currentTimeMillis());
        String ids = String.join(",", ready);
        Log.i(TAG, "workshop: subscribed " + subs.keySet() + ", ready " + ready + ", fetched " + fetched + ", failed " + failed + ", removed " + removed + (staged ? " (staged)" : ""));
        if (staged) {
            boolean changed = fetched > 0 || removed > 0 || !ids.equals(TokenStore.prefs(c).getString(PREF_IDS, ""));
            if (!changed) {
                deleteTree(dest);
                return "模组 " + ready.size() + " 个";
            }
            JSONObject j = new JSONObject().put("state", state).put("ids", ids).put("remove", remove);
            Files.write(nextState(c).toPath(), j.toString(1).getBytes("UTF-8"));
            return "模组有更新，" + STAGED_READY;
        }
        Files.write(stateFile(c).toPath(), state.toString(1).getBytes("UTF-8"));
        TokenStore.prefs(c).edit().putString(PREF_IDS, ids).apply();

        StringBuilder sb = new StringBuilder("模组 ").append(ready.size()).append(" 个");
        if (fetched > 0) sb.append("（新下载 ").append(fetched).append("）");
        if (failed > 0) sb.append("，").append(failed).append(" 个下载失败");
        return sb.toString();
    }

    // At launch, before Godot starts: switch in what a staged sync found last time.
    static void applyStaged(Context c) {
        File js = nextState(c), from = nextRoot(c);
        try {
            if (!js.isFile()) return;
            JSONObject j = new JSONObject(new String(Files.readAllBytes(js.toPath()), "UTF-8"));
            File root = root(c);
            root.mkdirs();
            File[] dirs = from.listFiles();
            if (dirs != null) for (File d : dirs) {
                if (!d.getName().matches("\\d+")) continue;
                File to = new File(root, d.getName());
                deleteTree(to);
                if (!d.renameTo(to)) Log.w(TAG, "workshop: could not switch in " + d.getName());
            }
            JSONArray rm = j.optJSONArray("remove");
            if (rm != null) for (int i = 0; i < rm.length(); i++) {
                String id = rm.optString(i);
                if (id.matches("\\d+")) deleteTree(new File(root, id));
            }
            JSONObject state = j.optJSONObject("state");
            if (state != null) Files.write(stateFile(c).toPath(), state.toString(1).getBytes("UTF-8"));
            TokenStore.prefs(c).edit().putString(PREF_IDS, j.optString("ids", "")).apply();
            Log.i(TAG, "workshop: switched in the changes found last time: " + j.optString("ids"));
        } catch (Exception e) {
            Log.w(TAG, "workshop: staged changes not applied", e);
        } finally {
            js.delete();
            deleteTree(from);
        }
    }

    // Titles of the items the game will load, for the launcher.
    static List<String> titles(Context c) {
        List<String> out = new ArrayList<>();
        try {
            String ids = TokenStore.prefs(c).getString(PREF_IDS, "");
            if (ids.isEmpty()) return out;
            JSONObject items = stateFile(c).isFile()
                    ? new JSONObject(new String(Files.readAllBytes(stateFile(c).toPath()), "UTF-8")).optJSONObject("items") : null;
            for (String id : ids.split(",")) {
                JSONObject k = items == null ? null : items.optJSONObject(id);
                String t = k == null ? "" : k.optString("title");
                out.add(t.isEmpty() ? id : t);
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    // ---- subscriptions ----

    // The PC client's own list query is PublishedFile.GetUserFiles with type "mysubscriptions"
    // (steamclient64.dll maps EUserUGCList to myfiles/myfavorites/mysubscriptions/...). The UCM
    // enumeration answered OK but empty for an account with two subscriptions (Thor, 11-06), so it
    // is only a fallback. Either one failing or coming back empty falls through to the other.
    private static Map<Long, Item> subscriptions(SteamSession s) throws Exception {
        Throwable first = null;
        try {
            Map<Long, Item> m = userFilesSubscriptions(s);
            if (!m.isEmpty()) return m;
        } catch (Throwable t) {
            first = t;
            Log.w(TAG, "workshop: GetUserFiles subscription list failed (" + t + "), trying UCM");
        }
        try {
            return ucmSubscriptions(s);
        } catch (Throwable t) {
            Log.w(TAG, "workshop: UCM subscription list failed (" + t + ")");
            if (first != null) throw new Exception("订阅列表读取失败", first);
            return new LinkedHashMap<>();
        }
    }

    private static Map<Long, Item> ucmSubscriptions(SteamSession s) throws Exception {
        Ucm ucm = s.client.getHandler(Ucm.class);
        if (ucm == null) throw new Exception("UCM handler missing");
        Map<Long, Item> out = new LinkedHashMap<>();
        int start = 0;
        while (true) {
            ClientMsgProtobuf<CMsgClientUCMEnumerateUserSubscribedFilesWithUpdates.Builder> req = new ClientMsgProtobuf<>(
                    CMsgClientUCMEnumerateUserSubscribedFilesWithUpdates.class, EMsg.ClientUCMEnumerateUserSubscribedFilesWithUpdates);
            req.getProtoHeader().setRoutingAppid(APP);
            req.getBody().setAppId(APP).setStartIndex(start).setStartTime(0).setDesiredRevision(0);
            IPacketMsg p = ucm.call(req).get(30, TimeUnit.SECONDS);
            CMsgClientUCMEnumerateUserSubscribedFilesWithUpdatesResponse.Builder r = new ClientMsgProtobuf<CMsgClientUCMEnumerateUserSubscribedFilesWithUpdatesResponse.Builder>(
                    CMsgClientUCMEnumerateUserSubscribedFilesWithUpdatesResponse.class, p).getBody();
            if (r.getEresult() != EResult.OK.code()) throw new Exception("UCM result " + EResult.from(r.getEresult()));
            List<CMsgClientUCMEnumerateUserSubscribedFilesWithUpdatesResponse.PublishedFileId> page = r.getSubscribedFilesList();
            for (CMsgClientUCMEnumerateUserSubscribedFilesWithUpdatesResponse.PublishedFileId f : page) {
                if (f.getAppid() != 0 && f.getAppid() != APP) continue;
                Item it = new Item(f.getPublishedFileId());
                it.manifest = f.getFileHcontent();
                it.updated = f.getRtime32LastUpdated() & 0xFFFFFFFFL;
                out.put(it.id, it);
            }
            start += page.size();
            Log.i(TAG, "workshop: UCM page of " + page.size() + ", total " + r.getTotalResults());
            if (page.isEmpty() || start >= r.getTotalResults()) break;
        }
        return out;
    }

    private static Map<Long, Item> userFilesSubscriptions(SteamSession s) throws Exception {
        PublishedFile pf = s.client.getHandler(SteamUnifiedMessages.class).createService(PublishedFile.class);
        Map<Long, Item> out = new LinkedHashMap<>();
        for (int page = 1; page <= 20; page++) {
            CPublishedFile_GetUserFiles_Request req = CPublishedFile_GetUserFiles_Request.newBuilder()
                    .setSteamid(s.steamId64).setAppid(APP).setType("mysubscriptions").setPage(page).setNumperpage(100)
                    .setReturnVoteData(false).setReturnKvTags(false).setReturnShortDescription(false).build();
            var body = pf.getUserFiles(req).toFuture().get(30, TimeUnit.SECONDS).getBody();
            for (PublishedFileDetails d : body.getPublishedfiledetailsList()) {
                Item it = new Item(d.getPublishedfileid());
                apply(it, d);
                out.put(it.id, it);
            }
            Log.i(TAG, "workshop: GetUserFiles page " + page + " gave " + body.getPublishedfiledetailsCount() + ", total " + body.getTotal());
            if (body.getPublishedfiledetailsCount() == 0 || out.size() >= body.getTotal()) break;
        }
        return out;
    }

    // Titles, and the latest manifest (the subscription list can lag behind an update).
    private static void details(SteamSession s, Map<Long, Item> subs) {
        try {
            PublishedFile pf = s.client.getHandler(SteamUnifiedMessages.class).createService(PublishedFile.class);
            CPublishedFile_GetDetails_Request req = CPublishedFile_GetDetails_Request.newBuilder()
                    .addAllPublishedfileids(subs.keySet()).setAppid(APP).build();
            var body = pf.getDetails(req).toFuture().get(30, TimeUnit.SECONDS).getBody();
            for (PublishedFileDetails d : body.getPublishedfiledetailsList()) {
                Item it = subs.get(d.getPublishedfileid());
                if (it == null) continue;
                if (d.getResult() != EResult.OK.code()) {
                    Log.w(TAG, "workshop: details of " + it.id + ": " + EResult.from(d.getResult()));
                    continue;
                }
                apply(it, d);
            }
        } catch (Throwable t) {
            Log.w(TAG, "workshop: GetDetails failed, using the subscription list as is", t);
        }
    }

    private static void apply(Item it, PublishedFileDetails d) {
        if (!d.getTitle().isEmpty()) it.title = d.getTitle();
        if (d.getHcontentFile() != 0) it.manifest = d.getHcontentFile();
        if (d.getTimeUpdated() != 0) it.updated = d.getTimeUpdated() & 0xFFFFFFFFL;
        it.fileUrl = d.getFileUrl();
        it.fileName = d.getFilename();
    }

    // Routes UCM replies back to their request by job id.
    static final class Ucm extends ClientMsgHandler {
        private final ConcurrentHashMap<Long, CompletableFuture<IPacketMsg>> waiting = new ConcurrentHashMap<>();

        CompletableFuture<IPacketMsg> call(ClientMsgProtobuf<?> msg) {
            JobID job = getClient().getNextJobID();
            msg.setSourceJobID(job);
            CompletableFuture<IPacketMsg> f = new CompletableFuture<>();
            waiting.put(job.getValue(), f);
            getClient().send(msg);
            return f;
        }

        @Override
        public void handleMsg(IPacketMsg p) {
            if (p.getMsgType() != EMsg.ClientUCMEnumerateUserSubscribedFilesWithUpdatesResponse) return;
            CompletableFuture<IPacketMsg> f = waiting.remove(p.getTargetJobID());
            if (f != null) f.complete(p);
        }
    }

    // ---- content ----

    // One CDN client, depot key and server list for the whole sync.
    private static final class Downloader {
        private final SteamSession s;
        private final Client cdn;
        private byte[] key;
        private List<Server> servers;

        Downloader(SteamSession s) {
            this.s = s;
            cdn = new Client(s.client);
        }

        private void setUp() throws Exception {
            if (key != null) return;
            DepotKeyCallback k = s.client.getHandler(SteamApps.class).getDepotDecryptionKey(APP, APP).toFuture().get(20, TimeUnit.SECONDS);
            if (k.getResult() != EResult.OK) throw new Exception("拿不到创意工坊内容的密钥：" + k.getResult());
            SteamContent content = s.client.getHandler(SteamContent.class);
            List<Server> all = FutureKt.asCompletableFuture(content.getServersForSteamPipe(null, null, GlobalScope.INSTANCE)).get(30, TimeUnit.SECONDS);
            List<Server> ok = new ArrayList<>();
            for (Server sv : all) {
                int[] allowed = sv.getAllowedAppIds();
                boolean eligible = allowed == null || allowed.length == 0 || Arrays.stream(allowed).anyMatch(a -> a == APP);
                if (eligible && ("SteamCache".equals(sv.getType()) || "CDN".equals(sv.getType()))) ok.add(sv);
            }
            ok.sort(Comparator.comparingDouble(Server::getWeightedLoad));
            if (ok.isEmpty()) throw new Exception("没有可用的 Steam 内容服务器");
            Log.i(TAG, "workshop: " + ok.size() + " content servers, first " + ok.get(0).getHost());
            servers = ok;
            key = k.getDepotKey();
        }

        void fetch(long manifestId, File into) throws Exception {
            setUp();
            long code = 0;
            try {
                Long c = FutureKt.asCompletableFuture(s.client.getHandler(SteamContent.class)
                        .getManifestRequestCode(APP, APP, manifestId, "public", null, GlobalScope.INSTANCE)).get(20, TimeUnit.SECONDS);
                if (c != null) code = c;
            } catch (Throwable t) {
                Log.w(TAG, "workshop: no manifest request code for " + Long.toUnsignedString(manifestId) + " (" + t + "), trying without");
            }
            Throwable last = null;
            for (int i = 0; i < Math.min(3, servers.size()); i++) {
                Server sv = servers.get(i);
                try {
                    deleteTree(into);
                    into.mkdirs();
                    DepotManifest m = cdn.downloadManifestFuture(APP, manifestId, code, sv, key).get(60, TimeUnit.SECONDS);
                    if (m.getFilenamesEncrypted() && !m.decryptFilenames(key)) throw new Exception("清单文件名解密失败");
                    for (FileData f : m.getFiles()) write(f, into, sv);
                    return;
                } catch (Throwable t) {
                    last = t;
                    Log.w(TAG, "workshop: " + sv.getHost() + " failed for manifest " + Long.toUnsignedString(manifestId), t);
                }
            }
            throw new Exception("内容服务器下载失败", last);
        }

        private void write(FileData f, File into, Server sv) throws Exception {
            String name = f.getFileName().replace('\\', '/');
            File out = resolve(into, name);
            if (f.getFlags().contains(EDepotFileFlag.Directory)) {
                out.mkdirs();
                return;
            }
            if (f.getFlags().contains(EDepotFileFlag.Symlink)) return;
            out.getParentFile().mkdirs();
            try (RandomAccessFile raf = new RandomAccessFile(out, "rw")) {
                raf.setLength(f.getTotalSize());
                for (ChunkData ch : f.getChunks()) {
                    // Room for the compressed form too, which is longer for incompressible data
                    // (see SteamDepot.chunk).
                    byte[] buf = new byte[Math.max(ch.getUncompressedLength(), ch.getCompressedLength())];
                    int n = cdn.downloadDepotChunkFuture(APP, ch, sv, buf, key).get(60, TimeUnit.SECONDS);
                    raf.seek(ch.getOffset());
                    raf.write(buf, 0, n);
                }
            }
            byte[] want = f.getFileHash();
            if (want != null && want.length == 20 && !isZero(want)) {
                byte[] got = MessageDigest.getInstance("SHA-1").digest(Files.readAllBytes(out.toPath()));
                if (!Arrays.equals(want, got)) throw new Exception(name + " 校验失败");
            }
        }

        void close() {
            try {
                cdn.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private static void httpGet(String url, File to) throws Exception {
        OkHttpClient http = new OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build();
        to.getParentFile().mkdirs();
        try (Response r = http.newCall(new Request.Builder().url(url).build()).execute()) {
            if (!r.isSuccessful()) throw new Exception("HTTP " + r.code());
            try (FileOutputStream o = new FileOutputStream(to)) {
                o.write(r.body().bytes());
            }
        }
    }

    // Workshop content is someone else's upload: keep every path inside the item's folder.
    private static File resolve(File base, String rel) throws Exception {
        if (rel.isEmpty() || rel.startsWith("/") || rel.contains(":")) throw new Exception("不安全的路径：" + rel);
        for (String part : rel.split("/")) if (part.equals("..")) throw new Exception("不安全的路径：" + rel);
        File f = new File(base, rel);
        if (!f.getCanonicalPath().startsWith(base.getCanonicalPath() + File.separator)) throw new Exception("不安全的路径：" + rel);
        return f;
    }

    private static String safeName(String name, String fallback) {
        return name.isEmpty() || name.equals("..") || name.equals(".") ? fallback : name;
    }

    private static boolean isZero(byte[] b) {
        for (byte x : b) if (x != 0) return false;
        return true;
    }

    static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.isDirectory() ? f.listFiles() : null;
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }

    private Workshop() { }
}
