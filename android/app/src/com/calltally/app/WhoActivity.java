package com.calltally.app;

import android.app.Activity;
import android.content.Context;
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
public class WhoActivity extends Activity {
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
    private int gap;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        Sync.noteCrashes(this);
        gap = (int) (16 * getResources().getDisplayMetrics().density);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setFitsSystemWindows(true);
        page.setPadding(gap, gap, gap, 0);
        TextView title = new TextView(this);
        title.setText("Who counts");
        title.setTextSize(22);
        note = new TextView(this);
        note.setTextSize(14);
        note.setText("Reading your contacts…");
        note.setPadding(0, gap / 4, 0, gap / 2);
        search = new EditText(this);
        search.setHint("Search by name or number");
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
        tickAll = new Button(this);
        tickAll.setText("Tick all shown");
        tickAll.setEnabled(false);
        tickAll.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                tickAllShown();
            }
        });
        ListView list = new ListView(this);
        adapter = new Adapter();
        list.setAdapter(adapter);
        page.addView(title);
        page.addView(note);
        page.addView(search);
        page.addView(tickAll);
        page.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(page);
        load();
    }

    /** Reads the phone's contacts and the people table away from the screen, then shows them together. */
    private void load() {
        final Context context = getApplicationContext();
        new Thread(new Runnable() {
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
                }
                final List<Line> lines = people == null ? new ArrayList<Line>() : lines(phone, people);
                final String text = problem;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        all.clear();
                        all.addAll(lines);
                        note.setText(text != null ? text : "Only ticked people count. Their calls and texts stay counted"
                            + " even if they leave your contacts.");
                        filter();
                    }
                });
            }
        }).start();
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
            if (!onPhone.containsKey(person.key)) out.add(new Line(person.key, person.label(), person.numbers, null, true));
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
        String query = search.getText().toString().trim().toLowerCase(Locale.ROOT);
        String digits = query.replaceAll("\\D", "");
        shown.clear();
        for (Line line : all) {
            if (query.isEmpty() || line.name.toLowerCase(Locale.ROOT).contains(query) || matchesNumber(line, digits)) shown.add(line);
        }
        boolean any = false;
        for (Line line : shown) if (!line.ticked) any = true;
        tickAll.setEnabled(any);
        adapter.notifyDataSetChanged();
    }

    private static boolean matchesNumber(Line line, String digits) {
        if (digits.isEmpty()) return false;
        for (String number : line.numbers) if (number.replaceAll("\\D", "").contains(digits)) return true;
        return false;
    }

    private void set(Line line, boolean ticked) {
        try {
            Record record = Record.get(this);
            if (ticked && line.contact != null) record.tick(Collections.singletonList(line.contact));
            else record.setTicked(line.key, ticked);
            line.ticked = ticked;
            changed = true;
        } catch (Record.TooNew error) {
            note.setText(error.getMessage());
        }
        filter();
    }

    private void tickAllShown() {
        List<Rules.Person> contacts = new ArrayList<>();
        for (Line line : shown) if (!line.ticked && line.contact != null) contacts.add(line.contact);
        try {
            Record record = Record.get(this);
            record.tick(contacts);
            for (Line line : shown) {
                if (!line.ticked && line.contact == null) record.setTicked(line.key, true);
                line.ticked = true;
            }
            changed = true;
        } catch (Record.TooNew error) {
            note.setText(error.getMessage());
        }
        filter();
    }

    @Override
    protected void onStop() {
        super.onStop();
        // New ticks count from the next run; start one now so the record catches up.
        if (!changed) return;
        changed = false;
        final Context context = getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                Sync.run(context);
            }
        }).start();
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
                TextView detail = new TextView(WhoActivity.this);
                detail.setTextSize(13);
                detail.setPadding((int) (32 * getResources().getDisplayMetrics().density), 0, 0, 0);
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
