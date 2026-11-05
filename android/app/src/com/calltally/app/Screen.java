package com.calltally.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * What every screen shares: the look chosen in Options (System, Light or Dark, and one of five themes), applied before
 * anything is drawn, the page's colours and shapes, and the frame of a coloured bar on top, the screen's body and an
 * optional Close bar at the bottom. A change of look made on one screen redraws the others when they come back.
 */
abstract class Screen extends Activity {
    static final String[] THEMES = {"teal", "blue", "plum", "amber", "slate"};
    static final String[] THEME_NAMES = {"Teal", "Blue", "Plum", "Amber", "Slate"};
    static final String[] MODES = {"system", "light", "dark"};

    private static String mode;
    private static boolean copyMissing;
    private static String theme;
    private String applied;

    int bg, card, card2, line, line2, txt, muted, loss, lossTint, accent, ink, tint;
    float density;
    LinearLayout root;
    LinearLayout header;
    TextView titleView;
    View body;
    LinearLayout footer;

    /**
     * Reads the look once per process from a small copy kept beside the record, so the first screen never waits on the
     * database. The record's settings hold the same two values; when there is no copy yet, the month screen makes one
     * from the record.
     */
    static synchronized void loadLook(Context context) {
        if (mode != null) return;
        android.content.SharedPreferences copy = context.getSharedPreferences("look", Context.MODE_PRIVATE);
        copyMissing = !copy.contains("mode");
        mode = known(MODES, copy.getString("mode", null), "system");
        theme = known(THEMES, copy.getString("theme", null), "teal");
    }

    private static String known(String[] names, String value, String fallback) {
        for (String name : names) if (name.equals(value)) return name;
        return fallback;
    }

    static synchronized String mode() {
        return mode;
    }

    static synchronized String theme() {
        return theme;
    }

