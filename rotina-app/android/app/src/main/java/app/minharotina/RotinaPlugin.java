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

import android.app.Activity;
import android.content.ContentValues;
import android.os.Environment;
import android.provider.MediaStore;
import android.speech.RecognizerIntent;

import androidx.activity.result.ActivityResult;
import androidx.core.content.FileProvider;

import com.getcapacitor.annotation.ActivityCallback;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

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

    /** Abre o reconhecimento de voz do Android em português. */
    @PluginMethod
    public void listen(PluginCall call) {
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR");
        i.putExtra(RecognizerIntent.EXTRA_PROMPT, "Fale a tarefa, o dia e o horário");
        try {
            startActivityForResult(call, i, "listenResult");
        } catch (Exception e) {
            call.reject("O reconhecimento de voz não está disponível neste celular", "NO_VOICE");
        }
    }

    @ActivityCallback
    private void listenResult(PluginCall call, ActivityResult result) {
        if (call == null) return;
        if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null) {
            call.reject("Cancelado", "CANCELLED");
            return;
        }
        ArrayList<String> r = result.getData().getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
        if (r == null || r.isEmpty()) {
            call.reject("Não entendi", "EMPTY");
            return;
        }
        JSObject o = new JSObject();
        o.put("text", r.get(0));
        call.resolve(o);
    }

    /** Salva a cópia de segurança em Downloads (ou abre o compartilhar em Androids antigos). */
    @PluginMethod
    public void saveBackup(PluginCall call) {
        String name = call.getString("name", "MinhaRotina.json");
        String data = call.getString("data", "");
        try {
            JSObject r = new JSObject();
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.Downloads.DISPLAY_NAME, name);
                v.put(MediaStore.Downloads.MIME_TYPE, "application/json");
                v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                Uri uri = getContext().getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (uri == null) throw new Exception("sem destino");
                try (OutputStream os = getContext().getContentResolver().openOutputStream(uri)) {
                    os.write(data.getBytes(StandardCharsets.UTF_8));
                }
                r.put("where", "Downloads");
            } else {
                File f = new File(getContext().getCacheDir(), name);
                try (FileOutputStream os = new FileOutputStream(f)) {
                    os.write(data.getBytes(StandardCharsets.UTF_8));
                }
                Uri uri = FileProvider.getUriForFile(getContext(), getContext().getPackageName() + ".fileprovider", f);
                Intent send = new Intent(Intent.ACTION_SEND).setType("application/json").putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                Intent chooser = Intent.createChooser(send, "Guardar cópia da rotina").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                getContext().startActivity(chooser);
                r.put("where", "share");
            }
            call.resolve(r);
        } catch (Exception e) {
            call.reject("Não foi possível salvar a cópia");
        }
    }
}
