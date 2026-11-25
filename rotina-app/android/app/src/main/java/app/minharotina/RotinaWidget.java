package app.minharotina;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.widget.RemoteViews;

import org.json.JSONObject;

import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/** Widget da tela inicial: mostra a próxima tarefa e deixa marcar como feita. */
public class RotinaWidget extends AppWidgetProvider {

    @Override
    public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
        RemoteViews v = build(c);
        for (int id : ids) m.updateAppWidget(id, v);
    }

    static void refresh(Context c) {
        try {
            AppWidgetManager m = AppWidgetManager.getInstance(c);
            int[] ids = m.getAppWidgetIds(new ComponentName(c, RotinaWidget.class));
            if (ids == null || ids.length == 0) return;
            RemoteViews v = build(c);
            for (int id : ids) m.updateAppWidget(id, v);
        } catch (Exception ignored) {}
    }

    static RemoteViews build(Context c) {
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_rotina);
        Intent open = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        v.setOnClickPendingIntent(R.id.w_root, PendingIntent.getActivity(c, 9000, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));

        Calendar now = Calendar.getInstance();
        String today = Scheduler.dateStr(now);
        String hm = String.format(Locale.US, "%02d:%02d", now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE));
        boolean hasTasks = !Scheduler.prefs(c).getString("tasks", "[]").replace("__teste__", "").equals("[]");
        List<JSONObject> pend = Scheduler.pendingFor(c, today);

        JSONObject next = null;
        for (JSONObject t : pend) { String tt = t.optString("time", ""); if (!tt.isEmpty() && tt.compareTo(hm) >= 0) { next = t; break; } }
        if (next == null && !pend.isEmpty()) next = pend.get(0);

        if (next == null) {
            v.setTextViewText(R.id.w_label, hasTasks ? "HOJE" : "MINHA ROTINA");
            v.setTextViewText(R.id.w_title, hasTasks ? "Tudo feito! 🎉" : "Toque para montar sua rotina");
            v.setViewVisibility(R.id.w_done, View.GONE);
            return v;
        }
        String time = next.optString("time", "");
        String label = (time.isEmpty() ? "PRÓXIMA" : "PRÓXIMA · " + time) + (pend.size() > 1 ? "  ·  faltam " + pend.size() : "");
        v.setTextViewText(R.id.w_label, label);
        v.setTextViewText(R.id.w_title, next.optString("title"));
        v.setViewVisibility(R.id.w_done, View.VISIBLE);
        Intent done = new Intent(c, AlarmReceiver.class).setAction(Scheduler.ACTION_DONE)
            .putExtra("taskId", next.optString("id")).putExtra("date", today);
        v.setOnClickPendingIntent(R.id.w_done, PendingIntent.getBroadcast(c, 9001, done, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        return v;
    }
}
