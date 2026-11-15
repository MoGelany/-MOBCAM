package com.jilani.mobcam;

import android.graphics.SurfaceTexture;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/** Draws the camera feed with monitor tools (peaking, zebra, false color, log view, zoom). */
public class MonitorRenderer implements GLSurfaceView.Renderer, SurfaceTexture.OnFrameAvailableListener {

    public interface Listener {
        void onSurfaceTextureReady(SurfaceTexture st);
        void onHistogram(float[] y, float[] r, float[] g, float[] b, boolean clipHi, boolean clipLo);
        void onFps(int fps);
    }

    private static final String TAG = "MOBCAM";

    private static final String VS =
            "attribute vec2 aPos;varying vec2 vUv;" +
            "void main(){vUv=aPos*0.5+0.5;gl_Position=vec4(aPos,0.0,1.0);}";

    private static final String FS_HEAD =
            "#extension GL_OES_EGL_image_external : require\n" +
            "#ifdef GL_FRAGMENT_PRECISION_HIGH\nprecision highp float;\n#else\nprecision mediump float;\n#endif\n" +
            "varying vec2 vUv;uniform samplerExternalOES uTex;uniform mat4 uM;\n";

    private static final String FS_MAIN = FS_HEAD +
            "uniform vec2 uTx;uniform float uZoom;uniform vec2 uOff;\n" +
            "uniform float uMir,uFlv,uPeak,uZeb,uFc,uLog,uSens,uZl,uTime;uniform vec3 uPc;\n" +
            "float L(vec3 c){return dot(c,vec3(0.2126,0.7152,0.0722));}\n" +
            "vec3 G(vec3 c){ if(uLog>0.5){ c=clamp((c-0.08)*1.6,0.0,1.0); c=mix(c,c*c*(3.0-2.0*c),0.45);" +
            "  float l=L(c); c=clamp(mix(vec3(l),c,1.35),0.0,1.0);} return c;}\n" +
            "vec3 T(vec2 p){return G(texture2D(uTex,(uM*vec4(p,0.0,1.0)).xy).rgb);}\n" +
            "float Y(vec2 p){return L(T(p));}\n" +
            "void main(){\n" +
            " vec2 p=vUv; if(uMir>0.5)p.x=1.0-p.x; if(uFlv>0.5)p=1.0-p;\n" +
            " p=(p-0.5)/uZoom+0.5+uOff;\n" +
            " if(p.x<0.0||p.y<0.0||p.x>1.0||p.y>1.0){gl_FragColor=vec4(0.0,0.0,0.0,1.0);return;}\n" +
            " vec3 c=T(p); float l=L(c); vec3 o=c;\n" +
            " if(uFc>0.5){ float i=l*100.0; o=vec3(l*0.85);\n" +
            "  if(i<2.5)o=vec3(0.55,0.0,0.7); else if(i<10.0)o=vec3(0.05,0.3,1.0); else if(i<18.0)o=vec3(0.2,0.5,0.6);\n" +
            "  else if(i>38.0&&i<45.0)o=vec3(0.15,0.85,0.25); else if(i>52.0&&i<58.0)o=vec3(1.0,0.55,0.75);\n" +
            "  else if(i>=98.0)o=vec3(1.0,0.0,0.0); else if(i>=90.0)o=vec3(1.0,0.9,0.0); }\n" +
            " if(uPeak>0.5){ vec2 d=uTx;\n" +
            "  float a=Y(p-d),b=Y(p+vec2(0.0,-d.y)),k=Y(p+vec2(d.x,-d.y)),e=Y(p+vec2(-d.x,0.0)),f=Y(p+vec2(d.x,0.0));\n" +
            "  float g=Y(p+vec2(-d.x,d.y)),h=Y(p+vec2(0.0,d.y)),j=Y(p+d);\n" +
            "  float gx=-a-2.0*e-g+k+2.0*f+j; float gy=-a-2.0*b-k+g+2.0*h+j; float m=sqrt(gx*gx+gy*gy);\n" +
            "  float th=mix(0.95,0.12,uSens); o=mix(o,uPc,smoothstep(th,th*1.25,m)); }\n" +
            " if(uZeb>0.5&&l>=uZl){ float s=mod(gl_FragCoord.x+gl_FragCoord.y+uTime*40.0,14.0);\n" +
            "  o=s<7.0?mix(o,vec3(0.0),0.7):mix(o,vec3(1.0),0.55); }\n" +
            " gl_FragColor=vec4(o,1.0);\n" +
            "}\n";

    private static final String FS_PLAIN = FS_HEAD +
            "void main(){gl_FragColor=vec4(texture2D(uTex,(uM*vec4(vUv,0.0,1.0)).xy).rgb,1.0);}";

    private static final int HW = 128, HH = 72;

