package com.calltally.app;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.DynamicDrawableSpan;
import android.text.style.ImageSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.time.LocalDate;
import java.util.Locale;

/** Options: Who counts, the pay period, hiding ignored entries, the look, the last run, the phone check and the first-start page. Saved in the record. */
public class OptionsActivity extends Screen {
    private static final int[] THEME_COLORS = {R.color.teal_accent, R.color.blue_accent, R.color.plum_accent, R.color.amber_accent, R.color.slate_accent};
    private static final int[] THEME_IDS = {R.id.theme_teal, R.id.theme_blue, R.id.theme_plum, R.id.theme_amber, R.id.theme_slate};
    private static final int[] MODE_IDS = {R.id.mode_system, R.id.mode_light, R.id.mode_dark};
    private static final String[] MODE_NAMES = {"System", "Light", "Dark"};

    private LinearLayout column;
    private TextView whoLine;
    private TextView payLine;
    private Button payChange;
    private CheckBox hide;
    private TextView runLine;
    private Button runNow;
    private TextView catchLine;
    private TextView stillBox;
    private Button switchBack;
    private final Button[] modes = new Button[3];
    private final Button[] themes = new Button[5];
    private static final int SWITCH_BACK = 21;

    private final SharedPreferences.OnSharedPreferenceChangeListener runs = new SharedPreferences.OnSharedPreferenceChangeListener() {
        @Override
        public void onSharedPreferenceChanged(SharedPreferences saved, String key) {
            if ("run_done".equals(key)) {
                enable(runNow, true);
                load();
            }
        }
    };

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(16), dp(4), dp(16), dp(16));

        heading("Who counts");
        whoLine = muted("");
        Button whoOpen = button("Change");
        whoOpen.setId(R.id.who_open);
        whoOpen.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(OptionsActivity.this, WhoActivity.class));
            }
        });
        whoLine.setId(R.id.who_line);
        pair(whoLine, whoOpen);

        heading("Pay period");
        payLine = muted("");
        payLine.setId(R.id.pay_line);
        payChange = button("Change");
        payChange.setId(R.id.pay_change);
        payChange.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(OptionsActivity.this, PayActivity.class));
            }
        });
        pair(payLine, payChange);

        heading("Day details");
        hide = new CheckBox(this);
        hide.setId(R.id.hide_ignored);
        hide.setText("Hide ignored entries");
        hide.setTextSize(14);
        hide.setTextColor(txt);
        hide.setMinHeight(dp(44));
        hide.setMinimumHeight(dp(44));
        hide.setButtonTintList(new ColorStateList(new int[][] {{android.R.attr.state_checked}, {}}, new int[] {accent, muted}));
        column.addView(hide, wide());

        heading("Look");
        column.addView(sub("Mode"), wide());
        LinearLayout modeRow = row();
        for (int i = 0; i < 3; i++) {
            final String mode = MODES[i];
            modes[i] = button(MODE_NAMES[i]);
            modes[i].setId(MODE_IDS[i]);
            modes[i].setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    look(mode, theme());
                }
            });
            modeRow.addView(modes[i], third(i));
        }
        column.addView(modeRow, wide());
        column.addView(sub("Theme"), wide());
        LinearLayout themeRow = null;
        for (int i = 0; i < 5; i++) {
            if (i % 3 == 0) {
                themeRow = row();
                LinearLayout.LayoutParams gap = wide();
                if (i > 0) gap.topMargin = dp(6);
                column.addView(themeRow, gap);
            }
            final String name = THEMES[i];
            themes[i] = button("");
            themes[i].setId(THEME_IDS[i]);
            themes[i].setText(swatch(THEME_NAMES[i], getColor(THEME_COLORS[i])));
            themes[i].setContentDescription(THEME_NAMES[i]);
            themes[i].setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    look(mode(), name);
                }
            });
            themeRow.addView(themes[i], third(i % 3));
        }
        // Keeps the second row's buttons the same width as the first's.
        themeRow.addView(new View(this), third(2));
        Button reset = button("Set to default");
        reset.setId(R.id.look_default);
        reset.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                look("system", "teal");
            }
        });
        LinearLayout.LayoutParams resetGap = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        resetGap.topMargin = dp(8);
        column.addView(reset, resetGap);

        heading("Last run");
        runLine = muted("");
        runLine.setId(R.id.run_line);
        runLine.setTextSize(13);
        column.addView(runLine, wide());
        runNow = button("Run now");
        runNow.setId(R.id.run_now);
        runNow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                enable(runNow, false);
                runLine.setText("Reading the phone…");
                thread(getApplicationContext());
            }
        });
        column.addView(runNow, resetGap);

        heading("Phone check");
        TextView phoneLine = muted("See what the phone hands the app.");
        Button phoneOpen = button("Open");
        phoneOpen.setId(R.id.phone_check);
        phoneOpen.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(OptionsActivity.this, PhoneCheckActivity.class));
            }
        });
        pair(phoneLine, phoneOpen);

        heading("Catch up encrypted");
        TextView catchInfo = muted("Read encrypted chats the phone only shows its messaging app.");
        Button catchOpen = button("Catch up encrypted");
        catchOpen.setId(R.id.catch_up);
        catchOpen.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(OptionsActivity.this, CatchUpActivity.class));
            }
        });
        pair(catchInfo, catchOpen);
        catchLine = muted("");
        catchLine.setId(R.id.catch_line);
        catchLine.setTextSize(13);
        column.addView(catchLine, wide());
        // Shown only while Call Tally still holds the messaging role, so the owner is never left as the messaging app
        // without seeing it, with a button to switch back to Google Messages.
        stillBox = problemBox();
        stillBox.setId(R.id.still_default);
        stillBox.setText(Passthrough.HOLDS_ROLE);
        LinearLayout.LayoutParams stillGap = wide();
        stillGap.topMargin = dp(8);
        column.addView(stillBox, stillGap);
        switchBack = button("Switch back to Messages");
        switchBack.setId(R.id.switch_back);
        switchBack.setVisibility(View.GONE);
        switchBack.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String previous = Sync.preferences(OptionsActivity.this).getString("prev_sms", Roles.GOOGLE_MESSAGES);
                try {
                    startActivityForResult(Roles.handBackIntent(OptionsActivity.this, previous), SWITCH_BACK);
                } catch (RuntimeException error) {
                    load();
                }
            }
        });
        LinearLayout.LayoutParams switchGap = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        switchGap.topMargin = dp(8);
        column.addView(switchBack, switchGap);

        heading("First start");
        Button again = button("Show the first-start page again");
        again.setId(R.id.start_again);
        again.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // Ticks, the pay period and the record stay as they are; the page leads back to the month as before.
                StartActivity.showAgain(OptionsActivity.this);
                startActivity(new Intent(OptionsActivity.this, StartActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
                finish();
            }
        });
        column.addView(again, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // The app's own version, so the owner can confirm which build is installed.
        TextView version = muted(versionLine());
        version.setId(R.id.version_line);
        version.setTextSize(13);
        version.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams versionGap = wide();
        versionGap.topMargin = dp(24);
        column.addView(version, versionGap);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(column);
        frame("Options", scroll, true);
        drawLook();
    }

    /** One run away from the screen. Its end redraws the status through the saved run_done. */
    private void thread(final Context context) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                // When another run is going this one does nothing, and that run's end brings the new status.
                Sync.run(context);
            }
        }).start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (isFinishing()) return;
        Sync.preferences(this).registerOnSharedPreferenceChangeListener(runs);
        load();
    }

    @Override
    protected void onPause() {
        super.onPause();
        Sync.preferences(this).unregisterOnSharedPreferenceChangeListener(runs);
    }

    @Override
    protected void onActivityResult(int code, int ignored, Intent data) {
        super.onActivityResult(code, ignored, data);
        // After the switch-back dialog, redraw the still-the-messaging-app line from what the phone now reports.
        if (code == SWITCH_BACK && !isFinishing()) load();
    }

    /** Reads the settings and the people away from the screen. */
    private void load() {
        final Context context = getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                String who, pay, problem = null;
                boolean hidden = false, havePeriod = false;
                boolean holds = Roles.holdsSmsRole(context);
                String caughtUp = Passthrough.lastCaughtUp(Sync.preferences(context).getLong("caught_up_at", 0), Locale.US);
                try {
                    Record record = Record.get(context);
                    int ticked = 0;
                    for (Rules.Person person : record.people()) if (person.ticked) ticked++;
                    who = ticked == 0 ? "Nobody is ticked yet." : ticked == 1 ? "1 person is ticked." : ticked + " people are ticked.";
                    PayPeriod period = PayPeriod.of(record.setting("pay_period_start"), record.setting("pay_period_days"));
                    havePeriod = period != null;
                    pay = period == null ? "No pay period is set." : period.describe(LocalDate.now());
                    hidden = "1".equals(record.setting("hide_ignored"));
                } catch (Record.TooNew error) {
                    who = pay = problem = error.getMessage();
                } catch (RuntimeException error) {
                    who = pay = problem = Record.UNREADABLE;
                }
                final String whoText = who, payText = pay, trouble = problem;
                final boolean hideIt = hidden, set = havePeriod, holdsRole = holds;
                final String caughtUpText = caughtUp;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (isDestroyed()) return;
                        whoLine.setText(whoText);
                        payLine.setText(payText);
                        payChange.setText(set ? "Change" : "Set");
                        hide.setOnCheckedChangeListener(null);
                        hide.setChecked(hideIt);
                        if (trouble == null) hide.setOnCheckedChangeListener(hideChanged);
                        runLine.setText(Sync.status(context));
                        catchLine.setText(caughtUpText);
                        stillBox.setVisibility(holdsRole ? View.VISIBLE : View.GONE);
                        switchBack.setVisibility(holdsRole ? View.VISIBLE : View.GONE);
                    }
                });
            }
        }).start();
    }

    private final CompoundButton.OnCheckedChangeListener hideChanged = new CompoundButton.OnCheckedChangeListener() {
        @Override
        public void onCheckedChanged(CompoundButton button, final boolean checked) {
            final Context context = getApplicationContext();
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Record.get(context).setSetting("hide_ignored", checked ? "1" : "0");
                    } catch (RuntimeException error) {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                // Shows what the record really holds.
                                if (!isDestroyed()) load();
                            }
                        });
                    }
                }
            }).start();
        }
    };

    /** Saves the look and redraws this screen in it at once; the others follow when they show again. */
    private void look(String mode, String theme) {
        if (mode.equals(mode()) && theme.equals(theme())) return;
        setLook(this, mode, theme);
        redraw();
    }

    private void drawLook() {
        for (int i = 0; i < 3; i++) {
            if (MODES[i].equals(mode())) filled(modes[i]);
            else plain(modes[i]);
            modes[i].setSelected(MODES[i].equals(mode()));
        }
        for (int i = 0; i < 5; i++) {
            if (THEMES[i].equals(theme())) outlined(themes[i]);
            else plain(themes[i]);
            themes[i].setSelected(THEMES[i].equals(theme()));
        }
    }

    private CharSequence swatch(String name, int color) {
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(color);
        dot.setStroke(Math.max(1, dp(1)), line2);
        dot.setBounds(0, 0, dp(14), dp(14));
        SpannableString text = new SpannableString("  " + name);
        text.setSpan(new ImageSpan(dot, DynamicDrawableSpan.ALIGN_BASELINE), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return text;
    }

    private void heading(String value) {
        TextView view = text(value, 14, txt, true);
        LinearLayout.LayoutParams params = wide();
        params.topMargin = dp(18);
        params.bottomMargin = dp(6);
        column.addView(view, params);
    }

    private TextView muted(String value) {
        return text(value, 14, muted, false);
    }

    /** "Call Tally 0.9.1" from the installed package; just "Call Tally" when the version can't be read. */
    private String versionLine() {
        try {
            String name = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            return name == null ? "Call Tally" : "Call Tally " + name;
        } catch (Exception error) {
            return "Call Tally";
        }
    }

    private TextView sub(String value) {
        TextView view = text(value, 13, muted, false);
        view.setPadding(0, dp(6), 0, dp(6));
        return view;
    }

    /** Words on the left, a button on the right. */
    private void pair(TextView words, Button button) {
        LinearLayout row = row();
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(words, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        LinearLayout.LayoutParams side = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        side.leftMargin = dp(10);
        row.addView(button, side);
        column.addView(row, wide());
    }

    private LinearLayout.LayoutParams third(int i) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        if (i > 0) params.leftMargin = dp(6);
        return params;
    }
}
