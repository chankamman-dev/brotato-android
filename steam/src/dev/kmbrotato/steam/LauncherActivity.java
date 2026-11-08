package dev.kmbrotato.steam;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.animation.DecelerateInterpolator;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

// The entry point. Pulls the Steam Cloud saves into user://<SteamID64>/, brings the game files
// (files/game/Brotato.pck, see GameFiles) up to date with Steam and the subscribed Workshop items into
// user://workshop/ before Godot starts, or signs in first. Without game files it offers the Steam
// download or importing the player's own copy. Playing without signing in keeps saves in
// user://user/, as the PC game does when Steam is not running.
//
// Layout (landscape): the game on the left, one card on the right that holds the current step --
// sign-in offer, QR code, sync progress, result or error. Everything is reachable with a gamepad:
// buttons take focus and show a ring.
public final class LauncherActivity extends Activity {
    private static final int BG_TOP = 0xFF0E1116, BG_BOTTOM = 0xFF1A1F27, CARD = 0xFF1A1E25, CARD_EDGE = 0xFF2A303A;
    private static final int TEXT = 0xFFF2F4F7, MUTED = 0xFF9AA3B2, FAINT = 0xFF636B78;
    private static final int ACCENT = 0xFF2F8CFF, ACCENT_DOWN = 0xFF1F6FD6, SECOND = 0xFF2A303A, SECOND_DOWN = 0xFF353C48;
    private static final int GOOD = 0xFF3CCB7F, BAD = 0xFFF0544F;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private View root;
    private LinearLayout cardBody, buttons;
    private TextView cardKicker, cardTitle, cardText, cardFoot;
    private Step[] steps;
    private volatile boolean busy;
    private boolean importing;
    private static final int PICK = 41;
    private int screen; // bumped on every screen change; late callbacks for an old screen are dropped

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        // Back from the home screen while the game still runs: return to it, never sync under it.
        if (SteamPlugin.gameRunning) {
            enterGame();
            return;
        }
        SteamCrypto.install();
        SteamSession.init(this);
        // Updates downloaded in the background last time take effect now, before Godot starts.
        GameFiles.applyStaged(this);
        Workshop.applyStaged(this);
        // A routine launch goes straight into the game; the Steam sync runs alongside it (the game
        // waits for the cloud saves right before reading them). See CloudSync.startRoutine.
        if (TokenStore.load(this) != null && routine() && !TokenStore.relogin(this)) {
            try {
                GameFiles.prepareLaunch(this);
                CloudSync.startRoutine(this);
                startGame();
                return;
            } catch (IOException e) {
                android.util.Log.w(SteamPlugin.TAG, "routine launch failed, showing the launcher", e);
            }
        }
        setContentView(build());
        hideBars();
        File drop = GameFiles.dropped(this);
        if (!GameFiles.ready(this) && drop != null && drop.isFile()) runImport(() -> new FileInputStream(drop), drop.length(), drop);
        else if (TokenStore.load(this) != null) startSync();
        else showSignedOut();
    }

    // ---- screens ----

    private void showSignedOut() {
        if (!GameFiles.ready(this)) {
            showNeedGame();
            return;
        }
        open("STEAM", "登录 Steam 账号", "登录后会自动处理这些：");
        LinearLayout list = column();
        list.addView(feature("云存档", "和电脑上的进度互相同步"));
        list.addView(feature("成就和时长", "计入你的 Steam 资料"));
        list.addView(feature("DLC", "按账号里已购买的解锁"));
        list.addView(feature("创意工坊", "已订阅的模组自动下载"));
        cardBody.addView(list);
        foot("不登录也能玩，存档只留在本机。");
        setButtons(new String[] { "扫码登录", "离线游玩" }, new Runnable[] { this::startLogin, this::enterGame });
    }

    // No game files yet: download them from Steam (signing in first) or import the player's copy.
    private void showNeedGame() {
        boolean signedIn = TokenStore.load(this) != null;
        open("游戏文件", "需要你的正版游戏文件", "安卓版不内置游戏内容，用的是你 Steam 账号里的 PC 版，约 " + (GameFiles.TYPICAL_SIZE / 1_000_000) + " MB。");
        LinearLayout list = column();
        list.addView(feature("从 Steam 下载", signedIn ? "以后自动跟着 Steam 更新" : "扫码登录后下载，以后自动更新"));
        list.addView(feature("导入文件", "选从电脑复制来的 " + GameFiles.NAME));
        cardBody.addView(list);
        foot("电脑上的位置：Steam 库里右键土豆兄弟 → 管理 → 浏览本地文件。");
        setButtons(new String[] { signedIn ? "下载" : "扫码登录", "导入文件" },
                new Runnable[] { signedIn ? this::startSync : this::startLogin, this::pickFile });
    }

    private void pickFile() {
        if (busy) return;
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        try {
            startActivityForResult(i, PICK);
        } catch (Throwable t) {
            open("游戏文件", "没法打开文件选择器", message(t));
            setButtons(new String[] { "返回" }, new Runnable[] { this::showNeedGame });
        }
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PICK || result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        runImport(() -> getContentResolver().openInputStream(uri), sizeOf(uri), null);
    }

    private long sizeOf(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, new String[] { OpenableColumns.SIZE }, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getLong(0);
        } catch (Throwable ignored) {
        }
        return -1;
    }

    private interface Opener {
        InputStream open() throws Exception;
    }

    // Copies the player's pck into place; `dropped` (the drop folder's copy) is removed afterwards.
    private void runImport(Opener opener, long size, File dropped) {
        if (busy) return;
        busy = true;
        importing = true;
        int me = open("游戏文件", "正在导入游戏文件", null);
        ProgressBar pb = bar();
        cardBody.addView(pb, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6)));
        TextView detail = text(13, FAINT, false);
        detail.setPadding(0, dp(8), 0, 0);
        cardBody.addView(detail);
        foot("导入完成前请不要离开。");
        setButtons(new String[0], new Runnable[0]);
        new Thread(() -> {
            try (InputStream in = opener.open()) {
                if (in == null) throw new IOException("打不开这个文件");
                GameFiles.importFrom(this, in, size, new GameFiles.Progress() {
                    @Override
                    public void line(String s) {
                        ui.post(() -> { if (screen == me) detail.setText(s); });
                    }

                    @Override
                    public void progress(long done, long total) {
                        ui.post(() -> { if (screen == me) setBar(pb, done, total); });
                    }
                });
                if (dropped != null) dropped.delete();
                ui.post(() -> {
                    if (screen != me) return;
                    busy = false;
                    importing = false;
                    // Signed in: the sync checks the copy against Steam (and updates an old one).
                    if (TokenStore.load(this) != null) startSync();
                    else showSignedOut();
                });
            } catch (Throwable t) {
                ui.post(() -> {
                    if (screen != me) return;
                    busy = false;
                    importing = false;
                    open("游戏文件", "导入没有成功", message(t));
                    setButtons(new String[] { "换个文件", "返回" }, new Runnable[] { this::pickFile, this::showNeedGame });
                });
            }
        }, "BroSteam-import").start();
    }

    private void startLogin() {
        if (busy) return;
        busy = true;
        int me = open("STEAM 登录", "用 Steam 手机应用扫码", null);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout box = new FrameLayout(this);
        box.setBackground(round(Color.WHITE, 0, 0, dp(12)));
        box.setPadding(dp(10), dp(10), dp(10), dp(10));
        ImageView qr = new ImageView(this);
        ProgressBar wait = spinner(dp(36));
        box.addView(qr, new FrameLayout.LayoutParams(dp(180), dp(180)));
        box.addView(wait, new FrameLayout.LayoutParams(dp(36), dp(36), Gravity.CENTER));
        row.addView(box);
        LinearLayout how = column();
        how.setPadding(dp(20), 0, 0, 0);
        how.addView(numbered(1, "打开手机上的 Steam 应用"));
        how.addView(numbered(2, "点底部的盾牌图标（Steam 令牌）"));
        how.addView(numbered(3, "点“扫描二维码”，对准左边的码"));
        how.addView(numbered(4, "在手机上确认登录"));
        row.addView(how, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        cardBody.addView(row);
        foot("二维码会自动刷新。密码不会经过这台设备。");
        setButtons(new String[] { "取消" }, new Runnable[] { () -> { busy = false; showSignedOut(); } });
        new Thread(() -> {
            try (SteamSession s = new SteamSession()) {
                String[] r = QrLogin.run(s, url -> ui.post(() -> {
                    if (screen != me) return;
                    Bitmap bmp = qrBitmap(url);
                    if (bmp != null) {
                        qr.setImageBitmap(bmp);
                        wait.setVisibility(View.GONE);
                    }
                }));
                TokenStore.save(this, r[0], r[1]);
                ui.post(() -> { if (screen != me) return; busy = false; startSync(); });
            } catch (Throwable t) {
                ui.post(() -> {
                    if (screen != me) return;
                    busy = false;
                    open("STEAM 登录", "登录没有完成", message(t));
                    setButtons(new String[] { "重试", "离线游玩" }, new Runnable[] { this::startLogin, this::enterGame });
                });
            }
        }, "BroSteam-login").start();
    }

    // A routine launch: signed in before, game files in place, the first cloud sync done. It shows a
    // short splash instead of the checklist and goes straight into the game unless something needs
    // the player's eye (a download, an update that failed, files backed up, a Workshop problem).
    private boolean routine() {
        return GameFiles.ready(this) && CloudSync.initialized(this);
    }

    private void startSync() {
        if (busy) return;
        busy = true;
        CloudSync.Gate.disarm();
        boolean quick = routine();
        TokenStore t = TokenStore.load(this);
        int me = open(t != null ? "STEAM · " + t.account : "STEAM", quick ? "正在同步" : "正在准备游戏", null);
        steps = new Step[] { new Step("登录 Steam"), new Step("同步云存档"), new Step("确认游戏和 DLC"), new Step("游戏文件"), new Step("创意工坊模组") };
        LinearLayout list = column();
        TextView status = text(15, MUTED, false);
        if (quick) {
            status.setText("正在连接 Steam…");
            list.addView(status);
            ProgressBar wait = bar();
            wait.setIndeterminate(true);
            wait.setIndeterminateTintList(ColorStateList.valueOf(ACCENT));
            LinearLayout.LayoutParams wl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6));
            wl.topMargin = dp(12);
            list.addView(wait, wl);
        } else {
            for (Step s : steps) list.addView(s.row);
        }
        ProgressBar pb = bar();
        pb.setVisibility(View.GONE);
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6));
        pl.topMargin = dp(6);
        list.addView(pb, pl);
        cardBody.addView(list);
        foot(quick ? "" : "首次同步以云端存档为准，本机原有的存档会先备份。");
        setButtons(new String[0], new Runnable[0]);
        new Thread(() -> {
            try {
                CloudSync.Result r = CloudSync.sync(this, new CloudSync.Log() {
                    @Override
                    public void line(int phase, String s) {
                        ui.post(() -> {
                            if (screen != me) return;
                            steps[phase].active(s);
                            if (quick) status.setText(s);
                        });
                    }

                    @Override
                    public void begin(int phase) {
                        ui.post(() -> { if (screen == me) steps[phase].active(null); });
                    }

                    @Override
                    public void done(int phase) {
                        ui.post(() -> { if (screen == me) steps[phase].done(null); });
                    }

                    @Override
                    public void progress(int phase, long done, long total) {
                        ui.post(() -> {
                            if (screen != me) return;
                            pb.setVisibility(done < total ? View.VISIBLE : View.GONE);
                            setBar(pb, done, total);
                        });
                    }
                }, true);
                ui.post(() -> {
                    if (screen != me) return;
                    busy = false;
                    if (quick && !notable(r)) enterGame();
                    else showDone(r);
                });
            } catch (Throwable e) {
                boolean rejected = e instanceof SteamSession.LogOnRejected;
                boolean missing = e instanceof GameFiles.Missing;
                ui.post(() -> {
                    if (screen != me) return;
                    busy = false;
                    pb.setVisibility(View.GONE);
                    showSteps();
                    for (Step s : steps) if (s.state == Step.ACTIVE) s.failed(null);
                    boolean ready = GameFiles.ready(this);
                    String play = ready ? "离线游玩" : "导入文件";
                    Runnable playOr = ready ? this::enterGame : this::pickFile;
                    if (rejected)
                        showError("Steam 登录已失效", message(e) + "\n需要重新扫码登录。本机存档没有改动。",
                                new String[] { "重新登录", play }, new Runnable[] { () -> { signOut(); startLogin(); }, playOr });
                    else if (missing)
                        showError("游戏文件还没下载好", message(e) + "\n可以重试，已下载的部分会保留；也可以导入自己复制来的文件。",
                                new String[] { "重试", "导入文件", "退出登录" }, new Runnable[] { this::startSync, this::pickFile, () -> { signOut(); showSignedOut(); } });
                    else
                        showError("同步没有完成", message(e) + (ready ? "\n本机存档没有改动，可以先离线玩，下次启动再同步。" : "\n本机存档没有改动。"),
                                new String[] { "重试", play, "退出登录" }, new Runnable[] { this::startSync, playOr, () -> { signOut(); showSignedOut(); } });
                });
            }
        }, "BroSteam-sync").start();
    }

    // Worth a look before playing: anything but "all as before".
    private static boolean notable(CloudSync.Result r) {
        if (r.gameFailed || r.backedUp > 0) return true;
        if (r.game != null && !r.game.equals("已是最新") && !r.game.equals("内置在安装包里")) return true;
        return r.workshop != null && (r.workshop.contains("失败") || r.workshop.contains("新下载"));
    }

    // The quick splash switches to the full checklist for a result or an error.
    private void showSteps() {
        if (steps == null || steps[0].row.getParent() != null) return;
        cardBody.removeAllViews();
        LinearLayout list = column();
        for (Step s : steps) list.addView(s.row);
        cardBody.addView(list);
    }

    private void showDone(CloudSync.Result r) {
        showSteps();
        steps[CloudSync.STEP_SIGN_IN].done("已登录");
        steps[CloudSync.STEP_CLOUD].done(r.down + r.up + r.deleted == 0 ? "已是最新" : "下载 " + r.down + " · 上传 " + r.up + (r.deleted > 0 ? " · 删除 " + r.deleted : ""));
        steps[CloudSync.STEP_OWNERSHIP].done(dlcLine());
        if (r.gameFailed) steps[CloudSync.STEP_GAME].failed("沿用当前版本");
        else steps[CloudSync.STEP_GAME].done(r.game == null ? "" : r.game);
        List<String> mods = Workshop.titles(this);
        if (r.workshop != null && r.workshop.contains("失败") && mods.isEmpty()) steps[CloudSync.STEP_WORKSHOP].failed("这次没同步上");
        else if (r.workshop != null && r.workshop.contains("失败")) steps[CloudSync.STEP_WORKSHOP].failed("沿用上次的 " + mods.size() + " 个");
        else steps[CloudSync.STEP_WORKSHOP].done(mods.isEmpty() ? "没有订阅" : mods.size() + " 个");
        cardTitle.setText("准备好了");
        StringBuilder f = new StringBuilder();
        if (r.gameFailed) f.append(r.game);
        if (!mods.isEmpty()) f.append(f.length() > 0 ? "\n" : "").append("模组：").append(TextUtils.join("、", mods));
        else if (r.backedUp > 0) f.append(f.length() > 0 ? "\n" : "").append("有 ").append(r.backedUp).append(" 个文件在覆盖前做了备份。");
        foot(f.toString());
        setButtons(new String[] { "进入游戏" }, new Runnable[] { this::enterGame });
        int me = screen;
        ui.postDelayed(() -> { if (screen == me) enterGame(); }, 1500);
    }

    private String dlcLine() {
        boolean at = Ownership.owns(this, Ownership.ABYSSAL_TERRORS, false), pd = Ownership.owns(this, Ownership.PRIMAL_DREAD, false);
        if (at && pd) return "本体 + 2 个 DLC";
        if (at || pd) return "本体 + 1 个 DLC";
        return "本体";
    }

    private void showError(String title, String text, String[] labels, Runnable[] actions) {
        cardTitle.setText(title);
        cardTitle.setTextColor(TEXT);
        cardText.setText(text);
        cardText.setVisibility(View.VISIBLE);
        foot("");
        setButtons(labels, actions);
    }

    private void signOut() {
        TokenStore.clear(this);
        CloudSync.forget(this);
        SteamPlay.forget(this);
    }

    private boolean entering;

    private void enterGame() {
        if (entering) return;
        if (!SteamPlugin.gameRunning) {
            if (!GameFiles.ready(this)) {
                showNeedGame();
                return;
            }
            try {
                GameFiles.prepareLaunch(this);
            } catch (IOException e) {
                open("游戏文件", "没法启动游戏", message(e));
                setButtons(new String[] { "重试" }, new Runnable[] { this::enterGame });
                return;
            }
        }
        startGame();
    }

    private void startGame() {
        entering = true;
        screen++;
        Intent i = new Intent();
        i.setClassName(this, "com.godot.game.GodotApp");
        startActivity(i);
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        finish();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // B / back on the QR screen cancels instead of leaving the app.
        if ((keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_BUTTON_B) && busy && importing) return true;
        if ((keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_BUTTON_B) && busy && steps == null) {
            busy = false;
            showSignedOut();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    // ---- layout ----

    private View build() {
        FrameLayout frame = new FrameLayout(this);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[] { BG_TOP, BG_BOTTOM });
        frame.setBackground(bg);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(40), dp(24), dp(40), dp(24));

        // The game.
        LinearLayout left = column();
        ImageView icon = new ImageView(this);
        try {
            icon.setImageDrawable(getPackageManager().getApplicationIcon(getApplicationInfo()));
        } catch (Throwable ignored) {
        }
        icon.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(22));
            }
        });
        icon.setClipToOutline(true);
        left.addView(icon, new LinearLayout.LayoutParams(dp(112), dp(112)));
        TextView name = text(40, TEXT, true);
        name.setText("Brotato");
        name.setPadding(0, dp(18), 0, 0);
        left.addView(name);
        TextView cn = text(18, MUTED, false);
        cn.setText("土豆兄弟");
        left.addView(cn);
        TextView ver = text(12, FAINT, false);
        ver.setText("Steam 版 · " + versionName());
        ver.setPadding(0, dp(18), 0, 0);
        left.addView(ver);
        row.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        // The card.
        LinearLayout card = column();
        card.setBackground(round(CARD, CARD_EDGE, dp(1), dp(18)));
        card.setPadding(dp(28), dp(24), dp(28), dp(22));
        card.setElevation(dp(8));
        cardKicker = text(12, ACCENT, true);
        cardKicker.setLetterSpacing(0.12f);
        cardTitle = text(24, TEXT, true);
        cardTitle.setPadding(0, dp(6), 0, 0);
        cardText = text(15, MUTED, false);
        cardText.setPadding(0, dp(8), 0, 0);
        cardText.setLineSpacing(0, 1.15f);
        cardBody = column();
        cardBody.setPadding(0, dp(16), 0, 0);
        cardFoot = text(12, FAINT, false);
        cardFoot.setPadding(0, dp(14), 0, 0);
        buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setPadding(0, dp(18), 0, 0);
        card.addView(cardKicker);
        card.addView(cardTitle);
        card.addView(cardText);
        card.addView(cardBody);
        card.addView(cardFoot);
        card.addView(buttons);
        row.addView(card, new LinearLayout.LayoutParams(dp(500), ViewGroup.LayoutParams.WRAP_CONTENT));

        frame.addView(row, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root = frame;
        return frame;
    }

    // Starts a new screen in the card; returns its id for the screen's callbacks.
    private int open(String kicker, String title, String text) {
        screen++;
        steps = null;
        cardKicker.setText(kicker);
        cardTitle.setText(title);
        cardTitle.setTextColor(TEXT);
        cardText.setText(text == null ? "" : text);
        cardText.setVisibility(text == null ? View.GONE : View.VISIBLE);
        cardBody.removeAllViews();
        foot("");
        View v = (View) cardBody.getParent();
        v.setAlpha(0f);
        v.setTranslationY(dp(10));
        v.animate().alpha(1f).translationY(0).setDuration(220).setInterpolator(new DecelerateInterpolator()).start();
        return screen;
    }

    private void foot(String s) {
        cardFoot.setText(s);
        cardFoot.setVisibility(s == null || s.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private View feature(String title, String detail) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, dp(5), 0, dp(5));
        View dot = new View(this);
        dot.setBackground(round(ACCENT, 0, 0, dp(4)));
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(dp(8), dp(8));
        dl.rightMargin = dp(12);
        r.addView(dot, dl);
        TextView t = text(15, TEXT, true);
        t.setText(title);
        r.addView(t);
        TextView d = text(14, MUTED, false);
        d.setText("  " + detail);
        r.addView(d);
        return r;
    }

    private View numbered(int n, String s) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, dp(5), 0, dp(5));
        TextView num = text(12, TEXT, true);
        num.setText(Integer.toString(n));
        num.setGravity(Gravity.CENTER);
        num.setBackground(round(SECOND, 0, 0, dp(11)));
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(dp(22), dp(22));
        nl.rightMargin = dp(10);
        r.addView(num, nl);
        TextView t = text(14, MUTED, false);
        t.setText(s);
        r.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        return r;
    }

    // One line of the sync checklist.
    private final class Step {
        static final int PENDING = 0, ACTIVE = 1, DONE = 2, FAILED = 3;
        final LinearLayout row = new LinearLayout(LauncherActivity.this);
        final FrameLayout mark = new FrameLayout(LauncherActivity.this);
        final TextView glyph = text(13, TEXT, true);
        final ProgressBar spin = spinner(dp(18));
        final TextView label = text(15, MUTED, false);
        final TextView detail = text(13, FAINT, false);
        int state;

        Step(String name) {
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(7), 0, dp(7));
            glyph.setGravity(Gravity.CENTER);
            mark.addView(glyph, new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER));
            mark.addView(spin, new FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER));
            LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(dp(22), dp(22));
            ml.rightMargin = dp(12);
            row.addView(mark, ml);
            label.setText(name);
            label.setGravity(Gravity.START);
            row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            detail.setSingleLine(true);
            detail.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            detail.setGravity(Gravity.END);
            detail.setMaxWidth(dp(240));
            row.addView(detail);
            set(PENDING, null);
        }

        void active(String d) { set(ACTIVE, d); }

        void done(String d) { set(DONE, d); }

        void failed(String d) { set(FAILED, d); }

        private void set(int s, String d) {
            state = s;
            spin.setVisibility(s == ACTIVE ? View.VISIBLE : View.GONE);
            glyph.setVisibility(s == ACTIVE ? View.GONE : View.VISIBLE);
            switch (s) {
                case DONE:
                    glyph.setText("✓");
                    glyph.setTextColor(0xFF0E1116);
                    glyph.setBackground(round(GOOD, 0, 0, dp(11)));
                    label.setTextColor(TEXT);
                    break;
                case FAILED:
                    glyph.setText("!");
                    glyph.setTextColor(Color.WHITE);
                    glyph.setBackground(round(BAD, 0, 0, dp(11)));
                    label.setTextColor(TEXT);
                    break;
                case ACTIVE:
                    label.setTextColor(TEXT);
                    break;
                default:
                    glyph.setText("");
                    glyph.setBackground(round(0, CARD_EDGE, dp(2), dp(11)));
                    label.setTextColor(MUTED);
            }
            if (d != null) detail.setText(d);
        }
    }

    private void setButtons(String[] labels, Runnable[] actions) {
        buttons.removeAllViews();
        for (int k = 0; k < labels.length; k++) {
            Button btn = new Button(this);
            btn.setText(labels[k]);
            btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            btn.setTypeface(Typeface.DEFAULT_BOLD);
            btn.setTextColor(TEXT);
            btn.setAllCaps(false);
            btn.setBackground(buttonBg(k == 0));
            btn.setPadding(dp(26), dp(11), dp(26), dp(11));
            btn.setMinHeight(0);
            btn.setMinimumHeight(0);
            btn.setMinWidth(0);
            btn.setMinimumWidth(0);
            btn.setStateListAnimator(null);
            btn.setOnFocusChangeListener((v, has) -> v.animate().scaleX(has ? 1.04f : 1f).scaleY(has ? 1.04f : 1f).setDuration(120).start());
            final Runnable a = actions[k];
            btn.setOnClickListener(v -> a.run());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(12);
            buttons.addView(btn, lp);
        }
        buttons.setVisibility(labels.length == 0 ? View.GONE : View.VISIBLE);
        if (buttons.getChildCount() > 0) buttons.getChildAt(0).requestFocus();
    }

    private Drawable buttonBg(boolean primary) {
        int base = primary ? ACCENT : SECOND, down = primary ? ACCENT_DOWN : SECOND_DOWN;
        StateListDrawable d = new StateListDrawable();
        d.addState(new int[] { android.R.attr.state_pressed }, round(down, 0, 0, dp(10)));
        d.addState(new int[] { android.R.attr.state_focused }, round(base, Color.WHITE, dp(2), dp(10)));
        d.addState(new int[0], round(base, 0, 0, dp(10)));
        return d;
    }

    private GradientDrawable round(int fill, int stroke, int strokeWidth, int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        if (strokeWidth > 0) g.setStroke(strokeWidth, stroke);
        g.setCornerRadius(radius);
        return g;
    }

    private ProgressBar bar() {
        ProgressBar p = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        p.setIndeterminate(false);
        p.setMax(1000);
        p.setProgressTintList(ColorStateList.valueOf(ACCENT));
        p.setProgressBackgroundTintList(ColorStateList.valueOf(SECOND));
        return p;
    }

    private static void setBar(ProgressBar p, long done, long total) {
        p.setProgress(total <= 0 ? 0 : (int) Math.min(1000, done * 1000 / total));
    }

    private ProgressBar spinner(int size) {
        ProgressBar p = new ProgressBar(this);
        p.setIndeterminate(true);
        p.setIndeterminateTintList(ColorStateList.valueOf(ACCENT));
        p.setMinimumWidth(size);
        p.setMinimumHeight(size);
        return p;
    }

    private LinearLayout column() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private TextView text(int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private static Bitmap qrBitmap(String url) {
        try {
            java.util.Map<EncodeHintType, Object> hints = new java.util.EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.MARGIN, 0);
            BitMatrix m = new QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, 360, 360, hints);
            int w = m.getWidth(), h = m.getHeight();
            int[] px = new int[w * h];
            for (int y = 0; y < h; y++)
                for (int x = 0; x < w; x++)
                    px[y * w + x] = m.get(x, y) ? 0xFF0E1116 : Color.WHITE;
            return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
        } catch (Throwable t) {
            return null;
        }
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "";
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private static String message(Throwable t) {
        while (t.getCause() != null && (t.getMessage() == null || t.getMessage().isEmpty())) t = t.getCause();
        String m = t.getMessage();
        return m == null || m.isEmpty() ? t.getClass().getSimpleName() : m;
    }

    private void hideBars() {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.systemBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        }
    }
}
