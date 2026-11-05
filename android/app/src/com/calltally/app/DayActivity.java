package com.calltally.app;

import android.content.Context;
import android.graphics.Paint;
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

/** One day: its call time large on top, its totals, its calls and texts on two tabs, and Ignore or Restore on each. */
public class DayActivity extends Screen {
    /** The tab last chosen, kept while the app is open. */
    private static String chosenTab;

    private String day;
    private String tab;
    private List<Record.Item> items = new ArrayList<>();
    private boolean hide;
    private int asking;
    /** The day's longest call time, so the time column is only as wide as this day needs. */
    private String widest = "0s";
    private TextView total;
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
        // The day's call time, large, with the summary line under it saying what it is made of.
        total = text("", 28, txt, false);
        total.setTypeface(medium());
        figures(total);
        head.addView(total, wide());
        line = text("", 14, muted, false);
        line.setId(R.id.day_line);
        head.addView(line, wide());
        // One track in the second card's grey; the chosen half sits in it as a white card with an accent line.
        LinearLayout tabs = row();
        tabs.setBackground(shape(card2, 0, 0, 10));
        tabs.setPadding(dp(2), dp(2), dp(2), dp(2));
        calls = button("Calls");
        calls.setId(R.id.tab_calls);
        texts = button("Texts");
        texts.setId(R.id.tab_texts);
        for (Button half : new Button[] {calls, texts}) {
            half.setMinHeight(0);
            half.setMinimumHeight(0);
            half.setPadding(dp(8), 0, dp(8), 0);
            tabs.addView(half, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        }
        LinearLayout.LayoutParams tabsGap = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
        tabsGap.topMargin = dp(10);
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
                            total.setVisibility(View.GONE);
                            line.setText(trouble);
                            return;
                        }
                        items = found;
                        hide = hideIt;
                        widest = widest(found);
                        total.setVisibility(View.VISIBLE);
                        total.setText(Words.fullTime(sums == null ? 0 : sums.callS));
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

    /** The longest time among the day's connected calls, as shown, or "0s" when there is none. */
    private static String widest(List<Record.Item> found) {
        String longest = "0s";
        if (found == null) return longest;
        for (Record.Item item : found) {
            if (!Words.connected(item.kind, item.direction, item.durationS)) continue;
            String time = Words.fullTime(item.durationS);
            if (time.length() > longest.length()) longest = time;
        }
        return longest;
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
        tab(calls, "call".equals(tab));
        tab(texts, "text".equals(tab));
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

    /** The chosen half: a white card with an accent line. The other: clear, a ripple when touched. */
    private void tab(Button half, boolean chosen) {
        half.setTextColor(chosen ? txt : muted);
        half.setTypeface(medium());
        half.setBackground(chosen ? touchable(shape(card, accent, 1, 8)) : touchable(null, 8));
    }

    private View row(final Record.Item item) {
        boolean gone = item.ignoredAt != null;
        LinearLayout row = row();
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(8), dp(4), dp(8));
        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        TextView who = text(item.contact, 15, txt, false);
        who.setTypeface(medium());
        TextView what = text(Words.status(item.kind, item.direction, item.at, item.durationS), 13, muted, false);
        info.addView(who);
        info.addView(what);
        row.addView(info, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView length = text(Words.connected(item.kind, item.direction, item.durationS) ? Words.fullTime(item.durationS) : "", 15, txt, false);
        // No gap on its right: the action's own margin is the gap.
        length.setPadding(dp(8), 0, 0, 0);
        timeColumn(length, widest);
        row.addView(length);
        if (gone) {
            for (TextView view : new TextView[] {who, what, length}) {
                view.setPaintFlags(view.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
                view.setAlpha(0.55f);
            }
        }
        // Ignore is quiet and muted, so the names and times lead. Restore, the one action on a dimmed row, is in the accent.
        final Button action = button(gone ? "Restore" : "Ignore");
        action.setId(R.id.entry_action);
        if (gone) {
            quiet(action);
            action.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13);
        } else {
            quietMuted(action);
        }
        action.setMinHeight(dp(44));
        action.setMinimumHeight(dp(44));
        action.setMinWidth(dp(64));
        action.setMinimumWidth(dp(64));
        action.setContentDescription(action.getText() + " " + item.contact + " at " + Words.clock(item.at));
        action.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                change(action, item);
            }
        });
        LinearLayout.LayoutParams actionGap = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        actionGap.leftMargin = dp(8);
        row.addView(action, actionGap);
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
