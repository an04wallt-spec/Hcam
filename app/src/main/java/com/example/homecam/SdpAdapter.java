package com.example.homecam;

import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;

abstract class SdpAdapter implements SdpObserver {
    @Override public void onCreateSuccess(SessionDescription sdp) {}
    @Override public void onSetSuccess() {}
    @Override public void onCreateFailure(String error) {}
    @Override public void onSetFailure(String error) {}
}
