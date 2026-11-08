package dev.kmbrotato.steam;

import android.content.Context;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Pattern;

// Keeps <files>/<SteamID64>/ -- the game's user://<SteamID64>/ -- in step with the PC's Steam Cloud
// folder %APPDATA%\Brotato\<SteamID64>\ (Auto-Cloud root 4, %WinAppDataRoaming%).
//
// Same rules as the Legend of Mortal port: for every file the state remembers the local SHA-1 and
// the cloud timestamp as of the last sync. One side changed: that side wins. Both changed: the newer
// one wins and the loser is backed up. The very first sync trusts the cloud, so saves made here
// before signing in never overwrite the PC's; whatever it replaces is backed up first.
// Backups go to <files>/cloud-backup/<SteamID64>/<time>/ (outside user://<id>, which the game lists).
final class CloudSync {
    static final int APP = Ownership.BASE;
    // settings.json (language, options, current profile), save*.json (progress, all formats and
    // profiles), run*.json (the run in progress). Not log.txt or the mod loader's files.
    static final Pattern SYNCED = Pattern.compile("^(settings|save[A-Za-z0-9_.-]*|run[A-Za-z0-9_.-]*)\\.json$");

    // Progress for the launcher, by phase (STEP_SIGN_IN ... STEP_WORKSHOP). After sign-in the
    // cloud phase runs alongside the others (ownership, game files, Workshop), so calls for different
    // phases can come from two threads.
    interface Log {
        void line(int phase, String s);

        default void begin(int phase) { }

        default void done(int phase) { }

        default void progress(int phase, long done, long total) { }

        // For code that reports a single phase (GameFiles, Workshop).
        default GameFiles.Progress forPhase(int phase) {
            Log self = this;
            return new GameFiles.Progress() {
                @Override
                public void line(String s) {
                    self.line(phase, s);
                }

                @Override
                public void progress(long done, long total) {
                    self.progress(phase, done, total);
                }
            };
        }

        Log NONE = (phase, s) -> { };
    }

    static final int STEP_SIGN_IN = 0, STEP_CLOUD = 1, STEP_OWNERSHIP = 2, STEP_GAME = 3, STEP_WORKSHOP = 4;

    static final class Result {
        int down, up, deleted, backedUp, same;
        String workshop;
        String game;           // summary of the game files step
        boolean gameFailed;    // an update did not finish; the current version is played
        volatile boolean cloudOk;

        @Override
        public String toString() {
            return "下载 " + down + "，上传 " + up + "，删除 " + deleted + "，一致 " + same + (backedUp > 0 ? "，已备份 " + backedUp : "")
                    + (workshop != null ? "\n" + workshop : "");
        }
    }

    // null until the account's SteamID64 is known.
    static File saveDir(Context c) {
        String id = TokenStore.steamId(c);
        return id.isEmpty() ? null : new File(c.getFilesDir(), id);
    }

    private static File stateFile(Context c) {
        return new File(c.getFilesDir(), "cloud-state.json");
    }

    static boolean initialized(Context c) {
        return stateFile(c).isFile();
    }

    // localWrites=false is the in-game mode: only uploads and cloud-side deletes, never touching the
    // files the running game has open.
    static Result sync(Context c, Log log, boolean localWrites) throws Exception {
        return sync(c, log, localWrites, false);
    }

    // The cloud phase of one sync at a time (launcher and in-game uploads); sign-in and the other
    // phases run outside it.
    private static final Object LOCK = new Object();

