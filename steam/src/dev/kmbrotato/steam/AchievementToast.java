package dev.kmbrotato.steam;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.Iterator;

import in.dragonbra.javasteam.types.KeyValue;

// The "achievement unlocked" notice the Steam overlay shows on PC: a card in the bottom-right corner
// (the game's own challenge notice is bottom-left) with the achievement's icon and name, which rises
// from the bottom edge, stays 5 s and goes back down the way it came. Several queue up.
//
// Names, descriptions and icons come from the game's Steam schema, cached by SteamPlay on every
// check (files/achievements.json, icons in files/achievement-icons/), so a challenge completed
// offline still shows them. Before the first check the game's own texts are used.
final class AchievementToast {
    private static final String TAG = SteamPlugin.TAG;
    private static final String ICON_URL = "https://cdn.cloudflare.steamstatic.com/steamcommunity/public/images/apps/" + Ownership.BASE + "/";
    private static final long SHOW_MS = 5000;

    private static final ArrayDeque<String[]> queue = new ArrayDeque<>();
    private static boolean showing;

    private static File metaFile(Context c) {
        return new File(c.getFilesDir(), "achievements.json");
    }

    private static File iconDir(Context c) {
        return new File(c.getFilesDir(), "achievement-icons");
    }

    // ---- schema cache (SteamPlay thread) ----

