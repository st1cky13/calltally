package com.calltally.app;

import android.content.Context;
import android.util.TypedValue;
import android.widget.TextView;

/** One line of text that shrinks, down to 11sp and no smaller, to fit its width: a day's call time in a narrow cell. */
final class FitText extends TextView {
    private final float most;
    private final float least;

    FitText(Context context, float mostSp) {
        super(context);
        float scale = context.getResources().getDisplayMetrics().scaledDensity;
        most = mostSp * scale;
        least = 11 * scale;
        setSingleLine(true);
        setEllipsize(null);
        setTextSize(TypedValue.COMPLEX_UNIT_PX, most);
    }

    void show(CharSequence value) {
        setText(value);
        fit(getWidth());
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w != oldw) fit(w);
    }

    private void fit(int width) {
        if (width <= 0) return;
        int room = width - getPaddingLeft() - getPaddingRight();
        String value = getText().toString();
        android.text.TextPaint paint = new android.text.TextPaint(getPaint());
        float size = most;
        paint.setTextSize(size);
        while (size > least && paint.measureText(value) > room) {
            size = Math.max(least, size - 0.5f);
            paint.setTextSize(size);
        }
        if (Math.abs(getTextSize() - size) > 0.01f) {
            final float chosen = size;
            post(new Runnable() {
                @Override
                public void run() {
                    setTextSize(TypedValue.COMPLEX_UNIT_PX, chosen);
                }
            });
        }
    }
}
