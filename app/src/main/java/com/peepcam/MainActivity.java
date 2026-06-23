package com.peepcam;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PictureInPictureParams;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.Log;
import android.util.Rational;
import android.view.SurfaceHolder;
import android.view.View;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.SurfaceView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final String TAG = "PeepCam";
    private static final String PREFS_NAME = "peepcam";
    private static final String PREF_URL = "server_url";
    private static final String PREF_REMOTE = "cam_remote";
    private static final String PREF_LOCAL = "cam_local";

    private FrameLayout mCallOverlay;
    private SurfaceView mRemoteSurface, mLocalSurface;
    private SurfaceHolder mRemoteHolder, mLocalHolder;
    private MjpegReader mRemoteReader, mLocalReader;
    private Bitmap mRemoteBitmap, mLocalBitmap;

    private TextView mStatusText, mTimerText;
    private LinearLayout mControlBar;
    private Button mBtnCall;
    private ImageButton mBtnMute, mBtnEndCall, mBtnPip;

    private String mServerUrl = "";
    private List<CamInfo> mCameras = new ArrayList<>();
    private int mCamRemote = -1, mCamLocal = -1;
    private boolean mInCall = false;
    private boolean mMuted = true;
    private boolean mInPip = false;
    private long mCallStartTime = 0;

    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final Runnable mTimerTick = new Runnable() {
        @Override
        public void run() {
            if (mInCall && mCallStartTime > 0) {
                long elapsed = System.currentTimeMillis() - mCallStartTime;
                int secs = (int) (elapsed / 1000);
                int mins = secs / 60; secs %= 60;
                int hrs = mins / 60; mins %= 60;
                mTimerText.setText(String.format(Locale.US, "%02d:%02d:%02d", hrs, mins, secs));
                mMainHandler.postDelayed(this, 1000);
            }
        }
    };

    private static class CamInfo {
        int id;
        String name;
        CamInfo(int id, String name) { this.id = id; this.name = name; }
        @Override public String toString() { return name + " (Cam " + id + ")"; }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        mCallOverlay = findViewById(R.id.callOverlay);
        mRemoteSurface = findViewById(R.id.remoteSurface);
        mLocalSurface = findViewById(R.id.localSurface);
        mStatusText = findViewById(R.id.statusText);
        mTimerText = findViewById(R.id.timerText);
        mControlBar = findViewById(R.id.controlBar);
        mBtnCall = findViewById(R.id.btnCall);
        mBtnPip = findViewById(R.id.btnPip);
        mBtnMute = findViewById(R.id.btnMute);
        mBtnEndCall = findViewById(R.id.btnEndCall);

        mRemoteSurface.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder h) { mRemoteHolder = h; drawOnHolder(h, mRemoteBitmap); }
            @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int ht) {}
            @Override public void surfaceDestroyed(SurfaceHolder h) { mRemoteHolder = null; }
        });
        mLocalSurface.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder h) { mLocalHolder = h; drawOnHolder(h, mLocalBitmap); }
            @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int ht) {}
            @Override public void surfaceDestroyed(SurfaceHolder h) { mLocalHolder = null; }
        });

        mBtnCall.setOnClickListener(v -> startCall());
        mBtnEndCall.setOnClickListener(v -> endCall());
        mBtnPip.setOnClickListener(v -> enterPip());
        mBtnMute.setOnClickListener(v -> toggleMute());

        String savedUrl = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(PREF_URL, "");
        if (!savedUrl.isEmpty()) {
            mServerUrl = savedUrl;
            connectToServer();
        } else {
            showUrlDialog();
        }
    }

    private void showUrlDialog() {
        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle("Enter Server URL");
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setHint("http://192.168.1.100:8084");
        if (!mServerUrl.isEmpty()) input.setText(mServerUrl);
        b.setView(input);
        b.setPositiveButton("Connect", (d, w) -> {
            mServerUrl = input.getText().toString().trim();
            if (mServerUrl.endsWith("/")) mServerUrl = mServerUrl.substring(0, mServerUrl.length() - 1);
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putString(PREF_URL, mServerUrl).apply();
            connectToServer();
        });
        b.setNegativeButton("Cancel", (d, w) -> { if (mServerUrl.isEmpty()) finish(); });
        b.setCancelable(false);
        b.show();
    }

    private void connectToServer() {
        showStatus("Connecting...");
        mBtnCall.setEnabled(false);
        new Thread(() -> {
            try {
                URL url = new URL(mServerUrl + "/api/cams");
                HttpURLConnection c = (HttpURLConnection) url.openConnection();
                c.setConnectTimeout(5000); c.setReadTimeout(5000);
                BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String l; while ((l = r.readLine()) != null) sb.append(l);
                r.close();
                JSONObject j = new JSONObject(sb.toString());
                JSONArray arr = j.getJSONArray("cameras");
                mCameras.clear();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject cam = arr.getJSONObject(i);
                    mCameras.add(new CamInfo(cam.getInt("id"), cam.optString("name", "Cam " + cam.getInt("id"))));
                }
                runOnUiThread(() -> {
                    if (mCameras.size() < 2) {
                        showStatus("Need 2+ cameras, found " + mCameras.size());
                        return;
                    }
                    hideStatus();
                    showCameraPicker();
                });
            } catch (Exception e) {
                runOnUiThread(() -> showStatus("Failed: " + e.getMessage()));
            }
        }).start();
    }

    private void showCameraPicker() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        int savedRemote = prefs.getInt(PREF_REMOTE, -1);
        int savedLocal = prefs.getInt(PREF_LOCAL, -1);

        // check saved choices are still valid
        boolean remoteValid = false, localValid = false;
        for (CamInfo ci : mCameras) {
            if (ci.id == savedRemote) remoteValid = true;
            if (ci.id == savedLocal) localValid = true;
        }
        if (remoteValid && localValid && savedRemote != savedLocal) {
            mCamRemote = savedRemote;
            mCamLocal = savedLocal;
            onCamerasReady();
            return;
        }

        // picker dialog
        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle("Select Cameras");

        View v = getLayoutInflater().inflate(android.R.layout.simple_list_item_2, null);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(32, 16, 32, 16);

        TextView tvRemote = new TextView(this);
        tvRemote.setText("Remote camera (main view):");
        tvRemote.setTextSize(14);
        layout.addView(tvRemote);

        String[] names = new String[mCameras.size()];
        for (int i = 0; i < mCameras.size(); i++) names[i] = mCameras.get(i).toString();
        int defaultRemote = 0, defaultLocal = Math.min(1, mCameras.size() - 1);

        EditText remoteInput = new EditText(this);
        remoteInput.setHint("Enter remote cam ID (e.g. 0)");
        remoteInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        remoteInput.setText(String.valueOf(mCameras.get(defaultRemote).id));
        layout.addView(remoteInput);

        TextView tvLocal = new TextView(this);
        tvLocal.setText("Local camera (small overlay):");
        tvLocal.setTextSize(14);
        tvLocal.setPadding(0, 16, 0, 0);
        layout.addView(tvLocal);

        EditText localInput = new EditText(this);
        localInput.setHint("Enter local cam ID (e.g. 2)");
        localInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        localInput.setText(String.valueOf(mCameras.get(defaultLocal).id));
        layout.addView(localInput);

        // show available cameras
        TextView tvAvail = new TextView(this);
        StringBuilder sb = new StringBuilder("\nAvailable cameras:\n");
        for (CamInfo ci : mCameras) sb.append("  ").append(ci.id).append(": ").append(ci.name).append("\n");
        tvAvail.setText(sb.toString());
        tvAvail.setTextSize(12);
        tvAvail.setPadding(0, 8, 0, 0);
        layout.addView(tvAvail);

        b.setView(layout);
        b.setPositiveButton("Start Call", (d, w) -> {
            try {
                int r = Integer.parseInt(remoteInput.getText().toString().trim());
                int l = Integer.parseInt(localInput.getText().toString().trim());
                boolean rOk = false, lOk = false;
                for (CamInfo ci : mCameras) { if (ci.id == r) rOk = true; if (ci.id == l) lOk = true; }
                if (rOk && lOk && r != l) {
                    mCamRemote = r;
                    mCamLocal = l;
                    getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                        .putInt(PREF_REMOTE, r).putInt(PREF_LOCAL, l).apply();
                    onCamerasReady();
                } else {
                    showStatus("Invalid camera selection");
                    showCameraPicker();
                }
            } catch (Exception e) {
                showStatus("Invalid input");
                showCameraPicker();
            }
        });
        b.setNegativeButton("Cancel", (d, w) -> showUrlDialog());
        b.setCancelable(false);
        b.show();
    }

    private void onCamerasReady() {
        mBtnCall.setEnabled(true);
        mBtnCall.setVisibility(View.VISIBLE);
        mBtnCall.setText("CALL");
        mStatusText.setText("Ready — Remote: Cam " + mCamRemote + ", Local: Cam " + mCamLocal);
        mStatusText.setVisibility(View.VISIBLE);

        // start previewing remote cam in background
        new Thread(() -> {
            try {
                URL u = new URL(mServerUrl + "/cam/" + mCamRemote + ".mjpeg");
                HttpURLConnection c = (HttpURLConnection) u.openConnection();
                c.setConnectTimeout(3000);
                c.connect();
                // just testing reachability
                c.disconnect();
            } catch (Exception e) {
                runOnUiThread(() -> showStatus("Cam " + mCamRemote + " unreachable: " + e.getMessage()));
            }
        }).start();
    }

    // -- call --

    private void startCall() {
        mInCall = true;
        mCallStartTime = System.currentTimeMillis();

        mBtnCall.setVisibility(View.GONE);
        mCallOverlay.setVisibility(View.VISIBLE);
        mControlBar.setVisibility(View.VISIBLE);
        mTimerText.setVisibility(View.VISIBLE);
        mStatusText.setVisibility(View.GONE);
        mMuted = true;
        updateMuteIcon();

        mMainHandler.post(mTimerTick);

        new Thread(() -> {
            try {
                JSONObject j = new JSONObject();
                j.put("action", "start");
                postJson(mServerUrl + "/api/call", j);
            } catch (Exception e) { Log.e(TAG, "call start: " + e.getMessage()); }
        }).start();

        startStreams();
    }

    private void endCall() {
        mInCall = false;
        mCallStartTime = 0;
        mMainHandler.removeCallbacks(mTimerTick);

        mBtnCall.setVisibility(View.VISIBLE);
        mCallOverlay.setVisibility(View.GONE);
        mControlBar.setVisibility(View.GONE);
        mTimerText.setVisibility(View.GONE);
        mStatusText.setVisibility(View.VISIBLE);
        mBtnCall.setEnabled(true);
        mStatusText.setText("Ready — Remote: Cam " + mCamRemote + ", Local: Cam " + mCamLocal);

        stopStreams();

        new Thread(() -> {
            try {
                JSONObject j = new JSONObject();
                j.put("action", "end");
                postJson(mServerUrl + "/api/call", j);
            } catch (Exception e) { Log.e(TAG, "call end: " + e.getMessage()); }
        }).start();
    }

    private void toggleMute() {
        mMuted = !mMuted;
        updateMuteIcon();
    }

    private void updateMuteIcon() {
        mBtnMute.setImageResource(mMuted
            ? android.R.drawable.ic_menu_close_clear_cancel
            : android.R.drawable.ic_btn_speak_now);
    }

    // -- streams --

    private void startStreams() {
        stopStreams();
        mRemoteReader = new MjpegReader(
            mServerUrl + "/cam/" + mCamRemote + ".mjpeg",
            bm -> { mRemoteBitmap = bm; drawOnHolder(mRemoteHolder, bm); }
        );
        mLocalReader = new MjpegReader(
            mServerUrl + "/cam/" + mCamLocal + ".mjpeg",
            bm -> { mLocalBitmap = bm; drawOnHolder(mLocalHolder, bm); }
        );
        mRemoteReader.start();
        mLocalReader.start();
    }

    private void stopStreams() {
        if (mRemoteReader != null) { mRemoteReader.shutdown(); mRemoteReader = null; }
        if (mLocalReader != null) { mLocalReader.shutdown(); mLocalReader = null; }
        if (mRemoteBitmap != null) { mRemoteBitmap.recycle(); mRemoteBitmap = null; }
        if (mLocalBitmap != null) { mLocalBitmap.recycle(); mLocalBitmap = null; }
    }

    private void drawOnHolder(SurfaceHolder h, Bitmap bm) {
        if (h == null || bm == null) return;
        Canvas cv = h.lockCanvas();
        if (cv != null) {
            cv.drawBitmap(bm, null,
                new Rect(0, 0, h.getSurfaceFrame().width(), h.getSurfaceFrame().height()), null);
            h.unlockCanvasAndPost();
        }
    }

    // -- PiP --

    private void enterPip() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        mInPip = true;
        enterPictureInPictureMode(new PictureInPictureParams.Builder()
            .setAspectRatio(new Rational(16, 9)).build());
    }

    @Override
    public void onPictureInPictureModeChanged(boolean pip, Configuration c) {
        super.onPictureInPictureModeChanged(pip, c);
        mInPip = pip;
        mControlBar.setVisibility(pip ? View.GONE : mInCall ? View.VISIBLE : View.GONE);
        mBtnCall.setVisibility(pip || mInCall ? View.GONE : View.VISIBLE);
    }

    // -- lifecycle --

    @Override
    protected void onDestroy() { stopStreams(); super.onDestroy(); }

    private void showStatus(String msg) {
        runOnUiThread(() -> { mStatusText.setText(msg); mStatusText.setVisibility(View.VISIBLE); });
    }
    private void hideStatus() {
        runOnUiThread(() -> mStatusText.setVisibility(View.GONE));
    }

    private void postJson(String urlStr, JSONObject j) {
        try {
            URL u = new URL(urlStr);
            HttpURLConnection c = (HttpURLConnection) u.openConnection();
            c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", "application/json");
            c.setDoOutput(true);
            c.setConnectTimeout(3000);
            OutputStream os = c.getOutputStream();
            os.write(j.toString().getBytes());
            os.close();
            c.getResponseCode();
            c.disconnect();
        } catch (Exception e) { Log.e(TAG, "POST " + urlStr + ": " + e.getMessage()); }
    }
}
