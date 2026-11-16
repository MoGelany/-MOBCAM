package com.jilani.mobcam;

import android.app.Activity;
import android.app.Dialog;
import android.content.ContentValues;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.MediaController;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Card browser + full-screen photo/video viewer on the phone. */
public class GalleryUi {

    private static final int YELLOW = Color.rgb(255, 214, 10);

    private final Activity a;
    private final CameraLive live;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable onClose;

    public GalleryUi(Activity a, CameraLive live, Runnable onClose) {
        this.a = a;
        this.live = live;
        this.onClose = onClose;
    }

    private int dp(float v) {
        return Math.round(v * a.getResources().getDisplayMetrics().density);
    }

    private TextView pillButton(String text) {
        TextView b = new TextView(a);
        b.setText(text);
        b.setTextColor(Color.WHITE);
        b.setTextSize(14);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(14), dp(8), dp(14), dp(8));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.argb(200, 40, 40, 40));
        bg.setCornerRadius(dp(10));
        b.setBackground(bg);
        return b;
    }

    private Dialog fullScreen() {
        Dialog d = new Dialog(a, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        d.setOnDismissListener(x -> { if (onClose != null) onClose.run(); });
        return d;
    }

    // ------------------------------------------------------------------ grid

    public void showGallery() {
        final Dialog d = fullScreen();
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);

        LinearLayout top = new LinearLayout(a);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(12), dp(10), dp(12), dp(10));
        final TextView title = new TextView(a);
        title.setText("الكارت — بحمّل…");
        title.setTextColor(YELLOW);
        title.setTextSize(17);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        top.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        TextView close = pillButton("رجوع للمونيتور");
        close.setOnClickListener(v -> d.dismiss());
        top.addView(close);
        root.addView(top);

        final List<PtpMedia.Item> items = new ArrayList<>();
        final GridView grid = new GridView(a);
        grid.setNumColumns(GridView.AUTO_FIT);
        grid.setColumnWidth(dp(150));
        grid.setHorizontalSpacing(dp(4));
        grid.setVerticalSpacing(dp(4));
        grid.setPadding(dp(4), 0, dp(4), dp(4));
        final BaseAdapter ad = new BaseAdapter() {
            @Override public int getCount() { return items.size(); }
            @Override public Object getItem(int i) { return items.get(i); }
            @Override public long getItemId(int i) { return i; }
            @Override public View getView(int i, View cv, ViewGroup parent) {
                FrameLayout f = (FrameLayout) cv;
                if (f == null) {
                    f = new FrameLayout(a);
                    f.setLayoutParams(new GridView.LayoutParams(-1, dp(110)));
                    f.setBackgroundColor(Color.rgb(25, 25, 25));
                    ImageView iv = new ImageView(a);
                    iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    f.addView(iv, new FrameLayout.LayoutParams(-1, -1));
                    TextView tv = new TextView(a);
                    tv.setTextColor(Color.WHITE);
                    tv.setTextSize(11);
                    tv.setShadowLayer(4, 0, 0, Color.BLACK);
                    tv.setPadding(dp(5), dp(3), dp(5), dp(3));
                    f.addView(tv, new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.LEFT));
                }
                PtpMedia.Item it = items.get(i);
                ((ImageView) f.getChildAt(0)).setImageBitmap(it.thumb);
                ((TextView) f.getChildAt(1)).setText((it.video ? "▶ " : "") + it.name);
                return f;
            }
        };
        grid.setAdapter(ad);
        grid.setOnItemClickListener((p, v, i, id) -> open(items.get(i)));
        root.addView(grid, new LinearLayout.LayoutParams(-1, 0, 1));
        d.setContentView(root);
        d.show();

        live.submit(t -> {
            final List<PtpMedia.Item> got;
            try {
                got = PtpMedia.list(t, 60);
            } catch (Exception e) {
                ui.post(() -> title.setText(e.getMessage() != null ? e.getMessage() : "مقدرتش أقرا الكارت"));
                return;
            }
            ui.post(() -> {
                items.clear();
                items.addAll(got);
                title.setText(got.isEmpty() ? "مفيش صور أو فيديوهات على الكارت" : "الكارت — آخر " + got.size() + " ملف");
                ad.notifyDataSetChanged();
            });
            for (final PtpMedia.Item it : got) {
                if (!d.isShowing()) return;
                live.submit(tt -> {
                    if (!d.isShowing()) return;
                    it.thumb = PtpMedia.thumb(tt, it.handle);
                    ui.post(ad::notifyDataSetChanged);
                });
            }
        });
    }

    // ------------------------------------------------------------------ open one file

    private void open(final PtpMedia.Item it) {
        final File f = new File(a.getCacheDir(), "view_" + it.name);
        final Dialog wait = new Dialog(a, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        final TextView msg = new TextView(a);
        msg.setTextColor(Color.WHITE);
        msg.setTextSize(16);
        msg.setGravity(Gravity.CENTER);
        msg.setText("بحمّل " + it.name + "…");
        wait.setContentView(msg);
        wait.show();
        live.submit(t -> {
            try {
                if (!(f.exists() && f.length() == it.size)) {
                    PtpMedia.download(t, it, f, (done, total) -> ui.post(() -> msg.setText(String.format(Locale.US,
                            "بحمّل %s\n%d%%  (%.0f / %.0f MB)", it.name, total > 0 ? done * 100 / total : 0,
                            done / 1048576f, total / 1048576f))));
                }
                final Bitmap b = it.video ? null : PtpMedia.decodeForScreen(f, 2400);
                ui.post(() -> {
                    wait.dismiss();
                    if (it.video) showVideo(it, f);
                    else showPhoto(it, f, b);
                });
            } catch (Exception e) {
                ui.post(() -> {
                    wait.dismiss();
                    toast(e.getMessage() != null ? e.getMessage() : "مقدرتش أفتح الملف");
                });
            }
        });
    }

    public void showPhoto(PtpMedia.Item it, File f, Bitmap b) {
        if (b == null) { toast("الموبايل مش قادر يعرض الصورة دي"); return; }
        Dialog d = fullScreen();
        FrameLayout root = new FrameLayout(a);
        root.setBackgroundColor(Color.BLACK);
        ImageView iv = new ImageView(a);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setImageBitmap(b);
        root.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        root.addView(bar(d, it, f, false), new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        d.setContentView(root);
        d.show();
    }

    private void showVideo(PtpMedia.Item it, File f) {
        Dialog d = fullScreen();
        FrameLayout root = new FrameLayout(a);
        root.setBackgroundColor(Color.BLACK);
        VideoView vv = new VideoView(a);
        root.addView(vv, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        MediaController mc = new MediaController(a);
        mc.setAnchorView(vv);
        vv.setMediaController(mc);
        vv.setVideoURI(Uri.fromFile(f));
        vv.setOnErrorListener((mp, w, x) -> {
            toast("الموبايل مش قادر يشغّل الفيديو ده (غالبًا كوديك 8K/HEVC 10-bit). تقدر تحفظه وتفتحه في برنامج تاني.");
            return true;
        });
        vv.setOnPreparedListener(mp -> vv.start());
        root.addView(bar(d, it, f, true), new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));
        d.setContentView(root);
        d.show();
    }

    private View bar(Dialog d, PtpMedia.Item it, File f, boolean video) {
        LinearLayout row = new LinearLayout(a);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(10), dp(10), dp(10));
        row.setBackgroundColor(Color.argb(120, 0, 0, 0));
        TextView name = new TextView(a);
        name.setText(it.name);
        name.setTextColor(Color.WHITE);
        row.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        TextView save = pillButton("حفظ في الموبايل");
        save.setOnClickListener(v -> save(it, f, video));
        row.addView(save);
        TextView close = pillButton("قفل");
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMarginStart(dp(8));
        close.setOnClickListener(v -> d.dismiss());
        row.addView(close, lp);
        return row;
    }

    private void save(PtpMedia.Item it, File f, boolean video) {
        new Thread(() -> {
            try {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.MediaColumns.DISPLAY_NAME, it.name);
                String ext = PtpMedia.ext(it.name);
                String mime = video ? (ext.equals("MOV") ? "video/quicktime" : "video/mp4")
                        : ext.equals("JPG") || ext.equals("JPEG") ? "image/jpeg"
                        : ext.equals("HIF") || ext.equals("HEIF") || ext.equals("HEIC") ? "image/heif"
                        : ext.equals("PNG") ? "image/png" : "image/x-canon-cr3";
                cv.put(MediaStore.MediaColumns.MIME_TYPE, mime);
                Uri coll;
                if (Build.VERSION.SDK_INT >= 29) {
                    cv.put(MediaStore.MediaColumns.RELATIVE_PATH,
                            (video ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_PICTURES) + "/MOBCAM");
                    coll = video ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI : MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
                } else {
                    File dir = new File(Environment.getExternalStoragePublicDirectory(
                            video ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_PICTURES), "MOBCAM");
                    dir.mkdirs();
                    cv.put(MediaStore.MediaColumns.DATA, new File(dir, it.name).getPath());
                    coll = video ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI : MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
                }
                Uri uri = a.getContentResolver().insert(coll, cv);
                if (uri == null) throw new Exception();
                try (InputStream in = new FileInputStream(f); OutputStream out = a.getContentResolver().openOutputStream(uri)) {
                    byte[] buf = new byte[1 << 16];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
                ui.post(() -> toast("اتحفظ في " + (video ? "Movies" : "Pictures") + "/MOBCAM"));
            } catch (Exception e) {
                ui.post(() -> toast("مقدرتش أحفظ الملف"));
            }
        }).start();
    }

    private void toast(String s) {
        Toast.makeText(a, s, Toast.LENGTH_LONG).show();
    }
}
