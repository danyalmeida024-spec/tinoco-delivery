package app.minharotina;

import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Calendar;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Calcula o próximo horário de cada tarefa e agenda no AlarmManager.
 * A regra é a mesma do app: todo dia, dias da semana ou data única.
 * Depois de tocar, a tarefa já agenda a próxima vez sozinha, então os
 * alarmes continuam mesmo que o app fique dias sem ser aberto.
 */
public class Scheduler {
    static final String PREFS = "rotina_nativo";
    static final String CH_ALARM = "rotina_alarme";
    static final String CH_NOTIFY = "rotina_lembrete";
    static final String ACTION_FIRE = "app.minharotina.FIRE";
    static final String ACTION_DONE = "app.minharotina.DONE";
    static final String ACTION_SNOOZE = "app.minharotina.SNOOZE";
    static final String ACTION_STOP = "app.minharotina.STOP";
    static final int SNOOZE_MIN = 5;

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static int code(String taskId) {
        return (taskId.hashCode() & 0x1fffffff) | 0x10000000;
    }

    static int snoozeCode(String taskId) {
        return (taskId.hashCode() & 0x1fffffff) | 0x40000000;
    }

    static void ensureChannels(Context c) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm.getNotificationChannel(CH_ALARM) == null) {
            NotificationChannel ch = new NotificationChannel(CH_ALARM, "Alarmes", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("Despertador das tarefas marcadas com alarme");
            Uri sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            if (sound == null) sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
            AudioAttributes aa = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
            ch.setSound(sound, aa);
            ch.enableVibration(true);
            ch.setVibrationPattern(new long[] { 0, 600, 400, 600, 400, 600 });
            ch.setLockscreenVisibility(android.app.Notification.VISIBILITY_PUBLIC);
            nm.createNotificationChannel(ch);
        }
        if (nm.getNotificationChannel(CH_NOTIFY) == null) {
            NotificationChannel ch = new NotificationChannel(CH_NOTIFY, "Lembretes", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("Aviso no horário das tarefas");
            ch.enableVibration(true);
            nm.createNotificationChannel(ch);
        }
    }

    static boolean canExact(Context c) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true;
        return c.getSystemService(AlarmManager.class).canScheduleExactAlarms();
    }

    static String dateStr(Calendar cal) {
        return String.format(Locale.US, "%04d-%02d-%02d", cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH));
    }

    static boolean applies(JSONObject t, Calendar cal) {
        String ds = dateStr(cal);
        String repeat = t.optString("repeat", "daily");
        if (repeat.equals("once")) return ds.equals(t.optString("date"));
        String start = t.optString("start", "");
        if (!start.isEmpty() && ds.compareTo(start) < 0) return false;
        if (repeat.equals("daily")) return true;
        if (repeat.equals("weekly")) {
            JSONArray days = t.optJSONArray("days");
            if (days == null) return false;
            int jsDow = cal.get(Calendar.DAY_OF_WEEK) - 1; // 0 = domingo
            for (int i = 0; i < days.length(); i++) if (days.optInt(i, -1) == jsDow) return true;
        }
        return false;
    }

    /** Próximo disparo depois de `after` (ms), ou -1. */
    static long next(JSONObject t, long after) {
        String alert = t.optString("alert", "none");
        String time = t.optString("time", "");
        if (alert.equals("none") || time.length() < 4) return -1;
        String[] hm = time.split(":");
        int h, m;
        try { h = Integer.parseInt(hm[0]); m = Integer.parseInt(hm[1]); } catch (Exception e) { return -1; }
        String repeat = t.optString("repeat", "daily");
        if (repeat.equals("once")) {
            String[] p = t.optString("date", "").split("-");
            if (p.length != 3) return -1;
            Calendar cal = Calendar.getInstance();
            cal.set(Integer.parseInt(p[0]), Integer.parseInt(p[1]) - 1, Integer.parseInt(p[2]), h, m, 0);
            cal.set(Calendar.MILLISECOND, 0);
            return cal.getTimeInMillis() > after ? cal.getTimeInMillis() : -1;
        }
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(after);
        cal.set(Calendar.HOUR_OF_DAY, h);
        cal.set(Calendar.MINUTE, m);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        for (int i = 0; i < 400; i++) {
            if (cal.getTimeInMillis() > after && applies(t, cal)) return cal.getTimeInMillis();
            cal.add(Calendar.DAY_OF_MONTH, 1);
            cal.set(Calendar.HOUR_OF_DAY, h);
            cal.set(Calendar.MINUTE, m);
        }
        return -1;
    }

    static PendingIntent firePi(Context c, int requestCode, String taskId, long at, boolean snooze, int flags) {
        Intent i = new Intent(c, AlarmReceiver.class).setAction(ACTION_FIRE);
        i.putExtra("taskId", taskId);
        i.putExtra("at", at);
        i.putExtra("snooze", snooze);
        return PendingIntent.getBroadcast(c, requestCode, i, flags | PendingIntent.FLAG_IMMUTABLE);
    }

    static JSONObject findTask(Context c, String taskId) {
        try {
            JSONArray arr = new JSONArray(prefs(c).getString("tasks", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject t = arr.getJSONObject(i);
                if (taskId.equals(t.optString("id"))) return t;
            }
        } catch (Exception ignored) {}
        return null;
    }

    static void setAt(Context c, JSONObject t, long at, int requestCode, boolean snooze) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        String taskId = t.optString("id");
        PendingIntent pi = firePi(c, requestCode, taskId, at, snooze, PendingIntent.FLAG_UPDATE_CURRENT);
        boolean alarm = "alarm".equals(t.optString("alert"));
        if (canExact(c)) {
            if (alarm) {
                Intent open = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                PendingIntent show = PendingIntent.getActivity(c, 7, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                am.setAlarmClock(new AlarmManager.AlarmClockInfo(at, show), pi);
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            } else {
                am.setExact(AlarmManager.RTC_WAKEUP, at, pi);
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        } else {
            am.set(AlarmManager.RTC_WAKEUP, at, pi);
        }
    }

    /** Agenda a próxima vez da tarefa depois de `after`. */
    static void scheduleNext(Context c, JSONObject t, long after) {
        long at = next(t, after);
        if (at > 0) setAt(c, t, at, code(t.optString("id")), false);
    }

    static void cancelCode(Context c, int requestCode, String taskId) {
        PendingIntent pi = firePi(c, requestCode, taskId, 0, false, PendingIntent.FLAG_NO_CREATE);
        if (pi != null) {
            c.getSystemService(AlarmManager.class).cancel(pi);
            pi.cancel();
        }
    }

    /** Substitui todas as tarefas e reagenda tudo. */
    static int sync(Context c, JSONArray tasks, JSONArray skip) {
        SharedPreferences p = prefs(c);
        Set<String> old = p.getStringSet("ids", new HashSet<>());
        for (String id : old) cancelCode(c, code(id), id);
        Set<String> ids = new HashSet<>();
        int n = 0;
        long now = System.currentTimeMillis();
        p.edit().putString("tasks", tasks.toString()).putString("skip", skip == null ? "[]" : skip.toString()).apply();
        for (int i = 0; i < tasks.length(); i++) {
            JSONObject t = tasks.optJSONObject(i);
            if (t == null) continue;
            String id = t.optString("id");
            ids.add(id);
            long at = next(t, now);
            if (at > 0) { setAt(c, t, at, code(id), false); n++; }
        }
        p.edit().putStringSet("ids", ids).apply();
        RotinaWidget.refresh(c);
        return n;
    }

    static void rescheduleAll(Context c) {
        try {
            JSONArray tasks = new JSONArray(prefs(c).getString("tasks", "[]"));
            JSONArray skip = new JSONArray(prefs(c).getString("skip", "[]"));
            sync(c, tasks, skip);
        } catch (Exception ignored) {}
    }

    static Calendar calFor(String date) {
        Calendar cal = Calendar.getInstance();
        try {
            String[] p = date.split("-");
            cal.set(Integer.parseInt(p[0]), Integer.parseInt(p[1]) - 1, Integer.parseInt(p[2]), 12, 0, 0);
        } catch (Exception ignored) {}
        return cal;
    }

    /** Tarefas do dia que não foram feitas nem puladas, em ordem de horário. */
    static java.util.List<JSONObject> pendingFor(Context c, String date) {
        java.util.List<JSONObject> out = new java.util.ArrayList<>();
        try {
            JSONArray arr = new JSONArray(prefs(c).getString("tasks", "[]"));
            Calendar cal = calFor(date);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject t = arr.getJSONObject(i);
                String id = t.optString("id");
                if (id.startsWith("__")) continue;
                if (!applies(t, cal) || isSkipped(c, id, date)) continue;
                out.add(t);
            }
        } catch (Exception ignored) {}
        java.util.Collections.sort(out, (a, b) -> {
            String ta = a.optString("time", ""), tb = b.optString("time", "");
            if (ta.isEmpty()) ta = "99:99";
            if (tb.isEmpty()) tb = "99:99";
            return ta.compareTo(tb);
        });
        return out;
    }

    /** Resumo para a tela do despertador: quantas tarefas hoje e qual vem depois. */
    static String daySummary(Context c, String date, String taskId, String time) {
        java.util.List<JSONObject> list = pendingFor(c, date);
        int n = list.size();
        StringBuilder sb = new StringBuilder();
        sb.append("Hoje: ").append(n).append(n == 1 ? " tarefa" : " tarefas");
        for (JSONObject t : list) {
            String tt = t.optString("time", "");
            if (!t.optString("id").equals(taskId) && !tt.isEmpty() && time != null && tt.compareTo(time) > 0) {
                sb.append("\nDepois: ").append(t.optString("title")).append(" às ").append(tt);
                break;
            }
        }
        return sb.toString();
    }

    static boolean isSkipped(Context c, String taskId, String date) {
        try {
            JSONArray skip = new JSONArray(prefs(c).getString("skip", "[]"));
            String k = taskId + "|" + date;
            for (int i = 0; i < skip.length(); i++) if (k.equals(skip.optString(i))) return true;
        } catch (Exception ignored) {}
        return false;
    }

    /** Guarda "feito" vindo do alarme para o app aplicar quando abrir. */
    static void addPendingDone(Context c, String taskId, String date) {
        SharedPreferences p = prefs(c);
        try {
            JSONArray arr = new JSONArray(p.getString("pendingDone", "[]"));
            JSONObject o = new JSONObject();
            o.put("taskId", taskId);
            o.put("date", date);
            arr.put(o);
            p.edit().putString("pendingDone", arr.toString()).apply();
            JSONArray skip = new JSONArray(p.getString("skip", "[]"));
            skip.put(taskId + "|" + date);
            p.edit().putString("skip", skip.toString()).apply();
        } catch (Exception ignored) {}
    }

    static String takePendingDone(Context c) {
        SharedPreferences p = prefs(c);
        String s = p.getString("pendingDone", "[]");
        p.edit().putString("pendingDone", "[]").apply();
        return s;
    }
}
