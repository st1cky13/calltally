package com.calltally.app;

import android.content.Context;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** One day: its totals, its calls and texts on two tabs, and Ignore or Restore on each. */
public class DayActivity extends Screen {
    /** The tab last chosen, kept while the app is open. */
    private static String chosenTab;

    private String day;
    private String tab;
    private List<Record.Item> items = new ArrayList<>();
    private boolean hide;
    private int asking;
    private TextView line;
    private Button calls;
    private Button texts;
    private LinearLayout rows;
    private TextView nothing;
    private TextView hiddenNote;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        day = getIntent().getStringExtra("day");
        LocalDate date = LocalDate.parse(day);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.VERTICAL);
        head.setBackgroundColor(card);
        head.setPadding(dp(16), dp(10), dp(16), dp(10));
        line = text("", 14, muted, false);
        line.setId(R.id.day_line);
        head.addView(line, wide());
        LinearLayout tabs = row();
        calls = button("Calls");
        calls.setId(R.id.tab_calls);
        texts = button("Texts");
        texts.setId(R.id.tab_texts);
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        LinearLayout.LayoutParams other = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        other.leftMargin = dp(6);
        tabs.addView(calls, half);
        tabs.addView(texts, other);
        LinearLayout.LayoutParams tabsGap = wide();
        tabsGap.topMargin = dp(8);
        head.addView(tabs, tabsGap);
        tabs.setVisibility(View.INVISIBLE);
        calls.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pick("call");
            }
        });
        texts.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pick("text");
            }
        });
        page.addView(head, wide());
        page.addView(divider());

        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setBackgroundColor(card);
        rows = new LinearLayout(this);
        rows.setId(R.id.rows);
        rows.setOrientation(LinearLayout.VERTICAL);
        nothing = note("Nothing here.");
        nothing.setId(R.id.nothing);
        nothing.setVisibility(View.GONE);
        hiddenNote = note("");
        hiddenNote.setId(R.id.hidden_note);
        hiddenNote.setTextSize(13);
        hiddenNote.setVisibility(View.GONE);
        list.addView(nothing, wide());
        list.addView(rows, wide());
        list.addView(hiddenNote, wide());
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(card);
        scroll.setFillViewport(true);
        scroll.addView(list);
        page.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        frame(Words.longDay(date), page, true);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!isFinishing()) load();
    }

    /** Reads the day and its totals away from the screen. */
    private void load() {
        final int mine = ++asking;
        final Context context = getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                List<Record.Item> got = null;
                Record.DayTotals totals = null;
                boolean hidden = false;
                String problem = null;
                try {
                    Record record = Record.get(context);
                    got = record.day(day);
                    totals = record.days(day, day).get(day);
                    hidden = "1".equals(record.setting("hide_ignored"));
                } catch (Record.TooNew error) {
                    problem = error.getMessage();
                } catch (RuntimeException error) {
                    problem = Record.UNREADABLE;
                }
                final List<Record.Item> found = got;
                final Record.DayTotals sums = totals;
                final boolean hideIt = hidden;
                final String trouble = problem;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (mine != asking || isDestroyed()) return;
                        if (trouble != null) {
                            line.setText(trouble);
                            return;
                        }
                        items = found;
                        hide = hideIt;
                        line.setText(sums == null ? Words.summary(0, 0, 0) : Words.summary(sums.callS, sums.calls, sums.texts));
                        if (tab == null) {
                            // The tab last chosen when it has anything, otherwise Calls if the day has a call, else Texts.
                            tab = chosenTab != null && has(chosenTab) ? chosenTab : has("call") ? "call" : "text";
                        }
                        ((View) calls.getParent()).setVisibility(View.VISIBLE);
                        showTab();
                    }
                });
            }
        }).start();
    }

    private boolean has(String kind) {
        for (Record.Item item : items) if (kind.equals(item.kind)) return true;
        return false;
    }

    private void pick(String kind) {
        chosenTab = tab = kind;
        showTab();
    }

    private void showTab() {
        if ("call".equals(tab)) {
            filled(calls);
            plain(texts);
        } else {
            plain(calls);
            filled(texts);
        }
        calls.setSelected("call".equals(tab));
        texts.setSelected("text".equals(tab));
        rows.removeAllViews();
        int all = 0, hidden = 0;
        for (Record.Item item : items) {
            if (!tab.equals(item.kind)) continue;
            all++;
            if (hide && item.ignoredAt != null) {
                hidden++;
                continue;
            }
            if (rows.getChildCount() > 0) rows.addView(divider());
            rows.addView(row(item));
        }
        nothing.setVisibility(all == 0 ? View.VISIBLE : View.GONE);
        hiddenNote.setText(hidden == 1 ? "1 ignored entry hidden" : hidden + " ignored entries hidden");
        hiddenNote.setVisibility(hidden > 0 ? View.VISIBLE : View.GONE);
    }

    private View row(final Record.Item item) {
        boolean gone = item.ignoredAt != null;
        LinearLayout row = row();
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(8), dp(12), dp(8));
        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        TextView who = text(item.contact, 15, txt, true);
        TextView what = text(Words.status(item.kind, item.direction, item.at, item.durationS), 13, muted, false);
        info.addView(who);
        info.addView(what);
        row.addView(info, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView length = text(Words.connected(item.kind, item.direction, item.durationS) ? Words.fullTime(item.durationS) : "", 14, txt, false);
        length.setTypeface(Typeface.MONOSPACE);
        length.setSingleLine(true);
        length.setPadding(dp(8), 0, dp(10), 0);
        row.addView(length);
        if (gone) {
            for (TextView view : new TextView[] {who, what, length}) {
                view.setPaintFlags(view.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
                view.setAlpha(0.55f);
            }
        }
        final Button action = button(gone ? "Restore" : "Ignore");
        action.setId(R.id.entry_action);
        action.setContentDescription(action.getText() + " " + item.contact + " at " + Words.clock(item.at));
        action.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                change(action, item);
            }
        });
        row.addView(action);
        return row;
    }

    /** Writes the mark to the record, then shows the day again. The month screen reads it again when it comes back. */
    private void change(final Button action, final Record.Item item) {
        enable(action, false);
        final Context context = getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                boolean saved;
                try {
                    saved = Record.get(context).setIgnored(item.id, item.ignoredAt == null);
                } catch (RuntimeException error) {
                    saved = false;
                }
                final boolean done = saved;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (isDestroyed()) return;
                        if (!done) {
                            enable(action, true);
                            line.setText("That did not save.");
                            return;
                        }
                        load();
                    }
                });
            }
        }).start();
    }
}
