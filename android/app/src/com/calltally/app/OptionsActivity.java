package com.calltally.app;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.DynamicDrawableSpan;
import android.text.style.ImageSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.time.LocalDate;
import java.util.Locale;

/** Options: Contacts, the pay period, the time per text, hiding ignored entries, the last run, the look and the first-start page. Saved in the record. */
public class OptionsActivity extends Screen {
    private static final int[] THEME_COLORS = {R.color.teal_accent, R.color.blue_accent, R.color.plum_accent, R.color.amber_accent, R.color.slate_accent};
    private static final int[] THEME_IDS = {R.id.theme_teal, R.id.theme_blue, R.id.theme_plum, R.id.theme_amber, R.id.theme_slate};
    private static final int[] MODE_IDS = {R.id.mode_system, R.id.mode_light, R.id.mode_dark};
    private static final String[] MODE_NAMES = {"System", "Light", "Dark"};

    private LinearLayout column;
    private ScrollView scroll;
    private TextView whoLine;
    private TextView ownLine;
    private Button ownForget;
    private LinearLayout ownRow;
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
    /** Muted() lines; those later set to 13sp are notes and go back to muted. */
    private final java.util.List<TextView> lines = new java.util.ArrayList<>();
    /** The top heading has no line above it. */
    private boolean firstHeading = true;
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
        column.setPadding(edge(), dp(4), edge(), dp(16));
        // Quiet buttons hang into the side padding, so their ripple and touch area are not cut off.
        column.setClipChildren(false);
        column.setClipToPadding(false);

        heading("Contacts");
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