    // staged: a routine launch, where the game starts at once (see Gate). The cloud phase opens the
    // gate when it ends; the other phases follow, and game files and Workshop items are only
    // downloaded, to be switched in at the next launch (GameFiles/Workshop.applyStaged).
    static Result sync(Context c, Log log, boolean localWrites, boolean staged) throws Exception {
        TokenStore t = TokenStore.load(c);
        if (t == null) throw new Exception("尚未登录 Steam");
        Result res = new Result();
        if (staged) lastCloudOk = false;
        long t0 = android.os.SystemClock.elapsedRealtime();
        try (SteamSession s = new SteamSession()) {
            if (localWrites) {
                Ownership.prepare(s);
                Workshop.prepare(s);
            }
            log.begin(STEP_SIGN_IN);
            log.line(STEP_SIGN_IN, "正在登录 Steam…");
            // In-game uploads sign in with their own login id: the launcher's session can still be
            // working in the background (staged), and the same id would log it off.
            s.logOn(t.account, t.refreshToken, localWrites ? TokenStore.loginId(c) : TokenStore.loginId(c) ^ 0x30000);
            TokenStore.saveSteamId(c, s.steamId64);
            TokenStore.setRelogin(c, false);
            log.done(STEP_SIGN_IN);
            android.util.Log.i(SteamPlugin.TAG, "timing: signed in after " + (android.os.SystemClock.elapsedRealtime() - t0) + " ms");
            // Ownership, the game files and the Workshop items do not touch the saves: they run
            // alongside the cloud phase, on the same connection.
            java.util.concurrent.FutureTask<Void> rest = null;
            if (localWrites && !staged) {
                rest = new java.util.concurrent.FutureTask<>(() -> {
                    others(c, s, log, res, t0, false);
                    return null;
                });
                Thread th = new Thread(rest, "BroSteam-others");
                th.setDaemon(true);
                th.start();
            }
            Exception cloudError = null;
            try {
                synchronized (LOCK) {
                    JSONObject state = stateFile(c).isFile() ? new JSONObject(new String(Files.readAllBytes(stateFile(c).toPath()), "UTF-8")) : new JSONObject();
                    JSONObject known = state.optJSONObject("files");
                    if (known == null) known = new JSONObject();
                    cloud(c, s, state, known, log, localWrites, res);
                }
                res.cloudOk = true;
                lastCloudOk = true;
                android.util.Log.i(SteamPlugin.TAG, "timing: cloud done after " + (android.os.SystemClock.elapsedRealtime() - t0) + " ms");
            } catch (Exception e) {
                cloudError = e;
            } finally {
                if (staged) Gate.open();
            }
            if (rest != null) {
                try {
                    rest.get();
                } catch (java.util.concurrent.ExecutionException e) {
                    if (cloudError == null) {
                        Throwable x = e.getCause();
                        throw x instanceof Exception ? (Exception) x : new Exception(x);
                    }
                }
            }
            if (cloudError != null) throw cloudError;
            if (staged) others(c, s, log, res, t0, true);
        }
        android.util.Log.i(SteamPlugin.TAG, "sync done after " + (android.os.SystemClock.elapsedRealtime() - t0) + " ms: " + res);
        return res;
    }

    private static void others(Context c, SteamSession s, Log log, Result res, long t0, boolean staged) throws Exception {
        log.begin(STEP_OWNERSHIP);
        log.line(STEP_OWNERSHIP, "正在确认游戏和 DLC 的所有权…");
        Ownership.refresh(c, s);
        log.done(STEP_OWNERSHIP);
        android.util.Log.i(SteamPlugin.TAG, "timing: ownership done after " + (android.os.SystemClock.elapsedRealtime() - t0) + " ms");
        log.begin(STEP_GAME);
        try {
            res.game = GameFiles.sync(c, s, log.forPhase(STEP_GAME), staged);
        } catch (GameFiles.Missing e) {
            throw e;
        } catch (Exception e) {
            android.util.Log.w(SteamPlugin.TAG, "game update failed, playing the current version", e);
            res.game = "更新没完成，先玩当前版本：" + SteamDepot.rootMessage(e);
            res.gameFailed = true;
        }
        log.done(STEP_GAME);
        android.util.Log.i(SteamPlugin.TAG, "timing: game files done after " + (android.os.SystemClock.elapsedRealtime() - t0) + " ms");
        log.begin(STEP_WORKSHOP);
        res.workshop = Workshop.sync(c, s, log.forPhase(STEP_WORKSHOP), staged);
        log.done(STEP_WORKSHOP);
        android.util.Log.i(SteamPlugin.TAG, "timing: workshop done after " + (android.os.SystemClock.elapsedRealtime() - t0) + " ms");
    }

