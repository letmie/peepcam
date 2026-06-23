package com.peepcam;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class MjpegReader extends Thread {

    public interface FrameCallback {
        void onFrame(Bitmap bitmap);
        void onError(String message);
    }

    private final String mUrl;
    private final FrameCallback mCallback;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private volatile boolean mRunning = true;
    private HttpURLConnection mConnection;
    private Bitmap mRecycledBitmap;

    public MjpegReader(String url, FrameCallback callback) {
        mUrl = url;
        mCallback = callback;
    }

    public void shutdown() {
        mRunning = false;
        interrupt();
        if (mConnection != null) {
            mConnection.disconnect();
        }
    }

    @Override
    public void run() {
        while (mRunning) {
            try {
                readStream();
            } catch (Exception e) {
                if (mRunning) {
                    postError("Reconnecting...");
                    try { Thread.sleep(2000); } catch (InterruptedException ignored) { break; }
                }
            }
        }
    }

    private void readStream() throws IOException {
        URL url = new URL(mUrl);
        mConnection = (HttpURLConnection) url.openConnection();
        mConnection.setConnectTimeout(5000);
        mConnection.setReadTimeout(0);
        mConnection.connect();

        String contentType = mConnection.getContentType();
        String boundary = null;
        if (contentType != null && contentType.contains("boundary=")) {
            boundary = contentType.split("boundary=")[1].trim();
        }
        if (boundary == null) {
            boundary = "frame";
        }

        InputStream in = new BufferedInputStream(mConnection.getInputStream(), 131072);
        byte[] boundaryMarker = ("--" + boundary).getBytes();

        while (mRunning) {
            byte[] jpeg = readJpegFrame(in);
            if (jpeg == null) break;

            Bitmap bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
            if (bitmap != null && mRunning) {
                postFrame(bitmap);
            }
        }

        in.close();
        mConnection.disconnect();
    }

    private byte[] readJpegFrame(InputStream in) throws IOException {
        ByteArrayOutputStream frameBuf = new ByteArrayOutputStream(65536);

        scan: while (mRunning) {
            int b = in.read();
            if (b == -1) return null;

            if (b == 0xFF) {
                int next = in.read();
                if (next == -1) return null;

                if (next == 0xD8) {
                    frameBuf.write(0xFF);
                    frameBuf.write(0xD8);

                    while (mRunning) {
                        int fb = in.read();
                        if (fb == -1) return null;

                        if (fb == 0xFF) {
                            int fn = in.read();
                            if (fn == -1) return null;

                            frameBuf.write(0xFF);
                            frameBuf.write(fn);

                            if (fn == 0xD9) {
                                byte[] data = frameBuf.toByteArray();
                                frameBuf.reset();
                                return data;
                            }
                        } else {
                            frameBuf.write(fb);
                        }
                    }
                }
            }
        }

        return null;
    }

    private void postFrame(final Bitmap bitmap) {
        if (mCallback != null) {
            mHandler.post(() -> {
                if (mRunning) {
                    if (mRecycledBitmap != null && mRecycledBitmap != bitmap) {
                        mRecycledBitmap.recycle();
                    }
                    mRecycledBitmap = bitmap;
                    mCallback.onFrame(bitmap);
                }
            });
        }
    }

    private void postError(final String message) {
        if (mCallback != null) {
            mHandler.post(() -> {
                if (mRunning) mCallback.onError(message);
            });
        }
    }

    private static class ByteArrayOutputStream extends java.io.ByteArrayOutputStream {
        ByteArrayOutputStream(int size) { super(size); }
    }
}