        // Your own number is learned from your sent messages, to leave it out of group counts. Forget clears what was
        // learned, so a stale or wrong number is dropped; your current number is learned again on the next run. The row
        // is shown only when there is something learned to forget.
        ownLine = muted("Your own number was learned from your sent messages.");
        ownLine.setId(R.id.own_line);
        ownLine.setTextSize(13);
        ownForget = button("Forget");
        ownForget.setId(R.id.own_forget);
        ownForget.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                enable(ownForget, false);
                final Context context = getApplicationContext();
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        boolean done;
                        try {
                            Record.get(context).clearSetting(Record.OWN_NUMBERS);
                            done = true;
                        } catch (RuntimeException error) {
                            done = false;
                        }
                        final boolean cleared = done;
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                if (isDestroyed()) return;
                                if (cleared) {
                                    ownLine.setText("Forgotten.");
                                    ownForget.setVisibility(View.GONE);
                                } else {
                                    ownLine.setText("Couldn't forget.");
                                    enable(ownForget, true);
                                }
                            }
                        });
                    }
                }).start();
            }
        });
        ownRow = pair(ownLine, ownForget);
        ownRow.setVisibility(View.GONE);

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

        // Time per text: the owner's seconds for each text, shown beside the text counts and kept apart from call time.
        // Change opens a small window with the old and new rule; nothing is saved until Confirm.
        heading("Texts");
        final TextView textLine = muted("");
        textLine.setId(R.id.text_time_line);
        final Button textChange = button("Change");
        textChange.setId(R.id.text_time_change);
        // Off until the record is read; it stays off when the record can't be read.
        enable(textChange, false);
        pair(textLine, textChange);
        final class TextTime {
            /** The saved seconds per text, 0 when off; -1 until the record is read. */
            int saved = -1;
            android.app.Dialog window;
            android.widget.EditText value;
            TextView review;
            Button close;
            Button revert;
            Button confirm;

            /** Reads the rule away from the screen and shows it on its line. */
            void load() {
                final Context context = getApplicationContext();
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        int seconds = -1;
                        String problem = null;
                        try {
                            seconds = Record.get(context).textSeconds();
                        } catch (Record.TooNew error) {
                            problem = error.getMessage();
                        } catch (RuntimeException error) {
                            problem = Record.UNREADABLE;
                        }
                        final int got = seconds;
                        final String trouble = problem;
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                if (isDestroyed()) return;
                                saved = got;
                                textLine.setText(trouble != null ? trouble : "Time per text: " + Words.textRule(got));
                                enable(textChange, trouble == null);
                            }
                        });
                    }
                }).start();
            }

            /** The seconds typed, or -1 when the box is empty. */
            int typed() {
                String words = value.getText().toString().trim();
                return words.isEmpty() ? -1 : Words.textSeconds(words);
            }

            /** A window in the page's style: what the rule does, the seconds, the old and new rule, and the buttons. */
            void open() {
                if (saved < 0 || window != null) return;
                LinearLayout box = new LinearLayout(OptionsActivity.this);
                box.setOrientation(LinearLayout.VERTICAL);
                box.setBackground(shape(card, line, 1, 12));
                box.setPadding(dp(12), dp(12), dp(12), dp(12));
                TextView title = text("Time per text", 16, txt, false);
                title.setTypeface(medium());
                box.addView(title, wide());
                TextView explain = text("Seconds counted for each text, shown apart from call time. 0 turns it off.", 14, muted, false);
                LinearLayout.LayoutParams explainGap = wide();
                explainGap.topMargin = dp(6);
                box.addView(explain, explainGap);
                value = new android.widget.EditText(OptionsActivity.this);
                value.setId(R.id.text_time_value);
                value.setSelectAllOnFocus(true);
                value.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
                value.setFilters(new android.text.InputFilter[] {new android.text.InputFilter.LengthFilter(4)});
                value.setSingleLine(true);
                value.setTextSize(16);
                value.setTextColor(txt);
                value.setHintTextColor(muted);
                value.setHint("0");
                figures(value);
                value.setBackground(shape(card2, line2, 1, 8));
                value.setPadding(dp(12), dp(10), dp(12), dp(10));
                value.setText(String.valueOf(saved));
                value.setSelection(value.getText().length());
                LinearLayout.LayoutParams valueGap = wide();
                valueGap.topMargin = dp(12);
                box.addView(value, valueGap);
                review = text("", 14, muted, false);
                review.setId(R.id.text_time_review);
                figures(review);
                review.setVisibility(View.GONE);
                LinearLayout.LayoutParams reviewGap = wide();
                reviewGap.topMargin = dp(10);
                box.addView(review, reviewGap);

                // Confirm is the one way forward and the only filled button; Close, or Revert changes once the seconds
                // differ, is a quiet word beside it.
                close = button("Close");
                close.setId(R.id.close);
                quiet(close);
                close.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (window != null) window.dismiss();
                    }
                });
                revert = button("Revert changes");
                revert.setId(R.id.revert);
                quiet(revert);
                revert.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        value.setText(String.valueOf(saved));
                        value.setSelection(value.getText().length());
                    }
                });
                confirm = button("Confirm");
                confirm.setId(R.id.confirm);
                filled(confirm);
                confirm.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        save();
                    }
                });
                LinearLayout buttons = footer(close, revert, confirm);
                ((LinearLayout.LayoutParams) revert.getLayoutParams()).leftMargin = 0;
                LinearLayout.LayoutParams buttonsGap = wide();
                buttonsGap.topMargin = dp(12);
                box.addView(buttons, buttonsGap);
                value.addTextChangedListener(new android.text.TextWatcher() {
                    @Override
                    public void beforeTextChanged(CharSequence words, int start, int count, int after) {}

                    @Override
                    public void onTextChanged(CharSequence words, int start, int before, int count) {}

                    @Override
                    public void afterTextChanged(android.text.Editable words) {
                        draw();
                    }
                });
                draw();

                // On a short screen, such as a phone on its side, the window scrolls so Confirm is always reachable.
                ScrollView boxScroll = new ScrollView(OptionsActivity.this);
                boxScroll.setFillViewport(true);
                boxScroll.addView(box, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                window = new android.app.Dialog(OptionsActivity.this);
                window.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
                window.setContentView(boxScroll);
                window.setCanceledOnTouchOutside(true);
                window.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
                    @Override
                    public void onDismiss(android.content.DialogInterface dialog) {
                        window = null;
                    }
                });
                window.show();
                android.view.Window frame = window.getWindow();
                if (frame != null) {
                    frame.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0));
                    int across = getResources().getDisplayMetrics().widthPixels - dp(32);
                    frame.setLayout(Math.min(dp(360), across), ViewGroup.LayoutParams.WRAP_CONTENT);
                    frame.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
                        | android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
                }
                value.requestFocus();
                // The whole number selected, so typing replaces it.
                value.selectAll();
            }

            /** The old and new rule once the seconds differ; Confirm only then. */
            void draw() {
                int now = typed();
                boolean changed = now >= 0 && now != saved;
                close.setVisibility(changed || now < 0 ? View.GONE : View.VISIBLE);
                revert.setVisibility(changed || now < 0 ? View.VISIBLE : View.GONE);
                enable(confirm, changed);
                review.setVisibility(changed ? View.VISIBLE : View.GONE);
                if (!changed) return;
                android.text.SpannableStringBuilder words = new android.text.SpannableStringBuilder("Time per text: ");
                strong(words, Words.textRule(saved));
                words.append(" → ");
                strong(words, Words.textRule(now));
                review.setText(words);
            }

            /** A value in the review line, in the text colour. */
            void strong(android.text.SpannableStringBuilder words, String part) {
                int from = words.length();
                words.append(part);
                words.setSpan(new android.text.style.ForegroundColorSpan(txt), from, words.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }

            /** Writes the rule to the record away from the screen, then closes and shows the new rule on its line. */
            void save() {
                final int seconds = typed();
                if (seconds < 0 || seconds == saved) return;
                enable(confirm, false);
                final Context context = getApplicationContext();
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        boolean done;
                        try {
                            Record.get(context).setTextSeconds(seconds);
                            done = true;
                        } catch (RuntimeException error) {
                            done = false;
                        }
                        final boolean stored = done;
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                if (isDestroyed()) return;
                                if (stored) {
                                    if (window != null) window.dismiss();
                                    load();
                                } else if (window != null) {
                                    enable(confirm, true);
                                    review.setText("That did not save.");
                                }
                            }
                        });
                    }
                }).start();
            }
        }
        final TextTime textTime = new TextTime();
        textChange.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                textTime.open();
            }
        });
        // The window goes with the screen, so Android has no window left behind.
        textLine.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {}

            @Override
            public void onViewDetachedFromWindow(View v) {
                if (textTime.window != null) textTime.window.dismiss();
            }
        });
        textTime.load();

        heading("Day details");
        hide = new CheckBox(this);
        hide.setId(R.id.hide_ignored);
        hide.setText("Hide ignored entries");
        hide.setTextSize(15);
        hide.setTextColor(txt);
        hide.setMinHeight(dp(44));
        hide.setMinimumHeight(dp(44));
        hide.setButtonTintList(new ColorStateList(new int[][] {{android.R.attr.state_checked}, {}}, new int[] {accent, muted}));
        column.addView(hide, wide());

        heading("Last run");
        runLine = muted("");
        runLine.setId(R.id.run_line);
        runLine.setTextSize(13);
        column.addView(runLine, wide());
        runNow = button("Run now");
        runNow.setId(R.id.run_now);
        quiet(runNow);
        runNow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                enable(runNow, false);
                runLine.setText("Reading the phone…");
                thread(getApplicationContext());
            }
        });
        column.addView(runNow, flushStart(runNow));

        heading("Catch up encrypted");
        // A description of what the button does, so it reads in the muted note colour like the app's other help text,
        // not the body colour the paired value lines use.
        TextView catchInfo = muted("Read encrypted chats the phone only shows its messaging app.");
        catchInfo.setTextColor(muted);
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
        switchBack = button("Switch back");
        switchBack.setId(R.id.switch_back);
        switchBack.setVisibility(View.GONE);
        switchBack.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // Opens the app that was the default before Call Tally took the role (which then offers to become the
                // default again), or, when it isn't known, Google Messages or a Settings screen where the owner picks it.
                // The stored previous app is trusted only when it was captured fresh in the in-app catch-up flow; otherwise
                // we pass null and fall back, so this never opens a stale app. It never changes the default itself.
                boolean fresh = Sync.preferences(OptionsActivity.this).getBoolean("prev_sms_fresh", false);
                String prev = Passthrough.prevIfFresh(Sync.preferences(OptionsActivity.this).getString("prev_sms", null), fresh);
                Intent intent = Roles.handBackIntent(OptionsActivity.this, prev);
                if (intent == null) {
                    load();
                    return;
                }
                try {
                    startActivityForResult(intent, SWITCH_BACK);
                } catch (RuntimeException error) {
                    load();
                }
            }
        });
        LinearLayout.LayoutParams switchGap = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        switchGap.topMargin = dp(8);
        column.addView(switchBack, switchGap);

        heading("Look");
        column.addView(sub("Mode"), wide());
        // One track in the second card's grey, like the day view's Calls and Texts; the chosen third sits in it as a card.
        LinearLayout modeRow = row();
        modeRow.setBackground(shape(card2, 0, 0, 10));
        modeRow.setPadding(dp(2), dp(2), dp(2), dp(2));
        for (int i = 0; i < 3; i++) {
            final String mode = MODES[i];
            modes[i] = button(MODE_NAMES[i]);
            modes[i].setId(MODE_IDS[i]);
            modes[i].setMinHeight(0);
            modes[i].setMinimumHeight(0);
            modes[i].setPadding(dp(8), 0, dp(8), 0);
            modes[i].setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    look(mode, theme());
                }
            });
            modeRow.addView(modes[i], new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        }
        column.addView(modeRow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));
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
            // A pill: the dot and the name, 40dp tall.
            themes[i].setMinHeight(dp(40));
            themes[i].setMinimumHeight(dp(40));
            themes[i].setPadding(dp(12), 0, dp(12), 0);
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

        heading("First start");
        Button again = button("Show the first-start page again");
        again.setId(R.id.start_again);
        quiet(again);
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
        column.addView(again, flushStart(again));

        // The app's own version, so the owner can confirm which build is installed.
        TextView version = muted(versionLine());
        version.setId(R.id.version_line);
        version.setTextSize(13);
        version.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams versionGap = wide();
        versionGap.topMargin = dp(24);
        column.addView(version, versionGap);
        // How to reach the author and where the app lives, under the version: two centred lines in the link colour that
        // open the mail app and the download page.
        LinearLayout.LayoutParams emailGap = wide();
        emailGap.topMargin = dp(6);
        column.addView(footerLink("retiredrobot@retiredrobot.com", R.id.contact_email, "mailto:retiredrobot@retiredrobot.com"), emailGap);
        column.addView(footerLink("retiredrobot.com/calltally", R.id.site_link, "https://retiredrobot.com/calltally/"), wide());
        // A line set to 13sp is a note, not a value: muted, as before.
        for (TextView line : lines) {
            if (line.getTextSize() == TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 13, getResources().getDisplayMetrics()) && line.getCurrentTextColor() == txt) {
                line.setTextColor(muted);
            }
        }

        scroll = new ScrollView(this);
        scroll.addView(column);
        frame("Options", scroll, true);
        drawLook();

        // Redrawn for a new look, or after Android let the screen go: back to the same place in the list, so a tap on a
        // mode or theme near the bottom does not jump the page to the top. The offset can only be set once the view is laid
        // out, and the list only reaches its full height after the system-bar insets are in, so it is set again on each
        // layout pass and re-applies until the page stops growing or the offset is reached, then the listener steps aside.
        Bundle state = saved != null ? saved : getIntent().getExtras();
        final int y = state != null ? state.getInt("scrollY", 0) : 0;
        if (y != 0) {
            scroll.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
                private int range = -1;
                private int passes;

                @Override
                public void onGlobalLayout() {
                    scroll.scrollTo(0, y);
                    int grown = scroll.getChildCount() > 0 ? scroll.getChildAt(0).getHeight() : 0;
                    // Stop once the offset is reached, or the page has stopped growing (so a shorter page's clamp is final),
                    // or after a hard cap of passes so it always ends. Once detached it never re-attaches.
                    if (scroll.getScrollY() == y || grown <= range || ++passes >= 12) {
                        scroll.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                    }
                    range = grown;
                }
            });
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt("scrollY", scroll.getScrollY());
    }

    @Override
    void keep(Intent intent) {
        intent.putExtra("scrollY", scroll.getScrollY());
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
        // The captured previous app only matters while Call Tally holds the role. If it no longer holds it (handed back,
        // or switched back by hand in Settings), forget the fresh flag so the Options Switch back can't reuse a stale app.
        // While the role is still held, leave it, so the normal catch-up hand-back still returns to the previous app.
        if (!Roles.holdsSmsRole(this)) {
            Sync.preferences(this).edit().putBoolean("prev_sms_fresh", false).commit();
        }
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
        // After the switch-back dialog, redraw the still-the-messaging-app line from what the phone now reports. When the
        // role was handed back, drop the fresh flag so a later Switch back can't reuse the captured previous app.
        if (code == SWITCH_BACK && !isFinishing()) {
            if (!Roles.holdsSmsRole(getApplicationContext())) {
                Sync.preferences(getApplicationContext()).edit().putBoolean("prev_sms_fresh", false).commit();
            }
            load();
        }
    }

    /** Reads the settings and the people away from the screen. */
    private void load() {
        final Context context = getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                String who, pay, problem = null;
                boolean hidden = false, havePeriod = false, haveOwn = false;
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
                    haveOwn = !record.ownNumbers().isEmpty();
                } catch (Record.TooNew error) {
                    who = pay = problem = error.getMessage();
                } catch (RuntimeException error) {
                    who = pay = problem = Record.UNREADABLE;
                }
                final String whoText = who, payText = pay, trouble = problem;
                final boolean hideIt = hidden, set = havePeriod, holdsRole = holds, showOwn = haveOwn;
                final String caughtUpText = caughtUp;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (isDestroyed()) return;
                        whoLine.setText(whoText);
                        // The own-number row shows only when there is something learned to forget. Back to its normal
                        // words and an on button when shown, so a re-learned number after a Forget reads afresh.
                        if (showOwn) {
                            ownLine.setText("Your own number was learned from your sent messages.");
                            ownForget.setVisibility(View.VISIBLE);
                            enable(ownForget, true);
                        }
                        ownRow.setVisibility(showOwn ? View.VISIBLE : View.GONE);
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
        // The chosen mode: a card with an accent line in the track. The others: clear, a ripple when touched.
        for (int i = 0; i < 3; i++) {
            boolean chosen = MODES[i].equals(mode());
            modes[i].setTextColor(chosen ? txt : muted);
            modes[i].setTypeface(medium());
            modes[i].setBackground(chosen ? touchable(shape(card, accent, 1, 8)) : touchable(null, 8));
            modes[i].setSelected(chosen);
        }
        // The chosen theme: a pill outlined in the accent. The others: clear, a ripple when touched.
        for (int i = 0; i < 5; i++) {
            boolean chosen = THEMES[i].equals(theme());
            if (chosen) {
                outlined(themes[i], 20);
            } else {
                plain(themes[i]);
                themes[i].setBackground(touchable(null, 20));
            }
            themes[i].setSelected(chosen);
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

    /** A section's caption, with a thin line above it except at the top. */
    private void heading(String value) {
        TextView view = caption(value);
        LinearLayout.LayoutParams params = wide();
        params.topMargin = dp(firstHeading ? 4 : 24);
        params.bottomMargin = dp(8);
        if (!firstHeading) {
            View rule = divider();
            LinearLayout.LayoutParams ruleGap = (LinearLayout.LayoutParams) rule.getLayoutParams();
            ruleGap.topMargin = dp(12);
            column.addView(rule, ruleGap);
        }
        firstHeading = false;
        column.addView(view, params);
    }

    /** A value line in the page's body text. */
    private TextView muted(String value) {
        TextView view = text(value, 15, txt, false);
        lines.add(view);
        return view;
    }

    /** A quiet button on its own line, its word lined up with the text above: the button's own padding hangs past it. */
    private LinearLayout.LayoutParams flushStart(Button button) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMarginStart(-hang());
        return params;
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

    /** A centred line in the link colour that opens the given address: mailto: in the mail app, https in the browser. */
    private TextView footerLink(String words, int id, final String address) {
        TextView view = text(words, 13, accent, false);
        view.setId(id);
        view.setGravity(Gravity.CENTER);
        view.setMinHeight(dp(40));
        view.setMinimumHeight(dp(40));
        view.setBackground(touchable(null, 8));
        view.setClickable(true);
        view.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String action = address.startsWith("mailto:") ? Intent.ACTION_SENDTO : Intent.ACTION_VIEW;
                try {
                    startActivity(new Intent(action, Uri.parse(address)));
                } catch (RuntimeException error) {
                    // No app on the phone to open it: nothing to do.
                }
            }
        });
        return view;
    }

    private TextView sub(String value) {
        TextView view = text(value, 15, txt, false);
        view.setPadding(0, dp(4), 0, dp(8));
        return view;
    }

    /** Words on the left, a quiet button on the right, its word lined up with the right edge. Returns the row. */
    private LinearLayout pair(TextView words, Button button) {
        quiet(button);
        // Quiet dims further when off, so the dimming is set again in its new look.
        enable(button, button.isEnabled());
        LinearLayout row = row();
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        row.addView(words, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        LinearLayout.LayoutParams side = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        side.leftMargin = dp(10);
        // The button's own padding hangs past the edge.
        side.setMarginEnd(-hang());
        row.addView(button, side);
        column.addView(row, wide());
        return row;
    }

    /** How far a quiet button's padding hangs past the text edge: never past the screen edge on narrow phones. */
    private int hang() {
        return Math.min(dp(12), edge());
    }

    private LinearLayout.LayoutParams third(int i) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        if (i > 0) params.leftMargin = dp(6);
        return params;
    }
}