    private static void cloud(Context c, SteamSession s, JSONObject state, JSONObject known, Log log, boolean localWrites, Result res) throws Exception {
        {
            File dir = new File(c.getFilesDir(), Long.toString(s.steamId64));
            dir.mkdirs();
            CloudClient cc = new CloudClient(s);
            log.begin(STEP_CLOUD);
            log.line(STEP_CLOUD, "正在读取云端文件列表…");
            String mine = "Brotato/" + s.steamId64 + "/";
            Map<String, CloudClient.CloudFile> cloud = listMine(cc, mine);
            String prefix = null;
            for (CloudClient.CloudFile f : cloud.values()) { prefix = f.name.substring(0, f.name.lastIndexOf('/') + 1); break; }
            if (prefix == null) prefix = state.optString("prefix", "%WinAppDataRoaming%Brotato/" + s.steamId64 + "/");
            boolean first = !state.optBoolean("initialized") || state.optLong("steamId") != s.steamId64;
            if (first && !localWrites) throw new Exception("首次同步要在启动时进行");

            TreeSet<String> names = new TreeSet<>(cloud.keySet());
            File[] locals = dir.listFiles();
            if (locals != null) for (File f : locals) if (f.isFile() && SYNCED.matcher(f.getName()).matches()) names.add(f.getName());

            File backup = new File(c.getFilesDir(), "cloud-backup/" + s.steamId64 + "/" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date()));
            JSONObject next = new JSONObject();
            List<String> uploaded = new ArrayList<>();
            for (String name : names) {
                CloudClient.CloudFile cf = cloud.get(name);
                File lf = new File(dir, name);
                String lsha = lf.isFile() ? sha1(lf) : null;
                String lastL = null;
                long lastC = -1;
                if (!first) {
                    Object k = known.opt(name);
                    if (k instanceof JSONObject) { lastL = ((JSONObject) k).optString("l", null); lastC = ((JSONObject) k).optLong("c", -1); }
                }
                boolean wasSynced = lastL != null;
                boolean localChanged = lsha != null && !lsha.equals(lastL);
                boolean cloudChanged = cf != null && cf.timestamp != lastC;
                String keepL = lastL;
                long keepC = lastC;

                if (cf == null) {
                    if (wasSynced && !localChanged) {
                        // Unchanged here and gone from the cloud: removed on another machine.
                        if (localWrites) { copy(lf, backup, res); lf.delete(); res.deleted++; keepL = null; }
                    } else if (!first) {
                        upload(cc, prefix + name, lf, log, res);
                        uploaded.add(name);
                        keepL = lsha;
                    }
                } else if (lsha == null) {
                    if (wasSynced && !cloudChanged) {
                        cc.delete(APP, cf.name);
                        res.deleted++;
                        log.line(STEP_CLOUD, "已从云端删除 " + name);
                        keepL = null;
                    } else if (localWrites) {
                        keepL = download(cc, cf, lf, log, res);
                        keepC = cf.timestamp;
                    }
                } else if (first) {
                    copy(lf, backup, res);
                    keepL = download(cc, cf, lf, log, res);
                    keepC = cf.timestamp;
                } else if (!localChanged && !cloudChanged) {
                    res.same++;
                } else if (!localChanged) {
                    if (localWrites) { keepL = download(cc, cf, lf, log, res); keepC = cf.timestamp; }
                } else if (!cloudChanged) {
                    upload(cc, cf.name, lf, log, res);
                    uploaded.add(name);
                    keepL = lsha;
                } else {
                    // Both changed. Identical content (an upload whose record was never written,
                    // e.g. the app was killed mid-upload) is not a conflict.
                    byte[] remote = cc.download(APP, cf.name);
                    if (lsha.equals(sha1(remote))) {
                        res.same++;
                        keepL = lsha;
                        keepC = cf.timestamp;
                    } else {
                        boolean takeCloud = cf.timestamp > lf.lastModified() / 1000;
                        if (takeCloud && localWrites) {
                            copy(lf, backup, res);
                            keepL = write(remote, cf, lf, log, res);
                            keepC = cf.timestamp;
                        } else if (!takeCloud) {
                            backup.mkdirs();
                            Files.write(new File(backup, name + ".cloud").toPath(), remote);
                            res.backedUp++;
                            upload(cc, cf.name, lf, log, res);
                            uploaded.add(name);
                            keepL = lsha;
                        }
                    }
                }
                if (keepL != null) next.put(name, new JSONObject().put("l", keepL).put("c", keepC));
            }

            // Record the timestamps Steam actually stored for what was just uploaded.
            if (!uploaded.isEmpty()) {
                Map<String, CloudClient.CloudFile> after = listMine(cc, mine);
                for (String name : uploaded) {
                    CloudClient.CloudFile f = after.get(name);
                    JSONObject e = next.optJSONObject(name);
                    if (f != null && e != null) e.put("c", f.timestamp);
                }
            }
            state.put("initialized", true);
            state.put("steamId", s.steamId64);
            state.put("prefix", prefix);
            state.put("files", next);
            state.put("lastSync", System.currentTimeMillis());
            Files.write(stateFile(c).toPath(), state.toString(1).getBytes("UTF-8"));
            log.done(STEP_CLOUD);
        }
    }

    private static Map<String, CloudClient.CloudFile> listMine(CloudClient cc, String mine) throws Exception {
        Map<String, CloudClient.CloudFile> m = new HashMap<>();
        for (CloudClient.CloudFile f : cc.list(APP)) {
            if (!f.name.contains(mine)) continue;
            String base = f.name.substring(f.name.lastIndexOf('/') + 1);
            if (SYNCED.matcher(base).matches()) m.put(base, f);
        }
        return m;
    }

    private static String download(CloudClient cc, CloudClient.CloudFile cf, File to, Log log, Result res) throws Exception {
        return write(cc.download(APP, cf.name), cf, to, log, res);
    }

    private static String write(byte[] data, CloudClient.CloudFile cf, File to, Log log, Result res) throws Exception {
        if (!Gate.localWritesAllowed()) throw new IOException("游戏已经读过存档，这次不再改本机存档");
        log.line(STEP_CLOUD, "下载 " + to.getName());
        File tmp = new File(to.getPath() + ".part");
        try (FileOutputStream o = new FileOutputStream(tmp)) { o.write(data); }
        if (!tmp.renameTo(to)) { to.delete(); tmp.renameTo(to); }
        to.setLastModified(cf.timestamp * 1000);
        res.down++;
        return sha1(to);
    }

    private static void upload(CloudClient cc, String cloudName, File lf, Log log, Result res) throws Exception {
        log.line(STEP_CLOUD, "上传 " + lf.getName());
        cc.upload(APP, cloudName, Files.readAllBytes(lf.toPath()), lf.lastModified() / 1000);
        res.up++;
    }

    private static boolean copy(File f, File backup, Result res) throws Exception {
        if (!f.isFile()) return false;
        if (!Gate.localWritesAllowed()) throw new IOException("游戏已经读过存档，这次不再改本机存档");
        backup.mkdirs();
        Files.copy(f.toPath(), new File(backup, f.getName()).toPath());
        res.backedUp++;
        return true;
    }

    static String sha1(File f) throws Exception {
        return sha1(Files.readAllBytes(f.toPath()));
    }

    static String sha1(byte[] data) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-1").digest(data);
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format(Locale.ROOT, "%02x", b));
        return sb.toString();
    }

    // Routine launches start the game while the cloud phase runs. The game reads its saves right
    // after the first autoload's _ready (assets/android/boot.gd), which waits here for the cloud
    // phase -- so the slow part of loading overlaps the sync. If the game stops waiting (timeout), the
    // sync makes no more local changes: the game has read the files by then.
    static final class Gate {
        private static volatile java.util.concurrent.CountDownLatch latch;
        private static volatile boolean passed;

        static synchronized void arm() {
            latch = new java.util.concurrent.CountDownLatch(1);
            passed = false;
        }

        // The launcher's own (non-routine) sync: no game waiting, local writes allowed.
        static synchronized void disarm() {
            java.util.concurrent.CountDownLatch l = latch;
            latch = null;
            passed = false;
            if (l != null) l.countDown();
        }

        static void open() {
            java.util.concurrent.CountDownLatch l = latch;
            if (l != null) l.countDown();
        }

        // From the game. true when the cloud phase is over (or none was pending).
        static boolean await(long ms) {
            java.util.concurrent.CountDownLatch l = latch;
            if (l == null) return true;
            boolean done;
            try {
                done = l.await(ms, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                done = false;
            }
            passed = true;
            return done;
        }

        static boolean localWritesAllowed() {
            return !passed;
        }

        // Waits like await() but without ending local writes: for work that should not compete
        // with the sync (SteamPlay's own sign-in).
        static void quietWait(long ms) {
            java.util.concurrent.CountDownLatch l = latch;
            if (l == null) return;
            try {
                l.await(ms, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (InterruptedException ignored) {
            }
        }
    }

    // A routine launch (signed in, game files and the first sync in place): the launcher starts the
    // game at once and this runs alongside it. The game waits for the cloud phase (Gate); ownership,
    // game updates and Workshop items follow in the background and take effect at the next launch.
    // Nothing here shows UI but a toast when something needs the player to know.
    static void startRoutine(Context c) {
        Context app = c.getApplicationContext();
        Gate.arm();
        Thread th = new Thread(() -> {
            Result r = null;
            try {
                r = sync(app, Log.NONE, true, true);
                if (GameFiles.STAGED_READY.equals(r.game)) Notice.show(app, "土豆兄弟有新版本，已经下载好，下次启动时生效");
                if (r.workshop != null && r.workshop.endsWith(Workshop.STAGED_READY)) Notice.show(app, "创意工坊模组有更新，下次启动时生效");
            } catch (SteamSession.LogOnRejected e) {
                android.util.Log.w(SteamPlugin.TAG, "routine sync: sign-in rejected", e);
                TokenStore.setRelogin(app, true);
                Notice.show(app, "Steam 登录已失效，这次先离线玩，下次打开时请重新扫码登录");
            } catch (Throwable t) {
                android.util.Log.w(SteamPlugin.TAG, "routine sync failed", t);
                if (!lastCloudOk) Notice.show(app, "没连上 Steam，这次先用本机存档");
            } finally {
                Gate.open();
            }
        }, "BroSteam-routine");
        th.setDaemon(true);
        th.start();
    }

    private static volatile boolean lastCloudOk;

    static void forget(Context c) {
        stateFile(c).delete();
    }

    private CloudSync() { }
}
