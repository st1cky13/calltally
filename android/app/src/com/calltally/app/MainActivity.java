package com.calltally.app;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.ActivityOptions;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.CornerPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.Window;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.Map;

/**
 * The month screen: the month's call time and texts day by day, the month and pay period totals above the calendar and
 * All time below it. Tapping a day opens it; tapping a total lists it by contact; tapping the month's name picks another
 * month; a sideways swipe on the calendar moves one month, or one pay period when it starts on the pay period line.
 * Also asks for permission and starts the first run. Runs happen without this screen; it redraws when one finishes.
 */
public class MainActivity extends Screen {
    private static final int ASK = 1;
    private static final String[] WEEK = {"Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"};
    // One scale of gaps and one of corners for the month screen and its picker, in dp.
    private static final int GAP_S = 4;
    private static final int GAP = 8;
    private static final int GAP_L = 12;
    private static final int GAP_XL = 16;
    // Tiles and buttons; the totals rows and the pay period line.
    private static final int CORNER = 8;
    private static final int CORNER_CARD = 12;
    /** How anything arriving eases in, and how a month leaving eases out. No overshoot anywhere. */
    private static final Interpolator ARRIVE = new PathInterpolator(0.16f, 1f, 0.3f, 1f);
    private static final Interpolator LEAVE = new PathInterpolator(0.4f, 0f, 1f, 1f);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean asked;
    private boolean narrow;
    /** The weekday names over the calendar, whose lead drawMonth drops once if a name and its lead would not fit. */
    private final TextView[] weekNames = new TextView[7];
    private boolean weekFitted;
    private int digitLead = -1;
    /** The room inside a day cell for its time and texts, set by drawMonth. */
    private int lineRoom;
    private int asking;
    /** The owner's time per text in seconds, 0 when off: shown under the totals, apart from call time. */
    private int textSeconds;

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
    /** The calendar's week rows, top to bottom, and the empty cells before the month's first day. */
    private final java.util.List<LinearLayout> weeks = new java.util.ArrayList<>();
    private int blanks;
    /** The pay period the calendar marks: the one on the card, which changes only when the record answers. */
    private long markedK;
    /** Whether markedK is the card's period yet: not on a page the calendar turned to until the record answers. */
    private boolean markedKnown;
    /** The month the outline was last drawn on. */
    private YearMonth outlineMonth;
    /** The shown period's days on this page as week row, first column and last column, and their outline in the grid. */
    private final java.util.List<int[]> spans = new java.util.ArrayList<>();
    private final Path outline = new Path();
    private Paint outlinePaint;
    /**
     * The outline's motion. A glide moves the shape of the period glideFirst to glideLast by glideOffset pixels, drawn
     * only where it meets this month's days; a fade shows the old outline going out, then the new one coming in.
     */
    private static final int STILL = 0, GLIDE = 1, FADE_OUT = 2, FADE_IN = 3;
    private int outlinePhase = STILL;
    private ValueAnimator outlineMove;
    private LocalDate glideFirst;
    private LocalDate glideLast;
    private float glideOffset;
    /**
     * A glide's geometry, built when it starts and on each layout: this month's own region, and the target period's
     * parts (runs of rows that join) where they lie at offset 0. Each frame moves the parts into the reused paths below,
     * clips them to the region, and fades each by how much of it is left.
     */
    private final Path glideRegion = new Path();
    private final java.util.List<Path> glideParts = new java.util.ArrayList<>();
    private final java.util.List<Path> glideDrawn = new java.util.ArrayList<>();
    private float[] glideAlpha = new float[0];
    private final Path glideMoved = new Path();
    private final RectF glideBounds = new RectF();
    private float glideRowHeight;
    private final Path fadingOutline = new Path();
    private float fadingAlpha;
    private float outlineAlpha = 1f;
    /** A month slide: the old page's picture leaving in the card's overlay, and the new page coming in. */
    private LinearLayout monthCard;
    private Bitmap leavingPicture;
    private BitmapDrawable leavingPage;
    private ValueAnimator leave;
    private ValueAnimator enter;
    private boolean entering;
    /** The new page's entrance waits for both: the old page gone and the new month's days filled. */
    private boolean leftPage;
    private boolean daysFilled;
    /** The new page has started coming in since the last month change. */
    private boolean pageCame;
    private int enterSide;
    private final Runnable enterSoon = new Runnable() {
        @Override
        public void run() {
            enterMonth();
        }
    };
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
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(edge(), dp(GAP_L), edge(), dp(GAP_L));
        // The damaged-record note stays until OK is tapped, apart from the run's own problems below it.
        damagedBox = new LinearLayout(this);
        damagedBox.setOrientation(LinearLayout.VERTICAL);
        damagedBox.setBackground(shape(lossTint, loss, 1, CORNER_CARD));
        damagedBox.setPadding(dp(GAP_L), dp(GAP), dp(GAP_L), dp(GAP));
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
        okGap.topMargin = dp(GAP);
        okGap.gravity = Gravity.END;
        damagedBox.addView(ok, okGap);
        LinearLayout.LayoutParams gapBelow = wide();
        gapBelow.bottomMargin = dp(GAP_L);
        column.addView(damagedBox, gapBelow);
        problem = problemBox();
        problem.setId(R.id.problem);
        column.addView(problem, gapBelow);
        empty = text("No work calls or texts yet.", 14, muted, false);
        empty.setGravity(Gravity.CENTER);
        empty.setVisibility(View.GONE);
        column.addView(empty, gapBelow);