    /** Takes a new look at once for every screen, then saves it to the record away from the screen. */
    static void setLook(Context context, String newMode, String newTheme) {
        final Context app = context.getApplicationContext();
        final String m = known(MODES, newMode, "system"), t = known(THEMES, newTheme, "teal");
        adopt(app, m, t);
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Record record = Record.get(app);
                    record.setSetting("look_mode", m);
                    record.setSetting("look_theme", t);
                } catch (RuntimeException error) {
                    // The copy keeps the look for now; the record is written the next time the look changes.
                }
            }
        }).start();
    }

    /** The record's look, taken only when there was no copy yet, so a look just chosen is never undone. True on a change. */
    static synchronized boolean adoptMissing(Context context, String newMode, String newTheme) {
        if (!copyMissing) return false;
        copyMissing = false;
        return adopt(context, newMode, newTheme);
    }

    /** Uses this look from now on and keeps it in the copy. Returns true when it is a change. */
    static synchronized boolean adopt(Context context, String newMode, String newTheme) {
        copyMissing = false;
        String m = known(MODES, newMode, "system"), t = known(THEMES, newTheme, "teal");
        boolean changed = !m.equals(mode) || !t.equals(theme);
        mode = m;
        theme = t;
        context.getSharedPreferences("look", Context.MODE_PRIVATE).edit().putString("mode", m).putString("theme", t).apply();
        return changed;
    }

    private static synchronized String look() {
        return mode + "/" + theme;
    }

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        loadLook(base);
        String chosen = mode();
        if (!"system".equals(chosen)) {
            // Only the night part is set; everything else stays as the phone has it.
            Configuration override = new Configuration();
            override.uiMode = "dark".equals(chosen) ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO;
            applyOverrideConfiguration(override);
        }
        applied = look();
    }

    @Override
    protected void onCreate(Bundle saved) {
        String chosen = theme();
        setTheme("blue".equals(chosen) ? R.style.Blue : "plum".equals(chosen) ? R.style.Plum : "amber".equals(chosen) ? R.style.Amber
            : "slate".equals(chosen) ? R.style.Slate : R.style.Teal);
        super.onCreate(saved);
        Sync.noteCrashes(this);
        density = getResources().getDisplayMetrics().density;
        bg = getColor(R.color.bg);
        card = getColor(R.color.card);
        card2 = getColor(R.color.card2);
        line = getColor(R.color.line);
        line2 = getColor(R.color.line2);
        txt = getColor(R.color.txt);
        muted = getColor(R.color.muted);
        loss = getColor(R.color.loss);
        lossTint = getColor(R.color.loss_tint);
        accent = attr(R.attr.ctAccent);
        ink = attr(R.attr.ctInk);
        tint = attr(R.attr.ctTint);
        bars();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!look().equals(applied)) redraw();
    }

    /**
     * Opens this screen afresh in the new look, in place of this one. A fresh start, rather than recreate(), so the
     * screen draws under the system bars the same way as when it was first opened.
     */
    void redraw() {
        // Only the screen and its extras: the launcher's intent would just bring the old task forward.
        Intent again = new Intent(this, getClass());
        if (getIntent().getExtras() != null) again.putExtras(getIntent().getExtras());
        keep(again);
        again.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
        startActivity(again);
        finish();
        overridePendingTransition(0, 0);
    }

    /** What a screen carries into its fresh copy, such as the month on show. */
    void keep(Intent intent) {}

    boolean night() {
        return (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    private int attr(int id) {
        TypedValue value = new TypedValue();
        getTheme().resolveAttribute(id, value, true);
        return value.data;
    }

    /** Draws under the system bars, with their icons dark or light to suit the coloured bar on top and the page below. */
    @SuppressWarnings("deprecation")
    private void bars() {
        Window window = getWindow();
        // Makes the window's frame now, which the bar settings below need.
        window.getDecorView();
        window.setStatusBarColor(0);
        window.setNavigationBarColor(0);
        // No grey wash behind the navigation buttons: the page's own colour shows there.
        if (Build.VERSION.SDK_INT >= 29) window.setNavigationBarContrastEnforced(false);
        // The bar on top is dark in Light and light in Dark; the page below the other way round.
        boolean darkIconsTop = night();
        boolean darkIconsBottom = !night();
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false);
            WindowInsetsController controller = window.getInsetsController();
            if (controller != null) {
                int mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                int want = (darkIconsTop ? WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS : 0)
                    | (darkIconsBottom ? WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS : 0);
                controller.setSystemBarsAppearance(want, mask);
            }
        } else {
            int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
            if (darkIconsTop) flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (darkIconsBottom && Build.VERSION.SDK_INT >= 27) flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            window.getDecorView().setSystemUiVisibility(flags);
        }
    }

    // The frame

    /** The coloured bar with a title, the body filling the middle, and a Close bar when closable. */
    void frame(String title, View content, boolean closable) {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(bg);
        header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setBackgroundColor(accent);
        header.setMinimumHeight(dp(56));
        titleView = text(title, 20, ink, false);
        titleView.setTypeface(medium());
        titleView.setId(R.id.screen_title);
        header.addView(titleView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        root.addView(header, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        body = content;
        root.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        if (closable) {
            Button close = button("Close");
            close.setId(R.id.close);
            close.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    finish();
                }
            });
            footer = footer(close);
        }
        if (footer != null) root.addView(footer, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        header.setPadding(dp(16), dp(8), dp(8), dp(8));
        if (footer != null) footer.setPadding(dp(16), dp(10), dp(16), dp(10));
        insets();
        setContentView(root);
        // A screen drawn again for a new look gets no insets of its own unless it asks.
        root.requestApplyInsets();
    }

    /** A bar at the bottom holding one or more buttons side by side. Replaces the Close bar when set before frame(). */
    LinearLayout footer(Button... buttons) {
        LinearLayout bar = row();
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(card);
        bar.setBackground(new android.graphics.drawable.LayerDrawable(new Drawable[] {shape, edge()}));
        for (int i = 0; i < buttons.length; i++) {
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            if (i > 0) params.leftMargin = dp(8);
            buttons[i].setMinHeight(dp(48));
            buttons[i].setMinimumHeight(dp(48));
            bar.addView(buttons[i], params);
        }
        return bar;
    }

    /** A one-pixel line along the top of the bottom bar. */
    private Drawable edge() {
        GradientDrawable top = new GradientDrawable();
        top.setColor(line);
        android.graphics.drawable.LayerDrawable layer = new android.graphics.drawable.LayerDrawable(new Drawable[] {top});
        layer.setLayerHeight(0, Math.max(1, dp(1)));
        layer.setLayerGravity(0, Gravity.TOP);
        return layer;
    }

    /** Keeps the bar's text clear of the status bar and the bottom clear of the navigation bar. */
    private void insets() {
        final int side = dp(16), top = dp(8), bottom = dp(10);
        final int bodyLeft = body.getPaddingLeft(), bodyTop = body.getPaddingTop(), bodyRight = body.getPaddingRight(), bodyBottom = body.getPaddingBottom();
        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            @SuppressWarnings("deprecation")
            public WindowInsets onApplyWindowInsets(View view, WindowInsets insets) {
                int left = insets.getSystemWindowInsetLeft(), right = insets.getSystemWindowInsetRight();
                header.setPadding(side + left, top + insets.getSystemWindowInsetTop(), side / 2 + right, top);
                body.setPadding(bodyLeft + left, bodyTop, bodyRight + right, bodyBottom + (footer == null ? insets.getSystemWindowInsetBottom() : 0));
                if (footer != null) footer.setPadding(side + left, dp(10), side + right, bottom + insets.getSystemWindowInsetBottom());
                return insets.consumeSystemWindowInsets();
            }
        });
    }

    // Parts in the page's style

    int dp(float value) {
        return Math.round(value * density);
    }

    TextView text(CharSequence value, float sp, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        return view;
    }

    /** The page's semi-bold, for titles and times. */
    static Typeface medium() {
        return Typeface.create("sans-serif-medium", Typeface.NORMAL);
    }

    /** Digits all the same width, so numbers line up and don't shift as they change. */
    static void figures(TextView view) {
        view.setFontFeatureSettings("tnum");
    }

    /**
     * A call time at the end of a row: semi-bold, even digits, right-aligned and as wide as the longest time, so the
     * times form a column. Set its padding first.
     */
    void timeColumn(TextView view) {
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        view.setTypeface(medium());
        figures(view);
        view.setGravity(Gravity.END);
        view.setSingleLine(true);
        int widest = (int) Math.ceil(view.getPaint().measureText("0h 00m 00s"));
        view.setMinWidth(widest + view.getPaddingLeft() + view.getPaddingRight());
    }

    GradientDrawable shape(int fill, int strokeColor, float strokeDp, float radiusDp) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(fill);
        shape.setCornerRadius(dp(radiusDp));
        if (strokeDp > 0) shape.setStroke(Math.max(1, dp(strokeDp)), strokeColor);
        return shape;
    }

    /**
     * Wraps an opaque background so a touch shows. With no mask the ripple is masked by the background itself, so it
     * never shows on a clear or see-through background, and hardly shows on one tinted in the accent: use
     * touchable(content, radius) for those.
     */
    Drawable touchable(Drawable content) {
        int ripple = (accent & 0x00ffffff) | 0x33000000;
        return new RippleDrawable(ColorStateList.valueOf(ripple), content, null);
    }

    /**
     * Wraps a clear, see-through or accent-tinted background, or none (null), so a touch shows: the ripple has a solid
     * rounded mask of its own, of this radius.
     */
    Drawable touchable(Drawable content, float maskRadiusDp) {
        int ripple = (accent & 0x00ffffff) | 0x33000000;
        return new RippleDrawable(ColorStateList.valueOf(ripple), content, shape(0xffffffff, 0, 0, maskRadiusDp));
    }

    /** The page's plain button: a soft grey with a thin line, 44dp tall. */
    Button button(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setStateListAnimator(null);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(dp(44));
        button.setMinimumHeight(dp(44));
        button.setPadding(dp(12), dp(6), dp(12), dp(6));
        plain(button);
        return button;
    }

    void plain(Button button) {
        button.setTextColor(txt);
        button.setTypeface(Typeface.DEFAULT);
        button.setBackground(touchable(shape(card2, line2, 1, 8)));
    }

    /** A chosen button: filled with the accent. */
    void filled(Button button) {
        button.setTextColor(ink);
        button.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        button.setBackground(touchable(shape(accent, accent, 1, 8)));
    }

    /** A chosen theme: outlined with the accent. */
    void outlined(Button button) {
        button.setTextColor(txt);
        button.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        button.setBackground(touchable(shape(card2, accent, 2, 8)));
    }

    /** A chevron in muted on a round touch, for moving between months and years. */
    android.widget.ImageButton arrow(int drawable, String description, int id) {
        android.widget.ImageButton button = new android.widget.ImageButton(this);
        button.setId(id);
        button.setContentDescription(description);
        Drawable icon = getDrawable(drawable).mutate();
        icon.setTint(muted);
        button.setImageDrawable(icon);
        button.setScaleType(android.widget.ImageView.ScaleType.CENTER_INSIDE);
        button.setBackground(touchable(null, 24));
        button.setPadding(0, 0, 0, 0);
        return button;
    }

    static void enable(View view, boolean on) {
        view.setEnabled(on);
        view.setAlpha(on ? 1f : 0.45f);
    }

    /** A thin line between rows. */
    View divider() {
        View view = new View(this);
        view.setBackgroundColor(line);
        view.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1))));
        return view;
    }

    /** The muted line shown when a list is empty, or for a note below it. */
    TextView note(String value) {
        TextView view = text(value, 14, muted, false);
        view.setPadding(dp(16), dp(16), dp(16), dp(16));
        return view;
    }

    /** The red box that says what is wrong. */
    TextView problemBox() {
        TextView view = text("", 14, loss, false);
        view.setBackground(shape(lossTint, loss, 1, 10));
        view.setPadding(dp(12), dp(8), dp(12), dp(8));
        view.setVisibility(View.GONE);
        return view;
    }

    /** A horizontal row. Lined up by its children's edges, not their text, so buttons of two lines don't drop. */
    LinearLayout row() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setBaselineAligned(false);
        return row;
    }

    LinearLayout.LayoutParams wide() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }
}
