package net.kdt.pojavlaunch.th;

import android.app.AlertDialog;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import net.kdt.pojavlaunch.PojavApplication;
import net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

/** Search Modrinth and install mods (with required dependencies) into the selected profile's mods folder. */
public class ModrinthBrowserFragment extends Fragment {
    public static final String TAG = "ThModrinthBrowser";
    private LinearLayout mResults;
    private TextView mStatus;
    private EditText mQuery;
    private Button mMore;
    private MinecraftProfile mProfile;
    private String mGame, mLoader;
    private int mOffset;
    private int mSearchId;

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle state) {
        LauncherProfiles.load();
        mProfile = LauncherProfiles.getCurrentProfile();
        String[] gl = ThTools.gameAndLoader(mProfile);
        mGame = gl[0];
        mLoader = gl[1];

        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(12), dp(12), dp(4));

        TextView title = new TextView(requireContext());
        title.setText("📦 ม็อดจาก Modrinth");
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView info = new TextView(requireContext());
        info.setText("โปรไฟล์: " + mProfile.name + "  •  " + (mGame == null ? "?" : mGame) + "  •  "
                + (mLoader == null ? "⚠️ ยังไม่มีตัวโหลดม็อด (สร้างโปรไฟล์ Fabric ก่อน)" : mLoader));
        info.setAlpha(0.8f);
        info.setPadding(0, dp(4), 0, dp(8));
        root.addView(info);

        LinearLayout bar = new LinearLayout(requireContext());
        bar.setGravity(Gravity.CENTER_VERTICAL);
        mQuery = new EditText(requireContext());
        mQuery.setHint("ค้นหาม็อด เช่น jei, waystones");
        mQuery.setSingleLine(true);
        mQuery.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        mQuery.setOnEditorActionListener((v, id, ev) -> {
            boolean enter = ev != null && ev.getKeyCode() == KeyEvent.KEYCODE_ENTER && ev.getAction() == KeyEvent.ACTION_DOWN;
            if (id == EditorInfo.IME_ACTION_SEARCH || enter) { search(true); return true; }
            return false;
        });
        bar.addView(mQuery, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button go = new Button(requireContext());
        go.setText("🔍");
        go.setOnClickListener(v -> search(true));
        bar.addView(go);
        Button installed = new Button(requireContext());
        installed.setText("📂");
        installed.setOnClickListener(v -> showInstalled());
        bar.addView(installed);
        root.addView(bar);

        mStatus = new TextView(requireContext());
        mStatus.setPadding(0, dp(4), 0, dp(4));
        mStatus.setAlpha(0.8f);
        root.addView(mStatus);

        ScrollView sv = new ScrollView(requireContext());
        LinearLayout inner = new LinearLayout(requireContext());
        inner.setOrientation(LinearLayout.VERTICAL);
        mResults = new LinearLayout(requireContext());
        mResults.setOrientation(LinearLayout.VERTICAL);
        inner.addView(mResults);
        mMore = new Button(requireContext());
        mMore.setText("โหลดเพิ่ม");
        mMore.setVisibility(View.GONE);
        mMore.setOnClickListener(v -> search(false));
        inner.addView(mMore);
        sv.addView(inner);
        root.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        search(true);
        return root;
    }

    private void search(boolean fresh) {
        if (fresh) { mOffset = 0; mResults.removeAllViews(); }
        final int id = ++mSearchId;
        final String q = mQuery.getText().toString();
        mStatus.setText("กำลังค้นหา...");
        mMore.setVisibility(View.GONE);
        PojavApplication.sExecutorService.execute(() -> {
            JSONArray hits = null;
            String err = null;
            try { hits = ThTools.search(q, mGame, mLoader, mOffset); }
            catch (Exception e) { Log.e(TAG, "search", e); err = e.getMessage(); }
            final JSONArray res = hits;
            final String error = err;
            if (getActivity() == null) return;
            requireActivity().runOnUiThread(() -> {
                if (id != mSearchId || !isAdded()) return;
                if (res == null) { mStatus.setText("❌ ค้นหาไม่ได้: " + error); return; }
                mOffset += res.length();
                mStatus.setText(mOffset == 0 ? "ไม่พบม็อดที่ใช้กับโปรไฟล์นี้ได้" : "พบ " + mOffset + " รายการ" + (TextUtils.isEmpty(q) ? " (ยอดนิยม)" : ""));
                for (int i = 0; i < res.length(); i++) addRow(res.optJSONObject(i));
                mMore.setVisibility(res.length() >= 20 ? View.VISIBLE : View.GONE);
            });
        });
    }

    private void addRow(JSONObject hit) {
        if (hit == null) return;
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(8), 0, dp(8));
        LinearLayout text = new LinearLayout(requireContext());
        text.setOrientation(LinearLayout.VERTICAL);
        TextView name = new TextView(requireContext());
        name.setText(hit.optString("title"));
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setTextSize(16);
        text.addView(name);
        TextView desc = new TextView(requireContext());
        desc.setText(hit.optString("description"));
        desc.setMaxLines(2);
        desc.setEllipsize(TextUtils.TruncateAt.END);
        desc.setAlpha(0.85f);
        text.addView(desc);
        TextView meta = new TextView(requireContext());
        meta.setText("⬇ " + shortNum(hit.optLong("downloads")) + "  •  " + hit.optString("author"));
        meta.setTextSize(12);
        meta.setAlpha(0.6f);
        text.addView(meta);
        row.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button btn = new Button(requireContext());
        btn.setText("ลง");
        btn.setOnClickListener(v -> install(hit.optString("project_id"), hit.optString("title"), btn));
        row.addView(btn);
        mResults.addView(row);
    }

    private static String shortNum(long n) {
        if (n >= 1_000_000) return String.format(Locale.ROOT, "%.1fM", n / 1e6);
        if (n >= 1_000) return String.format(Locale.ROOT, "%.1fK", n / 1e3);
        return String.valueOf(n);
    }

    private void install(String projectId, String title, Button btn) {
        btn.setEnabled(false);
        btn.setText("⏳");
        File mods = ThTools.modsDir(mProfile);
        PojavApplication.sExecutorService.execute(() -> {
            List<File> files = null;
            String err = null;
            try { files = ThTools.installWithDeps(projectId, mGame, mLoader, mods, new HashSet<>(), null); }
            catch (Exception e) { Log.e(TAG, "install", e); err = e.getMessage(); }
            final List<File> done = files;
            final String error = err;
            if (getActivity() == null) return;
            requireActivity().runOnUiThread(() -> {
                if (!isAdded()) return;
                if (done != null) {
                    btn.setText("✓");
                    Toast.makeText(requireContext(), "✅ ลง " + title + (done.size() > 1 ? " + ไลบรารีอีก " + (done.size() - 1) + " ไฟล์" : ""), Toast.LENGTH_LONG).show();
                } else {
                    btn.setEnabled(true);
                    btn.setText("ลง");
                    Toast.makeText(requireContext(), "❌ " + error, Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    private void showInstalled() {
        File mods = ThTools.modsDir(mProfile);
        File[] list = mods.listFiles((d, n) -> n.endsWith(".jar") || n.endsWith(".jar.disabled"));
        if (list == null || list.length == 0) { Toast.makeText(requireContext(), "ยังไม่มีม็อดในโปรไฟล์นี้", Toast.LENGTH_SHORT).show(); return; }
        Arrays.sort(list);
        String[] names = new String[list.length];
        for (int i = 0; i < list.length; i++) names[i] = (list[i].getName().endsWith(".disabled") ? "⏸ " : "✅ ") + list[i].getName();
        new AlertDialog.Builder(requireContext())
                .setTitle("ม็อดที่ลงแล้ว (" + list.length + ") — แตะเพื่อเปิด/ปิด/ลบ")
                .setItems(names, (d, which) -> manage(list[which]))
                .setNegativeButton(android.R.string.cancel, null).show();
    }

    private void manage(File f) {
        boolean disabled = f.getName().endsWith(".disabled");
        new AlertDialog.Builder(requireContext())
                .setTitle(f.getName())
                .setPositiveButton(disabled ? "เปิดใช้" : "ปิดชั่วคราว", (d, w) -> {
                    String n = disabled ? f.getName().substring(0, f.getName().length() - 9) : f.getName() + ".disabled";
                    boolean ok = f.renameTo(new File(f.getParentFile(), n));
                    Toast.makeText(requireContext(), ok ? "เรียบร้อย" : "ทำไม่สำเร็จ", Toast.LENGTH_SHORT).show();
                })
                .setNeutralButton("ลบ", (d, w) -> {
                    boolean ok = f.delete();
                    Toast.makeText(requireContext(), ok ? "ลบแล้ว" : "ลบไม่สำเร็จ", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(android.R.string.cancel, null).show();
    }
}
