package com.calltally.app;

import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Map;

/**
 * The month screen: the month's call time and texts day by day, the month and pay period totals above the calendar and
 * All time below it. Tapping a day opens it; tapping a total lists it by contact; tapping the month's name picks another
 * month. Also asks for permission and starts the first run. Runs happen without this screen; it redraws when one finishes.
 */
public class MainActivity extends Screen {
    private static final int ASK = 1;
    private static final String[] WEEK = {"Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"};
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean asked;
    private boolean narrow;
    private int asking;

    private LocalDate today;
    private YearMonth thisMonth;
    private YearMonth view;
    private String earliest;
    private PayPeriod period;
    private long periodK;
    private boolean periodFollow = true;

    private TextView updated;
    private LinearLayout damagedBox;
    private TextView damaged;
    private TextView problem;
    private TextView empty;
    private ImageButton prev;
    private ImageButton next;
    private ImageButton now;
    private FitText title;
    private Button monthRow;
    private LinearLayout periodLine;
    private Button periodRow;
    private ImageButton periodPrev;
    private ImageButton periodNext;
    private LinearLayout grid;
    private Button allRow;
    private final Map<String, LinearLayout> cells = new java.util.HashMap<>();
    private String permissionText;
    private Dialog picker;
    private int pickYear;
    private TextView pickTitle;
    private ImageButton pickPrev;
    private ImageButton pickNext;
    private final Button[] pickMonths = new Button[12];

    private final SharedPreferences.OnSharedPreferenceChangeListener runs = new SharedPreferences.OnSharedPreferenceChangeListener() {
        @Override
        public void onSharedPreferenceChanged(SharedPreferences saved, String key) {
            if ("run_done".equals(key)) load();
        }
    };

    private final Runnable minute = new Runnable() {
        @Override
        public void run() {
            if (!LocalDate.now().equals(today)) {
                boolean following = view.equals(thisMonth);
                today = LocalDate.now();
                thisMonth = YearMonth.from(today);
                if (following) {
                    view = thisMonth;
                    drawMonth();
                }
                load();
            } else {
                showUpdated();
            }
            handler.postDelayed(this, 60000);
        }
    };

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        if (!StartActivity.done(this)) {
            // A fresh install, an update over the uploading app, or cleared data: the first-start page comes first.
            startActivity(new Intent(this, StartActivity.class));
            finish();
            return;
        }
        today = LocalDate.now();
        thisMonth = YearMonth.from(today);
        view = thisMonth;
        Bundle state = saved != null ? saved : getIntent().getExtras();
        if (state != null && state.getString("view") != null) {
            // Redrawn for a new look, or after Android let the screen go: the same month and pay period as before.
            YearMonth was = YearMonth.parse(state.getString("view"));
            if (!was.isAfter(thisMonth)) view = was;
            periodFollow = state.getBoolean("periodFollow", true);
            periodK = state.getLong("periodK", 0);
        }

