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
 * Changing the day pay periods start: a month of day buttons with arrows, the days of the current pay period shaded, then
 * the old and new start and the current period they give, with Revert, Reset and Confirm. Reset turns pay periods off.
 * Nothing is saved until Confirm.
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
            if (state.getString("calView") != null) keptView = YearMonth.parse(state.getString("calView"));
            off = state.getBoolean("off", false);
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
                off = false;
                finish();
            }
        });
        reset = button("Reset");
        reset.setId(R.id.pay_reset);
        reset.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // Clears the day picked, and turns a saved pay period off on Confirm.
                chosen = null;
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
                        if (first) now.setText("Do your pay periods repeat every two weeks? Pick the day the current one started.");
                        else if (period == null) now.setText("No pay period is set. Pick the day the current one started.");
                        else now.setText(period.describe(today) + ". Pick a day to change it.");
                        if (chosen != null && chosen.equals(start)) chosen = null;
                        if (start == null || chosen != null) off = false;
                        view = keptView != null ? keptView : YearMonth.from(start != null ? start : today);
                        if (view.isAfter(thisMonth.plusMonths(1))) view = thisMonth.plusMonths(1);
                        drawCal();
                        drawReview();
                    }
                });
            }
        }).start();
    }

    /** The pay periods the screen shows: the pending choice's, else the saved one's; none when turned off or unset. */
    private PayPeriod shown() {
        if (off) return null;
        LocalDate from = chosen != null ? chosen : start;
        return from == null ? null : new PayPeriod(from, Integer.parseInt(days));
    }

    private void drawCal() {
        PayPeriod period = shown();
        long k = period == null ? 0 : period.index(today);
        LocalDate shadeFrom = period == null ? null : period.first(k), shadeTo = period == null ? null : period.last(k);
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
                boolean shaded = shadeFrom != null && !date.isBefore(shadeFrom) && !date.isAfter(shadeTo);
                row.addView(dayButton(date, shaded), cellParams);
            }
            grid.addView(row, rowParams);
        }
        // From the month of the earliest data, or of the current start if that is earlier, up to next month. Always back
        // to last month at least, so the start of the current period can be picked before anything is in the record.
        LocalDate first = start == null || start.isAfter(today.minusMonths(1)) ? today.minusMonths(1) : start;
        if (earliest != null && LocalDate.parse(earliest).isBefore(first)) first = LocalDate.parse(earliest);
        enable(calPrev, view.isAfter(YearMonth.from(first)));
        enable(calNext, view.isBefore(thisMonth.plusMonths(1)));
    }

    private Button dayButton(final LocalDate date, boolean shaded) {
        Button button = button(String.valueOf(date.getDayOfMonth()));
        button.setId(R.id.cal_day);
        button.setPadding(0, 0, 0, 0);
        button.setMinHeight(dp(44));
        button.setMinimumHeight(dp(44));
        button.setContentDescription(Words.longDay(date));
        // One day is highlighted: the day picked, else the saved start; none after Reset. The current pay period's days
        // have a quiet tint under it.
        final LocalDate marked = off ? null : chosen != null ? chosen : start;
        boolean isMarked = date.equals(marked);
        if (isMarked) filled(button);
        else if (shaded) button.setBackground(touchable(shape((accent & 0x00ffffff) | 0x33000000, line2, 1, 8)));
        button.setSelected(isMarked);
        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (date.equals(marked)) {
                    // The highlighted day unpicks it, as Reset does: a saved pay period is turned off on Confirm.
                    chosen = null;
                    off = start != null;
                } else {
                    // Any other day becomes the pick; the saved start is no change.
                    chosen = date.equals(start) ? null : date;
                    off = false;
                }
                drawCal();
                drawReview();
            }
        });
        return button;
    }

    private void drawReview() {
        boolean changing = chosen != null || off;
        if (!first) {
            close.setVisibility(changing ? View.GONE : View.VISIBLE);
            revert.setVisibility(changing ? View.VISIBLE : View.GONE);
            enable(reset, !off && (start != null || chosen != null));
        }
        confirm.setVisibility(changing ? View.VISIBLE : View.GONE);
        enable(confirm, true);
        review.setVisibility(changing ? View.VISIBLE : View.GONE);
        preview.setVisibility(changing || start != null ? View.VISIBLE : View.GONE);
        PayPeriod saved = start == null ? null : new PayPeriod(start, Integer.parseInt(days));
        SpannableStringBuilder current = new SpannableStringBuilder("Current pay period: ");
        bold(current, saved == null ? "none" : saved.current(today));
        if (changing) {
            PayPeriod next = shown();
            current.append(" → ");
            bold(current, next == null ? "none" : next.current(today));
        }
        preview.setText(current);
        if (!changing) return;
        SpannableStringBuilder text = new SpannableStringBuilder("Pay periods start: ");
        bold(text, start == null ? "not set" : Words.weekdayDay(start, today));
        text.append(" → ");
        bold(text, off ? "none" : Words.weekdayDay(chosen, today));
        review.setText(text);
    }

    private void bold(SpannableStringBuilder text, String value) {
        int from = text.length();
        text.append(value);
        text.setSpan(new StyleSpan(Typeface.BOLD), from, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.setSpan(new ForegroundColorSpan(txt), from, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private void save() {
        if (chosen == null && !off) return;
        enable(confirm, false);
        final String value = off ? null : chosen.toString(), length = days;
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
