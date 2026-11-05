package com.calltally.app;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Who counts: every contact on the phone with a check box, and everyone ticked before who is no longer on the phone.
 * Only ticked people count. Ticking adds the person and their numbers to the record's people table; unticking only
 * clears the tick, so their entries stay.
 */
public class WhoActivity extends Screen {
    /** One line of the list. contact is the phone's version, or null when the person is no longer on the phone. */
    private static final class Line {
        final String key;
        final String name;
        final List<String> numbers;
        final Rules.Person contact;
        boolean ticked;

        Line(String key, String name, List<String> numbers, Rules.Person contact, boolean ticked) {
            this.key = key;
            this.name = name;
            this.numbers = numbers;
            this.contact = contact;
            this.ticked = ticked;
        }
    }

    private final List<Line> all = new ArrayList<>();
    private final List<Line> shown = new ArrayList<>();
    private TextView note;
    private EditText search;
    private Button tickAll;
    private Adapter adapter;
    private boolean changed;
    private boolean saving;
    private String saveProblem;
    /**
     * One worker for the whole app, so the record is read only after every tick already made is written, also when the
     * screen is drawn again after a rotation.
     */
    private static final java.util.concurrent.ExecutorService WRITES = java.util.concurrent.Executors.newSingleThreadExecutor();
    private static final String HINT = "Tick the people whose calls and texts should count. You can change this later in Options.";
    private boolean first;
    private int gap;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        // Part of the first start: the hint on top, and Next on to the pay period instead of Close.
        first = getIntent().getBooleanExtra("first", false);
        gap = dp(16);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(edge(), gap / 2, edge(), 0);
        note = new TextView(this);
        note.setTextSize(14);
        note.setTextColor(muted);
        note.setId(R.id.who_note);
        note.setText(first ? HINT : "Reading your contacts…");
        note.setPadding(0, gap / 4, 0, gap / 2);
        search = new EditText(this);
        search.setId(R.id.who_search);
        search.setHint("Search by name or number");
        search.setTextColor(txt);
        search.setHintTextColor(muted);
        search.setBackgroundTintList(android.content.res.ColorStateList.valueOf(accent));
        search.setMinHeight(dp(48));
        search.setSingleLine(true);
        search.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence text, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence text, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable text) {
                filter();
            }
        });
        tickAll = button("Tick all shown");
        enable(tickAll, false);
        tickAll.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                tickAllShown();
            }
        });
        ListView list = new ListView(this);
        list.setId(R.id.who_list);
        adapter = new Adapter();
        list.setAdapter(adapter);
        page.addView(note);
        page.addView(search);
        page.addView(tickAll);
        list.setDivider(new android.graphics.drawable.ColorDrawable(line));
        list.setDividerHeight(Math.max(1, dp(1)));
        page.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        if (first) {
            Button next = button("Next");
            next.setId(R.id.who_next);
            filled(next);
            next.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    startActivity(new Intent(WhoActivity.this, PayActivity.class).putExtra("first", true));
                }
            });
            footer = footer(next);
        }
        frame("Who counts", page, !first);
        load();
    }

    /** Reads the phone's contacts and the people table away from the screen, then shows them together. */
    private void load() {
        final Context context = getApplicationContext();
        WRITES.execute(new Runnable() {
            @Override
            public void run() {
                List<Rules.Person> phone = null;
                List<Rules.Person> people = null;
                String problem = null;
                try {
                    try {
                        phone = Collector.readContacts(context.getContentResolver());
                    } catch (Collector.Problem | SecurityException error) {
                        problem = "Your contacts couldn't be read, so only people ticked before are shown.";
                    }
                    Record record = Record.get(context);
                    record.refreshPeople(phone);
                    people = record.people();
                } catch (Record.TooNew error) {
                    problem = error.getMessage();
                } catch (RuntimeException error) {
                    problem = Record.UNREADABLE;
                    people = null;
                }
                final List<Line> lines = people == null ? new ArrayList<Line>() : lines(phone, people);
                final String text = problem;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (isDestroyed()) return;
                        all.clear();
                        all.addAll(lines);
                        if (text == null && saveProblem != null) {
                            note.setText(saveProblem);
                            saveProblem = null;
                        } else note.setText(text != null ? text : first ? HINT : "Only ticked people count. Their calls and"
                            + " texts stay counted even if they leave your contacts.");
                        filter();
                    }
                });
            }
        });
    }

    /** Contacts on the phone, and people ticked at some time who are no longer there, sorted by name. */
    private static List<Line> lines(List<Rules.Person> phone, List<Rules.Person> people) {
        Map<String, Rules.Person> known = new HashMap<>();
        for (Rules.Person person : people) known.put(person.key, person);
        List<Line> out = new ArrayList<>();
        Map<String, Boolean> onPhone = new HashMap<>();
        if (phone != null) {
            for (Rules.Person contact : phone) {
                Rules.Person person = known.get(contact.key);
                out.add(new Line(contact.key, contact.label(), contact.numbers, contact, person != null && person.ticked));
                onPhone.put(contact.key, true);
            }
        }
        for (Rules.Person person : people) {
            if (!onPhone.containsKey(person.key)) out.add(new Line(person.key, person.label(), person.numbers, null, person.ticked));
        }
        Collections.sort(out, new Comparator<Line>() {
            @Override
            public int compare(Line a, Line b) {
                int byName = a.name.compareToIgnoreCase(b.name);
                return byName != 0 ? byName : a.key.compareTo(b.key);
            }
        });
        return out;
    }

    private void filter() {
        String query = search.getText().toString().trim();
        String digits = Rules.searchDigits(query);
        String words = query.toLowerCase(Locale.ROOT);
        shown.clear();
        for (Line line : all) {
            // Text made only of digits and phone punctuation looks up numbers; anything else looks up names.
            boolean match = query.isEmpty() || (digits != null ? matchesNumber(line, digits) : line.name.toLowerCase(Locale.ROOT).contains(words));
            if (match) shown.add(line);
        }
        boolean any = false;
        for (Line line : shown) if (!line.ticked) any = true;
        enable(tickAll, any && !saving);
        adapter.notifyDataSetChanged();
    }

    private static boolean matchesNumber(Line line, String digits) {
        for (String number : line.numbers) if (number.replaceAll("\\D", "").contains(digits)) return true;
        return false;
    }

    /** Ticks or unticks one person, writing the record away from the screen. */
    private void set(final Line line, final boolean ticked) {
        line.ticked = ticked;
        changed = true;
        filter();
        final Context context = getApplicationContext();
        save(new Runnable() {
            @Override
            public void run() {
                Record record = Record.get(context);
                if (ticked && line.contact != null) record.tick(Collections.singletonList(line.contact));
                else record.setTicked(line.key, ticked);
            }
        });
    }

    private void tickAllShown() {
        final List<Rules.Person> contacts = new ArrayList<>();
        final List<String> gone = new ArrayList<>();
        for (Line line : shown) {
            if (line.ticked) continue;
            if (line.contact != null) contacts.add(line.contact);
            else gone.add(line.key);
            line.ticked = true;
        }
        changed = true;
        filter();
        final Context context = getApplicationContext();
        save(new Runnable() {
            @Override
            public void run() {
                Record record = Record.get(context);
                record.tick(contacts);
                for (String key : gone) record.setTicked(key, true);
            }
        });
    }

    /** One write at a time, in order, off the screen. A failed write reloads the list from the record and says so. */
    private void save(final Runnable write) {
        saving = true;
        enable(tickAll, false);
        WRITES.execute(new Runnable() {
            @Override
            public void run() {
                String problem = null;
                try {
                    write.run();
                } catch (Record.TooNew error) {
                    problem = error.getMessage();
                } catch (RuntimeException error) {
                    problem = "That did not save.";
                }
                final String trouble = problem;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        saving = false;
                        if (isDestroyed()) return;
                        if (trouble != null) {
                            saveProblem = trouble;
                            load();
                        } else {
                            filter();
                        }
                    }
                });
            }
        });
    }

    @Override
    protected void onStop() {
        super.onStop();
        // New ticks count from the next run; start one now so the record catches up.
        if (!changed) return;
        changed = false;
        final Context context = getApplicationContext();
        // After the ticks are written: the worker runs one task at a time, in order. The run itself goes on its own
        // thread, so the worker is free for the next screen; a run already going does this one straight after.
        WRITES.execute(new Runnable() {
            @Override
            public void run() {
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        Sync.run(context);
                    }
                }).start();
            }
        });
    }

    private final class Adapter extends BaseAdapter {
        @Override
        public int getCount() {
            return shown.size();
        }

        @Override
        public Object getItem(int position) {
            return shown.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View view, ViewGroup parent) {
            LinearLayout row = (LinearLayout) view;
            if (row == null) {
                row = new LinearLayout(WhoActivity.this);
                row.setOrientation(LinearLayout.VERTICAL);
                row.setPadding(0, gap / 4, 0, gap / 4);
                CheckBox box = new CheckBox(WhoActivity.this);
                box.setTextSize(16);
                box.setTextColor(txt);
                box.setMinHeight(dp(44));
                box.setButtonTintList(new android.content.res.ColorStateList(new int[][] {{android.R.attr.state_checked}, {}}, new int[] {accent, muted}));
                TextView detail = new TextView(WhoActivity.this);
                detail.setTextSize(13);
                detail.setTextColor(muted);
                detail.setPadding(dp(32), 0, 0, 0);
                row.addView(box);
                row.addView(detail);
            }
            CheckBox box = (CheckBox) row.getChildAt(0);
            TextView detail = (TextView) row.getChildAt(1);
            final Line line = shown.get(position);
            box.setOnCheckedChangeListener(null);
            box.setText(line.name);
            box.setChecked(line.ticked);
            box.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton button, boolean checked) {
                    set(line, checked);
                }
            });
            detail.setText(String.join(", ", line.numbers) + (line.contact == null ? " · not on the phone" : ""));
            return row;
        }
    }
}