        // The month's nav, totals, calendar and All time sit straight on the page, with no card around them. A sideways
        // swipe anywhere on them moves one month.
        monthCard = new SwipeCard();
        monthCard.setOrientation(LinearLayout.VERTICAL);
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
        title.setPadding(dp(GAP_S), 0, dp(GAP_S), 0);
        title.setMinHeight(dp(48));
        title.setBackground(touchable(null, CORNER));
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
        rowGap.topMargin = dp(GAP);
        monthCard.addView(monthRow, rowGap);
        monthRow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openContacts(v, Words.monthTitle(view.getYear(), view.getMonthValue()), view.atDay(1).toString(), view.atEndOfMonth().toString());
            }
        });

        // One shape like the totals rows: the arrows at its two ends step the period, the middle lists it by contact.
        periodLine = row();
        periodLine.setBackground(shape(soft(), 0, 0, CORNER_CARD));
        periodPrev = arrow(R.drawable.chevron_left, "Previous pay period", R.id.period_prev);
        periodNext = arrow(R.drawable.chevron_right, "Next pay period", R.id.period_next);
        periodRow = totalRow(R.id.total_period);
        periodRow.setBackground(touchable(null, 0));
        periodLine.addView(periodPrev, new LinearLayout.LayoutParams(dp(48), ViewGroup.LayoutParams.MATCH_PARENT));
        periodLine.addView(periodRow, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        periodLine.addView(periodNext, new LinearLayout.LayoutParams(dp(48), ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout.LayoutParams periodGap = wide();
        periodGap.topMargin = dp(GAP);
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
                // The period the card shows: the marked one once known, even while a step waits for the record.
                long k = markedKnown ? markedK : periodK;
                openContacts(periodLine, period.label(k, today), period.first(k).toString(), period.last(k).toString());
            }
        });

        // Each weekday sits over its column with the days' own gaps, starting where a two-digit day's number starts in
        // its circle's room, so Sun lines up with the numbers below it.
        LinearLayout week = row();
        int lead = dp(narrow ? 2 : 4) + digitLead();
        for (int c = 0; c < WEEK.length; c++) {
            TextView day = caption(WEEK[c]);
            day.setGravity(Gravity.START);
            day.setSingleLine(true);
            day.setEllipsize(null);
            day.setPadding(lead, 0, 0, 0);
            weekNames[c] = day;
            LinearLayout.LayoutParams dayParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            if (c > 0) dayParams.leftMargin = dp(narrow ? 2 : 4);
            week.addView(day, dayParams);
        }
        LinearLayout.LayoutParams weekGap = wide();
        weekGap.topMargin = dp(GAP_L);
        weekGap.bottomMargin = dp(GAP);
        monthCard.addView(week, weekGap);

        grid = new LinearLayout(this);
        grid.setId(R.id.grid);
        grid.setOrientation(LinearLayout.VERTICAL);
        monthCard.addView(grid, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        // The pay period's outline follows the days wherever a layout puts them.
        grid.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int l, int t, int r, int b, int oldL, int oldT, int oldR, int oldB) {
                drawOutline();
            }
        });

        allRow = totalRow(R.id.total_all);
        monthCard.addView(allRow, rowGap);
        allRow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openContacts(v, "All time", null, null);
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
        Button row = new TotalButton();
        row.setId(id);
        row.setAllCaps(false);
        row.setStateListAnimator(null);
        row.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        row.setMinWidth(0);
        row.setMinimumWidth(0);
        row.setMinHeight(dp(46));
        row.setMinimumHeight(dp(46));
        row.setPadding(dp(GAP_L), dp(GAP_S), dp(GAP_L), dp(GAP_S));
        row.setTextSize(13);
        row.setTextColor(muted);
        row.setLineSpacing(0, 1.1f);
        row.setBackground(touchable(shape(soft(), 0, 0, CORNER_CARD), CORNER_CARD));
        figures(row);
        return row;
    }

    /**
     * The month card: a sideways swipe on it moves one month, as the arrows do, or one pay period when it starts on the pay
     * period line; taps and drags up and down go on as before.
     */
    private final class SwipeCard extends LinearLayout {
        private final int slop = ViewConfiguration.get(MainActivity.this).getScaledTouchSlop();
        private VelocityTracker speed;
        private float downX;
        private float downY;
        private boolean swiping;
        /** The finger the touch started with; a second finger ends the swipe until every finger is lifted. */
        private int pointer = MotionEvent.INVALID_POINTER_ID;
        private boolean ignoring;
        /** The touch started on the pay period line, so a swipe steps the pay period rather than the month. */
        private boolean onPeriod;

        SwipeCard() {
            super(MainActivity.this);
        }

        /** The pay period's outline goes first, beneath the days, and may reach a few dp past the grid into the gaps. */
        @Override
        protected void dispatchDraw(Canvas canvas) {
            if (grid != null) {
                // It rides with its month: the grid's own offset and fade during a month slide.
                canvas.save();
                canvas.translate(grid.getLeft() + grid.getTranslationX(), grid.getTop() + grid.getTranslationY());
                strokeOutline(canvas, grid.getAlpha());
                canvas.restore();
            }
            super.dispatchDraw(canvas);
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent event) {
            // Takes the touch from a day or a button only once it is plainly sideways, so a tap still opens what it hits.
            watch(event);
            return swiping;
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            // A touch nothing inside took, or one taken from inside: followed here until it ends. The page still scrolls
            // when the finger moves up or down first.
            watch(event);
            if (event.getActionMasked() == MotionEvent.ACTION_UP && swiping) {
                swiping = false;
                turn(event);
            }
            return true;
        }

        private void watch(MotionEvent event) {
            if (speed == null) speed = VelocityTracker.obtain();
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    speed.clear();
                    pointer = event.getPointerId(0);
                    ignoring = false;
                    downX = event.getX();
                    downY = event.getY();
                    swiping = false;
                    onPeriod = periodLine.getVisibility() == View.VISIBLE && downX >= periodLine.getLeft() && downX < periodLine.getRight()
                        && downY >= periodLine.getTop() && downY < periodLine.getBottom();
                    break;
                case MotionEvent.ACTION_POINTER_DOWN:
                    // A second finger: no swipe from this touch, so lifting the first can't make a jump look like one.
                    swiping = false;
                    ignoring = true;
                    speed.clear();
                    return;
                case MotionEvent.ACTION_MOVE:
                    if (ignoring) return;
                    int at = event.findPointerIndex(pointer);
                    if (at < 0) return;
                    float dx = Math.abs(event.getX(at) - downX), dy = Math.abs(event.getY(at) - downY);
                    if (!swiping && dx > slop && dx > 2 * dy) {
                        swiping = true;
                        getParent().requestDisallowInterceptTouchEvent(true);
                    }
                    break;
                case MotionEvent.ACTION_CANCEL:
                    swiping = false;
                    break;
                default:
                    break;
            }
            if (!ignoring) speed.addMovement(event);
        }

        /** A long enough swipe, or a quick flick, moves one month or pay period: left for the next, right for the one before. */
        private void turn(MotionEvent event) {
            float dx = event.getX() - downX;
            speed.computeCurrentVelocity(1000);
            boolean far = Math.abs(dx) > dp(64) || (Math.abs(dx) > dp(24) && Math.abs(speed.getXVelocity()) > dp(600));
            if (!far) return;
            // The same limits as the arrows: never past this month or period, or before the first entry.
            if (onPeriod) {
                if (dx < 0 && periodNext.isEnabled()) stepPeriod(1);
                else if (dx > 0 && periodPrev.isEnabled()) stepPeriod(-1);
                return;
            }
            if (dx < 0 && next.isEnabled()) go(1);
            else if (dx > 0 && prev.isEnabled()) go(-1);
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            if (speed != null) {
                speed.recycle();
                speed = null;
            }
        }
    }

    /**
     * A totals row whose text alone can fade, so a changed total swaps with a short fade while its fill, ripple and size
     * stay as they are. The background is drawn outside onDraw, so only the text goes through the faded layer.
     */
    private final class TotalButton extends Button {
        private float textAlpha = 1f;
        private boolean placeholder = true;
        private boolean fadingOut;
        private ValueAnimator fade;
        private CharSequence nextText;
        private CharSequence nextWords;

        TotalButton() {
            super(MainActivity.this);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (textAlpha >= 1f) {
                super.onDraw(canvas);
                return;
            }
            if (textAlpha <= 0f) return;
            int saved = canvas.saveLayerAlpha(getScrollX(), getScrollY(), getScrollX() + getWidth(), getScrollY() + getHeight(),
                Math.round(255 * textAlpha));
            super.onDraw(canvas);
            canvas.restoreToCount(saved);
        }

        /** New words for the row: at once for the "…" placeholder and the first value after it, otherwise with the fade. */
        void show(CharSequence text, CharSequence words, boolean isPlaceholder) {
            boolean first = placeholder;
            placeholder = isPlaceholder;
            if (first || isPlaceholder || !ValueAnimator.areAnimatorsEnabled() || !isLaidOut()) {
                settle(text, words);
                return;
            }
            boolean same = same(text, words, getText(), getContentDescription());
            if (fade == null) {
                if (!same) fadeOut(text, words);
                return;
            }
            if (fadingOut) {
                // The words going out are the right ones again: they come back from where they are.
                if (same) fadeIn();
                else {
                    nextText = text;
                    nextWords = words;
                }
            } else if (!same) {
                fadeOut(text, words);
            }
        }

        private boolean same(CharSequence text, CharSequence words, CharSequence shownText, CharSequence shownWords) {
            return text.toString().equals(String.valueOf(shownText)) && String.valueOf(words).equals(String.valueOf(shownWords));
        }

        /** The end of every swap, and the whole of one when animations are off: the words in place, fully shown. */
        private void settle(CharSequence text, CharSequence words) {
            stop();
            setText(text);
            setContentDescription(words);
            textAlpha = 1f;
            invalidate();
        }

        void stop() {
            ValueAnimator running = fade;
            fade = null;
            fadingOut = false;
            if (running != null) running.cancel();
        }

        private void fadeOut(CharSequence text, CharSequence words) {
            final float from = textAlpha;
            stop();
            nextText = text;
            nextWords = words;
            fadingOut = true;
            final ValueAnimator out = ValueAnimator.ofFloat(from, 0f);
            out.setDuration(Math.max(1, Math.round(60 * from)));
            out.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator animator) {
                    if (fade != out) return;
                    textAlpha = (float) animator.getAnimatedValue();
                    invalidate();
                }
            });
            out.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animator) {
                    if (fade != out) return;
                    // The swap: label, figures and spoken words change together.
                    setText(nextText);
                    setContentDescription(nextWords);
                    textAlpha = 0f;
                    fadeIn();
                }
            });
            fade = out;
            out.start();
        }

        private void fadeIn() {
            float from = textAlpha;
            stop();
            final ValueAnimator in = ValueAnimator.ofFloat(from, 1f);
            in.setDuration(Math.max(1, Math.round(140 * (1f - from))));
            in.setInterpolator(ARRIVE);
            in.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator animator) {
                    if (fade != in) return;
                    textAlpha = (float) animator.getAnimatedValue();
                    invalidate();
                }
            });
            in.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animator) {
                    if (fade != in) return;
                    fade = null;
                    textAlpha = 1f;
                    invalidate();
                }
            });
            fade = in;
            in.start();
        }
    }

    private void showRow(Button row, String label, Record.Totals totals) {
        SpannableStringBuilder text = new SpannableStringBuilder(label);
        text.setSpan(new CaptionSpan(), 0, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
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
            // The texts' own time, when the owner gives each text some seconds: a line of its own, never in the call time.
            if (textSeconds > 0) text.append("\nText time ").append(Words.textTime(totals.texts, textSeconds));
        }
        String words = totals == null ? label : label + ", call time " + Words.fullTime(totals.callS) + ", "
            + Words.plural(totals.calls, "call") + ", " + Words.plural(totals.texts, "text")
            + (textSeconds > 0 ? ", text time " + Words.textTime(totals.texts, textSeconds) : "");
        ((TotalButton) row).show(text, words, totals == null);
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
        // Any motion ends with the screen, and the month slide's picture is freed.
        if (view != null) {
            endSlide();
            stopOutline();
            for (Button row : new Button[] {monthRow, periodRow, allRow}) ((TotalButton) row).stop();
        }
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
        turnTo(view.plusMonths(by));
    }

    private void jump(YearMonth month) {
        turnTo(month);
    }

    private void stepPeriod(int by) {
        if (period == null) return;
        periodK += by;
        periodFollow = periodK == period.index(today);
        // The arrows' limits at once, so a second quick swipe before the record answers can't step past them.
        enablePeriodArrows();
        // A period with no day on this page turns the calendar to it. Its outline waits for the record's answer, so the
        // new page never shows the period the card no longer has.
        YearMonth turn = periodMonth();
        if (turn == null) {
            load();
            return;
        }
        markedKnown = false;
        turnTo(turn);
    }

    /**
     * The month to turn to for the stepped period: none when one of its days is on this page, otherwise the month of its
     * first day, kept within the months the month arrows allow.
     */
    private YearMonth periodMonth() {
        LocalDate first = period.first(periodK), last = period.last(periodK);
        if (onPage(first, last)) return null;
        YearMonth month = YearMonth.from(first);
        if (earliest != null && month.atEndOfMonth().toString().compareTo(earliest) < 0) {
            try {
                month = YearMonth.parse(earliest.substring(0, 7));
            } catch (RuntimeException unreadable) {
                return null;
            }
        }
        if (month.isAfter(thisMonth)) month = thisMonth;
        return month.equals(view) ? null : month;
    }

    /** Whether any day from first to last is in the month on screen. */
    private boolean onPage(LocalDate first, LocalDate last) {
        return !last.isBefore(view.atDay(1)) && !first.isAfter(view.atEndOfMonth());
    }

    private void openContacts(View from, String label, String first, String last) {
        Intent intent = new Intent(this, ContactsActivity.class).putExtra("label", label);
        if (first != null) intent.putExtra("from", first).putExtra("to", last);
        openFrom(from, intent);
    }

    /** Opens a day or a list out of the tile or card that was tapped; with animations removed, as before. */
    private void openFrom(View from, Intent intent) {
        if (ValueAnimator.areAnimatorsEnabled() && from != null && from.getWidth() > 0 && from.getHeight() > 0) {
            startActivity(intent, ActivityOptions.makeScaleUpAnimation(from, 0, 0, from.getWidth(), from.getHeight()).toBundle());
        } else {
            startActivity(intent);
        }
    }

    // The month slide

    /**
     * Shows another month. With animations on, the old page's picture leaves toward the side it goes, and the new page
     * comes in from the other once the old one is gone and its days are filled, or 120 ms after the tap at the latest,
     * so the two months are never both readable. A later month comes from the right.
     */
    private void turnTo(YearMonth month) {
        endSlide();
        boolean slide = ValueAnimator.areAnimatorsEnabled() && !month.equals(view) && grid.getWidth() > 0 && grid.getHeight() > 0;
        int side = month.isAfter(view) ? 1 : -1;
        pageCame = false;
        daysFilled = false;
        leftPage = false;
        if (slide) slide = leavePage(side);
        view = month;
        drawMonth();
        if (slide) {
            entering = true;
            enterSide = side;
            grid.setAlpha(0f);
            grid.setTranslationX(side * dp(24));
            monthCard.invalidate();
            // 120 ms, stretched like the animations themselves when the animator duration scale is changed.
            float scale = android.provider.Settings.Global.getFloat(getContentResolver(), android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f);
            handler.postDelayed(enterSoon, Math.max(1, Math.round(120 * scale)));
        }
        load();
    }

    /** The grid's part of the card, outline included, as a picture in the card's overlay that fades and moves away. */
    private boolean leavePage(int side) {
        int pad = dp(GAP_S);
        int width = grid.getWidth(), height = grid.getHeight() + 2 * pad;
        try {
            leavingPicture = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        } catch (OutOfMemoryError | IllegalArgumentException error) {
            leavingPicture = null;
            return false;
        }
        Canvas canvas = new Canvas(leavingPicture);
        canvas.translate(0, pad);
        strokeOutline(canvas, 1f);
        grid.draw(canvas);
        final BitmapDrawable page = new BitmapDrawable(getResources(), leavingPicture);
        final int left = grid.getLeft(), top = grid.getTop() - pad;
        page.setBounds(left, top, left + width, top + height);
        monthCard.getOverlay().add(page);
        leavingPage = page;
        final float away = -side * dp(16);
        final ValueAnimator going = ValueAnimator.ofFloat(0f, 1f);
        going.setDuration(70);
        going.setInterpolator(LEAVE);
        going.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animator) {
                if (leave != going) return;
                float f = (float) animator.getAnimatedValue();
                int x = left + Math.round(away * f);
                page.setBounds(x, top, x + page.getBounds().width(), top + page.getBounds().height());
                page.setAlpha(Math.round(255 * (1f - f)));
                monthCard.invalidate();
            }
        });
        going.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animator) {
                if (leave != going) return;
                freeLeavingPage();
                leftPage = true;
                if (daysFilled) enterMonth();
            }
        });
        leave = going;
        going.start();
        return true;
    }

    private void freeLeavingPage() {
        ValueAnimator running = leave;
        leave = null;
        if (running != null) running.cancel();
        if (leavingPage != null) monthCard.getOverlay().remove(leavingPage);
        leavingPage = null;
        leavingPicture = null;
    }

    /** The month's days are filled: the new page comes in now if the old one has already gone. */
    private void filledPage() {
        daysFilled = true;
        if (entering && leftPage) enterMonth();
    }

    /**
     * The new page comes in over 220 ms: from 24dp to the side to its place across the whole time, and from clear to
     * solid across the first 60 percent. A picture of the old page still leaving goes at once.
     */
    private void enterMonth() {
        handler.removeCallbacks(enterSoon);
        if (!entering) return;
        entering = false;
        pageCame = true;
        freeLeavingPage();
        final float from = enterSide * dp(24);
        final ValueAnimator coming = ValueAnimator.ofFloat(0f, 1f);
        coming.setDuration(220);
        coming.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animator) {
                if (enter != coming) return;
                float t = (float) animator.getAnimatedValue();
                grid.setAlpha(ARRIVE.getInterpolation(Math.min(1f, t / 0.6f)));
                grid.setTranslationX(from * (1f - ARRIVE.getInterpolation(t)));
                monthCard.invalidate();
            }
        });
        coming.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animator) {
                if (enter != coming) return;
                enter = null;
                placeGrid();
            }
        });
        enter = coming;
        coming.start();
    }

    /** Ends a month slide at once: the picture gone and freed, the grid in place. Also the end of every slide. */
    private void endSlide() {
        handler.removeCallbacks(enterSoon);
        entering = false;
        ValueAnimator running = enter;
        enter = null;
        if (running != null) running.cancel();
        freeLeavingPage();
        placeGrid();
    }

    private void placeGrid() {
        if (grid == null) return;
        grid.setAlpha(1f);
        grid.setTranslationX(0f);
        monthCard.invalidate();
    }

    // The calendar

    /** Lays out the month's days, empty until the record answers. */
    private void drawMonth() {
        String shown = Words.monthTitle(view.getYear(), view.getMonthValue());
        title.show(shown);
        title.setContentDescription(shown + ". Pick a month");
        // A new page: the outline's motion ends here and it is drawn in place below.
        stopOutline();
        grid.removeAllViews();
        cells.clear();
        weeks.clear();
        blanks = view.atDay(1).getDayOfWeek().getValue() % 7;
        int count = view.lengthOfMonth();
        int rows = (blanks + count + 6) / 7;
        int day = 1 - blanks;
        // A short phone gets lower rows, so the whole month and All time fit without scrolling; a busy day's three lines
        // still fit the lower row through their negative margins. A larger font size makes the rows taller with it, so
        // the screen scrolls rather than cut the lines off.
        int least = getResources().getConfiguration().screenHeightDp < 700 ? 44 : 52;
        int rowMin = Math.round(dp(least) * Math.max(1f, getResources().getConfiguration().fontScale));
        fitWeekNames();
        lineRoom = column() - dp(narrow ? 2 : 4) - dp(narrow ? 1 : 3);
        for (int r = 0; r < rows; r++) {
            LinearLayout row = row();
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
            if (r > 0) rowParams.topMargin = dp(narrow ? 2 : 4);
            row.setMinimumHeight(rowMin);
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
            weeks.add(row);
        }
        markPeriod();
        drawNav();
    }

    /** A day in the pay period shown on the card. */
    private boolean inPeriod(LocalDate date) {
        return period != null && markedKnown && !date.isBefore(period.first(markedK)) && !date.isAfter(period.last(markedK));
    }

    /**
     * Marks the shown pay period's days on the calendar: one outline around those on this page, and the words for each
     * day. With no pay period set, there are none.
     */
    private void markPeriod() {
        int count = view.lengthOfMonth();
        spans.clear();
        for (int r = 0; r < weeks.size(); r++) {
            int from = -1, to = -1;
            for (int c = 0; c < 7; c++) {
                int day = 1 - blanks + r * 7 + c;
                if (day < 1 || day > count || !inPeriod(view.atDay(day))) continue;
                if (from < 0) from = c;
                to = c;
            }
            if (from >= 0) spans.add(new int[] {r, from, to});
        }
        outlineMonth = view;
        drawOutline();
        for (Map.Entry<String, LinearLayout> cell : cells.entrySet()) {
            describe(cell.getValue(), LocalDate.parse(cell.getKey()));
        }
    }

    /** A day's words from fill, and whether it is in the pay period shown. */
    private void describe(LinearLayout cell, LocalDate date) {
        Object words = cell.getTag();
        if (words == null) return;
        cell.setContentDescription(inPeriod(date) ? words + ", in the pay period" : words.toString());
    }

    /**
     * Builds the outline from the laid-out cells: each row's run of days as a rectangle, joined into one shape with
     * rounded corners. The sides sit on the outer edges of the end days; the top and bottom sit in the gaps between rows,
     * clear of today's circle, and past the grid's top and bottom into the space beside it.
     */
    private void drawOutline() {
        outlinePaint();
        if (outlinePhase == GLIDE) {
            // A layout during a glide rebuilds it and draws it where it is, never its end.
            buildGlide();
            drawGlide();
            return;
        }
        outline.reset();
        float half = outlinePaint.getStrokeWidth() / 2f;
        float gap = dp(narrow ? 2 : 4);
        // About 2dp of air above a row's tops, today's circle included, as far as the gap allows; the rest goes below.
        float above = Math.min(dp(2) + half, gap / 2 + dp(1));
        float below = gap - above;
        int[] before = null;
        for (int[] run : spans) {
            if (run[0] >= weeks.size()) continue;
            // A pixel's overlap with the row above when they share columns, so the two join into one shape. A short
            // period that wraps a week with no shared column is deliberately two outlines, one in each row's band.
            boolean joins = before != null && run[0] == before[0] + 1 && run[1] <= before[2] && before[1] <= run[2];
            before = run;
            LinearLayout row = weeks.get(run[0]);
            View first = row.getChildAt(run[1]), last = row.getChildAt(run[2]);
            if (row.getWidth() == 0 || first == null || last == null) continue;
            float top = row.getTop() - (run[0] == 0 ? dp(2) + half : above) - (joins ? 1 : 0);
            float bottom = row.getBottom() + (run[0] == weeks.size() - 1 ? dp(2) + half : below);
            Path part = new Path();
            float left = row.getLeft() + first.getLeft() + half, right = row.getLeft() + last.getRight() - half;
            part.addRect(new RectF(left, top, right, bottom), Path.Direction.CW);
            // Should joining ever fail, the row is still outlined on its own.
            if (!outline.op(part, Path.Op.UNION)) outline.addPath(part);
        }
        View card = (View) grid.getParent();
        if (card != null) card.invalidate();
    }

    private Paint outlinePaint() {
        if (outlinePaint == null) {
            outlinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            outlinePaint.setStyle(Paint.Style.STROKE);
            outlinePaint.setStrokeWidth(1.5f * getResources().getDisplayMetrics().density);
            outlinePaint.setColor(accent);
            outlinePaint.setPathEffect(new CornerPathEffect(dp(10)));
        }
        return outlinePaint;
    }

    /** Strokes the outline, and the old one while it fades, at the given share of their own alpha, in grid coordinates. */
    private void strokeOutline(Canvas canvas, float alpha) {
        Paint paint = outlinePaint();
        int full = accent >>> 24;
        if (!fadingOutline.isEmpty() && fadingAlpha * alpha > 0f) {
            paint.setAlpha(Math.round(full * fadingAlpha * alpha));
            canvas.drawPath(fadingOutline, paint);
        }
        if (outlinePhase == GLIDE) {
            for (int i = 0; i < glideDrawn.size(); i++) {
                float a = glideAlpha[i] * outlineAlpha * alpha;
                if (a <= 0f || glideDrawn.get(i).isEmpty()) continue;
                paint.setAlpha(Math.round(full * a));
                canvas.drawPath(glideDrawn.get(i), paint);
            }
        } else if (!outline.isEmpty() && outlineAlpha * alpha > 0f) {
            paint.setAlpha(Math.round(full * outlineAlpha * alpha));
            canvas.drawPath(outline, paint);
        }
        paint.setAlpha(full);
    }

    // The outline's motion

    /** Whether the week rows have their places, for a glide's geometry. */
    private boolean gridLaidOut() {
        return weeks.size() >= 2 && weeks.get(0).getWidth() > 0 && weeks.get(0).getChildCount() == 7;
    }

    /** The distance from one week row's top to the next, as the real rows have it. */
    private float pitch() {
        int n = weeks.size();
        return (float) (weeks.get(n - 1).getTop() - weeks.get(0).getTop()) / (n - 1);
    }

    /**
     * Where week row r's band starts, for any r, the rows above the first week and below the last included: the same
     * bands as the static outline, from the gap between rows, or past the grid's top and bottom at its edges, with the
     * real rows' pitch beyond them.
     */
    private float bandTop(int r, float edge, float above, float pitch) {
        int n = weeks.size();
        if (r <= 0) return weeks.get(0).getTop() - edge + r * pitch;
        if (r >= n) return weeks.get(n - 1).getBottom() + edge + (r - n) * pitch;
        return weeks.get(r).getTop() - above;
    }

    /**
     * Builds a glide's geometry from the laid-out rows: this month's own region, its real days row by row with the same
     * bands as the static outline, and the target period's shape laid out as if the month had no edges, split into its
     * parts. Rows of a part share a column and overlap by a pixel, as the static outline joins them; a wrapped week's two
     * rows share none and are two parts.
     */
    private void buildGlide() {
        glideRegion.reset();
        glideParts.clear();
        glideDrawn.clear();
        if (!gridLaidOut() || glideFirst == null) {
            glideAlpha = new float[0];
            return;
        }
        float half = outlinePaint().getStrokeWidth() / 2f;
        float gap = dp(narrow ? 2 : 4);
        float above = Math.min(dp(2) + half, gap / 2 + dp(1));
        float edge = dp(2) + half;
        float pitch = pitch();
        int n = weeks.size();
        LinearLayout first = weeks.get(0);
        glideRowHeight = first.getHeight();
        float[] lefts = new float[7], rights = new float[7];
        for (int c = 0; c < 7; c++) {
            View cell = first.getChildAt(c);
            lefts[c] = first.getLeft() + cell.getLeft() + half;
            rights[c] = first.getLeft() + cell.getRight() - half;
        }
        int count = view.lengthOfMonth();
        int lastColumn = (blanks + count - 1) % 7;
        for (int r = 0; r < n; r++) {
            int from = r == 0 ? blanks : 0, to = r == n - 1 ? lastColumn : 6;
            float top = bandTop(r, edge, above, pitch) - (r > 0 ? 1 : 0);
            glideRegion.op(rect(lefts[from], top, rights[to], bandTop(r + 1, edge, above, pitch)), Path.Op.UNION);
        }
        long a = ChronoUnit.DAYS.between(view.atDay(1), glideFirst) + blanks;
        long b = ChronoUnit.DAYS.between(view.atDay(1), glideLast) + blanks;
        long firstRow = Math.floorDiv(a, 7L), lastRow = Math.floorDiv(b, 7L);
        Path part = null;
        int beforeFrom = -1, beforeTo = -1;
        for (long r = firstRow; r <= lastRow; r++) {
            int from = r == firstRow ? (int) Math.floorMod(a, 7L) : 0, to = r == lastRow ? (int) Math.floorMod(b, 7L) : 6;
            boolean joins = r > firstRow && from <= beforeTo && beforeFrom <= to;
            beforeFrom = from;
            beforeTo = to;
            // Rows more than seven away from the grid can never reach it within a six-row move.
            if (r < -7 || r > n + 6) {
                part = null;
                continue;
            }
            if (!joins || part == null) {
                part = new Path();
                glideParts.add(part);
                glideDrawn.add(new Path());
            }
            float top = bandTop((int) r, edge, above, pitch) - (joins ? 1 : 0);
            part.op(rect(lefts[from], top, rights[to], bandTop((int) r + 1, edge, above, pitch)), Path.Op.UNION);
        }
        glideAlpha = new float[glideParts.size()];
    }

    /**
     * One frame of a glide: each part moved by glideOffset, drawn only where it meets this month's days, and faded by
     * its clipped height over 0.6 of a row, so a part sliding off the grid is gone before it is a sliver. At offset 0
     * every part left on the page is at least a row tall and solid: the static outline.
     */
    private void drawGlide() {
        for (int i = 0; i < glideParts.size(); i++) {
            Path drawn = glideDrawn.get(i);
            glideParts.get(i).offset(0f, glideOffset, glideMoved);
            if (!drawn.op(glideMoved, glideRegion, Path.Op.INTERSECT)) drawn.reset();
            float height = 0f;
            if (!drawn.isEmpty()) {
                drawn.computeBounds(glideBounds, true);
                height = glideBounds.height();
            }
            glideAlpha[i] = glideRowHeight > 0 ? Math.min(1f, height / (0.6f * glideRowHeight)) : 0f;
        }
        if (monthCard != null) monthCard.invalidate();
    }

    private static Path rect(float left, float top, float right, float bottom) {
        Path part = new Path();
        part.addRect(left, top, right, bottom, Path.Direction.CW);
        return part;
    }

    /**
     * Moves the outline from the period it marked (was, wasK) to the card's (period, markedK), before markPeriod draws
     * the new one: a glide for whole weeks, a fade for other lengths, or the plain cut when the month changed since the
     * outline was drawn, the old period is not known, neither has a day here, the move is more than six rows, the grid
     * has no layout yet, or animations are removed. A move already running is taken from where it is now.
     */
    private void moveOutline(PayPeriod was, long wasK, boolean wasKnown) {
        if (period == null) {
            stopOutline();
            return;
        }
        boolean changed = !wasKnown || was == null || wasK != markedK || !was.start.equals(period.start) || was.days != period.days;
        if (!changed) return;
        // The record answered only after a turned month began coming in: the outline fades in rather than popping.
        if (!wasKnown && ValueAnimator.areAnimatorsEnabled() && (enter != null || pageCame)) {
            stopOutline();
            fadeInOutline();
            return;
        }
        if (!ValueAnimator.areAnimatorsEnabled() || !wasKnown || was == null || !view.equals(outlineMonth) || !gridLaidOut()) {
            stopOutline();
            return;
        }
        LocalDate toFirst = period.first(markedK), toLast = period.last(markedK);
        boolean gliding = outlinePhase == GLIDE;
        LocalDate fromFirst = gliding ? glideFirst : was.first(wasK), fromLast = gliding ? glideLast : was.last(wasK);
        if (!onPage(fromFirst, fromLast) && !onPage(toFirst, toLast)) {
            stopOutline();
            return;
        }
        long days = ChronoUnit.DAYS.between(fromFirst, toFirst);
        boolean weeks = period.days % 7 == 0 && days % 7 == 0
            && ChronoUnit.DAYS.between(fromFirst, fromLast) == ChronoUnit.DAYS.between(toFirst, toLast);
        if (weeks) {
            if (outlinePhase == FADE_OUT || outlinePhase == FADE_IN) stopOutline();
            float pitch = pitch();
            float from = (outlinePhase == GLIDE ? glideOffset : 0f) - (days / 7f) * pitch;
            // No rows to move, or more than six: the plain cut.
            if (Math.abs(from) < 0.5f || Math.abs(from) > 6 * pitch + 1) {
                stopOutline();
                return;
            }
            glide(toFirst, toLast, from, pitch);
            return;
        }
        // Other lengths: the shapes differ, so the old one fades out and the new one in.
        if (outlinePhase == FADE_OUT) return;
        float start = outlinePhase == FADE_IN ? outlineAlpha : 1f;
        cancelOutlineMove();
        if (outlinePhase == GLIDE) {
            // From a glide: what is on screen now is its parts.
            fadingOutline.reset();
            for (int i = 0; i < glideDrawn.size(); i++) if (glideAlpha[i] > 0f) fadingOutline.addPath(glideDrawn.get(i));
        } else {
            fadingOutline.set(outline);
        }
        fadingAlpha = start;
        outlineAlpha = 0f;
        fadeOutline(start);
    }

    private void glide(LocalDate first, LocalDate last, float from, float pitch) {
        cancelOutlineMove();
        outlinePhase = GLIDE;
        glideFirst = first;
        glideLast = last;
        glideOffset = from;
        buildGlide();
        long rows = Math.round(Math.abs(from) / pitch);
        final ValueAnimator move = ValueAnimator.ofFloat(from, 0f);
        move.setDuration(Math.min(380, 220 + 40 * rows));
        move.setInterpolator(ARRIVE);
        move.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animator) {
                if (outlineMove != move) return;
                glideOffset = (float) animator.getAnimatedValue();
                drawGlide();
            }
        });
        move.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animator) {
                if (outlineMove == move) stopOutline();
            }
        });
        outlineMove = move;
        move.start();
    }

    /** The old outline fades out over 90 ms, then the new one fades in over 160 ms. */
    private void fadeOutline(float from) {
        outlinePhase = FADE_OUT;
        final ValueAnimator out = ValueAnimator.ofFloat(from, 0f);
        out.setDuration(Math.max(1, Math.round(90 * from)));
        out.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animator) {
                if (outlineMove != out) return;
                fadingAlpha = (float) animator.getAnimatedValue();
                monthCard.invalidate();
            }
        });
        out.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animator) {
                if (outlineMove != out) return;
                fadeInOutline();
            }
        });
        outlineMove = out;
        out.start();
    }

    /** The outline on the card fades in over 160 ms. */
    private void fadeInOutline() {
        cancelOutlineMove();
        fadingOutline.reset();
        fadingAlpha = 0f;
        outlineAlpha = 0f;
        outlinePhase = FADE_IN;
        final ValueAnimator in = ValueAnimator.ofFloat(0f, 1f);
        in.setDuration(160);
        in.setInterpolator(ARRIVE);
        in.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animator) {
                if (outlineMove != in) return;
                outlineAlpha = (float) animator.getAnimatedValue();
                monthCard.invalidate();
            }
        });
        in.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animator) {
                if (outlineMove == in) stopOutline();
            }
        });
        outlineMove = in;
        monthCard.invalidate();
        in.start();
    }

    private void cancelOutlineMove() {
        ValueAnimator running = outlineMove;
        outlineMove = null;
        if (running != null) running.cancel();
    }

    /**
     * Ends the outline's motion at once and draws the static outline: the end of every glide and fade, and the whole of
     * a change when animations are removed.
     */
    private void stopOutline() {
        cancelOutlineMove();
        outlinePhase = STILL;
        glideFirst = null;
        glideLast = null;
        glideOffset = 0f;
        glideParts.clear();
        glideDrawn.clear();
        glideRegion.reset();
        fadingOutline.reset();
        fadingAlpha = 0f;
        outlineAlpha = 1f;
        if (grid != null) drawOutline();
    }

    /** How far a two-digit day number starts inside its 22dp room: where the time, the texts and the weekdays start. */
    private int digitLead() {
        if (digitLead < 0) {
            float digits = text("00", 11, muted, false).getPaint().measureText("00");
            digitLead = Math.max(0, Math.round((dp(22) - digits) / 2));
        }
        return digitLead;
    }

    /** Once: if the widest weekday name and its lead would not fit a column, the names start at the column's edge. */
    private void fitWeekNames() {
        if (weekFitted || weekNames[0] == null) return;
        weekFitted = true;
        int column = column();
        float widest = 0;
        for (int c = 0; c < WEEK.length; c++) {
            widest = Math.max(widest, weekNames[c].getPaint().measureText(WEEK[c]));
        }
        if (weekNames[0].getPaddingLeft() + widest <= column) return;
        for (TextView name : weekNames) name.setPadding(0, 0, 0, 0);
    }

    /** A calendar column's width: the page's width less its side margins and the gaps between the seven days. */
    private int column() {
        return (dp(getResources().getConfiguration().screenWidthDp) - 2 * edge() - 6 * dp(narrow ? 2 : 4)) / 7;
    }

    private LinearLayout cell(final LocalDate date) {
        LinearLayout cell = new LinearLayout(this);
        cell.setId(R.id.day_cell);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setPadding(dp(narrow ? 2 : 4), 0, dp(narrow ? 1 : 3), dp(1));
        // The call time leads; the day's number and its texts are small and quiet around it. The number has a 22dp room
        // at the top, round for today's circle; the time reaches 3dp up into it and the texts sit 1dp into the time's
        // empty space below its digits, so through these negative margins a busy day's three lines fit a 44dp row.
        TextView number = text(String.valueOf(date.getDayOfMonth()), 11, muted, false);
        number.setGravity(Gravity.CENTER);
        number.setMinWidth(dp(22));
        number.setIncludeFontPadding(false);
        FitText time = new FitText(this, narrow ? 14 : 15);
        time.setTextColor(txt);
        time.setTypeface(medium());
        time.setIncludeFontPadding(false);
        figures(time);
        TextView texts = text("", 11, muted, false);
        texts.setIncludeFontPadding(false);
        texts.setLineSpacing(0, 0.95f);
        cell.addView(number, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(22)));
        LinearLayout.LayoutParams timeClose = wide();
        timeClose.topMargin = -dp(3);
        cell.addView(time, timeClose);
        LinearLayout.LayoutParams textsClose = wide();
        textsClose.topMargin = -dp(1);
        cell.addView(texts, textsClose);
        cell.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openFrom(v, new Intent(MainActivity.this, DayActivity.class).putExtra("day", date.toString()));
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
        // Only a day with something gets a tile; today has its number in a circle of the accent. A day still to come has
        // its number paler.
        cell.setBackground(used ? touchable(shape(soft(), 0, 0, CORNER), CORNER) : null);
        cell.setClickable(used);
        cell.setEnabled(used);
        int faint = (muted & 0x00ffffff) | 0x80000000;
        number.setBackground(isToday ? todayCircle() : null);
        number.setTextColor(isToday ? ink : !used && date.isAfter(today) ? faint : muted);
        number.setTypeface(isToday ? medium() : Typeface.DEFAULT);
        StringBuilder words = new StringBuilder(Words.monthName(date.getMonthValue()) + " " + date.getDayOfMonth());
        String clock = data != null && data.calls > 0 ? Words.clockTime(data.callS) : "";
        String said = data != null && data.texts > 0 ? Words.plural(data.texts, "text")
            : used && data.calls == 0 ? (data.unanswered > 0 ? "no answer" : "ignored") : "";
        // The time and texts start under the day number's digits, unless that lead would push them out of a narrow
        // cell, where they start at its edge instead.
        TextPaint least = new TextPaint(time.getPaint());
        least.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11, getResources().getDisplayMetrics()));
        float need = Math.max(least.measureText(clock), texts.getPaint().measureText(said));
        int pad = need + digitLead() <= lineRoom ? digitLead() : 0;
        time.setPadding(pad, 0, 0, 0);
        texts.setPadding(pad, 0, 0, 0);
        if (!clock.isEmpty()) {
            time.show(clock);
            time.setVisibility(View.VISIBLE);
            words.append(", call time ").append(Words.fullTime(data.callS));
        } else {
            time.show("");
            time.setVisibility(View.GONE);
        }
        texts.setText(said);
        if (data != null && data.texts > 0) {
            words.append(", ").append(Words.plural(data.texts, "text"));
        } else if (used && data.calls == 0) {
            words.append(data.unanswered > 0 ? ", calls not answered only" : ", ignored entries only");
        }
        // The words go on the cell for markPeriod, which adds the pay period whenever it changes.
        cell.setTag(words.toString());
        describe(cell, date);
    }

    private void drawNav() {
        YearMonth before = view.minusMonths(1);
        enable(prev, earliest != null && before.atEndOfMonth().toString().compareTo(earliest) >= 0);
        enable(next, view.isBefore(thisMonth));
        enable(now, !view.equals(thisMonth));
        // The month's name always looks the same; with no entries yet there is nothing to pick, so a tap does nothing.
        title.setEnabled(earliest != null);
        title.setClickable(earliest != null);
        enablePeriodArrows();
    }

    private void enablePeriodArrows() {
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

    /** A year with arrows, its twelve months as a plain grid to tap, and Close, drawn in the page's own style. */
    private void pick() {
        if (earliest == null || picker != null) return;
        pickYear = view.getYear();
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(shape(card, line, 1, CORNER_CARD));
        box.setPadding(dp(GAP_L), dp(GAP_L), dp(GAP_L), dp(GAP_L));

        LinearLayout top = row();
        top.setGravity(Gravity.CENTER_VERTICAL);
        pickPrev = arrow(R.drawable.chevron_left, "Previous year", R.id.pick_prev);
        pickNext = arrow(R.drawable.chevron_right, "Next year", R.id.pick_next);
        pickTitle = text("", 22, txt, false);
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
                if (c > 0) cell.leftMargin = dp(GAP);
                months.addView(button, cell);
                pickMonths[month - 1] = button;
            }
            LinearLayout.LayoutParams gap = wide();
            gap.topMargin = dp(r == 0 ? GAP_L : GAP);
            box.addView(months, gap);
        }

        // Close is a plain word in the accent at the bottom right, with no box.
        Button close = button("Close");
        close.setId(R.id.close);
        close.setMinHeight(dp(48));
        close.setMinimumHeight(dp(48));
        close.setPadding(dp(GAP_XL), dp(GAP_S), dp(GAP_XL), dp(GAP_S));
        close.setTextColor(accent);
        close.setTypeface(medium());
        close.setBackground(touchable(null, CORNER));
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (picker != null) picker.dismiss();
            }
        });
        LinearLayout.LayoutParams closeGap = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        closeGap.topMargin = dp(GAP_L);
        closeGap.gravity = Gravity.END;
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
            boolean open = pickable(month);
            // No boxes: the month on show is an accent pill, this month has an accent ring like today on the calendar,
            // and a month with nothing to show is pale and does nothing.
            if (month.equals(view)) {
                filled(button, 24);
            } else {
                button.setTextColor(open ? txt : (muted & 0x00ffffff) | 0x80000000);
                button.setTypeface(month.equals(thisMonth) ? medium() : Typeface.DEFAULT);
                button.setBackground(month.equals(thisMonth) ? touchable(shape(0, accent, 2, 24), 24) : touchable(null, 24));
            }
            button.setEnabled(open);
            button.setAlpha(1f);
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
        int textSeconds;
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
                    found.textSeconds = record.textSeconds();
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
        PayPeriod was = period;
        long wasK = markedK;
        boolean wasKnown = markedKnown;
        earliest = found.earliest;
        empty.setVisibility(earliest == null && permissionText == null ? View.VISIBLE : View.GONE);
        textSeconds = found.textSeconds;
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
        markedK = periodK;
        markedKnown = period != null;
        moveOutline(was, wasK, wasKnown);
        markPeriod();
        showRow(allRow, "All time", found.all);
        drawNav();
        // A month sliding in waits for its days, so they don't pop in mid-slide.
        if (entering) filledPage();
    }
}
