package com.example.homecam;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import org.json.JSONObject;
import org.webrtc.IceCandidate;
import org.webrtc.SessionDescription;

public class HomeService extends Service {
    public static final String ACTION_START = "com.example.homecam.START_HOME";
    public static final String ACTION_STOP = "com.example.homecam.STOP_HOME";
    public static final String ACTION_STATUS = "com.example.homecam.STATUS";
    private static final String CHANNEL = "homecam_home";
    private static final int NOTIFICATION_ID = 41;

    private SignalingClient signaling;
    private WebRtcEngine rtc;
    private SettingsStore settings;
    private String sessionId;
    private boolean running;

    @Override public void onCreate() {
        super.onCreate();
        settings = new SettingsStore(this);
        createChannel();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopEverything();
            stopSelf();
            return START_NOT_STICKY;
        }
        startAsForeground();
        running = true;
        connectSignaling();
        return START_STICKY;
    }

    private void startAsForeground() {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(this, CHANNEL)
                .setContentTitle("HomeCam: дом активен")
                .setContentText("Камера выключена. Ожидаю подключение.")
                .setSmallIcon(android.R.drawable.presence_online)
                .setOngoing(true)
                .setContentIntent(pi)
                .build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, n,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA | ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } else {
            startForeground(NOTIFICATION_ID, n);
        }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel c = new NotificationChannel(CHANNEL, "HomeCam дома", NotificationManager.IMPORTANCE_LOW);
            c.setDescription("Служба ожидания удалённого подключения");
            getSystemService(NotificationManager.class).createNotificationChannel(c);
        }
    }

    private void connectSignaling() {
        if (!running) return;
        String password = settings.homePassword();
        if (password.isEmpty()) { broadcast("Не задан пароль"); return; }
        if (signaling != null) signaling.close();
        signaling = new SignalingClient(new SignalingClient.Listener() {
            @Override public void onOpen() { broadcast("Дом онлайн. Камера выключена."); }
            @Override public void onJson(JSONObject j) { handleSignal(j); }
            @Override public void onClosed(String reason) { if (running) reconnectLater(); }
            @Override public void onError(String error) { broadcast("Сервер: " + error); if (running) reconnectLater(); }
        });
        signaling.connect(settings.serverUrl(), "home", settings.homeId(), Crypto.sha256(password), null);
    }

    private void reconnectLater() {
        new android.os.Handler(getMainLooper()).postDelayed(() -> { if (running) connectSignaling(); }, 5000);
    }

    private void handleSignal(JSONObject j) {
        String type = j.optString("type", "");
        if ("viewer-request".equals(type)) {
            sessionId = j.optString("sessionId", "");
            startRtcAndOffer();
        } else if ("answer".equals(type) && sessionMatches(j)) {
            if (rtc != null) rtc.setRemoteSdp("answer", j.optString("sdp", ""), null);
        } else if ("ice".equals(type) && sessionMatches(j)) {
            if (rtc != null) rtc.addIce(j);
        } else if ("control".equals(type) && sessionMatches(j)) {
            String command = j.optString("command", "");
            if (rtc != null && "switch-camera".equals(command)) rtc.switchCamera();
        } else if ("viewer-disconnect".equals(type) && sessionMatches(j)) {
            endSession();
        }
    }

    private boolean sessionMatches(JSONObject j) { return sessionId != null && sessionId.equals(j.optString("sessionId", "")); }

    private void startRtcAndOffer() {
        endSessionRtcOnly();
        broadcast("Подключение: включаю камеру…");
        rtc = new WebRtcEngine(this, new WebRtcEngine.Listener() {
            @Override public void onLocalDescription(SessionDescription sdp) {
                try {
                    JSONObject j = new JSONObject().put("type", "offer").put("sessionId", sessionId).put("sdp", sdp.description);
                    signaling.send(j);
                } catch (Exception ignored) {}
            }
            @Override public void onIce(IceCandidate ice) {
                try {
                    signaling.send(new JSONObject().put("type", "ice").put("sessionId", sessionId)
                            .put("sdpMid", ice.sdpMid).put("sdpMLineIndex", ice.sdpMLineIndex).put("candidate", ice.sdp));
                } catch (Exception ignored) {}
            }
            @Override public void onState(String state) { broadcast(state); }
            @Override public void onError(String error) { broadcast("WebRTC: " + error); }
        });
        rtc.createPeer(true);
        rtc.createOffer();
    }

    private void endSessionRtcOnly() {
        if (rtc != null) { rtc.release(); rtc = null; }
    }

    private void endSession() {
        endSessionRtcOnly();
        sessionId = null;
        broadcast("Дом онлайн. Камера выключена.");
    }

    private void stopEverything() {
        running = false;
        endSession();
        if (signaling != null) { signaling.close(); signaling = null; }
        stopForeground(STOP_FOREGROUND_REMOVE);
        broadcast("Режим «Дом» выключен");
    }

    private void broadcast(String text) {
        Intent i = new Intent(ACTION_STATUS).setPackage(getPackageName()).putExtra("text", text);
        sendBroadcast(i);
    }

    @Override public void onDestroy() { stopEverything(); super.onDestroy(); }
    @Override public IBinder onBind(Intent intent) { return null; }
}
