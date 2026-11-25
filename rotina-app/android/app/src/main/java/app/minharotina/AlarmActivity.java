package app.minharotina;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Tela cheia do despertador, aparece por cima da tela bloqueada. */
public class AlarmActivity extends Activity {
    static final String ACTION_CLOSE = "app.minharotina.CLOSE_ALARM";
    private String taskId, date;
    private boolean firm;

    private final BroadcastReceiver closer = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { finish(); }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        Intent in = getIntent();
        taskId = in.getStringExtra("taskId");
        date = in.getStringExtra("date");
        String title = in.getStringExtra("title");
        String time = in.getStringExtra("time");

        int orange = 0xFFA86D14;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(orange);
        int pad = dp(28);
        root.setPadding(pad, pad, pad, pad);

        TextView label = text("ALARME", 14, false);
        label.setLetterSpacing(0.15f);
        label.setAlpha(0.85f);
        TextView clock = text(time == null ? "" : time, 88, true);
        TextView name = text(title == null ? "" : title, 30, true);
        name.setPadding(0, dp(8), 0, dp(14));
        org.json.JSONObject task = taskId == null ? null : Scheduler.findTask(this, taskId);
        firm = task != null && task.optBoolean("firm", false);
        String resumo = (date == null || taskId == null || taskId.startsWith("__")) ? "" : Scheduler.daySummary(this, date, taskId, time);
        TextView summary = text(resumo, 18, false);
        summary.setAlpha(0.92f);
        summary.setPadding(0, 0, 0, dp(firm ? 16 : 30));
        TextView firmNote = text(firm ? "Para desligar, faça a tarefa e toque em Feito" : "", 15, false);
        firmNote.setAlpha(0.85f);
        firmNote.setPadding(0, 0, 0, dp(firm ? 22 : 0));

        Button doneBtn = button("Feito ✓", Color.WHITE, orange);
        Button snoozeBtn = button("Soneca " + Scheduler.SNOOZE_MIN + " min", 0x33FFFFFF, Color.WHITE);
        Button stopBtn = button("Só desligar", Color.TRANSPARENT, Color.WHITE);

        doneBtn.setOnClickListener(v -> send(Scheduler.ACTION_DONE));
        snoozeBtn.setOnClickListener(v -> send(Scheduler.ACTION_SNOOZE));
        stopBtn.setOnClickListener(v -> send(Scheduler.ACTION_STOP));

        root.addView(label);
        root.addView(clock);
        root.addView(name);
        if (!resumo.isEmpty()) root.addView(summary);
        if (firm) root.addView(firmNote);
        root.addView(doneBtn, btnParams());
        if (!firm) {
            root.addView(snoozeBtn, btnParams());
            root.addView(stopBtn, btnParams());
        }
        setContentView(root);

        IntentFilter f = new IntentFilter(ACTION_CLOSE);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(closer, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(closer, f);
    }

    @Override protected void onDestroy() {
        try { unregisterReceiver(closer); } catch (Exception ignored) {}
        super.onDestroy();
    }

    @Override public void onBackPressed() {
        if (!firm) send(Scheduler.ACTION_SNOOZE);
    }

    private void send(String action) {
        Intent i = new Intent(this, AlarmReceiver.class).setAction(action);
        i.putExtra("taskId", taskId);
        i.putExtra("date", date);
        sendBroadcast(i);
        finish();
    }

    private int dp(int v) { return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()); }

    private TextView text(String s, int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setGravity(Gravity.CENTER);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private Button button(String s, int bg, int fg) {
        Button bt = new Button(this);
        bt.setText(s);
        bt.setAllCaps(false);
        bt.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        bt.setTypeface(Typeface.DEFAULT_BOLD);
        bt.setTextColor(fg);
        GradientDrawable g = new GradientDrawable();
        g.setColor(bg);
        g.setCornerRadius(dp(16));
        bt.setBackground(g);
        return bt;
    }

    private LinearLayout.LayoutParams btnParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(60));
        p.topMargin = dp(10);
        return p;
    }
}
