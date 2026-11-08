package dev.kmbrotato.steam;

import android.content.Context;
import android.os.StatFs;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import in.dragonbra.javasteam.types.ChunkData;
import in.dragonbra.javasteam.types.DepotManifest;
import in.dragonbra.javasteam.types.FileData;

// The game itself: Brotato.pck in files/game/, which Godot runs as the main pack (assets/_cl_ has
// --main-pack; override.cfg and assets/android/boot.gd put the port's overlay over it). The APK carries
// no game content. The pck comes from the Windows depot on Steam -- only that one file of it -- or
// from the player's own copy, picked in the launcher.
//
// files/game/state.json records the Steam manifest the pck is (0 = not known yet: an imported copy,
// until a signed-in launch matches its SHA-1 against Steam's) and the SHA-1 of the file as Steam has
// it. Every signed-in launch compares that with the public branch and updates: chunks the current
// file already has (same content, by the chunk's SHA-1) are copied, only the rest is downloaded. A
// download resumes where it stopped (files/game/part.json), and the new file replaces the old one
// only once complete and verified, so a failed update keeps the version that works.
//
// On disk the pck differs from Steam's in one place: the engine version in its header is lowered to
// 3.6. Godot 3.6 refuses packs "created with a newer version of the engine", and Brotato's is a 3.7
// dev build -- whose content runs on 3.6, as the bundled builds of this port showed.
final class GameFiles {
    static final int APP = Ownership.BASE, DEPOT = 1942281;
    static final String NAME = "Brotato.pck";
    static final long TYPICAL_SIZE = 281_316_965L; // 1.2.0.1, for messages before Steam is asked
    private static final String TAG = SteamPlugin.TAG;
    private static final int THREADS = 6, ROUNDS = 4;

    static final class Missing extends IOException {
        Missing(String m, Throwable cause) {
            super(m, cause);
        }
    }

    static File dir(Context c) {
        return new File(c.getFilesDir(), "game");
    }

    static File pck(Context c) {
        return new File(dir(c), NAME);
    }

    private static File stateFile(Context c) {
        return new File(dir(c), "state.json");
    }

    private static File part(Context c) {
        return new File(dir(c), NAME + ".part");
    }

    // A finished update downloaded while the game ran (staged), switched in by the next launch.
    private static File staged(Context c) {
        return new File(dir(c), NAME + ".next");
    }

    private static File stagedState(Context c) {
        return new File(dir(c), "next.json");
    }

    private static File partState(Context c) {
        return new File(dir(c), "part.json");
    }

    // A player's copy dropped into the app's folder on shared storage (Android/data/<package>/files/)
    // is imported on the next launch: handy over USB, and how the port is tested.
    static File dropped(Context c) {
        File d = c.getExternalFilesDir(null);
        return d == null ? null : new File(d, NAME);
    }

    // ---- state ----

    static final class State {
        long manifest;   // Steam manifest gid, 0 = unknown
        String sha1;     // of the file as Steam has it (original header); null = unknown
        long size;
        String source;   // "steam" or "import"
    }

    static State state(Context c) {
        State s = new State();
        try {
            if (stateFile(c).isFile()) {
                JSONObject j = new JSONObject(new String(Files.readAllBytes(stateFile(c).toPath()), StandardCharsets.UTF_8));
                s.manifest = Long.parseUnsignedLong(j.optString("manifest", "0"));
                s.sha1 = j.optString("sha1", null);
                s.size = j.optLong("size");
                s.source = j.optString("source", null);
            }
        } catch (Exception e) {
            Log.w(TAG, "game state unreadable: " + e);
        }
        return s;
    }

    private static void saveState(Context c, State s) throws Exception {
        writeAtomic(stateFile(c), stateJson(s).getBytes(StandardCharsets.UTF_8));
    }

