package app.minharotina;

import android.Manifest;
import android.app.NotificationManager;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

import org.json.JSONArray;
import org.json.JSONObject;

/** Ponte entre o app (JavaScript) e os alarmes do Android. */
@CapacitorPlugin(
    name = "Rotina",
    permissions = { @Permission(alias = "notifications", strings = { Manifest.permission.POST_NOTIFICATIONS }) }
)
public class RotinaPlugin extends Plugin {
    private static RotinaPlugin instance;

    @Override
    public void load() {
        instance = this;
        Scheduler.ensureChannels(getContext());
    }

    static void emitDone() {
        RotinaPlugin p = instance;
        if (p != null) p.notifyListeners("done", new JSObject());
    }

    @PluginMethod
    public void sync(PluginCall call) {
        try {
            JSArray tasks = call.getArray("tasks", new JSArray());
            JSArray skip = call.getArray("skip", new JSArray());
            int n = Scheduler.sync(getContext(), new JSONArray(tasks.toString()), new JSONArray(skip.toString()));
            JSObject r = new JSObject();
            r.put("scheduled", n);
            call.resolve(r);
        } catch (Exception e) {
            call.reject("Não foi possível agendar os alarmes", e);
        }
    }

    @PluginMethod
    public void status(PluginCall call) {
        JSObject r = new JSObject();
        r.put("notifications", notificationsOn());
        r.put("exact", Scheduler.canExact(getContext()));
        boolean full = true;
        if (Build.VERSION.SDK_INT >= 34) full = getContext().getSystemService(NotificationManager.class).canUseFullScreenIntent();
        r.put("fullScreen", full);
        call.resolve(r);
    }

    private boolean notificationsOn() {
        if (Build.VERSION.SDK_INT >= 33 && getPermissionState("notifications") != PermissionState.GRANTED) return false;
        return getContext().getSystemService(NotificationManager.class).areNotificationsEnabled();
    }

    @PluginMethod
    public void requestNotifications(PluginCall call) {
        if (Build.VERSION.SDK_INT < 33 || getPermissionState("notifications") == PermissionState.GRANTED) {
            JSObject r = new JSObject();
            r.put("granted", notificationsOn());
            call.resolve(r);
            return;
        }
        requestPermissionForAlias("notifications", call, "notifCallback");
    }

    @PermissionCallback
    private void notifCallback(PluginCall call) {
        JSObject r = new JSObject();
        r.put("granted", notificationsOn());
        call.resolve(r);
    }

    @PluginMethod
    public void openSettings(PluginCall call) {
        String what = call.getString("what", "app");
        String pkg = getContext().getPackageName();
        Intent i;
        if ("exact".equals(what) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            i = new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + pkg));
        } else if ("fullscreen".equals(what) && Build.VERSION.SDK_INT >= 34) {
            i = new Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:" + pkg));
        } else if ("notifications".equals(what) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, pkg);
        } else {
            i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg));
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { getContext().startActivity(i); call.resolve(); }
        catch (Exception e) { call.reject("Não foi possível abrir as configurações"); }
    }

    @PluginMethod
    public void takeDone(PluginCall call) {
        try {
            JSObject r = new JSObject();
            r.put("items", new JSArray(Scheduler.takePendingDone(getContext())));
            call.resolve(r);
        } catch (Exception e) {
            call.reject("erro", e);
        }
    }

    /** Toca um alarme de teste daqui a alguns segundos. */
    @PluginMethod
    public void test(PluginCall call) {
        try {
            int secs = call.getInt("seconds", 10);
            JSONObject t = new JSONObject();
            t.put("id", "__teste__");
            t.put("title", "Teste de alarme");
            t.put("alert", "alarm");
            java.util.Calendar cal = java.util.Calendar.getInstance();
            cal.add(java.util.Calendar.SECOND, secs);
            t.put("time", String.format(java.util.Locale.US, "%02d:%02d", cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE)));
            // guarda a tarefa de teste para o receptor encontrar
            JSONArray tasks = new JSONArray(Scheduler.prefs(getContext()).getString("tasks", "[]"));
            JSONArray kept = new JSONArray();
            for (int i = 0; i < tasks.length(); i++) if (!"__teste__".equals(tasks.getJSONObject(i).optString("id"))) kept.put(tasks.get(i));
            kept.put(t);
            Scheduler.prefs(getContext()).edit().putString("tasks", kept.toString()).apply();
            Scheduler.setAt(getContext(), t, System.currentTimeMillis() + secs * 1000L, Scheduler.snoozeCode("__teste__"), true);
            call.resolve();
        } catch (Exception e) {
            call.reject("Não foi possível testar", e);
        }
    }
}
