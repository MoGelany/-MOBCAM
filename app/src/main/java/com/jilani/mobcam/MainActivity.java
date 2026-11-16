package com.jilani.mobcam;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.RelativeSizeSpan;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.herohan.uvcapp.CameraHelper;
import com.herohan.uvcapp.ICameraHelper;
import com.serenegiant.usb.Size;
import com.serenegiant.usb.UVCCamera;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity implements MonitorRenderer.Listener, CameraLive.Listener {

    private static final String ACTION_USB_PERM = "com.jilani.mobcam.USB_PERMISSION";

    private static final int YELLOW = Color.rgb(255, 214, 10);
    private static final String[] GUIDE_NAMES = {"OFF", "9:16", "4:5", "1:1", "2.39"};
    private static final float[] GUIDES = {0f, 9f / 16f, 4f / 5f, 1f, 2.39f};
    private static final int[] PEAK_COLORS = {Color.rgb(255, 45, 45), Color.rgb(45, 255, 90),
            Color.rgb(255, 214, 10), Color.rgb(45, 180, 255), Color.WHITE};

    private final Handler ui = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;

    private GLSurfaceView glView;
    private MonitorRenderer renderer;
    private OverlayView overlay;
    private HistogramView hist;
    private TextView info, modes, centerMsg;
    private View bar;
    private final List<TextView> toggles = new ArrayList<>();
    private TextView guideBtn, zoomBtn;

    private ICameraHelper camera;
    private SurfaceTexture surfaceTexture;
    private boolean surfaceAttached = false, sizeChecked = false;
    private List<Size> sizes = new ArrayList<>();
    private int guide = 0, fps = 0;
    private boolean uiHidden = false;

    private UsbManager usbManager;
    private CameraLive live;
    private TextView modeBtn;
    private TextView recBtn;
    private long recStart = 0;
    private boolean receiverOn = false;

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 28) {
            getWindow().getAttributes().layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        prefs = getSharedPreferences("mobcam", MODE_PRIVATE);
        buildUi();
        loadSettings();
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, 1);
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] p, int[] r) {
        super.onRequestPermissionsResult(code, p, r);
        if (camera != null) {
            List<UsbDevice> list = camera.getDeviceList();
            if (list != null) for (UsbDevice d : list) {
                if (!UsbPtpTransport.isPtpCamera(d)) { camera.selectDevice(d); break; }
            }
        }
        scanCanon();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        scanCanon();
    }

    @Override
    protected void onStart() {
        super.onStart();
        hideSystemUi();
        glView.onResume();
        if (camera == null) {
            camera = new CameraHelper();
            camera.setStateCallback(stateCallback);
        }
        if (usbManager == null) usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        if (live == null) live = new CameraLive(this, getCacheDir());
        if (surfaceTexture != null) live.setTarget(surfaceTexture);
        if (!receiverOn) {
            IntentFilter f = new IntentFilter(ACTION_USB_PERM);
            f.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
            f.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(usbReceiver, f, Context.RECEIVER_NOT_EXPORTED);
            else registerReceiver(usbReceiver, f);
            receiverOn = true;
        }
        scanCanon();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (receiverOn) {
            try { unregisterReceiver(usbReceiver); } catch (Exception ignored) { }
            receiverOn = false;
        }
        if (live != null) live.stop();
        if (camera != null) {
            camera.release();
            camera = null;
        }
        surfaceAttached = false;
        sizeChecked = false;
        glView.onPause();
        showMsg(true);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUi();
    }

    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }

    // ------------------------------------------------------------------ Canon USB-C (PTP live view)

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            UsbDevice d = i.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            String a = i.getAction();
            if (ACTION_USB_PERM.equals(a)) {
                if (d != null && UsbPtpTransport.isPtpCamera(d)) {
                    if (i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) startCanon(d);
                    else toast("لازم توافق على صلاحية الـ USB عشان الصورة تظهر");
                }
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(a)) {
                scanCanon();
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(a)) {
                if (live != null && d != null && live.source() instanceof UsbDevice
                        && d.getDeviceName().equals(((UsbDevice) live.source()).getDeviceName())) live.stop();
            }
        }
    };

    /** Finds a Canon / Sony camera plugged straight in by USB-C and starts live view from it. */
    private void scanCanon() {
        if (usbManager == null || live == null || live.isRunning()) return;
        for (UsbDevice d : usbManager.getDeviceList().values()) {
            if (!UsbPtpTransport.isPtpCamera(d)) continue;
            if (usbManager.hasPermission(d)) {
                startCanon(d);
            } else {
                Intent pi = new Intent(ACTION_USB_PERM).setPackage(getPackageName());
                int flags = Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0;
                usbManager.requestPermission(d, PendingIntent.getBroadcast(this, 0, pi, flags));
            }
            return;
        }
    }

    private void startCanon(UsbDevice d) {
        if (live == null || live.isRunning()) return;
        if (surfaceTexture != null) live.setTarget(surfaceTexture);
        live.start("USB-C", d, d.getVendorId(), () -> new UsbPtpTransport(usbManager, d));
    }

    // ------------------------------------------------------------------ Wi-Fi

    private byte[] guid() {
        String g = prefs.getString("guid", null);
        byte[] b = new byte[16];
        if (g != null && g.length() == 32) {
            for (int i = 0; i < 16; i++) b[i] = (byte) Integer.parseInt(g.substring(i * 2, i * 2 + 2), 16);
            return b;
        }
        new java.security.SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format(Locale.US, "%02x", x & 0xff));
        prefs.edit().putString("guid", sb.toString()).apply();
        return b;
    }

    private void showWifi() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), dp(8));
        TextView help = new TextView(this);
        help.setTextColor(Color.rgb(200, 200, 200));
        help.setTextSize(13);
        help.setLineSpacing(0, 1.25f);
        help.setText("في الكاميرا: Wireless settings ← Wi‑Fi function ← Remote control (EOS Utility)\n"
                + "وصّل الموبايل على نفس الواي فاي (أو على شبكة الكاميرا نفسها)، وبعدين اكتب IP الكاميرا اللي ظاهر على شاشتها أو دوس «دوّر».\n"
                + "أول مرة الكاميرا هتسأل عن الربط — دوس OK.");
        box.addView(help);
        final android.widget.EditText ip = new android.widget.EditText(this);
        ip.setHint("192.168.1.2");
        ip.setText(prefs.getString("ip", ""));
        ip.setTextColor(Color.WHITE);
        ip.setInputType(android.text.InputType.TYPE_CLASS_PHONE);
        box.addView(ip, new LinearLayout.LayoutParams(-1, -2));
        final TextView status = settingRow(box, "");
        status.setTextColor(YELLOW);

        final AlertDialog dlg = new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                .setTitle("توصيل واي فاي")
                .setView(box)
                .setPositiveButton("اتصل", (d, w) -> connectWifi(ip.getText().toString().trim()))
                .setNeutralButton("دوّر", null)
                .setNegativeButton(live != null && live.isRunning() && "Wi-Fi".equals(live.how()) ? "اقطع" : "إلغاء",
                        (d, w) -> { if (live != null && "Wi-Fi".equals(live.how())) live.stop(); })
                .setOnDismissListener(d -> hideSystemUi())
                .create();
        dlg.setOnShowListener(d -> dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
            status.setText("بدوّر على الكاميرا…");
            new Thread(() -> {
                final java.util.Set<String> f = CameraFinder.find(getApplicationContext(), 3000);
                ui.post(() -> {
                    if (f.isEmpty()) status.setText("ملقتش حاجة — اكتب الـ IP من شاشة الكاميرا");
                    else {
                        ip.setText(f.iterator().next());
                        status.setText("لقيت: " + android.text.TextUtils.join(" ، ", f));
                    }
                });
            }).start();
        }));
        dlg.show();
    }

    private void connectWifi(final String host) {
        if (host.isEmpty()) { toast("اكتب IP الكاميرا"); return; }
        prefs.edit().putString("ip", host).apply();
        if (live.isRunning()) live.stop();
        if (surfaceTexture != null) live.setTarget(surfaceTexture);
        if (camera != null) detachSurface();
        final byte[] g = guid();
        toast("بتصل بالكاميرا… لو سألت عن الربط دوس OK على الكاميرا");
        live.start("Wi-Fi", host, 0, () -> new IpPtpTransport(getApplicationContext(), host, g, "MOBCAM"));
    }

    // ------------------------------------------------------------------ record / focus

    private boolean needLive() {
        if (live != null && live.isRunning()) return true;
        toast("التحكم والمعرض شغالين مع توصيل Type-C أو الواي فاي بس (مش الكابشر كارد)");
        return false;
    }

    private final Runnable recTick = new Runnable() {
        @Override public void run() {
            if (live != null && live.isRecording()) { applySettings(); ui.postDelayed(this, 1000); }
        }
    };

    @Override
    public void onRecordState(boolean recording, boolean ok) {
        ui.post(() -> {
            if (!ok) toast(recording ? "مقدرتش أوقف التسجيل" : "مقدرتش أبدأ التسجيل — حوّل الكاميرا لوضع الفيديو 🎥");
            recStart = recording ? android.os.SystemClock.elapsedRealtime() : 0;
            ui.removeCallbacks(recTick);
            if (recording) ui.post(recTick);
            applySettings();
        });
    }

    // ------------------------------------------------------------------ rotation lock

    private int currentOrientation() {
        int rot = getWindowManager().getDefaultDisplay().getRotation();
        boolean land = getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        if (land) return (rot == android.view.Surface.ROTATION_0 || rot == android.view.Surface.ROTATION_90)
                ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                : android.content.pm.ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE;
        return (rot == android.view.Surface.ROTATION_0 || rot == android.view.Surface.ROTATION_270)
                ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                : android.content.pm.ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT;
    }

    private void applyRotation() {
        if (prefs.getBoolean("rotlock", false)) {
            int o = prefs.getInt("rotO", -100);
            if (o == -100) { o = currentOrientation(); prefs.edit().putInt("rotO", o).apply(); }
            setRequestedOrientation(o);
        } else {
            prefs.edit().remove("rotO").apply();
            setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR);
        }
    }

    @Override
    public void onLiveStarted(String how, String brand) {
        ui.post(() -> { toast("اتوصلت بالكاميرا " + brand + " (" + how + ")"); updateInfo(); applySettings(); });
    }

    @Override
    public void onModeChanged(boolean movie, boolean ok) {
        ui.post(() -> {
            if (!ok) toast("الكاميرا دي مش بتسمح تغيير وضع الصور/الفيديو من التطبيق — غيّره من الكاميرا");
            else toast(movie ? "وضع الفيديو 🎥" : "وضع الصور 📷");
            applySettings();
        });
    }

    @Override
    public void onPhotoTaken(boolean ok) {
        ui.post(() -> {
            if (!ok) toast("مقدرتش أصوّر — لو الكاميرا في وضع الفيديو حوّلها لوضع الصور");
            else if (overlay != null) {
                overlay.animate().alpha(0f).setDuration(60).withEndAction(() -> overlay.animate().alpha(1f).setDuration(120).start()).start();
            }
        });
    }

    @Override
    public void onPhotoReady(PtpMedia.Item it, java.io.File f, android.graphics.Bitmap b) {
        ui.post(() -> new GalleryUi(this, live, this::hideSystemUi).showPhoto(it, f, b));
    }

    @Override
    public void onLiveFrameSize(int w, int h) {
        ui.post(() -> {
            renderer.videoW = w;
            renderer.videoH = h;
            showMsg(false);
            layoutOverlay();
            updateInfo();
        });
    }

    @Override
    public void onLiveError(String msg) {
        ui.post(() -> toast("مشكلة في الاتصال بالكاميرا: " + msg));
    }

    @Override
    public void onLiveStopped() {
        ui.post(() -> {
            showMsg(true);
            recStart = 0;
            applySettings();
            updateInfo();
            scanCanon();
        });
    }

    // ------------------------------------------------------------------ camera

    private final ICameraHelper.StateCallback stateCallback = new ICameraHelper.StateCallback() {
        @Override
        public void onAttach(UsbDevice device) {
            if (UsbPtpTransport.isPtpCamera(device)) { scanCanon(); return; }
            if (live != null && live.isRunning()) return;
            if (camera != null) camera.selectDevice(device);
        }

        @Override
        public void onDeviceOpen(UsbDevice device, boolean isFirstOpen) {
            if (camera != null) camera.openCamera();
        }

        @Override
        public void onCameraOpen(UsbDevice device) {
            if (camera == null) return;
            camera.startPreview();
            List<Size> list = camera.getSupportedSizeList();
            sizes = list != null ? list : new ArrayList<>();
            Size want = pickSize();
            Size cur = camera.getPreviewSize();
            if (!sizeChecked && want != null && !same(want, cur)) {
                sizeChecked = true;
                camera.setPreviewSize(want);
                ui.postDelayed(MainActivity.this::attachSurface, 700);
            } else {
                sizeChecked = true;
                attachSurface();
            }
        }

        @Override
        public void onCameraClose(UsbDevice device) {
            detachSurface();
            showMsg(true);
        }

        @Override
        public void onDeviceClose(UsbDevice device) {
        }

        @Override
        public void onDetach(UsbDevice device) {
            detachSurface();
            sizeChecked = false;
            showMsg(true);
        }

        @Override
        public void onCancel(UsbDevice device) {
            toast("لازم توافق على صلاحية الـ USB عشان الصورة تظهر");
        }
    };

    private void attachSurface() {
        if (camera == null || surfaceTexture == null) return;
        Size s = camera.getPreviewSize();
        if (s != null && s.width > 0) {
            renderer.videoW = s.width;
            renderer.videoH = s.height;
        }
        if (surfaceAttached) camera.removeSurface(surfaceTexture);
        surfaceTexture.setDefaultBufferSize(renderer.videoW, renderer.videoH);
        camera.addSurface(surfaceTexture, false);
        surfaceAttached = true;
        showMsg(false);
        layoutOverlay();
        updateInfo();
    }

    private void detachSurface() {
        if (camera != null && surfaceTexture != null && surfaceAttached) {
            try { camera.removeSurface(surfaceTexture); } catch (Exception ignored) { }
        }
        surfaceAttached = false;
    }

    private static boolean same(Size a, Size b) {
        return a != null && b != null && a.width == b.width && a.height == b.height && a.type == b.type;
    }

    /** Saved size, else 1920x1080 MJPEG with the highest fps, else the biggest MJPEG up to 1080p. */
    private Size pickSize() {
        if (sizes.isEmpty()) return null;
        int sw = prefs.getInt("w", 0), sh = prefs.getInt("h", 0), st = prefs.getInt("t", -1);
        Size best = null;
        for (Size s : sizes) if (s.width == sw && s.height == sh && s.type == st) return s;
        for (Size s : sizes) {
            if (s.type != UVCCamera.UVC_VS_FRAME_MJPEG || s.width > 1920) continue;
            if (best == null || s.width * s.height > best.width * best.height
                    || (s.width * s.height == best.width * best.height && s.fps > best.fps)) best = s;
        }
        return best != null ? best : sizes.get(0);
    }

    @Override
    public void onSurfaceTextureReady(SurfaceTexture st) {
        surfaceTexture = st;
        surfaceAttached = false;
        if (live != null) live.setTarget(st);
        if (camera != null && camera.isCameraOpened()) attachSurface();
    }

    @Override
    public void onHistogram(float[] y, float[] r, float[] g, float[] b, boolean hi, boolean lo) {
        hist.set(y, r, g, b, hi, lo);
    }

    @Override
    public void onFps(int f) {
        fps = f;
        updateInfo();
    }

    // ------------------------------------------------------------------ UI

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        glView = new GLSurfaceView(this);
        glView.setEGLContextClientVersion(2);
        renderer = new MonitorRenderer(glView, this);
        glView.setRenderer(renderer);
        glView.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
        root.addView(glView, new FrameLayout.LayoutParams(-1, -1));

        overlay = new OverlayView(this);
        root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));

        // gestures: tap = hide UI, double tap = punch-in, drag = pan
        final GestureDetector gd = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) { return true; }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                setUiHidden(!uiHidden);
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                if (renderer.zoom > 1f) { setZoom(1f); return true; }
                float fx = (e.getX() - overlay.video.left) / overlay.video.width();
                float fy = 1f - (e.getY() - overlay.video.top) / overlay.video.height();
                if (renderer.mirror) fx = 1f - fx;
                if (renderer.flipV) { fx = 1f - fx; fy = 1f - fy; }
                renderer.offX = fx - 0.5f;
                renderer.offY = fy - 0.5f;
                setZoom(3f);
                return true;
            }

            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                if (renderer.zoom <= 1f) return false;
                float sx = (renderer.mirror ? -1 : 1) * (renderer.flipV ? -1 : 1), sy = renderer.flipV ? -1 : 1;
                renderer.offX += sx * dx / (overlay.video.width() * renderer.zoom);
                renderer.offY -= sy * dy / (overlay.video.height() * renderer.zoom);
                clampOff();
                glView.requestRender();
                return true;
            }
        });
        glView.setOnTouchListener((v, ev) -> gd.onTouchEvent(ev));
        glView.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> layoutOverlay());

        // HUD
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        info = pill();
        info.setText("MOBCAM");
        left.addView(info);
        hist = new HistogramView(this);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(dp(150), dp(58));
        hp.topMargin = dp(6);
        left.addView(hist, hp);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.LEFT);
        lp.setMargins(dp(10), dp(10), dp(10), dp(10));
        root.addView(left, lp);

        modes = pill();
        modes.setTextColor(YELLOW);
        FrameLayout.LayoutParams mp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.RIGHT);
        mp.setMargins(dp(10), dp(10), dp(10), dp(10));
        root.addView(modes, mp);

        centerMsg = new TextView(this);
        centerMsg.setGravity(Gravity.CENTER);
        centerMsg.setTextColor(Color.WHITE);
        centerMsg.setTextSize(16);
        centerMsg.setLineSpacing(0, 1.3f);
        centerMsg.setText(centerText());
        root.addView(centerMsg, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));

        // bottom toolbar
        HorizontalScrollView sv = new HorizontalScrollView(this);
        sv.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        row.setPadding(dp(8), dp(8), dp(8), dp(10));
        sv.addView(row);
        GradientDrawable fade = new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{Color.argb(220, 0, 0, 0), Color.TRANSPARENT});
        sv.setBackground(fade);
        bar = sv;
        root.addView(sv, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

        recBtn = button(row, "ريكورد", "● REC", v -> { if (needLive()) live.toggleRecord(); });
        button(row, "صورة", "◉ PHOTO", v -> { if (needLive()) live.takePhoto(prefs.getBoolean("showshot", true)); });
        modeBtn = button(row, "صور/فيديو", "PHOTO", v -> { if (needLive()) live.setMovieMode(!live.isMovieMode()); });
        button(row, "المعرض", "PLAYBACK", v -> { if (needLive()) new GalleryUi(this, live, this::hideSystemUi).showGallery(); });
        button(row, "فوكس", "AF", v -> { if (needLive()) live.autofocus(); });
        button(row, "فوكس قريب", "◀ NEAR", v -> { if (needLive()) live.driveFocus(true, 2); });
        button(row, "فوكس بعيد", "FAR ▶", v -> { if (needLive()) live.driveFocus(false, 2); });
        addToggle(row, "بيكينج", "PEAKING", "peak");
        addToggle(row, "زيبرا", "ZEBRA", "zebra");
        addToggle(row, "ألوان التعريض", "FALSE CLR", "fc");
        addToggle(row, "هيستوجرام", "HISTO", "hist");
        addToggle(row, "جريد", "GRID", "grid");
        guideBtn = button(row, "كادر", "OFF", v -> {
            guide = (guide + 1) % GUIDES.length;
            prefs.edit().putInt("guide", guide).apply();
            applySettings();
        });
        zoomBtn = button(row, "زووم", "1×", v -> setZoom(renderer.zoom == 1f ? 2f : renderer.zoom == 2f ? 4f : 1f));
        addToggle(row, "ميرور", "MIRROR", "mirror");
        addToggle(row, "عرض اللوج", "LOG→709", "log");
        addToggle(row, "قلب الصورة", "FLIP 180", "flip");
        addToggle(row, "قفل التدوير", "ROT LOCK", "rotlock");
        button(row, "واي فاي", "Wi-Fi", v -> showWifi());
        button(row, "الإعدادات", "SETUP", v -> showSettings());

        setContentView(root);
    }

    private CharSequence centerText() {
        SpannableString s = new SpannableString("MOBCAM\n\nوصّل الكاميرا بالموبايل بكابل Type-C\nأو دوس «واي فاي» تحت\n(أو كابشر كارد + HDMI)\n\nلو طلب صلاحية USB دوس OK");
        s.setSpan(new RelativeSizeSpan(1.8f), 0, 6, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        s.setSpan(new android.text.style.ForegroundColorSpan(YELLOW), 0, 6, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        s.setSpan(new android.text.style.StyleSpan(Typeface.BOLD), 0, 6, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return s;
    }

    private TextView pill() {
        TextView t = new TextView(this);
        t.setTextColor(Color.WHITE);
        t.setTextSize(12);
        t.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        t.setPadding(dp(8), dp(4), dp(8), dp(4));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.argb(140, 0, 0, 0));
        bg.setCornerRadius(dp(6));
        t.setBackground(bg);
        return t;
    }

    private TextView button(LinearLayout row, String ar, String en, View.OnClickListener l) {
        TextView b = new TextView(this);
        b.setGravity(Gravity.CENTER);
        b.setMinWidth(dp(66));
        b.setPadding(dp(10), dp(6), dp(10), dp(6));
        b.setTextSize(13);
        b.setOnClickListener(l);
        b.setTag(ar);
        setLabel(b, en);
        styleBtn(b, false);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, dp(54));
        p.setMarginEnd(dp(6));
        row.addView(b, p);
        return b;
    }

    private void setLabel(TextView b, String en) {
        String ar = (String) b.getTag();
        SpannableString s = new SpannableString(ar + "\n" + en);
        s.setSpan(new RelativeSizeSpan(0.72f), ar.length() + 1, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        b.setText(s);
    }

    private void styleBtn(TextView b, boolean on) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(10));
        bg.setColor(on ? YELLOW : Color.argb(220, 30, 30, 30));
        bg.setStroke(dp(1), on ? YELLOW : Color.argb(50, 255, 255, 255));
        b.setBackground(bg);
        b.setTextColor(on ? Color.BLACK : Color.rgb(220, 220, 220));
    }

    private void addToggle(LinearLayout row, String ar, String en, final String key) {
        TextView b = button(row, ar, en, v -> {
            prefs.edit().putBoolean(key, !prefs.getBoolean(key, key.equals("hist"))).apply();
            applySettings();
        });
        b.setContentDescription(key);
        toggles.add(b);
    }

    private void loadSettings() {
        guide = prefs.getInt("guide", 0);
        applySettings();
    }

    private void applySettings() {
        renderer.peak = prefs.getBoolean("peak", false);
        renderer.zebra = prefs.getBoolean("zebra", false);
        renderer.falseColor = prefs.getBoolean("fc", false);
        renderer.histogram = prefs.getBoolean("hist", true);
        renderer.mirror = prefs.getBoolean("mirror", false);
        renderer.logView = prefs.getBoolean("log", false);
        renderer.flipV = prefs.getBoolean("flip", false);
        renderer.sens = prefs.getFloat("sens", 0.55f);
        renderer.zebraLevel = prefs.getInt("zl", 95) / 100f;
        int pc = prefs.getInt("pc", PEAK_COLORS[0]);
        renderer.pr = Color.red(pc) / 255f;
        renderer.pg = Color.green(pc) / 255f;
        renderer.pb = Color.blue(pc) / 255f;

        for (TextView t : toggles) {
            String k = t.getContentDescription().toString();
            styleBtn(t, prefs.getBoolean(k, k.equals("hist")));
        }
        setLabel(guideBtn, GUIDE_NAMES[guide]);
        styleBtn(guideBtn, guide > 0);
        setLabel(zoomBtn, (int) renderer.zoom + "×");
        styleBtn(zoomBtn, renderer.zoom > 1f);

        overlay.grid = prefs.getBoolean("grid", false);
        overlay.guide = GUIDES[guide];
        overlay.hidden = renderer.zoom > 1f;
        overlay.invalidate();
        hist.setVisibility(renderer.histogram && !uiHidden ? View.VISIBLE : View.GONE);

        applyRotation();
        boolean rec = live != null && live.isRecording();
        if (recBtn != null) {
            styleBtn(recBtn, false);
            if (rec) {
                GradientDrawable rb = new GradientDrawable();
                rb.setCornerRadius(dp(10));
                rb.setColor(Color.rgb(220, 30, 30));
                recBtn.setBackground(rb);
                recBtn.setTextColor(Color.WHITE);
            }
        }

        if (modeBtn != null) {
            boolean mv = live != null && live.isMovieMode();
            setLabel(modeBtn, mv ? "MOVIE 🎥" : "PHOTO 📷");
        }

        StringBuilder m = new StringBuilder();
        if (rec && recStart > 0) {
            long sec = (android.os.SystemClock.elapsedRealtime() - recStart) / 1000;
            m.append(String.format(Locale.US, "● REC %02d:%02d  ", sec / 60, sec % 60));
        }
        if (renderer.zoom > 1f) m.append("ZOOM ").append((int) renderer.zoom).append("×  ");
        if (renderer.peak) m.append("PEAK  ");
        if (renderer.zebra) m.append("ZEB ").append(Math.round(renderer.zebraLevel * 100)).append("  ");
        if (renderer.falseColor) m.append("FC  ");
        if (renderer.logView) m.append("LOG  ");
        modes.setText(m.toString().trim());
        modes.setVisibility(m.length() > 0 && !uiHidden ? View.VISIBLE : View.GONE);
        glView.requestRender();
    }

    private void setZoom(float z) {
        renderer.zoom = z;
        if (z == 1f) { renderer.offX = 0; renderer.offY = 0; }
        clampOff();
        applySettings();
    }

    private void clampOff() {
        float m = 0.5f - 0.5f / renderer.zoom;
        renderer.offX = Math.max(-m, Math.min(m, renderer.offX));
        renderer.offY = Math.max(-m, Math.min(m, renderer.offY));
    }

    private void setUiHidden(boolean h) {
        uiHidden = h;
        bar.animate().translationY(h ? bar.getHeight() : 0).alpha(h ? 0 : 1).setDuration(180).start();
        applySettings();
    }

    private void layoutOverlay() {
        float W = glView.getWidth(), H = glView.getHeight();
        if (W <= 0) return;
        float s = Math.min(W / renderer.videoW, H / renderer.videoH);
        float vw = renderer.videoW * s, vh = renderer.videoH * s;
        overlay.video.set((W - vw) / 2, (H - vh) / 2, (W + vw) / 2, (H + vh) / 2);
        overlay.invalidate();
    }

    private void updateInfo() {
        info.setText(String.format(Locale.US, "%d×%d • %d fps%s", renderer.videoW, renderer.videoH, fps,
                live != null && live.isRunning() ? " • " + live.brand() + " " + live.how() : ""));
    }

    private void showMsg(boolean show) {
        centerMsg.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    // ------------------------------------------------------------------ settings dialog

    private void showSettings() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), dp(8));

        // resolution
        Size cur = camera != null ? camera.getPreviewSize() : null;
        TextView res = settingRow(box, "الجودة: " + (cur != null ? describe(cur) : "—"));
        res.setTextColor(YELLOW);
        res.setOnClickListener(v -> chooseSize());

        final android.widget.CheckBox showShot = new android.widget.CheckBox(this);
        showShot.setText("اعرض الصورة على الموبايل بعد ما أصوّر");
        showShot.setTextColor(Color.rgb(220, 220, 220));
        showShot.setChecked(prefs.getBoolean("showshot", true));
        showShot.setOnCheckedChangeListener((cb, on) -> prefs.edit().putBoolean("showshot", on).apply());
        box.addView(showShot);

        // peaking colour
        settingRow(box, "لون البيكينج");
        LinearLayout sw = new LinearLayout(this);
        int sel = prefs.getInt("pc", PEAK_COLORS[0]);
        final List<View> dots = new ArrayList<>();
        for (final int c : PEAK_COLORS) {
            View d = new View(this);
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(c);
            g.setStroke(dp(3), c == sel ? Color.WHITE : Color.DKGRAY);
            d.setBackground(g);
            d.setOnClickListener(v -> {
                prefs.edit().putInt("pc", c).apply();
                for (int i = 0; i < dots.size(); i++)
                    ((GradientDrawable) dots.get(i).getBackground()).setStroke(dp(3), PEAK_COLORS[i] == c ? Color.WHITE : Color.DKGRAY);
                applySettings();
            });
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(34), dp(34));
            p.setMarginEnd(dp(10));
            sw.addView(d, p);
            dots.add(d);
        }
        box.addView(sw);

        final TextView sensLbl = settingRow(box, "حساسية البيكينج");
        SeekBar sens = new SeekBar(this);
        sens.setMax(100);
        sens.setProgress(Math.round(prefs.getFloat("sens", 0.55f) * 100));
        sens.setOnSeekBarChangeListener(new SimpleSeek() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean u) {
                prefs.edit().putFloat("sens", p / 100f).apply();
                applySettings();
            }
        });
        box.addView(sens);

        final TextView zl = settingRow(box, "مستوى الزيبرا: " + prefs.getInt("zl", 95) + "%");
        SeekBar zs = new SeekBar(this);
        zs.setMax(30);
        zs.setProgress(prefs.getInt("zl", 95) - 70);
        zs.setOnSeekBarChangeListener(new SimpleSeek() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean u) {
                prefs.edit().putInt("zl", p + 70).apply();
                zl.setText("مستوى الزيبرا: " + (p + 70) + "%");
                applySettings();
            }
        });
        box.addView(zs);

        new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                .setTitle("إعدادات MOBCAM")
                .setView(box)
                .setPositiveButton("تمام", null)
                .setOnDismissListener(d -> hideSystemUi())
                .show();
    }

    private TextView settingRow(LinearLayout box, String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(Color.rgb(220, 220, 220));
        t.setTextSize(15);
        t.setPadding(0, dp(14), 0, dp(6));
        box.addView(t, new LinearLayout.LayoutParams(-1, -2));
        return t;
    }

    private String describe(Size s) {
        return String.format(Locale.US, "%d×%d %s %dfps", s.width, s.height,
                s.type == UVCCamera.UVC_VS_FRAME_MJPEG ? "MJPEG" : "YUV", s.fps);
    }

    private void chooseSize() {
        if (live != null && live.isRunning()) {
            toast("في توصيل Type-C والواي فاي الجودة بتتحدد من الكاميرا نفسها");
            return;
        }
        if (camera == null || sizes.isEmpty()) {
            toast("وصّل الكابشر كارد الأول");
            return;
        }
        final List<Size> list = new ArrayList<>(sizes);
        String[] names = new String[list.size()];
        for (int i = 0; i < list.size(); i++) names[i] = describe(list.get(i));
        new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                .setTitle("الجودة")
                .setItems(names, (d, i) -> {
                    Size s = list.get(i);
                    prefs.edit().putInt("w", s.width).putInt("h", s.height).putInt("t", s.type).apply();
                    if (camera != null) {
                        camera.setPreviewSize(s);
                        ui.postDelayed(this::attachSurface, 700);
                    }
                })
                .setOnDismissListener(d -> hideSystemUi())
                .show();
    }

    private abstract static class SimpleSeek implements SeekBar.OnSeekBarChangeListener {
        @Override public void onStartTrackingTouch(SeekBar s) { }
        @Override public void onStopTrackingTouch(SeekBar s) { }
    }
}
