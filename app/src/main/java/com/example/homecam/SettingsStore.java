package com.example.homecam;

import android.content.Context;
import android.content.SharedPreferences;
import java.security.SecureRandom;

final class SettingsStore {
    private final SharedPreferences p;
    SettingsStore(Context c) { p = c.getSharedPreferences("homecam", Context.MODE_PRIVATE); }

    String serverUrl() { return p.getString("server", "wss://YOUR-SERVER.example/ws"); }
    void setServerUrl(String v) { p.edit().putString("server", v.trim()).apply(); }

    String homeId() {
        String id = p.getString("home_id", null);
        if (id == null) {
            int n = 10000000 + new SecureRandom().nextInt(90000000);
            id = Integer.toString(n);
            p.edit().putString("home_id", id).apply();
        }
        return id;
    }
    String homePassword() { return p.getString("home_password", ""); }
    void setHomePassword(String v) { p.edit().putString("home_password", v).apply(); }
    String viewerId() { return p.getString("viewer_id", ""); }
    void setViewerId(String v) { p.edit().putString("viewer_id", v.trim()).apply(); }
    String viewerPassword() { return p.getString("viewer_password", ""); }
    void setViewerPassword(String v) { p.edit().putString("viewer_password", v).apply(); }
}
