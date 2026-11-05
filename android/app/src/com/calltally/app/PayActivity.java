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
 * Changing the pay period: a month of day buttons with arrows. The first tap picks the first day, the second its last day,
 * and the days between are shaded. Then the old and new start and period, with Revert, Reset and Confirm. Periods repeat
 * with the length picked. Reset turns pay periods off. Nothing is saved until Confirm.
 */
public class PayActivity extends Screen {
    private static final String[] WEEK = {"Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"};
    private LocalDate today;
    private YearMonth thisMonth;
    private YearMonth view;
    private LocalDate start;
    private String days = "14";
    private String earliest;
    // The first and last day picked; the last stays null until it is picked.
    private LocalDate chosen;
    private LocalDate chosenLast;
    // Reset pressed: Confirm removes the pay period.
    private boolean off;
    private TextView now;
    private TextView calTitle;
    private Button calPrev;
    private Button calNext;
    private LinearLayout grid;
    private TextView review;
    private TextView preview;
    private Button reset;
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
            if (chosen != null && state.getString("chosenLast") != null) chosenLast = LocalDate.parse(state.getString("chosenLast"));
            if (state.getString("calView") != null) keptView = YearMonth.parse(state.getString("calView"));
            off = state.getBoolean("off", false);
        }
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(16), dp(10), dp(16), dp(12));
        now = text(first ? "Pick the first day of your current pay period, then its last day." : "", 14, first ? txt : muted, false);
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
        preview = text("", 14, muted, false);
        preview.setId(R.id.period_preview);
        preview.setVisibility(View.GONE);
        LinearLayout.LayoutParams previewGap = wide();
        previewGap.topMargin = dp(6);
        column.addView(preview, previewGap);

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
        // "Revert changes" does not fit beside Reset and Confirm on a narrow phone.
        revert = button("Revert");
        revert.setId(R.id.revert);
        revert.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                chosen = null;
                chosenLast = null;
                off = false;
                finish();
            }
        });
        reset = button("Reset");
        reset.setId(R.id.pay_reset);
        // Off until the record is read; it stays off when the record can't be read.
        enable(reset, false);
        reset.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // Clears the day picked, and turns a saved pay period off on Confirm.
                if (view == null) return;
                chosen = null;
                chosenLast = null;
                off = start != null;
                drawCal();
                drawReview();
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
            reset.setVisibility(View.GONE);
        } else {
            footer = footer(close, revert, reset, confirm);
            skip.setVisibility(View.GONE);
            revert.setVisibility(View.GONE);
            confirm.setVisibility(View.GONE);
            // Close and Reset, or Revert, Reset and Confirm.
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
        out.putString("chosenLast", chosenLast == null ? null : chosenLast.toString());
        out.putBoolean("off", off);
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
                        if (first) now.setText("Pick the first day of your current pay period, then its last day.");
                        else if (period == null) now.setText("No pay period is set. Pick the first day of your current pay period, then its last day.");
                        else now.setText(period.describe(today) + ". Pick the first day to change it.");
                        noChange();
                        if (start == null || chosen != null) off = false;
                        // Opens on the day picked, else the first day of the current period, else this month.
                        LocalDate opening = chosen != null ? chosen : shownFirst();
                        view = keptView != null ? keptView : YearMonth.from(opening != null ? opening : today);
                        if (view.isAfter(latestMonth())) view = latestMonth();
                        drawCal();
                        drawReview();
                    }
                });
            }
        }).start();
    }

    /** The saved pay periods, or null when none is set. */
    private PayPeriod saved() {
        return start == null ? null : new PayPeriod(start, Integer.parseInt(days));
    }

    /** The highlighted first day: the one picked, else the first day of the current period; none when off or unset. */
    private LocalDate shownFirst() {
        if (off) return null;
        if (chosen != null) return chosen;
        PayPeriod period = saved();
        return period == null ? null : period.first(period.index(today));
    }

    /** The highlighted last day: the one picked, else the last day of the current period; none until it is picked. */
    private LocalDate shownLast() {
        if (off) return null;
        if (chosen != null) return chosenLast;
        PayPeriod period = saved();
        return period == null ? null : period.last(period.index(today));
    }

    /** Picking the current period's first and last day again is no change. */
    private void noChange() {
        PayPeriod period = saved();
        if (chosen == null || period == null) return;
        long k = period.index(today);
        if (chosen.equals(period.first(k)) && period.last(k).equals(chosenLast)) {
            chosen = null;
            chosenLast = null;
        }
    }

    private void drawCal() {
        // The first and last day are highlighted and the days between shaded; nothing else.
        LocalDate shadeFrom = shownFirst(), shadeTo = shownLast();
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
                LocalDate date = view.atDay(day);
                boolean shaded = shadeTo != null && date.isAfter(shadeFrom) && date.isBefore(shadeTo);
                row.addView(dayButton(date, shaded), cellParams);
            }
            grid.addView(row, rowParams);
        }
        // From the month of the earliest data, or of the current start if that is earlier, up to next month. Always back
        // to last month at least, so the start of the current period can be picked before anything is in the record.
        LocalDate first = start == null || start.isAfter(today.minusMonths(1)) ? today.minusMonths(1) : start;
        if (earliest != null && LocalDate.parse(earliest).isBefore(first)) first = LocalDate.parse(earliest);
        enable(calPrev, view.isAfter(YearMonth.from(first)));
        enable(calNext, view.isBefore(latestMonth()));
    }

    /** The last month the arrows reach: next month at least, and two months past a first day picked, for its last day. */
    private YearMonth latestMonth() {
        YearMonth latest = thisMonth.plusMonths(1);
        if (!off && chosen != null && YearMonth.from(chosen).plusMonths(2).isAfter(latest)) latest = YearMonth.from(chosen).plusMonths(2);
        return latest;
    }

    private Button dayButton(final LocalDate date, boolean shaded) {
        Button button = button(String.valueOf(date.getDayOfMonth()));
        button.setId(R.id.cal_day);
        button.setPadding(0, 0, 0, 0);
        button.setMinHeight(dp(44));
        button.setMinimumHeight(dp(44));
        button.setContentDescription(Words.longDay(date));
        // The first and last day are highlighted, the days between have a quiet tint; none after Reset.
        final LocalDate firstDay = shownFirst(), lastDay = shownLast();
        boolean isMarked = date.equals(firstDay) || date.equals(lastDay);
        if (isMarked) filled(button);
        else if (shaded) button.setBackground(touchable(shape((accent & 0x00ffffff) | 0x40000000, (accent & 0x00ffffff) | 0x80000000, 1, 8)));
        button.setSelected(isMarked);
        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (chosen == null && (date.equals(firstDay) || date.equals(lastDay))) {
                    // Either day of the saved period shown unpicks it, as Reset does: it is turned off on Confirm.
                    chosen = null;
                    chosenLast = null;
                    off = true;
                } else if (chosen == null || date.isBefore(firstDay)) {
                    // A new first day; its last day is picked next.
                    chosen = date;
                    chosenLast = null;
                    off = false;
                } else if (date.equals(firstDay)) {
                    // The first day picked unpicks both, as Reset does: a saved pay period is turned off on Confirm.
                    chosen = null;
                    chosenLast = null;
                    off = start != null;
                } else if (date.equals(lastDay)) {
                    // The last day clears just the last day.
                    chosen = firstDay;
                    chosenLast = null;
                } else {
                    // A later day sets or moves the last day.
                    chosen = firstDay;
                    chosenLast = date;
                }
                noChange();
                drawCal();
                drawReview();
            }
        });
        return button;
    }

    private void drawReview() {
        boolean changing = chosen != null || off;
        // A change is ready once both days are picked, or the pay period is turned off.
        boolean ready = off || chosenLast != null;
        if (!first) {
            close.setVisibility(changing ? View.GONE : View.VISIBLE);
            revert.setVisibility(changing ? View.VISIBLE : View.GONE);
            enable(reset, view != null && !off && (start != null || chosen != null));
        }
        confirm.setVisibility(ready ? View.VISIBLE : View.GONE);
        enable(confirm, true);
        review.setVisibility(ready ? View.VISIBLE : View.GONE);
        preview.setVisibility(changing || start != null ? View.VISIBLE : View.GONE);
        PayPeriod saved = saved();
        SpannableStringBuilder line = new SpannableStringBuilder();
        if (changing && !ready) {
            line.append("Now pick the last day.");
        } else if (ready) {
            // "Pay period: Oct 25 – Nov 7 → Nov 28 – Dec 11, 14 days"
            line.append("Pay period: ");
            bold(line, saved == null ? "none" : saved.current(today));
            line.append(" → ");
            bold(line, off ? "none" : picked().span(today));
        } else if (saved != null) {
            line.append("Pay period ");
            bold(line, saved.current(today) + ", " + Words.plural(saved.days, "day"));
        }
        preview.setText(line);
        if (!ready) return;
        SpannableStringBuilder text = new SpannableStringBuilder("Pay periods start: ");
        bold(text, start == null ? "not set" : Words.weekdayDay(start, today));
        text.append(" → ");
        bold(text, off ? "none" : Words.weekdayDay(chosen, today));
        review.setText(text);
    }

    /** The pay periods from the first and last day picked. */
    private PayPeriod picked() {
        return new PayPeriod(chosen, (int) java.time.temporal.ChronoUnit.DAYS.between(chosen, chosenLast) + 1);
    }

    private void bold(SpannableStringBuilder text, String value) {
        int from = text.length();
        text.append(value);
        text.setSpan(new StyleSpan(Typeface.BOLD), from, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.setSpan(new ForegroundColorSpan(txt), from, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private void save() {
        if (!off && (chosen == null || chosenLast == null)) return;
        enable(confirm, false);
        final String value = off ? null : chosen.toString(), length = off ? days : String.valueOf(picked().days);
        final Context context = getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                boolean done;
                try {
                    Record record = Record.get(context);
                    if (value == null) {
                        record.clearSetting("pay_period_start");
                        record.clearSetting("pay_period_days");
                    } else {
                        record.setSetting("pay_period_start", value);
                        record.setSetting("pay_period_days", length);
                    }
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