    private static String stateJson(State s) throws Exception {
        JSONObject j = new JSONObject().put("manifest", Long.toUnsignedString(s.manifest)).put("size", s.size)
                .put("time", System.currentTimeMillis());
        if (s.sha1 != null) j.put("sha1", s.sha1);
        if (s.source != null) j.put("source", s.source);
        return j.toString(1);
    }

    // Built with BUNDLE=1: the game's files are in the APK's assets.
    static boolean bundled(Context c) {
        try (InputStream in = c.getAssets().open("project.binary")) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    static boolean ready(Context c) {
        if (bundled(c)) return true;
        File f = pck(c);
        if (!f.isFile() || f.length() < 1024) return false;
        try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
            return headerProblem(r, true) == null;
        } catch (IOException e) {
            return false;
        }
    }

    static long freeBytes(Context c) {
        return new StatFs(c.getFilesDir().getPath()).getAvailableBytes();
    }

    // ---- launch ----

    // Before Godot starts: override.cfg next to the main pack makes assets/android/boot.gd the
    // CrashReporter autoload (the first one; it mounts the overlay), and the overlay from this APK.
    static void prepareLaunch(Context c) throws IOException {
        if (bundled(c)) return;
        File d = dir(c);
        d.mkdirs();
        writeAtomic(new File(d, "override.cfg"), "[autoload]\n\nCrashReporter=\"*res://android/boot.gd\"\n".getBytes(StandardCharsets.UTF_8));
        try (InputStream in = c.getAssets().open("android/overlay.zip")) {
            writeAtomic(new File(d, "overlay.zip"), readAll(in));
        }
    }

    // ---- Steam ----

    interface Progress {
        void line(String s);

        default void progress(long done, long total) { }
    }

    static final String STAGED_READY = "更新已下载，下次启动时生效";

    // Brings the pck up to Steam's public branch. Returns a short summary for the launcher. Throws
    // Missing when there is no playable pck afterwards; any other failure leaves the current one.
    // staged: the game is running from the current pck (Godot reopens it by path for every file),
    // so a new version is only downloaded and verified, and applyStaged() switches it in at the
    // next launch.
    static String sync(Context c, SteamSession s, Progress p, boolean staged) throws Exception {
        if (bundled(c)) return "内置在安装包里";
        boolean have = ready(c);
        if (staged && !have) throw new Missing("没有游戏文件", null);
        State st = state(c);
        try (SteamDepot depot = new SteamDepot(s, APP, DEPOT)) {
            p.line("正在查询 Steam 上的游戏版本…");
            long latest = depot.branchManifest("public");
            if (have && st.manifest == latest) {
                Log.i(TAG, "game: up to date (manifest " + Long.toUnsignedString(latest) + ")");
                staged(c).delete();
                stagedState(c).delete();
                return "已是最新";
            }
            if (staged && staged(c).isFile() && stagedState(c).isFile()) {
                try {
                    JSONObject j = new JSONObject(new String(Files.readAllBytes(stagedState(c).toPath()), StandardCharsets.UTF_8));
                    if (Long.parseUnsignedLong(j.optString("manifest", "0")) == latest) return STAGED_READY;
                } catch (Exception ignored) {
                }
            }
            p.line("正在读取版本清单…");
            DepotManifest m = depot.manifest(latest);
            FileData f = find(m);
            if (f == null) throw new IOException("Steam 上的游戏里没有 " + NAME + "（游戏改了打包方式，需要新版安卓移植）");
            String want = hex(f.getFileHash());
            if (have && st.manifest == 0 && want != null && want.equals(st.sha1)) {
                st.manifest = latest;
                saveState(c, st);
                Log.i(TAG, "game: imported copy is manifest " + Long.toUnsignedString(latest));
                return "已是最新";
            }
            DepotManifest old = null;
            if (have && st.manifest != 0) {
                try {
                    old = depot.manifest(st.manifest);
                } catch (Exception e) {
                    Log.w(TAG, "game: old manifest unavailable, comparing in place: " + e);
                }
            }
            install(c, depot, latest, f, have ? find(old) : null, have, staged, p);
            return staged ? STAGED_READY : have ? "已更新到 Steam 最新版" : "已从 Steam 下载";
        } catch (Exception e) {
            Log.w(TAG, "game: sync failed", e);
            if (!have && !ready(c)) throw e instanceof Missing ? e : new Missing("游戏文件下载没有完成：" + SteamDepot.rootMessage(e), e);
            throw e;
        }
    }

