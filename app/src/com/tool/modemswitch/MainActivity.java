package com.tool.modemswitch;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.security.MessageDigest;
import java.util.ArrayList;

public class MainActivity extends Activity {

    static final String FWDIR = "/system/etc/firmware/";
    static final String NAME_TG = "modem_1_tg_n.img";
    static final String NAME_WG = "modem_1_wg_n.img";
    static final String MD5_WG = "6789082ad59d2967c89a392c61cf54a2";
    static final String MD5_TG = "497b0272a823640b0c4b6f031b3a6fa8";
    static final int IMG_LEN = 5005356;  // modem 分区内镜像定长，短镜像写入后尾部补零
    static final int BG = 0xFF0E1014, CARD = 0xFF171B22, TXT = 0xFFF2F5FA,
            SUB = 0xFF7A8394, BLUE = 0xFF2F6BFF, GREEN = 0xFF12B76A;

    Handler ui = new Handler(Looper.getMainLooper());
    TextView badge, badgeDev, tvName, tvSys, tvFw, log;
    Button bt, bw;
    ScrollView logScroll;
    boolean busy;
    volatile String sysKind;  // 系统分支：TG=移动版，WG=联通版

    int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    GradientDrawable gd(int color, float r) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(r));
        return g;
    }

    TextView tv(String s, int color, float sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    protected void onCreate(Bundle b) {
        super.onCreate(b);
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(BG);
        sv.setFillViewport(true);
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(20), dp(26), dp(20), dp(24));
        sv.addView(c, new ViewGroup.LayoutParams(-1, -2));
        setContentView(sv);

        TextView title = tv("华为 Y511 基带固件切换", TXT, 21, true);
        c.addView(title);

        LinearLayout card = mkCard(c, 16);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        badge = tv("--", Color.WHITE, 11, true);
        badge.setGravity(Gravity.CENTER);
        badge.setPadding(dp(9), dp(3), dp(9), dp(3));
        badge.setBackgroundDrawable(gd(SUB, 6));
        row.addView(badge);
        TextView l1 = tv("基带版本", SUB, 12, false);
        l1.setPadding(dp(10), 0, 0, 0);
        row.addView(l1);
        card.addView(row);

        tvFw = tv("读取中…", TXT, 11, true);
        tvFw.setTypeface(Typeface.MONOSPACE);
        tvFw.setPadding(0, dp(10), 0, 0);
        card.addView(tvFw);

        tvName = tv("--", SUB, 11, false);
        tvName.setPadding(0, dp(6), 0, 0);
        card.addView(tvName);

        LinearLayout card2 = mkCard(c, 12);

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setGravity(Gravity.CENTER_VERTICAL);
        badgeDev = tv("--", Color.WHITE, 11, true);
        badgeDev.setGravity(Gravity.CENTER);
        badgeDev.setPadding(dp(9), dp(3), dp(9), dp(3));
        badgeDev.setBackgroundDrawable(gd(SUB, 6));
        row2.addView(badgeDev);
        TextView l2 = tv("系统版本", SUB, 12, false);
        l2.setPadding(dp(10), 0, 0, 0);
        row2.addView(l2);
        card2.addView(row2);

        tvSys = tv("", SUB, 11, false);
        tvSys.setPadding(0, dp(9), 0, 0);
        card2.addView(tvSys);

        bt = mkBtn("切换到移动版 TG", BLUE);
        bt.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { ask(true); }
        });
        LinearLayout.LayoutParams p1 = new LinearLayout.LayoutParams(-1, dp(52));
        p1.topMargin = dp(18);
        c.addView(bt, p1);

        bw = mkBtn("切换到联通版 WG", GREEN);
        bw.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { ask(false); }
        });
        LinearLayout.LayoutParams p2 = new LinearLayout.LayoutParams(-1, dp(52));
        p2.topMargin = dp(12);
        c.addView(bw, p2);

        TextView lt = tv("执行日志", SUB, 11, false);
        lt.setPadding(dp(2), dp(20), 0, dp(6));
        c.addView(lt);

        logScroll = new ScrollView(this);
        log = tv("", 0xFF8A94A6, 11, false);
        log.setTypeface(Typeface.MONOSPACE);
        log.setTextIsSelectable(true);
        logScroll.addView(log);
        LinearLayout.LayoutParams p3 = new LinearLayout.LayoutParams(-1, dp(140));
        c.addView(logScroll, p3);
    }

    LinearLayout mkCard(LinearLayout parent, int topDp) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundDrawable(gd(CARD, 16));
        card.setPadding(dp(18), dp(16), dp(18), dp(16));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = dp(topDp);
        parent.addView(card, p);
        return card;
    }

    Button mkBtn(String s, final int color) {
        Button b = new Button(this);
        b.setText(s);
        b.setTextColor(Color.WHITE);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setAllCaps(false);
        b.setBackgroundDrawable(gd(color, 14));
        return b;
    }

    void append(final String s) {
        ui.post(new Runnable() {
            public void run() {
                log.append(s + "\n");
                logScroll.post(new Runnable() {
                    public void run() { logScroll.fullScroll(View.FOCUS_DOWN); }
                });
            }
        });
    }

    void setBusy(final boolean b) {
        ui.post(new Runnable() {
            public void run() {
                busy = b;
                bt.setEnabled(!b);
                bw.setEnabled(!b);
                bt.setAlpha(b ? 0.5f : 1f);
                bw.setAlpha(b ? 0.5f : 1f);
            }
        });
    }

    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    void refreshStatus() {
        new Thread(new Runnable() {
            public void run() {
                final File f = findModemImg();
                final boolean exists = f.exists();
                if (sysKind == null) sysKind = detectSystemType();
                final String sk = sysKind;
                final String[] vs;
                try {
                    vs = exists ? scanVersions(f) : null;
                    android.util.Log.i("MSW", "refresh " + f.getAbsolutePath() + " exists=" + exists
                            + " vs=" + (vs == null ? "null" : vs[0] + " | " + vs[1]));
                } catch (Throwable t) {
                    android.util.Log.e("MSW", "refresh failed", t);
                    ui.post(new Runnable() {
                        public void run() {
                            badge.setText("!");
                            tvFw.setText("读取失败");
                            tvName.setText("--");
                            tvSys.setText("");
                        }
                    });
                    return;
                }
                ui.post(new Runnable() {
                    public void run() {
                        String fwV = vs == null ? null : vs[0];
                        String fwT = mdType(fwV);
                        if ("TG".equals(fwT)) {
                            badge.setText("TG 移动版");
                            badge.setBackgroundDrawable(gd(BLUE, 6));
                        } else if ("WG".equals(fwT)) {
                            badge.setText("WG 联通版");
                            badge.setBackgroundDrawable(gd(GREEN, 6));
                        } else {
                            badge.setText("? 未知");
                            badge.setBackgroundDrawable(gd(0xFFB54708, 6));
                        }
                        tvFw.setText(fwV == null ? "无固件" : fwV);
                        tvName.setText(exists ? f.getName() : "文件不存在");
                        String scn = cnName(sk);
                        badgeDev.setText(scn == null ? "未知" : scn);
                        badgeDev.setBackgroundDrawable(gd(
                                sk == null ? 0xFFB54708 : ("TG".equals(sk) ? BLUE : GREEN), 6));
                        tvSys.setText("Android " + android.os.Build.VERSION.RELEASE
                                + " · " + android.os.Build.DISPLAY);
                    }
                });
            }
        }).start();
    }

    File findModemImg() {
        File dir = new File(FWDIR);
        File def = new File(dir, NAME_TG);
        if (def.exists()) return def;
        File[] fs = dir.listFiles();
        if (fs != null) {
            for (File x : fs) {
                String n = x.getName();
                if (n.startsWith("modem_") && n.endsWith(".img")) return x;
            }
        }
        return def;
    }

    /**
     * 按系统版本决定写入的文件名（与内置固件无关，只跟这台机的系统走）：
     * Y511-T00（移动/TDD）→ modem_1_tg_n.img；Y511-U00（联通/WCDMA）→ modem_1_wg_n.img。
     * 系统版本对不上表时，退回 firmware 目录里实际存在的 modem_*.img（那才是这台机真正加载的名字）。
     */
    String nameForSystem() {
        String d = (android.os.Build.DISPLAY + " " + android.os.Build.MODEL + " "
                + android.os.Build.DEVICE).toUpperCase(java.util.Locale.US);
        String cand = d.indexOf("U00") >= 0 ? NAME_WG
                : d.indexOf("T00") >= 0 ? NAME_TG : null;
        if (cand != null && new File(FWDIR, cand).exists()) return cand;
        File[] fs = new File(FWDIR).listFiles();
        if (fs != null) {
            for (File x : fs) {
                String n = x.getName();
                if (n.startsWith("modem_") && n.endsWith(".img")) return n;
            }
        }
        return cand == null ? NAME_TG : cand;
    }

    /**
     * 扫描镜像，返回 {固件数据区版本串, 文件尾镜像版本串}。
     * 数据区那串（约 0x4ab584）是 modem 真正上报的基带固件版本，
     * 尾部那串只是镜像 metadata，可以单独改掉伪装外壳。
     */
    String[] scanVersions(File f) {
        try {
            RandomAccessFile raf = new RandomAccessFile(f, "r");
            int n = (int) Math.min(f.length(), 8L * 1024 * 1024);
            byte[] buf = new byte[n];
            raf.readFully(buf);
            raf.close();
            String first = null, last = null;
            for (int i = 0; i + 6 < n; i++) {
                if (buf[i] == 'M' && buf[i + 1] == 'O' && buf[i + 2] == 'L' && buf[i + 3] == 'Y' && buf[i + 4] == '.') {
                    StringBuilder sb = new StringBuilder();
                    int j = i;
                    while (j < n && buf[j] >= 32 && buf[j] < 127 && sb.length() < 96) sb.append((char) buf[j++]);
                    String s = sb.toString();
                    if (s.startsWith("MOLY.WR8.")) {
                        if (first == null) first = s;
                        last = s;
                    }
                    i = j;
                }
            }
            return new String[]{first, last};
        } catch (Throwable t) {
            return null;
        }
    }

    String versionString(File f) {
        String[] a = scanVersions(f);
        return a == null ? null : a[1];
    }

    /** 从版本串判定 TD/GSM 制式标记（TG 移动 / WG 联通），以串内名称判断，不看文件名 */
    String mdType(String s) {
        if (s == null) return null;
        if (s.indexOf(".TG.") >= 0) return "TG";
        if (s.indexOf(".WG.") >= 0) return "WG";
        return null;
    }

    void ask(final boolean toTg) {
        if (busy) return;
        String imgType = toTg ? "TG" : "WG";
        String src = toTg ? NAME_TG : NAME_WG;
        String sysType = sysKind != null ? sysKind : detectSystemType();
        String dst = nameForSystem();
        String msg;
        if (sysType != null && !sysType.equals(imgType)) {
            msg = "当前为" + cnName(sysType) + "系统，将 " + imgType + " patch 为 " + sysType + " 后写入";
        } else {
            msg = "将写入原版 " + imgType + " 基带固件";
        }
        msg += "\n\n源文件：" + src + "\n目标文件：" + dst + "\n\n重启生效";
        final AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle(toTg ? "切换到移动版 TG" : "切换到联通版 WG")
                .setMessage(msg)
                .setNegativeButton("取消", null)
                .setPositiveButton("开始切换", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { doSwitch(toTg); }
                }).create();
        dlg.show();
        TextView mt = (TextView) dlg.findViewById(android.R.id.message);
        if (mt != null) {
            mt.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            mt.setLineSpacing(dp(3), 1f);
        }
        TextView dt = (TextView) dlg.findViewById(
                getResources().getIdentifier("alertTitle", "id", "android"));
        if (dt != null) dt.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
    }

    void doSwitch(final boolean toTg) {
        setBusy(true);
        log.setText("");
        append("=== 切换到 " + (toTg ? "移动版 (TG)" : "联通版 (WG)") + " ===");
        new Thread(new Runnable() {
            public void run() {
                try {
                    String assetName = toTg ? NAME_TG : NAME_WG;
                    String want = toTg ? MD5_TG : MD5_WG;
                    File tmp = new File(getFilesDir(), assetName);
                    copyAsset(assetName, tmp);
                    append("固件包: " + assetName + "  " + tmp.length() + " 字节（原版，包内不改动）");
                    String got = md5(tmp);
                    append("原版 md5: " + got);
                    if (!want.equals(got)) {
                        append("!! 固件校验失败");
                        finishFail();
                        return;
                    }
                    // 跨系统版本时只改这份副本：原版资产保持原样
                    String sysType = detectSystemType();
                    String imgType = detectImageType(tmp);
                    if (sysType != null && imgType != null && !imgType.equals(sysType)) {
                        append("跨版本: " + imgType + " 镜像装入 " + sysType + " 系统，改写副本标识");
                        if (patchImage(tmp, sysType)) {
                            append("补丁后 md5: " + md5(tmp));
                        } else {
                            append("!! 标识改写失败");
                            finishFail();
                            return;
                        }
                    } else if (imgType != null) {
                        append("版本一致: " + imgType + " 镜像 / "
                                + (sysType == null ? "系统未知，按原样" : sysType + " 系统") + "，原样写入");
                    }
                    String sysVer = android.os.Build.DISPLAY + "  (Android " + android.os.Build.VERSION.RELEASE + ")";
                    String dst = FWDIR + nameForSystem();
                    append("系统版本: " + sysVer);
                    append("写入为: " + dst);
                    File sh = new File(getFilesDir(), "switch.sh");
                    writeScript(sh, tmp.getAbsolutePath(), dst);
                    append("申请 root …");
                    String out = runRoot("sh " + sh.getAbsolutePath() + " 2>&1");
                    if (out != null && out.trim().length() > 0) append(out.trim());
                    boolean ok = out != null && out.contains("OK_REPLACED");
                    if (ok) {
                        String after = runRoot("md5 " + dst);
                        if (after != null) append("设备端: " + after.trim());
                        ui.post(new Runnable() {
                            public void run() { doneDialog(); }
                        });
                        refreshStatus();
                    } else {
                        append("!! 写入失败");
                        finishFail();
                    }
                } catch (Throwable t) {
                    append("!! 异常: " + t);
                    finishFail();
                } finally {
                    setBusy(false);
                }
            }
        }).start();
    }

    void finishFail() {
        ui.post(new Runnable() {
            public void run() {
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("失败")
                        .setMessage("固件未写入，请确认已授予 root 权限后重试。")
                        .setPositiveButton("好", null).show();
            }
        });
    }

    void doneDialog() {
        new AlertDialog.Builder(this)
                .setTitle("写入完成")
                .setMessage("固件已替换，重启后 modem 会加载新基带。")
                .setNegativeButton("稍后", null)
                .setPositiveButton("立即重启", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        append("重启中 …");
                        new Thread(new Runnable() {
                            public void run() { runRoot("reboot"); }
                        }).start();
                    }
                }).show();
    }

    /** TG=移动版，WG=联通版 */
    String cnName(String t) {
        if ("TG".equals(t)) return "移动版";
        if ("WG".equals(t)) return "联通版";
        return null;
    }

    /** 系统分支：T00(移动/TDD) → TG，U00(联通/WCDMA) → WG；识别不出返回 null */
    String detectSystemType() {
        String d = android.os.Build.DISPLAY;
        d = d == null ? "" : d.toUpperCase();
        if (d.contains("T00")) return "TG";
        if (d.contains("U00")) return "WG";
        String p = runRoot("cat /sys/kernel/ccci/md1_postfix 2>/dev/null");
        if (p != null) {
            String s = p.toLowerCase();
            if (s.contains("tg")) return "TG";
            if (s.contains("wg")) return "WG";
        }
        return null;
    }

    /** 镜像标识：取尾部最后一处 MOLY 版本串里的 MD.TG / MD.WG */
    String detectImageType(File f) {
        try {
            RandomAccessFile raf = new RandomAccessFile(f, "r");
            long len = raf.length();
            int win = (int) Math.min(1024, len);
            byte[] b = new byte[win];
            raf.seek(len - win);
            raf.read(b, 0, win);
            raf.close();
            String s = new String(b, "ISO-8859-1");
            int i = s.lastIndexOf("MOLY.");
            if (i < 0) return null;
            String t = s.substring(i);
            if (t.contains("MD.TG.")) return "TG";
            if (t.contains("MD.WG.")) return "WG";
        } catch (Throwable ignore) {
        }
        return null;
    }

    /**
     * 只改写拷贝出来的副本：版本串 MD.WG/MD.TG 标识 + 尾部 16 位字段（TG=0x00c0，WG=0x0120）。
     * 射频校准、DSP、协议栈数据一律不动，APK 内原版也不动。
     */
    boolean patchImage(File f, String toType) {
        try {
            RandomAccessFile raf = new RandomAccessFile(f, "rw");
            long len = raf.length();
            int win = (int) Math.min(1024, len);
            byte[] b = new byte[win];
            raf.seek(len - win);
            raf.read(b, 0, win);
            String s = new String(b, "ISO-8859-1");
            int i = s.lastIndexOf("MOLY.");
            int k = i < 0 ? -1 : s.indexOf("MD.", i);
            if (k < 0 || k + 3 >= s.length()) {
                raf.close();
                return false;
            }
            char want = "TG".equals(toType) ? 'T' : 'W';
            int pos = k + 3;  // "MD." 后面的 W/T
            if (s.charAt(pos) != want) {
                raf.seek(len - win + pos);
                raf.write(want);
            }
            int v = "TG".equals(toType) ? 0x00c0 : 0x0120;
            raf.seek(len - 14);
            raf.write(v & 0xFF);
            raf.write((v >> 8) & 0xFF);
            raf.close();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    void writeScript(File f, String src, String dst) throws Exception {
        String s = "#!/system/bin/sh\n"
                + "mount -o rw,remount /system 2>&1\n"
                + "cp -f " + src + " " + dst + " 2>&1 || exit 2\n"
                + "SZ=$(wc -c < " + dst + ")\n"
                + "if [ \"$SZ\" -lt " + IMG_LEN + " ]; then "
                + "dd if=/dev/zero bs=1 count=$(( " + IMG_LEN + " - SZ )) seek=$SZ of=" + dst + " 2>&1; fi\n"
                + "chown 0:0 " + dst + " 2>&1\n"
                + "chmod 644 " + dst + " 2>&1\n"
                + "sync\n"
                + "echo OK_REPLACED " + dst + "\n";
        FileOutputStream o = new FileOutputStream(f);
        o.write(s.getBytes("UTF-8"));
        o.close();
    }

    String runRoot(String cmd) {
        String r = exec(new String[]{"su", "0", "sh", "-c", cmd});
        if (r == null || r.trim().length() == 0) {
            String r2 = exec(new String[]{"su", "-c", cmd});
            if (r2 != null && r2.trim().length() > 0) return r2;
        }
        return r;
    }

    String exec(String[] argv) {
        try {
            ProcessBuilder pb = new ProcessBuilder(argv);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            p.getOutputStream().close();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            InputStream in = p.getInputStream();
            byte[] buf = new byte[4096];
            int k;
            while ((k = in.read(buf)) > 0) bo.write(buf, 0, k);
            in.close();
            p.waitFor();
            return bo.toString("UTF-8");
        } catch (Throwable t) {
            return "exec error: " + t;
        }
    }

    void copyAsset(String name, File dst) throws Exception {
        if (dst.exists() && dst.length() > 0) dst.delete();
        InputStream in = getAssets().open(name);
        FileOutputStream o = new FileOutputStream(dst);
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
        o.close();
        in.close();
    }

    String md5(File f) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            FileInputStream in = new FileInputStream(f);
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            in.close();
            StringBuilder sb = new StringBuilder();
            for (byte x : md.digest()) sb.append(String.format("%02x", x));
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }
}