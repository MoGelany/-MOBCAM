package com.jilani.mobcam;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Browsing / downloading the photos and videos on the camera's card over standard PTP. */
public class PtpMedia {

    public static class Item {
        public int handle;
        public String name = "";
        public long size;
        public boolean video;
        public volatile Bitmap thumb;
    }

    public interface Progress {
        void onProgress(long done, long total);
    }

    private static final int OP_GET_STORAGE_IDS = 0x1004;
    private static final int OP_GET_HANDLES = 0x1007;
    private static final int OP_GET_INFO = 0x1008;
    private static final int OP_GET_OBJECT = 0x1009;
    private static final int OP_GET_THUMB = 0x100A;
    private static final int OP_GET_PARTIAL = 0x101B;
    private static final int OP_CANON_GET_PARTIAL = 0x9107;
    private static final int FMT_FOLDER = 0x3001;

    private static final String[] PHOTO = {"JPG", "JPEG", "HIF", "HEIF", "HEIC", "CR3", "CR2", "ARW", "PNG"};
    private static final String[] VIDEO = {"MP4", "MOV", "MTS", "M2TS", "AVI", "CRM"};

    private static ByteBuffer le(byte[] d) {
        return ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN);
    }

    private static int[] u32Array(byte[] d) {
        if (d == null || d.length < 4) return new int[0];
        ByteBuffer b = le(d);
        int n = Math.min(b.getInt(0), (d.length - 4) / 4);
        int[] r = new int[n];
        for (int i = 0; i < n; i++) r[i] = b.getInt(4 + i * 4);
        return r;
    }

    /** All object handles on all cards, highest (= usually newest) first. */
    public static int[] allHandles(PtpTransport t) throws Exception {
        PtpTransport.Resp s = t.transact(OP_GET_STORAGE_IDS, null);
        if (s.code != CameraDriver.RC_OK) throw new Exception("الكاميرا مش بتسمح بعرض الكارت في الوضع ده");
        List<Integer> all = new ArrayList<>();
        for (int sid : u32Array(s.data)) {
            PtpTransport.Resp h = t.transact(OP_GET_HANDLES, null, sid, 0, 0);
            if (h.code != CameraDriver.RC_OK) continue;
            for (int x : u32Array(h.data)) all.add(x);
        }
        int[] r = new int[all.size()];
        for (int i = 0; i < r.length; i++) r[i] = all.get(i);
        Arrays.sort(r);
        for (int i = 0; i < r.length / 2; i++) { int x = r[i]; r[i] = r[r.length - 1 - i]; r[r.length - 1 - i] = x; }
        return r;
    }

    /** Object info → Item, or null for folders / other files. */
    public static Item info(PtpTransport t, int handle) throws Exception {
        PtpTransport.Resp r = t.transact(OP_GET_INFO, null, handle);
        if (r.code != CameraDriver.RC_OK || r.data == null || r.data.length < 53) return null;
        ByteBuffer b = le(r.data);
        if ((b.getShort(4) & 0xffff) == FMT_FOLDER) return null;
        Item it = new Item();
        it.handle = handle;
        it.size = b.getInt(8) & 0xffffffffL;
        int n = r.data[52] & 0xff;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n && 53 + i * 2 + 1 < r.data.length; i++) {
            char c = b.getChar(53 + i * 2);
            if (c == 0) break;
            sb.append(c);
        }
        it.name = sb.toString();
        String ext = ext(it.name);
        if (Arrays.asList(VIDEO).contains(ext)) it.video = true;
        else if (!Arrays.asList(PHOTO).contains(ext)) return null;
        return it;
    }

    public static String ext(String name) {
        int d = name.lastIndexOf('.');
        return d < 0 ? "" : name.substring(d + 1).toUpperCase(Locale.US);
    }

    /** Newest photos/videos, at most {@code max}. */
    public static List<Item> list(PtpTransport t, int max) throws Exception {
        List<Item> out = new ArrayList<>();
        int looked = 0;
        for (int h : allHandles(t)) {
            if (out.size() >= max || looked++ > 400) break;
            Item it = info(t, h);
            if (it != null) out.add(it);
        }
        return out;
    }

    public static Bitmap thumb(PtpTransport t, int handle) throws Exception {
        PtpTransport.Resp r = t.transact(OP_GET_THUMB, null, handle);
        if (r.code != CameraDriver.RC_OK || r.data == null) return null;
        return BitmapFactory.decodeByteArray(r.data, 0, r.data.length);
    }

    /** Downloads the file in chunks so big videos don't need to fit in memory. */
    public static void download(PtpTransport t, Item it, File out, Progress p) throws Exception {
        final int chunk = 4 * 1024 * 1024;
        int partialOp = OP_GET_PARTIAL;
        try (FileOutputStream fo = new FileOutputStream(out)) {
            long off = 0;
            while (off < it.size || it.size == 0) {
                int want = (int) Math.min(chunk, it.size - off);
                PtpTransport.Resp r = t.transact(partialOp, null, it.handle, (int) off, want);
                if (r.code != CameraDriver.RC_OK && off == 0 && partialOp == OP_GET_PARTIAL) {
                    partialOp = OP_CANON_GET_PARTIAL;
                    r = t.transact(partialOp, null, it.handle, (int) off, want);
                }
                if (r.code != CameraDriver.RC_OK && off == 0) {
                    if (it.size > 300L * 1024 * 1024) throw new Exception("الملف كبير والكاميرا مش بتسمح تحميله على أجزاء");
                    r = t.transact(OP_GET_OBJECT, null, it.handle);
                    if (r.code != CameraDriver.RC_OK || r.data == null) throw new Exception("مقدرتش أحمّل الملف");
                    fo.write(r.data);
                    if (p != null) p.onProgress(r.data.length, r.data.length);
                    return;
                }
                if (r.code != CameraDriver.RC_OK || r.data == null || r.data.length == 0)
                    throw new Exception("التحميل وقف (0x" + Integer.toHexString(r.code) + ")");
                fo.write(r.data);
                off += r.data.length;
                if (p != null) p.onProgress(off, it.size);
                if (it.size == 0) break;
            }
        }
    }

    /** Decodes a downloaded photo for the screen; for RAW files, uses the biggest embedded JPEG preview. */
    public static Bitmap decodeForScreen(File f, int maxSide) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        if (o.outWidth > 0) {
            o.inSampleSize = sample(o.outWidth, o.outHeight, maxSide);
            o.inJustDecodeBounds = false;
            Bitmap b = BitmapFactory.decodeFile(f.getPath(), o);
            if (b != null) return b;
        }
        // RAW (CR3 / CR2 / ARW): find the largest embedded JPEG
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            if (raf.length() > 200L * 1024 * 1024) return null;
            byte[] d = new byte[(int) raf.length()];
            raf.readFully(d);
            int best = -1, bestPx = 0;
            for (int i = 0; i + 3 < d.length; i++) {
                if ((d[i] & 0xff) != 0xFF || (d[i + 1] & 0xff) != 0xD8 || (d[i + 2] & 0xff) != 0xFF) continue;
                BitmapFactory.Options q = new BitmapFactory.Options();
                q.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(d, i, d.length - i, q);
                if (q.outWidth * q.outHeight > bestPx) { bestPx = q.outWidth * q.outHeight; best = i; }
            }
            if (best < 0) return null;
            BitmapFactory.Options q = new BitmapFactory.Options();
            q.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(d, best, d.length - best, q);
            q.inSampleSize = sample(q.outWidth, q.outHeight, maxSide);
            q.inJustDecodeBounds = false;
            return BitmapFactory.decodeByteArray(d, best, d.length - best, q);
        } catch (Throwable e) {
            return null;
        }
    }

    private static int sample(int w, int h, int maxSide) {
        int s = 1;
        while (Math.max(w, h) / (s * 2) >= maxSide) s *= 2;
        return s;
    }
}
