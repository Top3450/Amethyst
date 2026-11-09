package net.kdt.pojavlaunch.th;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import android.widget.TextView;
import android.widget.Toast;

import androidx.fragment.app.FragmentActivity;

import net.kdt.pojavlaunch.PojavApplication;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.extra.ExtraConstants;
import net.kdt.pojavlaunch.extra.ExtraCore;
import net.kdt.pojavlaunch.modloaders.FabricVersion;
import net.kdt.pojavlaunch.modloaders.FabriclikeDownloadTask;
import net.kdt.pojavlaunch.modloaders.FabriclikeUtils;
import net.kdt.pojavlaunch.modloaders.ModloaderDownloadListener;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.utils.DownloadUtils;
import net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Amethyst TH extras: one-tap Immersive Portals, Modrinth mod installs and a light preset for phones. */
public final class ThTools {
    private static final String TAG = "AmethystTH";
    public static final String IP_PROFILE_KEY = "th_immersive_portals";
    public static final String IP_GAME_VERSION = "1.21.1";
    private static final String MODRINTH = "https://api.modrinth.com/v2";

    private ThTools() { }

    public interface Progress { void update(String message); }

    // ---------------- menu ----------------
    public static void showMenu(FragmentActivity a) {
        String[] items = {
                "🌀 ติดตั้ง Immersive Portals (1.21.1)",
                "📦 ค้นหาและลงม็อดจาก Modrinth",
                "⚙️ ตั้งค่าเบาสุดสำหรับมือถือ"
        };
        new AlertDialog.Builder(a)
                .setTitle("🛠 เครื่องมือ TH")
                .setItems(items, (d, which) -> {
                    if (which == 0) confirmImmersivePortals(a);
                    else if (which == 1) {
                        if (!Tools.hasOnlineProfile()) { Tools.hasNoOnlineProfileDialog(a); return; }
                        Tools.swapFragment(a, ModrinthBrowserFragment.class, ModrinthBrowserFragment.TAG, null);
                    } else confirmLite(a);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // ---------------- profile helpers ----------------
    /** Returns {gameVersion, loader}; loader is fabric, quilt, forge, neoforge or null for vanilla. */
    public static String[] gameAndLoader(MinecraftProfile p) {
        String v = p == null || p.lastVersionId == null ? "" : p.lastVersionId;
        String low = v.toLowerCase(Locale.ROOT);
        if (low.startsWith("fabric-loader-")) return new String[]{v.substring(v.lastIndexOf('-') + 1), "fabric"};
        if (low.startsWith("quilt-loader-")) return new String[]{v.substring(v.lastIndexOf('-') + 1), "quilt"};
        if (low.startsWith("neoforge-")) {
            String[] parts = v.substring(9).split("\\.");
            if (parts.length >= 2) return new String[]{"1." + parts[0] + ("0".equals(parts[1]) ? "" : "." + parts[1]), "neoforge"};
        }
        if (low.contains("-forge-")) return new String[]{v.substring(0, low.indexOf("-forge-")), "forge"};
        if (MinecraftProfile.LATEST_RELEASE.equals(v) || MinecraftProfile.LATEST_SNAPSHOT.equals(v)) return new String[]{null, null};
        return new String[]{v, null};
    }

    public static File modsDir(MinecraftProfile p) {
        File d = new File(Tools.getGameDirPath(p), "mods");
        if (!d.isDirectory() && !d.mkdirs()) Log.w(TAG, "mkdirs failed " + d);
        return d;
    }

    private static void selectProfile(String key) {
        LauncherPreferences.DEFAULT_PREF.edit().putString(LauncherPreferences.PREF_KEY_CURRENT_PROFILE, key).apply();
        ExtraCore.setValue(ExtraConstants.REFRESH_VERSION_SPINNER, key);
    }

    /** First renderer from the wish list that this device and build support. */
    public static String pickRenderer(Context c, String... wish) {
        List<String> ok = Tools.getCompatibleRenderers(c).rendererIds;
        for (String w : wish) if (ok.contains(w)) return w;
        return ok.isEmpty() ? null : ok.get(0);
    }

    // ---------------- options.txt ----------------
    public static void mergeOptions(File gameDir, Map<String, String> values) throws IOException {
        File f = new File(gameDir, "options.txt");
        LinkedHashMap<String, String> all = new LinkedHashMap<>();
        if (f.isFile()) {
            for (String line : Tools.read(f.getAbsolutePath()).split("\n")) {
                int i = line.indexOf(':');
                if (i > 0) all.put(line.substring(0, i), line.substring(i + 1).trim());
            }
        }
        all.putAll(values);
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : all.entrySet()) sb.append(e.getKey()).append(':').append(e.getValue()).append('\n');
        if (!gameDir.isDirectory() && !gameDir.mkdirs()) throw new IOException("cannot create " + gameDir);
        Tools.write(f.getAbsolutePath(), sb.toString());
    }

    private static Map<String, String> liteOptions(int renderDistance) {
        LinkedHashMap<String, String> m = new LinkedHashMap<>();
        m.put("renderDistance", String.valueOf(renderDistance));
        m.put("simulationDistance", "5");
        m.put("graphicsMode", "0");
        m.put("maxFps", "60");
        m.put("enableVsync", "false");
        m.put("particles", "2");
        m.put("entityShadows", "false");
        m.put("mipmapLevels", "0");
        m.put("biomeBlendRadius", "0");
        m.put("renderClouds", "\"false\"");
        m.put("ao", "false");
        m.put("entityDistanceScaling", "0.5");
        m.put("prioritizeChunkUpdates", "0");
        m.put("showAutosaveIndicator", "false");
        return m;
    }

    /** Launcher-side settings: lower internal resolution, steady clocks, big cores, sensible RAM. */
    private static void liteLauncherPrefs(Context c, int minRamMb) {
        ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        int totalMb = (int) (mi.totalMem / 1048576L);
        int ram = Math.max(minRamMb, Math.min(4096, totalMb / 3));
        ram = Math.min(ram, Math.max(1024, totalMb / 2));
        SharedPreferences.Editor ed = LauncherPreferences.DEFAULT_PREF.edit();
        ed.putInt("resolutionRatio", 70);
        ed.putBoolean("sustainedPerformance", true);
        ed.putBoolean("bigCoreAffinity", true);
        ed.putInt("allocation", ram);
        ed.apply();
        LauncherPreferences.loadPreferences(c);
    }

    private static void confirmLite(FragmentActivity a) {
        MinecraftProfile p = LauncherProfiles.getCurrentProfile();
        new AlertDialog.Builder(a)
                .setTitle("⚙️ ตั้งค่าเบาสุด")
                .setMessage("ปรับโปรไฟล์ \"" + p.name + "\":\n• ระยะมองเห็น 6, จำลอง 5, กราฟิกเร็ว, ไม่มีเงา/เมฆ/อนุภาคน้อย\n• ความละเอียดภายใน 70%, ล็อก FPS 60\n• เลือกตัววาดภาพที่เร็วสุดของเครื่องนี้\n• ใช้คอร์ CPU ตัวแรง + จัด RAM ให้พอดี")
                .setPositiveButton("ปรับเลย", (d, w) -> applyLite(a))
                .setNegativeButton(android.R.string.cancel, null).show();
    }

    public static void applyLite(Activity a) {
        try {
            LauncherProfiles.load();
            String key = LauncherPreferences.DEFAULT_PREF.getString(LauncherPreferences.PREF_KEY_CURRENT_PROFILE, null);
            MinecraftProfile p = key == null ? null : LauncherProfiles.mainProfileJson.profiles.get(key);
            if (p != null) {
                String r = pickRenderer(a, "opengles_mobileglues", "opengles2", "opengles3_desktopgl_zink_kopper");
                if (r != null) p.pojavRendererName = r;
                LauncherProfiles.write();
                ExtraCore.setValue(ExtraConstants.REFRESH_VERSION_SPINNER, key);
            }
            mergeOptions(p == null ? new File(Tools.DIR_GAME_NEW) : Tools.getGameDirPath(p), liteOptions(6));
            liteLauncherPrefs(a, 2048);
            Toast.makeText(a, "✅ ปรับเป็นโหมดเบาสุดแล้ว", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Log.e(TAG, "lite", e);
            Toast.makeText(a, "❌ ปรับไม่สำเร็จ: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    // ---------------- Immersive Portals one-tap ----------------
    private static void confirmImmersivePortals(FragmentActivity a) {
        if (!Tools.hasOnlineProfile()) { Tools.hasNoOnlineProfileDialog(a); return; }
        new AlertDialog.Builder(a)
                .setTitle("🌀 Immersive Portals")
                .setMessage("จะสร้างโปรไฟล์ใหม่ (โฟลเดอร์แยก ไม่ยุ่งกับโลกเดิม):\n• Fabric " + IP_GAME_VERSION + " + Fabric API + Immersive Portals (+ไลบรารีที่ต้องใช้)\n• ตัววาดภาพ Zink (แนะนำสำหรับม็อดนี้)\n• ตั้งค่าเบาสุดสำหรับมือถือ\n\nต้องใช้อินเทอร์เน็ตโหลดประมาณ 20–30 MB (ตัวเกมจะโหลดเพิ่มตอนกดเล่นครั้งแรก)")
                .setPositiveButton("ติดตั้ง", (d, w) -> installImmersivePortals(a))
                .setNegativeButton(android.R.string.cancel, null).show();
    }

    public static void installImmersivePortals(FragmentActivity a) {
        TextView msg = new TextView(a);
        msg.setPadding(48, 32, 48, 16);
        msg.setText("กำลังเริ่ม...");
        AlertDialog dlg = new AlertDialog.Builder(a).setTitle("🌀 กำลังติดตั้ง").setView(msg).setCancelable(false).show();
        Progress pr = s -> a.runOnUiThread(() -> msg.setText(s));
        PojavApplication.sExecutorService.execute(() -> {
            String error = null;
            try {
                pr.update("หา Fabric Loader สำหรับ " + IP_GAME_VERSION + "...");
                FabricVersion[] loaders = FabriclikeUtils.FABRIC_UTILS.downloadLoaderVersions(IP_GAME_VERSION);
                if (loaders == null || loaders.length == 0) throw new IOException("ไม่พบ Fabric Loader");
                String loader = loaders[0].version;
                for (FabricVersion v : loaders) if (v.stable) { loader = v.version; break; }

                pr.update("ติดตั้ง Fabric " + loader + "...");
                final String[] fail = {null};
                new FabriclikeDownloadTask(new ModloaderDownloadListener() {
                    @Override public void onDownloadFinished(File downloadedFile) { }
                    @Override public void onDataNotAvailable() { fail[0] = "ข้อมูล Fabric ไม่พร้อม"; }
                    @Override public void onDownloadError(Exception e) { fail[0] = String.valueOf(e.getMessage()); }
                }, FabriclikeUtils.FABRIC_UTILS, IP_GAME_VERSION, loader, false).run();
                if (fail[0] != null) throw new IOException(fail[0]);

                LauncherProfiles.load();
                MinecraftProfile p = LauncherProfiles.mainProfileJson.profiles.get(IP_PROFILE_KEY);
                if (p == null) p = new MinecraftProfile();
                p.name = "🌀 Immersive Portals";
                p.lastVersionId = "fabric-loader-" + loader + "-" + IP_GAME_VERSION;
                p.icon = FabriclikeUtils.FABRIC_UTILS.getIconName();
                p.gameDir = "immersive_portals";
                String r = pickRenderer(a, "opengles3_desktopgl_zink_kopper", "opengles_mobileglues", "opengles2");
                if (r != null) p.pojavRendererName = r;
                LauncherProfiles.mainProfileJson.profiles.put(IP_PROFILE_KEY, p);
                LauncherProfiles.write();

                File mods = modsDir(p);
                Set<String> seen = new HashSet<>();
                for (String slug : new String[]{"fabric-api", "immersiveportals"})
                    installWithDeps(slug, IP_GAME_VERSION, "fabric", mods, seen, pr);

                pr.update("ตั้งค่าให้เบาสำหรับมือถือ...");
                LinkedHashMap<String, String> opt = new LinkedHashMap<>(liteOptions(6));
                mergeOptions(Tools.getGameDirPath(p), opt);
                liteLauncherPrefs(a, 3072);
            } catch (Exception e) {
                Log.e(TAG, "ip install", e);
                error = e.getMessage() == null ? e.toString() : e.getMessage();
            }
            final String err = error;
            a.runOnUiThread(() -> {
                dlg.dismiss();
                if (err == null) {
                    selectProfile(IP_PROFILE_KEY);
                    new AlertDialog.Builder(a).setTitle("✅ พร้อมเล่น")
                            .setMessage("เลือกโปรไฟล์ \"🌀 Immersive Portals\" ให้แล้ว กด เล่น ได้เลย\n\nถ้าจอดำหรือเด้งออก: แตะไอคอนแก้โปรไฟล์ แล้วเปลี่ยนตัววาดภาพเป็น MobileGlues")
                            .setPositiveButton(android.R.string.ok, null).show();
                } else {
                    new AlertDialog.Builder(a).setTitle("❌ ติดตั้งไม่สำเร็จ").setMessage(err + "\n\nเช็กอินเทอร์เน็ตแล้วลองใหม่ — ไฟล์ที่โหลดแล้วจะไม่โหลดซ้ำ")
                            .setPositiveButton(android.R.string.ok, null).show();
                }
            });
        });
    }

    // ---------------- Modrinth ----------------
    private static String enc(String s) {
        try { return URLEncoder.encode(s, "UTF-8"); } catch (Exception e) { return s; }
    }

    public static JSONArray search(String query, String game, String loader, int offset) throws IOException, JSONException {
        StringBuilder facets = new StringBuilder("[[\"project_type:mod\"]");
        if (loader != null) facets.append(",[\"categories:").append(loader).append("\"]");
        if (game != null) facets.append(",[\"versions:").append(game).append("\"]");
        facets.append(']');
        String url = MODRINTH + "/search?limit=20&offset=" + offset + "&index=" + (query.trim().isEmpty() ? "downloads" : "relevance")
                + "&query=" + enc(query.trim()) + "&facets=" + enc(facets.toString());
        return new JSONObject(DownloadUtils.downloadString(url)).getJSONArray("hits");
    }

    /** Newest version of a project for this game version + loader (release preferred), or null. */
    public static JSONObject bestVersion(String project, String game, String loader) throws IOException, JSONException {
        StringBuilder url = new StringBuilder(MODRINTH + "/project/" + enc(project) + "/version?");
        if (loader != null) url.append("loaders=").append(enc("[\"" + loader + "\"]")).append('&');
        if (game != null) url.append("game_versions=").append(enc("[\"" + game + "\"]"));
        JSONArray arr = new JSONArray(DownloadUtils.downloadString(url.toString()));
        if (arr.length() == 0) return null;
        for (int i = 0; i < arr.length(); i++) if ("release".equals(arr.getJSONObject(i).optString("version_type"))) return arr.getJSONObject(i);
        return arr.getJSONObject(0);
    }

    /** Downloads a project into mods/ along with its required dependencies; returns the files written. */
    public static List<File> installWithDeps(String project, String game, String loader, File mods, Set<String> seen, Progress pr)
            throws IOException, JSONException {
        List<File> out = new ArrayList<>();
        if (!seen.add(project)) return out;
        if (pr != null) pr.update("หาไฟล์ " + project + "...");
        JSONObject ver = bestVersion(project, game, loader);
        if (ver == null) throw new IOException(project + " ยังไม่มีเวอร์ชันสำหรับ " + (loader == null ? "" : loader + " ") + (game == null ? "" : game));
        seen.add(ver.optString("project_id"));
        installVersion(ver, game, loader, mods, seen, pr, out);
        return out;
    }

    private static void installVersion(JSONObject ver, String game, String loader, File mods, Set<String> seen, Progress pr, List<File> out)
            throws IOException, JSONException {
        JSONArray files = ver.getJSONArray("files");
        JSONObject file = files.getJSONObject(0);
        for (int i = 0; i < files.length(); i++) if (files.getJSONObject(i).optBoolean("primary")) { file = files.getJSONObject(i); break; }
        String name = file.getString("filename").replaceAll("[\\\\/:]", "_");
        File dst = new File(mods, name);
        if (!dst.isFile()) {
            if (pr != null) pr.update("โหลด " + name + "...");
            File tmp = new File(mods, name + ".part");
            DownloadUtils.downloadFile(file.getString("url"), tmp);
            if (!tmp.renameTo(dst)) throw new IOException("บันทึก " + name + " ไม่ได้");
        }
        out.add(dst);
        JSONArray deps = ver.optJSONArray("dependencies");
        if (deps == null) return;
        for (int i = 0; i < deps.length(); i++) {
            JSONObject d = deps.getJSONObject(i);
            if (!"required".equals(d.optString("dependency_type"))) continue;
            String vid = d.optString("version_id", ""), pid = d.optString("project_id", "");
            if (!pid.isEmpty() && seen.contains(pid)) continue;
            if (!vid.isEmpty() && !"null".equals(vid)) {
                JSONObject dv = new JSONObject(DownloadUtils.downloadString(MODRINTH + "/version/" + enc(vid)));
                if (!seen.add(dv.optString("project_id", vid))) continue;
                installVersion(dv, game, loader, mods, seen, pr, out);
            } else if (!pid.isEmpty() && !"null".equals(pid)) {
                out.addAll(installWithDeps(pid, game, loader, mods, seen, pr));
            }
        }
    }
}