    private static FileData find(DepotManifest m) {
        if (m == null) return null;
        for (FileData f : m.getFiles()) {
            String n = f.getFileName().replace('\\', '/');
            if (n.equalsIgnoreCase(NAME)) return f;
        }
        return null;
    }

    private static void install(Context c, SteamDepot depot, long latest, FileData f, FileData oldFile, boolean have, boolean staged, Progress p) throws Exception {
        File d = dir(c), part = part(c), cur = pck(c);
        d.mkdirs();
        long total = f.getTotalSize();
        String want = hex(f.getFileHash());

        // Resume a download of this same version.
        Set<Long> done = new HashSet<>();
        try {
            if (part.isFile() && partState(c).isFile()) {
                JSONObject j = new JSONObject(new String(Files.readAllBytes(partState(c).toPath()), StandardCharsets.UTF_8));
                if (Long.parseUnsignedLong(j.optString("manifest", "0")) == latest && part.length() == total) {
                    JSONArray a = j.optJSONArray("done");
                    if (a != null) for (int i = 0; i < a.length(); i++) done.add(a.getLong(i));
                }
            }
        } catch (Exception e) {
            done.clear();
        }
        if (done.isEmpty()) part.delete();

        long need = total - doneBytes(f, done) + (64L << 20);
        if (freeBytes(c) < need)
            throw new IOException(String.format(java.util.Locale.ROOT, "存储空间不够：需要约 %d MB，现在只剩 %d MB", need / 1_000_000, freeBytes(c) / 1_000_000));

        List<ChunkData> chunks = new ArrayList<>(f.getChunks());
        chunks.sort(Comparator.comparingLong(ChunkData::getOffset));
        AtomicLong bytes = new AtomicLong(doneBytes(f, done));
        try (RandomAccessFile out = new RandomAccessFile(part, "rw")) {
            out.setLength(total);

            // What the current file already has: chunks by content, at their old offset (old
            // manifest) or at the same offset.
            if (have && cur.isFile()) {
                p.line("正在比对本机已有的游戏文件…");
                Map<String, Long> oldAt = new HashMap<>();
                if (oldFile != null) for (ChunkData oc : oldFile.getChunks()) oldAt.put(hex(oc.getChunkID()), oc.getOffset());
                int reused = 0;
                try (RandomAccessFile in = new RandomAccessFile(cur, "r")) {
                    MessageDigest md = MessageDigest.getInstance("SHA-1");
                    byte[] buf = new byte[0];
                    for (ChunkData ch : chunks) {
                        if (done.contains(ch.getOffset())) continue;
                        int len = ch.getUncompressedLength();
                        if (buf.length < len) buf = new byte[len];
                        byte[] id = ch.getChunkID();
                        Long at = oldAt.get(hex(id));
                        for (long off : at != null && at != ch.getOffset() ? new long[] { at, ch.getOffset() } : new long[] { ch.getOffset() }) {
                            if (off + len > in.length()) continue;
                            in.seek(off);
                            in.readFully(buf, 0, len);
                            md.reset();
                            md.update(buf, 0, len);
                            if (!Arrays.equals(md.digest(), id)) continue;
                            out.seek(ch.getOffset());
                            out.write(buf, 0, len);
                            done.add(ch.getOffset());
                            bytes.addAndGet(len);
                            reused++;
                            break;
                        }
                    }
                }
                Log.i(TAG, "game: " + reused + " of " + chunks.size() + " chunks already here");
                savePart(c, latest, done);
            }

            List<ChunkData> todo = new ArrayList<>();
            long todoBytes = 0;
            for (ChunkData ch : chunks) if (!done.contains(ch.getOffset())) { todo.add(ch); todoBytes += ch.getUncompressedLength(); }
            Log.i(TAG, "game: manifest " + Long.toUnsignedString(latest) + ", " + todo.size() + " chunks (" + (todoBytes >> 20) + " MB) to download");
            // A chunk that keeps failing (a busy CDN answers 503) does not stop the others: it is
            // set aside and tried again after the rest, up to ROUNDS times with a pause.
            long t0 = System.currentTimeMillis(), b0 = bytes.get();
            Exception[] first = new Exception[1];
            AtomicLong lastSave = new AtomicLong(System.currentTimeMillis());
            for (int round = 0; round < ROUNDS && !todo.isEmpty(); round++) {
                if (round == 0) {
                    p.line("正在从 Steam 下载…");
                } else {
                    Log.w(TAG, "game: " + todo.size() + " chunks failed (" + SteamDepot.rootMessage(first[0]) + "), round " + (round + 1));
                    p.line("有 " + todo.size() + " 块没下载成功，稍等后重试…");
                    Thread.sleep(3000L * round);
                }
                List<ChunkData> failedChunks = java.util.Collections.synchronizedList(new ArrayList<>());
                ExecutorService pool = Executors.newFixedThreadPool(THREADS, r -> {
                    Thread t = new Thread(r, "BroSteam-game");
                    t.setDaemon(true);
                    return t;
                });
                for (ChunkData ch : todo) {
                    pool.submit(() -> {
                        try {
                            byte[] buf = depot.chunk(ch);
                            int len = ch.getUncompressedLength();
                            synchronized (out) {
                                out.seek(ch.getOffset());
                                out.write(buf, 0, len);
                            }
                            synchronized (done) {
                                done.add(ch.getOffset());
                            }
                            bytes.addAndGet(len);
                            long now = System.currentTimeMillis();
                            if (now - lastSave.get() > 3000) {
                                lastSave.set(now);
                                synchronized (done) {
                                    savePart(c, latest, done);
                                }
                            }
                        } catch (Exception e) {
                            synchronized (first) {
                                if (first[0] == null || !(e instanceof IOException)) first[0] = e;
                            }
                            failedChunks.add(ch);
                        }
                    });
                }
                pool.shutdown();
                while (!pool.awaitTermination(400, TimeUnit.MILLISECONDS)) {
                    long b = bytes.get(), dt = System.currentTimeMillis() - t0;
                    p.progress(b, total);
                    if (dt > 1500) p.line(String.format(java.util.Locale.ROOT, "%d / %d MB · %.1f MB/s", b / 1_000_000, total / 1_000_000, (b - b0) / 1e6 / (dt / 1000.0)));
                }
                synchronized (done) {
                    savePart(c, latest, done);
                }
                todo = new ArrayList<>(failedChunks);
            }
            if (!todo.isEmpty())
                throw new IOException("下载中断：" + SteamDepot.rootMessage(first[0]) + "（已下载的部分会保留，下次接着下）", first[0]);
            long secs = Math.max(1, (System.currentTimeMillis() - t0) / 1000);
            if (bytes.get() > b0) Log.i(TAG, "game: downloaded " + ((bytes.get() - b0) >> 20) + " MB in " + secs + " s");
            p.progress(total, total);

            p.line("正在校验…");
            String got = hex(sha1(out, total));
            if (want != null && !want.equals(got)) {
                out.close();
                part.delete();
                partState(c).delete();
                throw new IOException("下载的文件校验失败，已删除，请重试");
            }
            String bad = headerProblem(out, false);
            if (bad != null) {
                // Keep it for when the port supports it, but do not switch to it.
                throw new IOException(bad);
            }
            lowerEngineVersion(out);
        }
        State st = new State();
        st.manifest = latest;
        st.sha1 = want;
        st.size = total;
        st.source = "steam";
        if (staged) {
            File next = staged(c);
            next.delete();
            if (!part.renameTo(next)) throw new IOException("无法保存下载好的更新");
            partState(c).delete();
            writeAtomic(stagedState(c), stateJson(st).getBytes(StandardCharsets.UTF_8));
            Log.i(TAG, "game: update to manifest " + Long.toUnsignedString(latest) + " downloaded, switched in at the next launch");
            return;
        }
        if (cur.exists() && !cur.delete()) throw new IOException("无法替换旧的游戏文件");
        if (!part.renameTo(cur)) throw new IOException("无法写入游戏文件");
        partState(c).delete();
        saveState(c, st);
        Log.i(TAG, "game: installed manifest " + Long.toUnsignedString(latest) + " (" + (total >> 20) + " MB)");
    }