        // A narrow phone gets tighter edges and gaps, so a day's call time still fits its cell.
        narrow = getResources().getConfiguration().screenWidthDp < 380;
        int edge = narrow ? 6 : 12;
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(edge), dp(12), dp(edge), dp(12));
        // The damaged-record note stays until OK is tapped, apart from the run's own problems below it.
        damagedBox = new LinearLayout(this);
        damagedBox.setOrientation(LinearLayout.VERTICAL);
        damagedBox.setBackground(shape(lossTint, loss, 1, 10));
        damagedBox.setPadding(dp(12), dp(8), dp(12), dp(8));
        damagedBox.setVisibility(View.GONE);
        damaged = text("", 14, loss, false);
        damaged.setId(R.id.damaged);
        damagedBox.addView(damaged, wide());
        Button ok = button("OK");
        ok.setId(R.id.damaged_ok);
        ok.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                damagedBox.setVisibility(View.GONE);
                final Context context = getApplicationContext();
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        Sync.clearDamaged(context);
                    }
                }).start();
            }
        });
        LinearLayout.LayoutParams okGap = new LinearLayout.LayoutParams(dp(88), ViewGroup.LayoutParams.WRAP_CONTENT);
        okGap.topMargin = dp(8);
        okGap.gravity = Gravity.END;
        damagedBox.addView(ok, okGap);
        LinearLayout.LayoutParams gapBelow = wide();
        gapBelow.bottomMargin = dp(10);
        column.addView(damagedBox, gapBelow);
        problem = problemBox();
        problem.setId(R.id.problem);
        column.addView(problem, gapBelow);
        empty = text("No work calls or texts yet.", 14, muted, false);
        empty.setGravity(Gravity.CENTER);
        empty.setVisibility(View.GONE);
        column.addView(empty, gapBelow);

        LinearLayout monthCard = new LinearLayout(this);
        monthCard.setOrientation(LinearLayout.VERTICAL);
        monthCard.setBackground(shape(card, line, 1, 12));
        monthCard.setPadding(dp(narrow ? 4 : 8), dp(8), dp(narrow ? 4 : 8), dp(8));
        column.addView(monthCard, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        LinearLayout nav = row();
        nav.setGravity(Gravity.CENTER_VERTICAL);
        prev = arrow(R.drawable.chevron_left, "Previous month", R.id.month_prev);
        next = arrow(R.drawable.chevron_right, "Next month", R.id.month_next);
        now = arrow(R.drawable.chevron_double, "This month", R.id.month_now);
        // The month's name shrinks to one line on a narrow phone, and a tap on it picks a month.
        title = new FitText(this, 20);
        title.setTextColor(txt);
        title.setTypeface(medium());
        figures(title);
        title.setId(R.id.month_title);
        title.setGravity(Gravity.CENTER);
        title.setPadding(dp(6), 0, dp(6), 0);
        title.setMinHeight(dp(48));
        title.setBackground(touchable(null, 8));
        // The month's name sits between its own two arrows, with >> at the far right.
        nav.addView(prev, new LinearLayout.LayoutParams(dp(48), dp(48)));
        nav.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        nav.addView(next, new LinearLayout.LayoutParams(dp(48), dp(48)));
        nav.addView(now, new LinearLayout.LayoutParams(dp(48), dp(48)));
        monthCard.addView(nav, wide());
        prev.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                go(-1);
            }
        });
        next.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                go(1);
            }
        });
        now.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                jump(thisMonth);
            }
        });
        title.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pick();
            }
        });

        monthRow = totalRow(R.id.total_month);
        LinearLayout.LayoutParams rowGap = wide();
        rowGap.topMargin = dp(8);
        monthCard.addView(monthRow, rowGap);
        monthRow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openContacts(Words.monthTitle(view.getYear(), view.getMonthValue()), view.atDay(1).toString(), view.atEndOfMonth().toString());
            }
        });

        periodLine = row();
        periodPrev = arrow(R.drawable.chevron_left, "Previous pay period", R.id.period_prev);
        periodNext = arrow(R.drawable.chevron_right, "Next pay period", R.id.period_next);
        periodRow = totalRow(R.id.total_period);
        periodLine.addView(periodPrev, new LinearLayout.LayoutParams(dp(42), ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout.LayoutParams middle = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        middle.leftMargin = dp(6);
        middle.rightMargin = dp(6);
        periodLine.addView(periodRow, middle);
        periodLine.addView(periodNext, new LinearLayout.LayoutParams(dp(42), ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout.LayoutParams periodGap = wide();
        periodGap.topMargin = dp(5);
        monthCard.addView(periodLine, periodGap);
        periodPrev.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stepPeriod(-1);
            }
        });
        periodNext.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stepPeriod(1);
            }
        });
        periodRow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (period == null) return;
                openContacts(period.label(periodK, today), period.first(periodK).toString(), period.last(periodK).toString());
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
        monthCard.addView(week, weekGap);

        grid = new LinearLayout(this);
        grid.setId(R.id.grid);
        grid.setOrientation(LinearLayout.VERTICAL);
        monthCard.addView(grid, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        allRow = totalRow(R.id.total_all);
        monthCard.addView(allRow, rowGap);
        allRow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openContacts("All time", null, null);
            }
        });

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(column, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        frame("Call Tally", scroll, false);

        // The title over a quiet line saying when the phone was last read.
        header.removeView(titleView);
        LinearLayout heading = new LinearLayout(this);
        heading.setOrientation(LinearLayout.VERTICAL);
        heading.addView(titleView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        updated = text("", 12, ink, false);
        updated.setId(R.id.updated);
        updated.setSingleLine(true);
        LinearLayout.LayoutParams updatedGap = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        updatedGap.topMargin = dp(1);
        heading.addView(updated, updatedGap);
        header.addView(heading, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        ImageButton gear = new ImageButton(this);
        gear.setId(R.id.gear);
        gear.setContentDescription("Options");
        Drawable icon = getDrawable(R.drawable.gear).mutate();
        icon.setTint(ink);
        gear.setImageDrawable(icon);
        gear.setBackground(touchable(null, 22));
        gear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, OptionsActivity.class));
            }
        });
        LinearLayout.LayoutParams gearSize = new LinearLayout.LayoutParams(dp(44), dp(44));
        gearSize.leftMargin = dp(4);
        header.addView(gear, gearSize);

        showRow(monthRow, "Month", null);
        showRow(periodRow, "Pay period", null);
        showRow(allRow, "All time", null);
        drawMonth();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (view == null) return;
        out.putString("view", view.toString());
        out.putBoolean("periodFollow", periodFollow);
        out.putLong("periodK", periodK);
    }

    @Override
    void keep(Intent intent) {
        intent.putExtra("view", view.toString()).putExtra("periodFollow", periodFollow).putExtra("periodK", periodK);
    }

    /** A totals row: a small label, then the call time in bold, the calls and the texts. */
    private Button totalRow(int id) {
        Button row = new Button(this);
        row.setId(id);
        row.setAllCaps(false);
        row.setStateListAnimator(null);
        row.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        row.setMinWidth(0);
        row.setMinimumWidth(0);
        row.setMinHeight(dp(46));
        row.setMinimumHeight(dp(46));
        row.setPadding(dp(10), dp(4), dp(10), dp(4));
        row.setTextSize(13);
        row.setTextColor(muted);
        row.setLineSpacing(0, 1.1f);
        row.setBackground(touchable(shape(tint, line2, 1, 8)));
        figures(row);
        return row;
    }

    private void showRow(Button row, String label, Record.Totals totals) {
        SpannableStringBuilder text = new SpannableStringBuilder(label);
        text.setSpan(new AbsoluteSizeSpan(12, true), 0, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.append('\n');
        if (totals == null) {
            text.append("…");
        } else {
            int start = text.length();
            text.append(Words.fullTime(totals.callS));
            text.setSpan(new StyleSpan(Typeface.BOLD), start, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            text.setSpan(new AbsoluteSizeSpan(15, true), start, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            text.setSpan(new ForegroundColorSpan(txt), start, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            text.append(" · ").append(Words.plural(totals.calls, "call")).append(" · ").append(Words.plural(totals.texts, "text"));
        }
        row.setText(text);
        row.setContentDescription(totals == null ? label : label + ", call time " + Words.fullTime(totals.callS) + ", "
            + Words.plural(totals.calls, "call") + ", " + Words.plural(totals.texts, "text"));
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (isFinishing()) return;
        Sync.preferences(this).registerOnSharedPreferenceChangeListener(runs);
        handler.removeCallbacks(minute);
        handler.postDelayed(minute, 60000);
        permissionText = null;
        if (Sync.allowed(this)) {
            Sync.schedule(this);
            // Opening the app reads the phone at once, unless a run started in the last two minutes; this also covers
            // access granted in Settings, where the permission dialog's answer never arrives.
            if (Sync.sinceLastRun(this) > 2 * 60 * 1000L) start();
        } else {
            permissionText = "This app needs your call log, SMS and contacts. If Android won't offer them, open App info"
                + " for Call Tally, tap the three-dot menu, choose Allow restricted settings, and come back.";
            // Only once each time the app is opened: Android answers a refused request at once, which would loop.
            if (!asked) {
                asked = true;
                requestPermissions(Sync.PERMISSIONS, ASK);
            }
        }
        load();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (view == null) return;
        Sync.preferences(this).unregisterOnSharedPreferenceChangeListener(runs);
        handler.removeCallbacks(minute);
    }

    @Override
    protected void onDestroy() {
        // An open picker goes with the screen, so Android has no window left behind.
        if (picker != null) picker.dismiss();
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] names, int[] results) {
        if (code == ASK && Sync.allowed(this)) {
            permissionText = null;
            Sync.schedule(this);
            start();
            load();
        }
    }

    private void start() {
        final Context context = getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                Sync.run(context);
            }
        }).start();
    }

    private void go(int by) {
        view = view.plusMonths(by);
        drawMonth();
        load();
    }

    private void jump(YearMonth month) {
        view = month;
        drawMonth();
        load();
    }

    private void stepPeriod(int by) {
        if (period == null) return;
        periodK += by;
        periodFollow = periodK == period.index(today);
        load();
    }

    private void openContacts(String label, String from, String to) {
        Intent intent = new Intent(this, ContactsActivity.class).putExtra("label", label);
        if (from != null) intent.putExtra("from", from).putExtra("to", to);
        startActivity(intent);
    }

    // The calendar

    /** Lays out the month's days, empty until the record answers. */
    private void drawMonth() {
        String shown = Words.monthTitle(view.getYear(), view.getMonthValue());
        title.show(shown);
        title.setContentDescription(shown + ". Pick a month");
        grid.removeAllViews();
        cells.clear();
        int lead = view.atDay(1).getDayOfWeek().getValue() % 7;
        int count = view.lengthOfMonth();
        int rows = (lead + count + 6) / 7;
        int day = 1 - lead;
        for (int r = 0; r < rows; r++) {
            LinearLayout row = row();
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
            if (r > 0) rowParams.topMargin = dp(narrow ? 2 : 4);
            row.setMinimumHeight(dp(52));
            for (int c = 0; c < 7; c++, day++) {
                LinearLayout.LayoutParams cellParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
                if (c > 0) cellParams.leftMargin = dp(narrow ? 2 : 4);
                if (day < 1 || day > count) {
                    row.addView(new View(this), cellParams);
                    continue;
                }
                LocalDate date = view.atDay(day);
                LinearLayout cell = cell(date);
                cells.put(date.toString(), cell);
                fill(cell, date, null);
                row.addView(cell, cellParams);
            }
            grid.addView(row, rowParams);
        }
        drawNav();
    }

    private LinearLayout cell(final LocalDate date) {
        LinearLayout cell = new LinearLayout(this);
        cell.setId(R.id.day_cell);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setPadding(dp(narrow ? 2 : 4), dp(3), dp(narrow ? 1 : 3), dp(2));
        TextView number = text(String.valueOf(date.getDayOfMonth()), 13, muted, false);
        FitText time = new FitText(this, 14);
        time.setTextColor(txt);
        time.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        figures(time);
        TextView texts = text("", 12, muted, false);
        texts.setLineSpacing(0, 0.95f);
        cell.addView(number, wide());
        cell.addView(time, wide());
        cell.addView(texts, wide());
        cell.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, DayActivity.class).putExtra("day", date.toString()));
            }
        });
        return cell;
    }

    private void fill(LinearLayout cell, LocalDate date, Record.DayTotals data) {
        TextView number = (TextView) cell.getChildAt(0);
        FitText time = (FitText) cell.getChildAt(1);
        TextView texts = (TextView) cell.getChildAt(2);
        boolean used = data != null && data.calls + data.unanswered + data.texts + data.ignored > 0;
        boolean isToday = date.equals(today);
        // Only a day with something gets a tile; today keeps its ring. A day still to come has its number paler.
        int busy = (accent & 0x00ffffff) | (night() ? 0x33000000 : 0x22000000);
        cell.setBackground(isToday ? touchable(shape(used ? busy : 0, accent, 2, 8), 8) : used ? touchable(shape(busy, 0, 0, 8), 8) : null);
        cell.setClickable(used);
        cell.setEnabled(used);
        int faint = (muted & 0x00ffffff) | 0x80000000;
        number.setTextColor(isToday ? accent : !used && date.isAfter(today) ? faint : muted);
        number.setTypeface(isToday ? Typeface.create(Typeface.DEFAULT, Typeface.BOLD) : Typeface.DEFAULT);
        StringBuilder words = new StringBuilder(Words.monthName(date.getMonthValue()) + " " + date.getDayOfMonth());
        if (data != null && data.calls > 0) {
            time.show(Words.clockTime(data.callS));
            time.setVisibility(View.VISIBLE);
            words.append(", call time ").append(Words.fullTime(data.callS));
        } else {
            time.show("");
            time.setVisibility(View.GONE);
        }
        texts.setTextSize(12);
        if (data != null && data.texts > 0) {
            texts.setText(Words.plural(data.texts, "text"));
            words.append(", ").append(Words.plural(data.texts, "text"));
        } else if (used && data.calls == 0) {
            texts.setTextSize(11);
            texts.setText(data.unanswered > 0 ? "no answer" : "ignored");
            words.append(data.unanswered > 0 ? ", calls not answered only" : ", ignored entries only");
        } else {
            texts.setText("");
        }
        cell.setContentDescription(words);
    }

    private void drawNav() {
        YearMonth before = view.minusMonths(1);
        enable(prev, earliest != null && before.atEndOfMonth().toString().compareTo(earliest) >= 0);
        enable(next, view.isBefore(thisMonth));
        enable(now, !view.equals(thisMonth));
        // The month's name always looks the same; with no entries yet there is nothing to pick, so a tap does nothing.
        title.setEnabled(earliest != null);
        title.setClickable(earliest != null);
        if (period == null) {
            enable(periodPrev, false);
            enable(periodNext, false);
        } else {
            enable(periodPrev, earliest != null && period.last(periodK - 1).toString().compareTo(earliest) >= 0);
            enable(periodNext, periodK < period.index(today));
        }
    }

    // The month picker

    /** A month with something to show: not after this month, and not ended before the record's first entry. */
    private boolean pickable(YearMonth month) {
        return earliest != null && !month.isAfter(thisMonth) && month.atEndOfMonth().toString().compareTo(earliest) >= 0;
    }

    private boolean anyPickable(int year) {
        for (int m = 1; m <= 12; m++) if (pickable(YearMonth.of(year, m))) return true;
        return false;
    }

    /** A year with arrows, its twelve months to tap, and Close, drawn in the page's own style. */
    private void pick() {
        if (earliest == null || picker != null) return;
        pickYear = view.getYear();
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(shape(card, line, 1, 12));
        box.setPadding(dp(12), dp(12), dp(12), dp(12));

        LinearLayout top = row();
        top.setGravity(Gravity.CENTER_VERTICAL);
        pickPrev = arrow(R.drawable.chevron_left, "Previous year", R.id.pick_prev);
        pickNext = arrow(R.drawable.chevron_right, "Next year", R.id.pick_next);
        pickTitle = text("", 20, txt, false);
        pickTitle.setTypeface(medium());
        pickTitle.setId(R.id.pick_year);
        pickTitle.setGravity(Gravity.CENTER);
        top.addView(pickPrev, new LinearLayout.LayoutParams(dp(48), dp(48)));
        top.addView(pickTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        top.addView(pickNext, new LinearLayout.LayoutParams(dp(48), dp(48)));
        box.addView(top, wide());
        pickPrev.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickYear--;
                drawPicker();
            }
        });
        pickNext.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickYear++;
                drawPicker();
            }
        });

        for (int r = 0; r < 4; r++) {
            LinearLayout months = row();
            for (int c = 0; c < 3; c++) {
                final int month = r * 3 + c + 1;
                String name = Words.monthName(month);
                Button button = button(name.substring(0, 3));
                button.setId(R.id.month_pick);
                button.setContentDescription(name);
                button.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (picker != null) picker.dismiss();
                        jump(YearMonth.of(pickYear, month));
                    }
                });
                LinearLayout.LayoutParams cell = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
                if (c > 0) cell.leftMargin = dp(6);
                months.addView(button, cell);
                pickMonths[month - 1] = button;
            }
            LinearLayout.LayoutParams gap = wide();
            gap.topMargin = dp(r == 0 ? 10 : 6);
            box.addView(months, gap);
        }

        Button close = button("Close");
        close.setId(R.id.close);
        close.setMinHeight(dp(48));
        close.setMinimumHeight(dp(48));
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (picker != null) picker.dismiss();
            }
        });
        LinearLayout.LayoutParams closeGap = wide();
        closeGap.topMargin = dp(14);
        box.addView(close, closeGap);

        // On a short screen, such as a phone on its side, the picker scrolls so Close is always reachable.
        ScrollView boxScroll = new ScrollView(this);
        boxScroll.setFillViewport(true);
        boxScroll.addView(box, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        picker = new Dialog(this);
        picker.requestWindowFeature(Window.FEATURE_NO_TITLE);
        picker.setContentView(boxScroll);
        picker.setCanceledOnTouchOutside(true);
        picker.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(android.content.DialogInterface dialog) {
                picker = null;
            }
        });
        drawPicker();
        picker.show();
        Window window = picker.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(0));
            int across = getResources().getDisplayMetrics().widthPixels - dp(32);
            window.setLayout(Math.min(dp(360), across), ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    private void drawPicker() {
        pickTitle.setText(String.valueOf(pickYear));
        for (int m = 1; m <= 12; m++) {
            YearMonth month = YearMonth.of(pickYear, m);
            Button button = pickMonths[m - 1];
            if (month.equals(view)) {
                outlined(button);
            } else {
                plain(button);
                // This month in bold, like today on the calendar.
                if (month.equals(thisMonth)) button.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
            }
            enable(button, pickable(month));
        }
        enable(pickPrev, anyPickable(pickYear - 1));
        enable(pickNext, anyPickable(pickYear + 1));
    }

    private void showUpdated() {
        long now = System.currentTimeMillis(), last = Sync.updatedAt(this);
        updated.setText(Words.updated(last, now));
        int stale = Words.staleness(last, now);
        if (stale == 0) {
            updated.setBackground(null);
            updated.setPadding(0, 0, 0, 0);
            updated.setTextColor((ink & 0x00ffffff) | 0xD9000000);
            updated.setTypeface(Typeface.DEFAULT);
        } else {
            updated.setBackground(shape(stale == 2 ? 0xffc23a3a : 0xfff3c25a, 0, 0, 10));
            updated.setPadding(dp(8), dp(2), dp(8), dp(2));
            updated.setTextColor(stale == 2 ? 0xffffffff : 0xff2b1d00);
            updated.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        }
    }

    // Loading

    /** What one look at the record found, for one month and one pay period. */
    private static final class Found {
        YearMonth month;
        long periodK;
        Map<String, Record.DayTotals> days;
        Record.Totals monthTotals;
        Record.Totals periodTotals;
        Record.Totals all;
        String earliest;
        PayPeriod period;
        boolean followed;
        String problem;
        String lookMode;
        String lookTheme;
    }

    /** Asks the record away from the screen; an answer for a month or period no longer shown is dropped. */
    private void load() {
        showUpdated();
        final int mine = ++asking;
        final YearMonth month = view;
        final LocalDate day = today;
        final boolean follow = periodFollow;
        final long k = periodK;
        final LocalDate shownStart = period == null ? null : period.start;
        final Context context = getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final Found found = new Found();
                found.month = month;
                try {
                    Record record = Record.get(context);
                    found.period = PayPeriod.of(record.setting("pay_period_start"), record.setting("pay_period_days"));
                    found.lookMode = record.setting("look_mode");
                    found.lookTheme = record.setting("look_theme");
                    found.days = record.days(month.atDay(1).toString(), month.atEndOfMonth().toString());
                    found.monthTotals = record.totals(month.atDay(1).toString(), month.atEndOfMonth().toString());
                    found.all = record.totals();
                    found.earliest = record.earliest();
                    if (found.period != null) {
                        // A new start day brings back the current period.
                        found.followed = follow || (shownStart != null && !shownStart.equals(found.period.start));
                        found.periodK = found.followed ? found.period.index(day) : k;
                        found.periodTotals = record.totals(found.period.first(found.periodK).toString(), found.period.last(found.periodK).toString());
                    }
                } catch (Record.TooNew error) {
                    found.problem = error.getMessage();
                } catch (RuntimeException error) {
                    found.problem = Record.UNREADABLE;
                }
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (mine == asking && !isDestroyed()) show(found);
                    }
                });
            }
        }).start();
    }

    private void show(Found found) {
        // The record holds the look; a phone with no quick copy of it yet takes it from there.
        if (found.lookMode != null && found.lookTheme != null && adoptMissing(this, found.lookMode, found.lookTheme)) {
            redraw();
            return;
        }
        String note = Sync.damaged(this);
        damaged.setText(note == null ? "" : note);
        damagedBox.setVisibility(note == null ? View.GONE : View.VISIBLE);
        String trouble = found.problem != null ? found.problem : permissionText != null ? permissionText : Sync.problem(this);
        problem.setText(trouble == null ? "" : trouble);
        problem.setVisibility(trouble == null ? View.GONE : View.VISIBLE);
        if (found.problem != null) return;
        earliest = found.earliest;
        empty.setVisibility(earliest == null && permissionText == null ? View.VISIBLE : View.GONE);
        for (Map.Entry<String, LinearLayout> cell : cells.entrySet()) {
            fill(cell.getValue(), LocalDate.parse(cell.getKey()), found.days.get(cell.getKey()));
        }
        showRow(monthRow, "Month", found.monthTotals);
        period = found.period;
        periodLine.setVisibility(period == null ? View.GONE : View.VISIBLE);
        if (period != null) {
            periodK = found.periodK;
            if (found.followed) periodFollow = true;
            showRow(periodRow, period.label(periodK, today), found.periodTotals);
        }
        showRow(allRow, "All time", found.all);
        drawNav();
    }
}
