package app.stremioorigin.tv;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;

/* the last start died or gave up in its first seconds: its own log (data/laststart.shown.txt, kept by MainActivity) on
   the screen, plain Android views only (nothing of the app's own drawing, which may be what failed), so whoever sees it
   can photograph it. Start again opens the app afresh (a safe start: the app's start mark is still there) */
public class StartReport extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        File f = new File(new File(getFilesDir(), "data"), "laststart.shown.txt");
        ArrayList<String> lines = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"))) {
            String l; while ((l = r.readLine()) != null) { lines.add(l); if (lines.size() > 600) lines.remove(0); }
        } catch (Throwable e) { lines.add("(the log could not be read: " + e + ")"); }
        StringBuilder sb = new StringBuilder(); for (String l : lines) sb.append(l).append('\n');

        float d = getResources().getDisplayMetrics().density; int pad = (int) (24 * d);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(Color.rgb(16, 14, 24)); root.setPadding(pad, pad / 2, pad, pad / 2);
        TextView title = new TextView(this); title.setTextColor(Color.WHITE); title.setTextSize(20); title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setText("Stremio Origin did not start last time");
        TextView hint = new TextView(this); hint.setTextColor(Color.rgb(200, 200, 210)); hint.setTextSize(14);
        hint.setText("Please take a photo of this screen and send it to us. Press Down to reach the log, then Up and Down to scroll it; the end of the log is shown first.");
        LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.START);
        Button again = new Button(this); again.setText("Start again");
        Button close = new Button(this); close.setText("Close");
        row.addView(again); row.addView(close);
        final ScrollView sv = new ScrollView(this); sv.setFocusable(true); sv.setBackgroundColor(Color.rgb(28, 26, 38));
        final TextView log = new TextView(this); log.setTextColor(Color.rgb(230, 230, 235)); log.setTextSize(11); log.setTypeface(Typeface.MONOSPACE); log.setPadding(pad / 2, pad / 3, pad / 2, pad / 3);
        log.setText(sb.length() > 0 ? sb.toString() : "(the log is empty: the app stopped before it wrote anything)");
        sv.addView(log);
        root.addView(title); root.addView(hint); root.addView(row);
        root.addView(sv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        again.setFocusableInTouchMode(true); /* (a TV starts the screen in touch mode: no focus at all until a key, and the first press went nowhere) */
        sv.post(() -> { sv.scrollTo(0, Math.max(0, log.getHeight() - sv.getHeight())); again.requestFocus(); }); /* (the log's end in view without moving the focus into it) */
        again.setOnClickListener(v -> {
            try { startActivity(Intent.makeRestartActivityTask(new ComponentName(this, MainActivity.class))); } catch (Throwable t) { }
            android.os.Process.killProcess(android.os.Process.myPid()); });
        close.setOnClickListener(v -> { finish(); android.os.Process.killProcess(android.os.Process.myPid()); });
    }
}
