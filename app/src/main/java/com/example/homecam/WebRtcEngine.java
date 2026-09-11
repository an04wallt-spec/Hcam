package com.example.homecam;

import android.content.Context;
import org.json.JSONObject;
import org.webrtc.*;
import java.util.*;

final class WebRtcEngine {
    interface Listener {
        void onLocalDescription(SessionDescription sdp);
        void onIce(IceCandidate ice);
        void onState(String state);
        void onError(String error);
    }

    private final Context context;
    private final Listener listener;
    private final EglBase egl;
    private final PeerConnectionFactory factory;
    private PeerConnection pc;
    private CameraVideoCapturer capturer;
    private SurfaceTextureHelper textureHelper;
    private VideoSource videoSource;
    private AudioSource audioSource;
    private VideoTrack localVideo;
    private AudioTrack localAudio;
    private SurfaceViewRenderer remoteRenderer;
    private boolean cameraStarted;

    WebRtcEngine(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(this.context)
                        .setEnableInternalTracer(false)
                        .createInitializationOptions());
        egl = EglBase.create();
        DefaultVideoEncoderFactory enc = new DefaultVideoEncoderFactory(egl.getEglBaseContext(), true, true);
        DefaultVideoDecoderFactory dec = new DefaultVideoDecoderFactory(egl.getEglBaseContext());
        factory = PeerConnectionFactory.builder()
                .setVideoEncoderFactory(enc)
                .setVideoDecoderFactory(dec)
                .createPeerConnectionFactory();
    }

    EglBase.Context eglContext() { return egl.getEglBaseContext(); }

    void setRemoteRenderer(SurfaceViewRenderer renderer) {
        remoteRenderer = renderer;
        renderer.init(egl.getEglBaseContext(), null);
        renderer.setEnableHardwareScaler(true);
        renderer.setMirror(false);
    }

    void createPeer(boolean withLocalMedia) {
        closePeerOnly();
        List<PeerConnection.IceServer> servers = new ArrayList<>();
        servers.add(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer());
        PeerConnection.RTCConfiguration cfg = new PeerConnection.RTCConfiguration(servers);
        cfg.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
        pc = factory.createPeerConnection(cfg, new PeerConnection.Observer() {
            @Override public void onSignalingChange(PeerConnection.SignalingState s) {}
            @Override public void onIceConnectionChange(PeerConnection.IceConnectionState s) { listener.onState("ICE: " + s); }
            @Override public void onStandardizedIceConnectionChange(PeerConnection.IceConnectionState s) {}
            @Override public void onConnectionChange(PeerConnection.PeerConnectionState s) { listener.onState("Связь: " + s); }
            @Override public void onIceConnectionReceivingChange(boolean b) {}
            @Override public void onIceGatheringChange(PeerConnection.IceGatheringState s) {}
            @Override public void onIceCandidate(IceCandidate c) { listener.onIce(c); }
            @Override public void onIceCandidatesRemoved(IceCandidate[] c) {}
            @Override public void onAddStream(MediaStream s) {}
            @Override public void onRemoveStream(MediaStream s) {}
            @Override public void onDataChannel(DataChannel d) {}
            @Override public void onRenegotiationNeeded() {}
            @Override public void onAddTrack(RtpReceiver r, MediaStream[] streams) {
                MediaStreamTrack t = r.track();
                if (t instanceof VideoTrack && remoteRenderer != null) ((VideoTrack)t).addSink(remoteRenderer);
            }
            @Override public void onTrack(RtpTransceiver transceiver) {
                MediaStreamTrack t = transceiver.getReceiver().track();
                if (t instanceof VideoTrack && remoteRenderer != null) ((VideoTrack)t).addSink(remoteRenderer);
            }
            @Override public void onSelectedCandidatePairChanged(CandidatePairChangeEvent event) {}
        });
        if (pc == null) throw new IllegalStateException("PeerConnection creation failed");
        if (withLocalMedia) startLocalMedia();
    }

    private void startLocalMedia() {
        Camera2Enumerator en = new Camera2Enumerator(context);
        String selected = null;
        for (String n : en.getDeviceNames()) if (en.isBackFacing(n)) { selected = n; break; }
        if (selected == null && en.getDeviceNames().length > 0) selected = en.getDeviceNames()[0];
        if (selected == null) { listener.onError("Камера не найдена"); return; }
        capturer = en.createCapturer(selected, null);
        if (capturer == null) { listener.onError("Не удалось открыть камеру"); return; }
        textureHelper = SurfaceTextureHelper.create("HomeCamCapture", egl.getEglBaseContext());
        videoSource = factory.createVideoSource(false);
        capturer.initialize(textureHelper, context, videoSource.getCapturerObserver());
        try { capturer.startCapture(1280, 720, 24); cameraStarted = true; }
        catch (Exception e) { listener.onError("Ошибка камеры: " + e.getMessage()); }
        localVideo = factory.createVideoTrack("video0", videoSource);
        pc.addTrack(localVideo, Collections.singletonList("homecam"));

        MediaConstraints ac = new MediaConstraints();
        audioSource = factory.createAudioSource(ac);
        localAudio = factory.createAudioTrack("audio0", audioSource);
        pc.addTrack(localAudio, Collections.singletonList("homecam"));
    }

    void createOffer() {
        MediaConstraints mc = new MediaConstraints();
        pc.createOffer(new SdpAdapter() {
            @Override public void onCreateSuccess(SessionDescription sdp) {
                pc.setLocalDescription(new SdpAdapter() {
                    @Override public void onSetSuccess() { listener.onLocalDescription(sdp); }
                }, sdp);
            }
            @Override public void onCreateFailure(String e) { listener.onError(e); }
        }, mc);
    }

    void createAnswer() {
        MediaConstraints mc = new MediaConstraints();
        pc.createAnswer(new SdpAdapter() {
            @Override public void onCreateSuccess(SessionDescription sdp) {
                pc.setLocalDescription(new SdpAdapter() {
                    @Override public void onSetSuccess() { listener.onLocalDescription(sdp); }
                }, sdp);
            }
            @Override public void onCreateFailure(String e) { listener.onError(e); }
        }, mc);
    }

    void setRemoteSdp(String type, String sdp, Runnable afterSet) {
        SessionDescription.Type t = "offer".equalsIgnoreCase(type) ? SessionDescription.Type.OFFER : SessionDescription.Type.ANSWER;
        pc.setRemoteDescription(new SdpAdapter() {
            @Override public void onSetSuccess() { if (afterSet != null) afterSet.run(); }
            @Override public void onSetFailure(String e) { listener.onError(e); }
        }, new SessionDescription(t, sdp));
    }

    void addIce(JSONObject j) {
        if (pc == null) return;
        pc.addIceCandidate(new IceCandidate(j.optString("sdpMid", null), j.optInt("sdpMLineIndex", 0), j.optString("candidate", "")));
    }

    void switchCamera() { if (capturer != null) capturer.switchCamera(null); }
    void setAudioEnabled(boolean enabled) { if (localAudio != null) localAudio.setEnabled(enabled); }

    private void closePeerOnly() {
        if (pc != null) { pc.close(); pc.dispose(); pc = null; }
    }

    void stopSession() {
        closePeerOnly();
        if (capturer != null) {
            try { if (cameraStarted) capturer.stopCapture(); } catch (Exception ignored) {}
            capturer.dispose(); capturer = null;
        }
        cameraStarted = false;
        if (localVideo != null) { localVideo.dispose(); localVideo = null; }
        if (localAudio != null) { localAudio.dispose(); localAudio = null; }
        if (videoSource != null) { videoSource.dispose(); videoSource = null; }
        if (audioSource != null) { audioSource.dispose(); audioSource = null; }
        if (textureHelper != null) { textureHelper.dispose(); textureHelper = null; }
    }

    void release() {
        stopSession();
        if (remoteRenderer != null) { remoteRenderer.release(); remoteRenderer = null; }
        factory.dispose();
        egl.release();
    }
}