    private final GLSurfaceView view;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());

    private SurfaceTexture surfaceTexture;
    private int oesTex, prog, plainProg, fbo, fboTex;
    private final float[] stM = new float[16];
    private FloatBuffer quad;
    private final ByteBuffer pix = ByteBuffer.allocateDirect(HW * HH * 4).order(ByteOrder.nativeOrder());
    private int surfW = 1, surfH = 1, frameCount = 0, fpsFrames = 0;
    private long fpsT = 0;
    private final long t0 = SystemClock.elapsedRealtime();
    private boolean hasFrame = false;

    // settings (written from UI thread)
    public volatile boolean peak, zebra, falseColor, logView, mirror, flipV, histogram = true;
    public volatile float sens = 0.55f, zebraLevel = 0.95f, zoom = 1f, offX = 0f, offY = 0f;
    public volatile float pr = 1f, pg = 0.18f, pb = 0.18f;
    public volatile int videoW = 1920, videoH = 1080;

    public MonitorRenderer(GLSurfaceView view, Listener listener) {
        this.view = view;
        this.listener = listener;
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        quad = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        quad.put(new float[]{-1, -1, 1, -1, -1, 1, 1, 1}).position(0);

        int[] t = new int[1];
        GLES20.glGenTextures(1, t, 0);
        oesTex = t[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

        prog = buildProgram(VS, FS_MAIN);
        plainProg = buildProgram(VS, FS_PLAIN);

        // small FBO for histogram
        GLES20.glGenTextures(1, t, 0);
        fboTex = t[0];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTex);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, HW, HH, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glGenFramebuffers(1, t, 0);
        fbo = t[0];
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, fboTex, 0);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);

        if (surfaceTexture != null) surfaceTexture.release();
        surfaceTexture = new SurfaceTexture(oesTex);
        surfaceTexture.setDefaultBufferSize(videoW, videoH);
        surfaceTexture.setOnFrameAvailableListener(this);
        hasFrame = false;
        final SurfaceTexture st = surfaceTexture;
        main.post(() -> listener.onSurfaceTextureReady(st));
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int w, int h) {
        surfW = w;
        surfH = h;
    }

    @Override
    public void onFrameAvailable(SurfaceTexture st) {
        hasFrame = true;
        fpsFrames++;
        view.requestRender();
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glViewport(0, 0, surfW, surfH);
        GLES20.glClearColor(0f, 0f, 0f, 1f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        if (surfaceTexture == null) return;
        try {
            surfaceTexture.updateTexImage();
        } catch (Exception e) {
            return;
        }
        if (!hasFrame) return;
        surfaceTexture.getTransformMatrix(stM);

        // letterbox to keep the camera's aspect ratio
        float s = Math.min((float) surfW / videoW, (float) surfH / videoH);
        int vw = Math.round(videoW * s), vh = Math.round(videoH * s);
        GLES20.glViewport((surfW - vw) / 2, (surfH - vh) / 2, vw, vh);

        GLES20.glUseProgram(prog);
        bindCommon(prog);
        u1(prog, "uZoom", zoom);
        GLES20.glUniform2f(GLES20.glGetUniformLocation(prog, "uOff"), offX, offY);
        GLES20.glUniform2f(GLES20.glGetUniformLocation(prog, "uTx"), 1f / videoW, 1f / videoH);
        u1(prog, "uMir", mirror ? 1 : 0);
        u1(prog, "uFlv", flipV ? 1 : 0);
        u1(prog, "uPeak", peak ? 1 : 0);
        u1(prog, "uZeb", zebra ? 1 : 0);
        u1(prog, "uFc", falseColor ? 1 : 0);
        u1(prog, "uLog", logView ? 1 : 0);
        u1(prog, "uSens", sens);
        u1(prog, "uZl", zebraLevel);
        u1(prog, "uTime", (SystemClock.elapsedRealtime() - t0) / 1000f);
        GLES20.glUniform3f(GLES20.glGetUniformLocation(prog, "uPc"), pr, pg, pb);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

        frameCount++;
        if (histogram && frameCount % 5 == 0) histogramPass();

        long now = SystemClock.elapsedRealtime();
        if (fpsT == 0) fpsT = now;
        if (now - fpsT >= 1000) {
            final int fps = Math.round(fpsFrames * 1000f / (now - fpsT));
            fpsFrames = 0;
            fpsT = now;
            main.post(() -> listener.onFps(fps));
        }
    }

    private void histogramPass() {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo);
        GLES20.glViewport(0, 0, HW, HH);
        GLES20.glUseProgram(plainProg);
        bindCommon(plainProg);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        pix.position(0);
        GLES20.glReadPixels(0, 0, HW, HH, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pix);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);

        final float[] y = new float[64], r = new float[64], g = new float[64], b = new float[64];
        int n = HW * HH;
        for (int i = 0; i < n; i++) {
            int R = pix.get(i * 4) & 0xff, G = pix.get(i * 4 + 1) & 0xff, B = pix.get(i * 4 + 2) & 0xff;
            int L = (int) (0.2126f * R + 0.7152f * G + 0.0722f * B);
            y[Math.min(63, L >> 2)]++;
            r[R >> 2]++;
            g[G >> 2]++;
            b[B >> 2]++;
        }
        final boolean hi = y[63] / n > 0.01f, lo = y[0] / n > 0.01f;
        main.post(() -> listener.onHistogram(y, r, g, b, hi, lo));
    }

    private void bindCommon(int p) {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex);
        GLES20.glUniform1i(GLES20.glGetUniformLocation(p, "uTex"), 0);
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(p, "uM"), 1, false, stM, 0);
        int loc = GLES20.glGetAttribLocation(p, "aPos");
        GLES20.glEnableVertexAttribArray(loc);
        GLES20.glVertexAttribPointer(loc, 2, GLES20.GL_FLOAT, false, 0, quad);
    }

    private static void u1(int p, String name, float v) {
        GLES20.glUniform1f(GLES20.glGetUniformLocation(p, name), v);
    }

    private static int buildProgram(String vs, String fs) {
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, shader(GLES20.GL_VERTEX_SHADER, vs));
        GLES20.glAttachShader(p, shader(GLES20.GL_FRAGMENT_SHADER, fs));
        GLES20.glLinkProgram(p);
        int[] ok = new int[1];
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) Log.e(TAG, "link: " + GLES20.glGetProgramInfoLog(p));
        return p;
    }

    private static int shader(int type, String src) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) Log.e(TAG, "shader: " + GLES20.glGetShaderInfoLog(s));
        return s;
    }
}
