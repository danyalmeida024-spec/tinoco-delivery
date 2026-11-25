package app.minharotina;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.core.app.NotificationCompat;

import org.json.JSONObject;

import java.util.Calendar;
import java.util.Locale;

public class AlarmReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context c, Intent intent) {
        String action = intent.getAction();
        if (action == null) return;
        Scheduler.ensureChannels(c);
        switch (action) {
            case Scheduler.ACTION_FIRE: fire(c, intent); break;
            case Scheduler.ACTION_DONE: done(c, intent); break;
            case Scheduler.ACTION_SNOOZE: snooze(c, intent); break;
            case Scheduler.ACTION_STOP: dismiss(c, intent.getStringExtra("taskId")); break;
            default: Scheduler.rescheduleAll(c); // boot, mudança de hora ou fuso
        }
    }

    static int notifId(String taskId) { return Scheduler.code(taskId) & 0x0fffffff; }

    private void fire(Context c, Intent intent) {
        String taskId = intent.getStringExtra("taskId");
        long at = intent.getLongExtra("at", System.currentTimeMillis());
        boolean snooze = intent.getBooleanExtra("snooze", false);
        if (taskId == null) return;
        JSONObject t = Scheduler.findTask(c, taskId);
        if (t == null) return;
        if (!snooze) Scheduler.scheduleNext(c, t, at + 60_000);
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(at);
        String date = Scheduler.dateStr(cal);
        RotinaWidget.refresh(c);
        if (!snooze && Scheduler.isSkipped(c, taskId, date)) return; // já feita ou pulada
        show(c, t, date);
    }

    static PendingIntent actionPi(Context c, String action, String taskId, String date, int req) {
        Intent i = new Intent(c, AlarmReceiver.class).setAction(action);
        i.putExtra("taskId", taskId);
        i.putExtra("date", date);
        return PendingIntent.getBroadcast(c, req, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static void show(Context c, JSONObject t, String date) {
        String taskId = t.optString("id");
        String title = t.optString("title", "Tarefa");
        String time = t.optString("time", "");
        boolean alarm = "alarm".equals(t.optString("alert"));
        boolean firm = alarm && t.optBoolean("firm", false);
        int id = notifId(taskId);

        Intent open = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openPi = PendingIntent.getActivity(c, id, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder b = new NotificationCompat.Builder(c, alarm ? Scheduler.CH_ALARM : Scheduler.CH_NOTIFY)
            .setSmallIcon(R.drawable.ic_stat_rotina)
            .setColor(0xFF1C6A50)
            .setContentTitle(title)
            .setContentText(alarm ? "Alarme das " + time : "Agora: " + time)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(alarm ? NotificationCompat.CATEGORY_ALARM : NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(openPi)
            .setAutoCancel(!alarm)
            .addAction(0, "Feito", actionPi(c, Scheduler.ACTION_DONE, taskId, date, id + 1));
        if (!firm) b.addAction(0, "Soneca " + Scheduler.SNOOZE_MIN + " min", actionPi(c, Scheduler.ACTION_SNOOZE, taskId, date, id + 2));

        if (alarm) {
            Intent full = new Intent(c, AlarmActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_USER_ACTION)
                .putExtra("taskId", taskId).putExtra("date", date).putExtra("title", title).putExtra("time", time);
            PendingIntent fullPi = PendingIntent.getActivity(c, id + 3, full, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            b.setFullScreenIntent(fullPi, true).setOngoing(true);
            if (!firm) b.addAction(0, "Desligar", actionPi(c, Scheduler.ACTION_STOP, taskId, date, id + 4));
        }
        Notification n = b.build();
        if (alarm) n.flags |= Notification.FLAG_INSISTENT; // repete o som até desligar
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        try { nm.notify(id, n); } catch (SecurityException ignored) {}
    }

    static void dismiss(Context c, String taskId) {
        if (taskId == null) return;
        c.getSystemService(NotificationManager.class).cancel(notifId(taskId));
        c.sendBroadcast(new Intent(AlarmActivity.ACTION_CLOSE).setPackage(c.getPackageName()));
    }

    private void done(Context c, Intent intent) {
        String taskId = intent.getStringExtra("taskId");
        String date = intent.getStringExtra("date");
        dismiss(c, taskId);
        if (taskId == null || date == null) return;
        Scheduler.addPendingDone(c, taskId, date);
        RotinaWidget.refresh(c);
        RotinaPlugin.emitDone();
    }

    private void snooze(Context c, Intent intent) {
        String taskId = intent.getStringExtra("taskId");
        dismiss(c, taskId);
        if (taskId == null) return;
        JSONObject t = Scheduler.findTask(c, taskId);
        if (t == null) return;
        long at = System.currentTimeMillis() + Scheduler.SNOOZE_MIN * 60_000L;
        Scheduler.setAt(c, t, at, Scheduler.snoozeCode(taskId), true);
    }
}
