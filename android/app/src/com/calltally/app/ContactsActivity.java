package com.calltally.app;

import android.content.Context;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.List;

/** One of the totals by contact or group, biggest call time first. People with only unanswered calls are left out. */
public class ContactsActivity extends Screen {
    private String from;
    private String to;
    private TextView line;
    private LinearLayout rows;
    private TextView nothing;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        from = getIntent().getStringExtra("from");
        to = getIntent().getStringExtra("to");
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(card);
        line = text("", 14, muted, false);
        line.setId(R.id.day_line);
        line.setPadding(dp(16), dp(10), dp(16), dp(10));
        page.addView(line, wide());
        page.addView(divider());
        rows = new LinearLayout(this);
        rows.setId(R.id.rows);
        rows.setOrientation(LinearLayout.VERTICAL);
        nothing = note("Nothing here.");
        nothing.setId(R.id.nothing);
        nothing.setVisibility(View.GONE);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.addView(nothing, wide());
        list.addView(rows, wide());
        ScrollView scroll = new ScrollView(this);
        scroll.addView(list);
        page.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        String label = getIntent().getStringExtra("label");
        frame(label == null ? "All time" : label, page, true);
        load();
    }

    private void load() {
        final Context context = getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                List<Record.Totals> list = null;
                Record.Totals totals = null;
                String problem = null;
                try {
                    Record record = Record.get(context);
                    list = from == null ? record.contacts() : record.contacts(from, to);
                    totals = from == null ? record.totals() : record.totals(from, to);
                } catch (Record.TooNew error) {
                    problem = error.getMessage();
                } catch (RuntimeException error) {
                    problem = Record.UNREADABLE;
                }
                final List<Record.Totals> found = list;
                final Record.Totals sums = totals;
                final String trouble = problem;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (isDestroyed()) return;
                        if (trouble != null) {
                            line.setText(trouble);
                            return;
                        }
                        line.setText(Words.summary(sums.callS, sums.calls, sums.texts));
                        show(found);
                    }
                });
            }
        }).start();
    }

    private void show(List<Record.Totals> list) {
        rows.removeAllViews();
        for (Record.Totals item : list) {
            if (rows.getChildCount() > 0) rows.addView(divider());
            LinearLayout row = row();
            row.setId(R.id.contact_row);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(16), dp(10), dp(16), dp(10));
            row.setMinimumHeight(dp(48));
            LinearLayout info = new LinearLayout(this);
            info.setOrientation(LinearLayout.VERTICAL);
            info.addView(text(item.contact, 15, txt, true));
            info.addView(text(Words.plural(item.calls, "call") + " · " + Words.plural(item.texts, "text"), 13, muted, false));
            row.addView(info, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            TextView time = text(Words.fullTime(item.callS), 14, txt, false);
            time.setTypeface(Typeface.MONOSPACE);
            time.setSingleLine(true);
            time.setPadding(dp(10), 0, 0, 0);
            row.addView(time);
            rows.addView(row);
        }
        nothing.setVisibility(list.isEmpty() ? View.VISIBLE : View.GONE);
    }
}
