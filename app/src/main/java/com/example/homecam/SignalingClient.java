package com.example.homecam;

import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONObject;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import java.util.concurrent.TimeUnit;

final class SignalingClient {
    interface Listener {
        void onOpen();
        void onJson(JSONObject json);
        void onClosed(String reason);
        void onError(String error);
    }

    private final OkHttpClient client = new OkHttpClient.Builder()
            .pingInterval(25, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build();
    private final Handler main = new Handler(Looper.getMainLooper());
    private WebSocket ws;
    private final Listener listener;

    SignalingClient(Listener listener) { this.listener = listener; }

    void connect(String baseUrl, String role, String deviceId, String token, String targetId) {
        close();
        Uri.Builder b = Uri.parse(baseUrl).buildUpon()
                .appendQueryParameter("role", role)
                .appendQueryParameter("id", deviceId == null ? "" : deviceId)
                .appendQueryParameter("token", token == null ? "" : token);
        if (targetId != null) b.appendQueryParameter("target", targetId);
        Request request = new Request.Builder().url(b.build().toString()).build();
        ws = client.newWebSocket(request, new WebSocketListener() {
            @Override public void onOpen(WebSocket webSocket, Response response) { main.post(listener::onOpen); }
            @Override public void onMessage(WebSocket webSocket, String text) {
                try { JSONObject j = new JSONObject(text); main.post(() -> listener.onJson(j)); }
                catch (Exception e) { main.post(() -> listener.onError("Bad signaling message")); }
            }
            @Override public void onClosed(WebSocket webSocket, int code, String reason) { main.post(() -> listener.onClosed(reason)); }
            @Override public void onFailure(WebSocket webSocket, Throwable t, Response response) { main.post(() -> listener.onError(t.getMessage() == null ? "Connection error" : t.getMessage())); }
        });
    }

    void send(JSONObject j) { WebSocket s = ws; if (s != null) s.send(j.toString()); }
    void close() { WebSocket s = ws; ws = null; if (s != null) s.close(1000, "bye"); }
}
