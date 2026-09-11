package com.example.homecam;

import android.Manifest;
import android.app.Activity;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import org.json.JSONObject;
import org.webrtc.IceCandidate;
import org.webrtc.SessionDescription;
import org.webrtc.SurfaceViewRenderer;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {
    private SettingsStore settings;
    private TextView status;
    private EditText server;
    private EditText homePassword;
    private EditText viewerId;
    private EditText viewerPassword;
    private boolean pendingHomeStart;

    private SignalingClient viewerSignal;
    private WebRtcEngine viewerRtc;
    private String viewerSessionId;
    private SurfaceViewRenderer renderer;
    private boolean speakerOn = true;

    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (status != null) status.setText(i.getStringExtra("text"));
        }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        settings = new SettingsStore(this);
        showHomeScreen();
    }

    private TextView label(String s) {
        TextView t = new TextView(this); t.setText(s); t.setTextSize(16); t.setTextColor(Color.DKGRAY); t.setPadding(0,14,0,5); return t;
    }
    private EditText field(String hint, String value) {
        EditText e = new EditText(this); e.setHint(hint); e.setText(value); e.setSingleLine(true); e.setTextSize(17); return e;
    }
    private Button button(String text) { Button b = new Button(this); b.setText(text); b.setTextSize(16); return b; }

    private void showHomeScreen() {
        closeViewer();
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL); root.setPadding(32,32,32,32);
        ScrollView scroll = new ScrollView(this); scroll.addView(root); setContentView(scroll);

        TextView title = label("HomeCam"); title.setTextSize(28); title.setTextColor(Color.BLACK); root.addView(title);
        TextView subtitle = label("Камера включается только во время удалённого просмотра."); root.addView(subtitle);

        root.addView(label("Сервер"));
        server = field("wss://…/ws", settings.serverUrl()); root.addView(server);

        TextView id = label("ID этого телефона: " + settings.homeId()); id.setTextSize(20); id.setTextColor(Color.BLACK); root.addView(id);
        root.addView(label("Пароль для режима «Дом»"));
        homePassword = field("минимум 6 символов", settings.homePassword()); homePassword.setInputType(0x00000081); root.addView(homePassword);

        Button start = button("Включить режим «Дом»"); root.addView(start);
        Button stop = button("Выключить режим «Дом»"); root.addView(stop);
        status = label("Камера выключена"); status.setTextSize(17); root.addView(status);

        View line = new View(this); line.setBackgroundColor(Color.LTGRAY); root.addView(line, new LinearLayout.LayoutParams(-1,2));
        TextView watch = label("Смотреть другой телефон"); watch.setTextSize(22); watch.setTextColor(Color.BLACK); root.addView(watch);
        root.addView(label("ID домашнего телефона"));
        viewerId = field("12345678", settings.viewerId()); root.addView(viewerId);
        root.addView(label("Пароль"));
        viewerPassword = field("пароль", settings.viewerPassword()); viewerPassword.setInputType(0x00000081); root.addView(viewerPassword);
        Button connect = button("Смотреть"); root.addView(connect);

        start.setOnClickListener(v -> beginHomeMode());
        stop.setOnClickListener(v -> stopHomeMode());
        connect.setOnClickListener(v -> beginViewer());
    }

    private void saveCommon() { settings.setServerUrl(server.getText().toString()); }

    private void beginHomeMode() {
        saveCommon();
        String p = homePassword.getText().toString();
        if (p.length() < 6) { toast("Пароль — минимум 6 символов"); return; }
        if (!validServer(settings.serverUrl())) { toast("Сначала укажи адрес signaling-сервера"); return; }
        settings.setHomePassword(p);
        if (!hasHomePermissions()) { pendingHomeStart = true; requestHomePermissions(); return; }
        startHomeService();
    }

    private boolean validServer(String s) { return s.startsWith("ws://") || s.startsWith("wss://"); }

    private boolean hasHomePermissions() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return false;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return false;
        return Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestHomePermissions() {
        List<String> p = new ArrayList<>(); p.add(Manifest.permission.CAMERA); p.add(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT >= 33) p.add(Manifest.permission.POST_NOTIFICATIONS);
        requestPermissions(p.toArray(new String[0]), 77);
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 77 && pendingHomeStart) {
            pendingHomeStart = false;
            if (hasHomePermissions()) startHomeService(); else toast("Нужны разрешения камеры, микрофона и уведомлений");
        }
    }

    private void startHomeService() {
        Intent i = new Intent(this, HomeService.class).setAction(HomeService.ACTION_START);
        startForegroundService(i); status.setText("Запускаю режим «Дом»…");
    }
    private void stopHomeMode() { startService(new Intent(this, HomeService.class).setAction(HomeService.ACTION_STOP)); }

    private void beginViewer() {
        saveCommon();
        String id = viewerId.getText().toString().trim(); String p = viewerPassword.getText().toString();
        if (id.length() < 4 || p.length() < 6) { toast("Проверь ID и пароль"); return; }
        if (!validServer(settings.serverUrl())) { toast("Укажи адрес signaling-сервера"); return; }
        settings.setViewerId(id); settings.setViewerPassword(p);
        showViewerScreen(id, p);
    }

    private void showViewerScreen(String targetId, String password) {
        FrameLayout frame = new FrameLayout(this); frame.setBackgroundColor(Color.BLACK); setContentView(frame);
        renderer = new SurfaceViewRenderer(this); frame.addView(renderer, new FrameLayout.LayoutParams(-1,-1));

        LinearLayout bar = new LinearLayout(this); bar.setOrientation(LinearLayout.HORIZONTAL); bar.setGravity(Gravity.CENTER);
        bar.setPadding(12,12,12,22); bar.setBackgroundColor(0x88000000);
        Button back = button("Закрыть"); Button sw = button("Камера"); Button sound = button("Звук");
        bar.addView(back); bar.addView(sw); bar.addView(sound);
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM); frame.addView(bar,bp);
        TextView connecting = new TextView(this); connecting.setText("Подключение…"); connecting.setTextColor(Color.WHITE); connecting.setTextSize(18); connecting.setPadding(24,24,24,24); frame.addView(connecting);

        viewerRtc = new WebRtcEngine(this, new WebRtcEngine.Listener() {
            @Override public void onLocalDescription(SessionDescription sdp) {
                try { viewerSignal.send(new JSONObject().put("type","answer").put("sessionId",viewerSessionId).put("sdp",sdp.description)); } catch(Exception ignored) {}
            }
            @Override public void onIce(IceCandidate ice) {
                try { viewerSignal.send(new JSONObject().put("type","ice").put("sessionId",viewerSessionId).put("sdpMid",ice.sdpMid).put("sdpMLineIndex",ice.sdpMLineIndex).put("candidate",ice.sdp)); } catch(Exception ignored) {}
            }
            @Override public void onState(String state) { connecting.setText(state); if (state.contains("CONNECTED")) connecting.setVisibility(View.GONE); }
            @Override public void onError(String error) { connecting.setText("Ошибка: " + error); }
        });
        viewerRtc.setRemoteRenderer(renderer);
        viewerRtc.createPeer(false);

        viewerSignal = new SignalingClient(new SignalingClient.Listener() {
            @Override public void onOpen() { connecting.setText("Дом найден. Запрашиваю камеру…"); }
            @Override public void onJson(JSONObject j) {
                String type = j.optString("type","");
                if ("session".equals(type)) viewerSessionId = j.optString("sessionId","");
                else if ("offer".equals(type)) {
                    viewerSessionId = j.optString("sessionId",viewerSessionId);
                    viewerRtc.setRemoteSdp("offer",j.optString("sdp",""), () -> viewerRtc.createAnswer());
                } else if ("ice".equals(type)) viewerRtc.addIce(j);
                else if ("error".equals(type)) connecting.setText(j.optString("message","Ошибка подключения"));
            }
            @Override public void onClosed(String reason) { connecting.setVisibility(View.VISIBLE); connecting.setText("Соединение закрыто"); }
            @Override public void onError(String error) { connecting.setText("Сервер: " + error); }
        });
        viewerSignal.connect(settings.serverUrl(), "viewer", "viewer", Crypto.sha256(password), targetId);

        back.setOnClickListener(v -> showHomeScreen());
        sw.setOnClickListener(v -> {
            if (viewerSignal != null && viewerSessionId != null) try { viewerSignal.send(new JSONObject().put("type","control").put("sessionId",viewerSessionId).put("command","switch-camera")); } catch(Exception ignored) {}
        });
        sound.setOnClickListener(v -> { speakerOn = !speakerOn; android.media.AudioManager am = (android.media.AudioManager)getSystemService(AUDIO_SERVICE); am.setStreamMute(android.media.AudioManager.STREAM_VOICE_CALL, !speakerOn); sound.setText(speakerOn ? "Звук" : "Без звука"); });
    }

    private void closeViewer() {
        if (viewerSignal != null) {
            if (viewerSessionId != null) try { viewerSignal.send(new JSONObject().put("type","disconnect").put("sessionId",viewerSessionId)); } catch(Exception ignored) {}
            viewerSignal.close(); viewerSignal = null;
        }
        if (viewerRtc != null) { viewerRtc.release(); viewerRtc = null; }
        renderer = null; viewerSessionId = null;
    }

    private void toast(String s) { Toast.makeText(this,s,Toast.LENGTH_LONG).show(); }

    @Override protected void onStart() {
        super.onStart();
        IntentFilter f = new IntentFilter(HomeService.ACTION_STATUS);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(statusReceiver, f, RECEIVER_NOT_EXPORTED); else registerReceiver(statusReceiver, f);
    }
    @Override protected void onStop() { try { unregisterReceiver(statusReceiver); } catch(Exception ignored){} super.onStop(); }
    @Override protected void onDestroy() { closeViewer(); super.onDestroy(); }
}