    // stats/<id>/bits/<bit>/{name, display/{name/{lang}, desc/{lang}, icon}}
    static void remember(Context c, KeyValue stats) {
        try {
            JSONObject all = new JSONObject();
            for (KeyValue stat : stats.getChildren()) {
                for (KeyValue bit : stat.get("bits").getChildren()) {
                    String name = bit.get("name").getValue();
                    if (name == null) continue;
                    KeyValue disp = bit.get("display");
                    JSONObject e = new JSONObject();
                    e.put("t", langs(disp.get("name")));
                    e.put("d", langs(disp.get("desc")));
                    String icon = disp.get("icon").getValue();
                    if (icon != null) e.put("i", icon);
                    all.put(name, e);
                }
            }
            if (all.length() == 0) return;
            String text = all.toString();
            File f = metaFile(c);
            if (!f.isFile() || !text.equals(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8))) {
                Files.write(f.toPath(), text.getBytes(StandardCharsets.UTF_8));
                Log.i(TAG, "achievement names cached: " + all.length());
            }
            Thread t = new Thread(() -> fetchIcons(c, all), "BroSteam-icons");
            t.setDaemon(true);
            t.start();
        } catch (Throwable t) {
            Log.w(TAG, "achievement names not cached: " + t);
        }
    }

    private static JSONObject langs(KeyValue kv) throws Exception {
        JSONObject o = new JSONObject();
        for (KeyValue l : kv.getChildren()) if (l.getValue() != null) o.put(l.getName(), l.getValue());
        return o;
    }

    private static void fetchIcons(Context c, JSONObject all) {
        File dir = iconDir(c);
        dir.mkdirs();
        int got = 0;
        for (Iterator<String> it = all.keys(); it.hasNext(); ) {
            String icon = all.optJSONObject(it.next()).optString("i", "");
            if (!icon.matches("[0-9a-fA-F]{20,64}\\.(jpg|png)")) continue;
            File f = new File(dir, icon);
            if (f.isFile() && f.length() > 0) continue;
            try {
                HttpURLConnection h = (HttpURLConnection) new URL(ICON_URL + icon).openConnection();
                h.setConnectTimeout(10000);
                h.setReadTimeout(15000);
                if (h.getResponseCode() != 200) continue;
                File tmp = new File(dir, icon + ".part");
                try (InputStream in = h.getInputStream(); FileOutputStream o = new FileOutputStream(tmp)) {
                    byte[] b = new byte[16384];
                    for (int r; (r = in.read(b)) > 0; ) o.write(b, 0, r);
                }
                if (tmp.renameTo(f)) got++;
            } catch (Throwable t) {
                Log.w(TAG, "achievement icon " + icon + ": " + t);
                return; // offline or blocked: try again next time
            }
        }
        if (got > 0) Log.i(TAG, "achievement icons cached: " + got);
    }

    // ---- the notice (UI thread) ----

    static void show(Activity a, String name, String title, String desc, String locale) {
        queue.add(new String[] { name, title, desc, locale });
        if (!showing) next(a);
    }

    private static void next(Activity a) {
        String[] n = queue.poll();
        if (n == null || a.isFinishing() || a.isDestroyed()) {
            showing = false;
            queue.clear();
            return;
        }
        showing = true;
        try {
            present(a, n[0], n[1], n[2], n[3]);
        } catch (Throwable t) {
            Log.w(TAG, "achievement notice failed: " + t);
            showing = false;
        }
    }

    private static void present(Activity a, String name, String title, String desc, String locale) {
        String lang = steamLanguage(locale);
        String sTitle = null, sDesc = null;
        Bitmap icon = null;
        try {
            File f = metaFile(a);
            if (f.isFile()) {
                JSONObject e = new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8)).optJSONObject(name);
                if (e != null) {
                    sTitle = pick(e.optJSONObject("t"), lang);
                    sDesc = pick(e.optJSONObject("d"), lang);
                    String i = e.optString("i", "");
                    File img = new File(iconDir(a), i);
                    if (!i.isEmpty() && img.isFile()) icon = BitmapFactory.decodeFile(img.getPath());
                }
            }
        } catch (Throwable ignored) {
        }
        if (TextUtils.isEmpty(sTitle)) sTitle = TextUtils.isEmpty(title) ? name : title;
        if (TextUtils.isEmpty(sDesc)) sDesc = desc == null ? null : desc.replaceAll("\\[/?[a-zA-Z_]+(=[^\\]]*)?\\]", ""); // BBCode tags of the game's texts
        Log.i(TAG, "achievement notice: " + name + " \"" + sTitle + "\"");

        float dp = a.getResources().getDisplayMetrics().density;
        LinearLayout card = new LinearLayout(a);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[] { 0xF02A2F39, 0xF01B1E25 });
        bg.setCornerRadius(8 * dp);
        bg.setStroke(Math.max(1, Math.round(dp)), 0x40FFFFFF);
        card.setBackground(bg);
        card.setElevation(12 * dp);
        int pad = Math.round(10 * dp);
        card.setPadding(pad, pad, Math.round(16 * dp), pad);

        ImageView iv = new ImageView(a);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        if (icon != null) iv.setImageBitmap(icon);
        else {
            try {
                iv.setImageDrawable(a.getPackageManager().getApplicationIcon(a.getApplicationInfo()));
            } catch (Throwable ignored) {
            }
        }
        iv.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), 4 * dp);
            }
        });
        iv.setClipToOutline(true);
        int size = Math.round(56 * dp);
        LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(size, size);
        il.rightMargin = Math.round(12 * dp);
        card.addView(iv, il);

        LinearLayout texts = new LinearLayout(a);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView kicker = label(a, 12, 0xFF9BA6B3, false);
        kicker.setText(lang.equals("english") ? "Achievement Unlocked!" : "成就已解锁");
        TextView head = label(a, 15, Color.WHITE, true);
        head.setText(sTitle);
        texts.addView(kicker);
        texts.addView(head);
        if (!TextUtils.isEmpty(sDesc)) {
            TextView sub = label(a, 12, 0xFFB8C0CA, false);
            sub.setText(sDesc);
            texts.addView(sub);
        }
        card.addView(texts, new LinearLayout.LayoutParams(Math.round(250 * dp), ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.END);
        int margin = Math.round(20 * dp);
        lp.setMargins(margin, margin, margin, margin);
        ViewGroup root = a.findViewById(android.R.id.content);
        root.addView(card, lp);
        card.setAlpha(0f);
        card.post(() -> {
            float away = card.getHeight() + margin * 2f;
            // Rises from the bottom edge, and later goes back down the same way.
            card.setTranslationY(away);
            card.animate().translationY(0).alpha(1f).setDuration(320).setInterpolator(new DecelerateInterpolator(2f)).start();
            card.postDelayed(() -> card.animate().translationY(away).alpha(0f).setDuration(260).setInterpolator(new AccelerateInterpolator(1.5f))
                    .withEndAction(() -> {
                        root.removeView(card);
                        root.postDelayed(() -> next(a), 250);
                    }).start(), SHOW_MS);
        });
    }

    private static TextView label(Context c, int sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private static String pick(JSONObject o, String lang) {
        if (o == null) return null;
        String[] order = { lang, "schinese", "english" };
        for (String l : order) {
            String v = o.optString(l, "");
            if (!v.isEmpty()) return v;
        }
        Iterator<String> it = o.keys();
        return it.hasNext() ? o.optString(it.next(), null) : null;
    }

    // Godot locale ("zh", "zh_TW", "en", ...) to a Steam language name.
    private static String steamLanguage(String locale) {
        if (locale == null) locale = "";
        String l = locale.toLowerCase(java.util.Locale.ROOT);
        if (l.startsWith("zh")) return l.contains("tw") || l.contains("hk") || l.contains("hant") ? "tchinese" : "schinese";
        if (l.startsWith("ja")) return "japanese";
        if (l.startsWith("ko")) return "koreana";
        if (l.startsWith("fr")) return "french";
        if (l.startsWith("de")) return "german";
        if (l.startsWith("es")) return "spanish";
        if (l.startsWith("it")) return "italian";
        if (l.startsWith("pt")) return "brazilian";
        if (l.startsWith("ru")) return "russian";
        if (l.startsWith("pl")) return "polish";
        if (l.startsWith("tr")) return "turkish";
        return "english";
    }

    private AchievementToast() { }
}
