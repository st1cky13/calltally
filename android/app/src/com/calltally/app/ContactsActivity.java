package com.calltally.app;

import android.content.Context;
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
    private TextView total;
    private TextView line;
    private TextView textTime;
    private LinearLayout rows;
    /** The owner's time per text in seconds, 0 when off. */
    private int perText;
    private TextView nothing;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        from = getIntent().getStringExtra("from");
        to = getIntent().getStringExtra("to");
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(card);
        String label = getIntent().getStringExtra("label");
        if (label == null) label = "All time";
        // The sheet's call time on top, with the calls and texts it is made of under it; the title bar names it.
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.VERTICAL);
        head.setPadding(edge(), dp(10), edge(), dp(10));
        total = text("", 22, txt, false);
        total.setTypeface(medium());
        figures(total);
        head.addView(total, wide());
        line = text("", 13, muted, false);
        line.setId(R.id.day_line);
        head.addView(line, wide());
        // The texts' own time under their count, only when the owner gives each text some seconds.
        textTime = text("", 13, muted, false);
        textTime.setId(R.id.text_time);
        figures(textTime);
        textTime.setVisibility(View.GONE);
        head.addView(textTime, wide());
        page.addView(head, wide());
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
        frame(label, page, true);
        load();
    }

    private void load() {
        final Context context = getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                List<Record.Totals> list = null;
                Record.Totals totals = null;
                int seconds = 0;
                String problem = null;
                try {
                    Record record = Record.get(context);
                    list = from == null ? record.contacts() : record.contacts(from, to);
                    totals = from == null ? record.totals() : record.totals(from, to);
                    seconds = record.textSeconds();
                } catch (Record.TooNew error) {
                    problem = error.getMessage();
                } catch (RuntimeException error) {
                    problem = Record.UNREADABLE;
                }
                final List<Record.Totals> found = list;
                final Record.Totals sums = totals;
                final int each = seconds;
                final String trouble = problem;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (isDestroyed()) return;
                        if (trouble != null) {
                            total.setVisibility(View.GONE);
                            line.setText(trouble);
                            return;
                        }
                        total.setText(Words.fullTime(sums.callS));
                        line.setText(Words.counts(sums.calls, sums.texts));
                        perText = each;
                        textTime.setText("Text time " + Words.textTime(sums.texts, perText));
                        textTime.setVisibility(perText > 0 ? View.VISIBLE : View.GONE);
                        show(found);
                    }
                });
            }
        }).start();
    }

    private void show(List<Record.Totals> list) {
        rows.removeAllViews();
        // The times form a column as wide as the longest one here.
        String widest = "0s";
        for (Record.Totals item : list) {
            String time = Words.fullTime(item.callS);
            if (time.length() > widest.length()) widest = time;
        }
        for (Record.Totals item : list) {
            if (rows.getChildCount() > 0) rows.addView(divider());
            LinearLayout row = row();
            row.setId(R.id.contact_row);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(edge(), dp(10), edge(), dp(10));
            row.setMinimumHeight(dp(48));
            LinearLayout info = new LinearLayout(this);
            info.setOrientation(LinearLayout.VERTICAL);
            TextView who = text(item.contact, 15, txt, false);
            who.setTypeface(medium());
            info.addView(who);
            String counts = Words.counts(item.calls, item.texts);
            if (perText > 0) counts += " · text time " + Words.textTime(item.texts, perText);
            info.addView(text(counts, 13, muted, false));
            row.addView(info, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            TextView time = text(Words.fullTime(item.callS), 15, txt, false);
            time.setPadding(dp(10), 0, 0, 0);
            timeColumn(time, widest);
            row.addView(time);
            rows.addView(row);
        }
        nothing.setVisibility(list.isEmpty() ? View.VISIBLE : View.GONE);
    }
}