    // At launch, before Godot opens the pck: switch in an update downloaded last time.
    static void applyStaged(Context c) {
        File next = staged(c), js = stagedState(c);
        if (!next.isFile() || !js.isFile()) return;
        try (RandomAccessFile r = new RandomAccessFile(next, "r")) {
            if (headerProblem(r, true) != null) throw new IOException("staged pck is not usable");
        } catch (IOException e) {
            Log.w(TAG, "game: dropping the staged update: " + e);
            next.delete();
            js.delete();
            return;
        }
        File cur = pck(c);
        if (cur.exists() && !cur.delete()) return;
        if (!next.renameTo(cur)) return;
        if (!js.renameTo(stateFile(c))) Log.w(TAG, "game: state of the staged update not moved");
        Log.i(TAG, "game: switched to the update downloaded last time");
    }

    private static long doneBytes(FileData f, Set<Long> done) {
        long n = 0;
        for (ChunkData ch : f.getChunks()) if (done.contains(ch.getOffset())) n += ch.getUncompressedLength();
        return n;
    }

    private static void savePart(Context c, long manifest, Set<Long> done) {
        try {
            JSONArray a = new JSONArray();
            for (long o : done) a.put(o);
            writeAtomic(partState(c), new JSONObject().put("manifest", Long.toUnsignedString(manifest)).put("done", a).toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            Log.w(TAG, "game: progress not saved: " + e);
        }
    }

    // ---- the player's own copy ----

    // Copies a pck (from the system file picker, or the drop folder) into place. The SHA-1 recorded
    // is that of the file as given, so a signed-in launch can tell whether it is Steam's current one.
    static void importFrom(Context c, InputStream in, long size, Progress p) throws Exception {
        File d = dir(c), tmp = new File(d, "import.part"), cur = pck(c);
        d.mkdirs();
        if (size > 0 && freeBytes(c) < size + (64L << 20)) throw new IOException("存储空间不够");
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        long n = 0;
        try (OutputStream o = new FileOutputStream(tmp)) {
            byte[] b = new byte[1 << 20];
            long last = 0;
            for (int r; (r = in.read(b)) > 0; ) {
                o.write(b, 0, r);
                md.update(b, 0, r);
                n += r;
                long now = System.currentTimeMillis();
                if (now - last > 200) {
                    last = now;
                    p.progress(n, size > 0 ? size : TYPICAL_SIZE);
                    p.line((n / 1_000_000) + (size > 0 ? " / " + (size / 1_000_000) : "") + " MB");
                }
            }
        } catch (Exception e) {
            tmp.delete();
            throw e;
        }
        p.progress(n, n);
        try (RandomAccessFile r = new RandomAccessFile(tmp, "rw")) {
            String bad = headerProblem(r, false);
            if (bad != null) throw new IOException(bad);
            lowerEngineVersion(r);
        } catch (Exception e) {
            tmp.delete();
            throw e;
        }
        if (cur.exists() && !cur.delete()) { tmp.delete(); throw new IOException("无法替换旧的游戏文件"); }
        if (!tmp.renameTo(cur)) throw new IOException("无法写入游戏文件");
        part(c).delete();
        partState(c).delete();
        State st = new State();
        st.sha1 = hex(md.digest());
        st.size = n;
        st.source = "import";
        saveState(c, st);
        Log.i(TAG, "game: imported " + (n >> 20) + " MB, sha1 " + st.sha1);
    }

    // ---- the pck itself ----

    // null when this is a Brotato pck that Godot 3.6 can run (after lowerEngineVersion when
    // `installed` is false), else why not. `installed`: the quick check of the file in place (header
    // only); otherwise the file table is read too, to be sure it is Brotato's.
    static String headerProblem(RandomAccessFile f, boolean installed) throws IOException {
        if (f.length() < 96) return "不是游戏文件（太小）";
        f.seek(0);
        byte[] h = new byte[20];
        f.readFully(h);
        if (h[0] != 'G' || h[1] != 'D' || h[2] != 'P' || h[3] != 'C') return "不是 Godot 游戏文件（选的是 " + NAME + " 吗？）";
        int fmt = le32(h, 4), major = le32(h, 8), minor = le32(h, 12);
        if (fmt != 1) return "游戏文件格式是 v" + fmt + "，这个安卓版只支持 Godot 3 的 v1";
        if (major != 3) return "这个版本的游戏用的是 Godot " + major + "." + minor + "，这个安卓版只支持 Godot 3，需要新版安卓移植";
        if (installed) return minor > 6 ? "游戏文件还没处理" : null;
        f.seek(20 + 64);
        int count = Integer.reverseBytes(f.readInt());
        if (count < 100 || count > 1_000_000) return "游戏文件目录不对";
        boolean project = false, crash = false;
        byte[] nb = new byte[0];
        for (int i = 0; i < count && !(project && crash); i++) {
            int len = Integer.reverseBytes(f.readInt());
            if (len <= 0 || len > 4096) return "游戏文件目录不对";
            if (nb.length < len) nb = new byte[len];
            f.readFully(nb, 0, len);
            String name = new String(nb, 0, len, StandardCharsets.UTF_8).trim();
            if (name.startsWith("res://project.binary")) project = true;
            else if (name.startsWith("res://singletons/crash_reporter.gd")) crash = true;
            f.skipBytes(16 + 16);
        }
        if (!project || !crash) return "这不是土豆兄弟（Brotato）的游戏文件";
        return null;
    }

    // Godot 3.6 refuses "Pack created with a newer version of the engine" (3.7 here).
    private static void lowerEngineVersion(RandomAccessFile f) throws IOException {
        f.seek(12);
        int minor = Integer.reverseBytes(f.readInt());
        if (minor > 6) {
            f.seek(12);
            f.writeInt(Integer.reverseBytes(6));
            f.seek(16);
            f.writeInt(0);
        }
    }

    // ---- helpers ----

    private static int le32(byte[] b, int o) {
        return (b[o] & 0xFF) | (b[o + 1] & 0xFF) << 8 | (b[o + 2] & 0xFF) << 16 | (b[o + 3] & 0xFF) << 24;
    }

    static byte[] sha1(RandomAccessFile f, long len) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        byte[] b = new byte[1 << 20];
        f.seek(0);
        for (long left = len; left > 0; ) {
            int r = f.read(b, 0, (int) Math.min(b.length, left));
            if (r < 0) break;
            md.update(b, 0, r);
            left -= r;
        }
        return md.digest();
    }

    static String hex(byte[] b) {
        if (b == null) return null;
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format(java.util.Locale.ROOT, "%02x", x & 0xFF));
        return sb.toString();
    }

    private static byte[] readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
        byte[] b = new byte[1 << 16];
        for (int r; (r = in.read(b)) > 0; ) o.write(b, 0, r);
        return o.toByteArray();
    }

    private static void writeAtomic(File f, byte[] data) throws IOException {
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream o = new FileOutputStream(tmp)) {
            o.write(data);
        }
        if (!tmp.renameTo(f)) {
            f.delete();
            if (!tmp.renameTo(f)) throw new IOException("无法写入 " + f.getName());
        }
    }

    private GameFiles() { }
}
