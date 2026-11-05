package com.calltally.app;

import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.time.LocalDate;
import java.time.YearMonth;

/**
 * Changing the day pay periods start: a month of day buttons with arrows, then the old and new start side by side with
 * Revert changes and Confirm. Nothing is saved until Confirm.
 */
public class PayActivity extends Screen {
    private static final String[] WEEK = {"Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"};
    private LocalDate today;
    private YearMonth thisMonth;
    private YearMonth view;
    private LocalDate start;
    private String days = "14";
    private String earliest;
    private LocalDate chosen;
    private TextView now;
    private TextView calTitle;
    private Button calPrev;
    private Button calNext;
    private LinearLayout grid;
    private TextView review;
    private Button close;
    private Button revert;
    private Button confirm;
    private Button skip;
    private boolean first;
    private YearMonth keptView;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        today = LocalDate.now();
        thisMonth = YearMonth.from(today);
        // Part of the first start: the question, Skip, and the month screen after.
        first = getIntent().getBooleanExtra("first", false);
        // The day picked and the month shown, kept through a rotation or a new look.
        Bundle state = saved != null ? saved : getIntent().getExtras();
        if (state != null) {
            if (state.getString("chosen") != null) chosen = LocalDate.parse(state.getString("chosen"));
            if (state.getString("calView") != null) keptView = YearMonth.parse(state.getString("calView"));
        }
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(16), dp(10), dp(16), dp(12));
        now = text(first ? "Do your pay periods repeat every two weeks? Pick the day the current one started." : "", 14, first ? txt : muted, false);
        now.setId(R.id.pay_question);
        column.addView(now, wide());

        LinearLayout nav = row();
        nav.setGravity(Gravity.CENTER_VERTICAL);
        calPrev = arrow("<", "Previous month", R.id.cal_prev);
        calNext = arrow(">", "Next month", R.id.cal_next);
        calTitle = text("", 17, txt, true);
        calTitle.setId(R.id.cal_title);
        calTitle.setGravity(Gravity.CENTER);
        nav.addView(calPrev, new LinearLayout.LayoutParams(dp(48), dp(48)));
        nav.addView(calTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        nav.addView(calNext, new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout.LayoutParams navGap = wide();
        navGap.topMargin = dp(10);
        column.addView(nav, navGap);
        calPrev.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                view = view.minusMonths(1);
                drawCal();
            }
        });
        calNext.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                view = view.plusMonths(1);
                drawCal();
            }
        });

        LinearLayout week = row();
        for (String name : WEEK) {
            TextView day = text(name, 11, muted, false);
            day.setGravity(Gravity.CENTER);
            week.addView(day, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        }
        LinearLayout.LayoutParams weekGap = wide();
        weekGap.topMargin = dp(8);
        weekGap.bottomMargin = dp(3);
        column.addView(week, weekGap);
        grid = new LinearLayout(this);
        grid.setId(R.id.cal_grid);
        grid.setOrientation(LinearLayout.VERTICAL);
        column.addView(grid, wide());
        review = text("", 14, muted, false);
        review.setId(R.id.review);
        review.setVisibility(View.GONE);
        LinearLayout.LayoutParams reviewGap = wide();
        reviewGap.topMargin = dp(14);
        column.addView(review, reviewGap);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(card);
        scroll.addView(column);

        close = button("Close");
        close.setId(R.id.close);
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        revert = button("Revert changes");
        revert.setId(R.id.revert);
        revert.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                chosen = null;
                finish();
            }
        });
        confirm = button("Confirm");
        confirm.setId(R.id.confirm);
        confirm.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                save();
            }
        });
        skip = button("Skip");
        skip.setId(R.id.pay_skip);
        skip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // No pay period: its row stays hidden until one is set in Options.
                StartActivity.finishFirstStart(PayActivity.this);
            }
        });
        if (first) {
            footer = footer(skip, confirm);
            // Confirm shows once a day is picked.
            confirm.setVisibility(View.GONE);
            close.setVisibility(View.GONE);
            revert.setVisibility(View.GONE);
        } else {
            footer = footer(close, revert, confirm);
            skip.setVisibility(View.GONE);
            revert.setVisibility(View.GONE);
            confirm.setVisibility(View.GONE);
            // Close shows alone, or Revert changes and Confirm side by side.
            ((LinearLayout.LayoutParams) revert.getLayoutParams()).leftMargin = 0;
        }
        frame(first ? "Pay period" : "Pay periods", scroll, false);
        load();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        keep(out);
    }

    @Override
    void keep(Intent intent) {
        Bundle out = new Bundle();
        keep(out);
        intent.putExtras(out);
    }

    private void keep(Bundle out) {
        out.putString("chosen", chosen == null ? null : chosen.toString());
        YearMonth shown = view != null ? view : keptView;
        if (shown != null) out.putString("calView", shown.toString());
    }

    private Button arrow(String label, String description, int id) {
        Button button = button(label);
        button.setId(id);
        button.setContentDescription(description);
        button.setTextSize(18);
        button.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        button.setPadding(0, 0, 0, 0);
        return button;
    }

    private void load() {
        final Context context = getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                String startText = null, length = null, firstDay = null, problem = null;
                try {
                    Record record = Record.get(context);
                    startText = record.setting("pay_period_start");
                    length = record.setting("pay_period_days");
                    firstDay = record.earliest();
                } catch (Record.TooNew error) {
                    problem = error.getMessage();
                } catch (RuntimeException error) {
                    problem = Record.UNREADABLE;
                }
                final String s = startText, d = length, e = firstDay, trouble = problem;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (isDestroyed()) return;
                        if (trouble != null) {
                            now.setText(trouble);
                            return;
                        }
                        PayPeriod period = PayPeriod.of(s, d);
                        start = period == null ? null : period.start;
                        days = period == null ? "14" : String.valueOf(period.days);
                        earliest = e;
                        if (first) now.setText("Do your pay periods repeat every two weeks? Pick the day the current one started.");
                        else if (period == null) now.setText("No pay period is set. Pick the day the current one started.");
                        else now.setText(period.describe(today) + ". To change it, pick the day the current one started.");
                        if (chosen != null && (chosen.equals(start) || chosen.isAfter(today))) chosen = null;
                        view = keptView != null ? keptView : YearMonth.from(start != null ? start : today);
                        if (view.isAfter(thisMonth)) view = thisMonth;
                        drawCal();
                        drawReview();
                    }
                });
            }
        }).start();
    }

    private void drawCal() {
        calTitle.setText(Words.monthTitle(view.getYear(), view.getMonthValue()));
        grid.removeAllViews();
        int lead = view.atDay(1).getDayOfWeek().getValue() % 7;
        int count = view.lengthOfMonth();
        int rows = (lead + count + 6) / 7;
        int day = 1 - lead;
        for (int r = 0; r < rows; r++) {
            LinearLayout row = row();
            LinearLayout.LayoutParams rowParams = wide();
            if (r > 0) rowParams.topMargin = dp(4);
            for (int c = 0; c < 7; c++, day++) {
                LinearLayout.LayoutParams cellParams = new LinearLayout.LayoutParams(0, dp(44), 1);
                if (c > 0) cellParams.leftMargin = dp(4);
                if (day < 1 || day > count) {
                    row.addView(new View(this), cellParams);
                    continue;
                }
                row.addView(dayButton(view.atDay(day)), cellParams);
            }
            grid.addView(row, rowParams);
        }
        // From the month of the earliest data, or of the current start if that is earlier, up to this month. Always back
        // to last month at least, so the start of the current period can be picked before anything is in the record.
        LocalDate first = start == null || start.isAfter(today.minusMonths(1)) ? today.minusMonths(1) : start;
        if (earliest != null && LocalDate.parse(earliest).isBefore(first)) first = LocalDate.parse(earliest);
        enable(calPrev, view.isAfter(YearMonth.from(first)));
        enable(calNext, view.isBefore(thisMonth));
    }

    private Button dayButton(final LocalDate date) {
        Button button = button(String.valueOf(date.getDayOfMonth()));
        button.setId(R.id.cal_day);
        button.setPadding(0, 0, 0, 0);
        button.setMinHeight(dp(44));
        button.setMinimumHeight(dp(44));
        button.setContentDescription(Words.longDay(date));
        boolean isSet = date.equals(start), isChosen = date.equals(chosen);
        if (isChosen) filled(button);
        else if (isSet) outlined(button);
        button.setSelected(isChosen);
        // A pay period can't start after today.
        if (date.isAfter(today)) {
            enable(button, false);
            return button;
        }
        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // The set day, or the day already chosen, clears the choice.
                chosen = date.equals(start) || date.equals(chosen) ? null : date;
                drawCal();
                drawReview();
            }
        });
        return button;
    }

    private void drawReview() {
        boolean changing = chosen != null;
        if (!first) {
            close.setVisibility(changing ? View.GONE : View.VISIBLE);
            revert.setVisibility(changing ? View.VISIBLE : View.GONE);
        }
        confirm.setVisibility(changing ? View.VISIBLE : View.GONE);
        enable(confirm, true);
        review.setVisibility(changing ? View.VISIBLE : View.GONE);
        if (!changing) return;
        SpannableStringBuilder text = new SpannableStringBuilder("Pay periods start: ");
        bold(text, start == null ? "not set" : Words.weekdayDay(start, today));
        text.append(" → ");
        bold(text, Words.weekdayDay(chosen, today));
        review.setText(text);
    }

    private void bold(SpannableStringBuilder text, String value) {
        int from = text.length();
        text.append(value);
        text.setSpan(new StyleSpan(Typeface.BOLD), from, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.setSpan(new ForegroundColorSpan(txt), from, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private void save() {
        if (chosen == null) return;
        enable(confirm, false);
        final String value = chosen.toString(), length = days;
        final Context context = getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                boolean done;
                try {
                    Record record = Record.get(context);
                    record.setSetting("pay_period_start", value);
                    record.setSetting("pay_period_days", length);
                    done = true;
                } catch (RuntimeException error) {
                    done = false;
                }
                final boolean saved = done;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (isDestroyed()) return;
                        if (saved && first) {
                            StartActivity.finishFirstStart(PayActivity.this);
                        } else if (saved) {
                            finish();
                        } else {
                            enable(confirm, true);
                            review.setText("That did not save.");
                        }
                    }
                });
            }
        }).start();
    }
}
