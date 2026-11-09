package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.format.DateFormat;
import android.view.View;

import java.util.Calendar;
import java.util.Locale;

/** StandBy clock faces, drawn on one view that repaints once a second. Left and right change the face (ClockActivity). */
final class StandbyView extends View {
    static final int FACES = 3;
    private static final int ORANGE = 0xFFF2A33A, RED = 0xFFFF453A;
    private static final String[] DAYS = {"S", "M", "T", "W", "T", "F", "S"};

    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int face;
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            invalidate();
            postDelayed(this, 1000 - System.currentTimeMillis() % 1000);
        }
    };

    StandbyView(Context context) {
        super(context);
        line.setStrokeCap(Paint.Cap.ROUND);
        text.setTextAlign(Paint.Align.CENTER);
        try {
            text.setTypeface(UiFont.get(context, R.string.font_medium));
        } catch (Exception ignored) {
            text.setTypeface(Typeface.DEFAULT_BOLD);
        }
    }

    void setFace(int value) {
        face = value;
        invalidate();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        post(tick);
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(tick);
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas c) {
        c.drawColor(Color.BLACK);
        Calendar now = Calendar.getInstance();
        int w = getWidth(), h = getHeight();
        if (face == 0) {
            analogue(c, now, w * 0.28f, h * 0.5f, h * 0.36f);
            calendar(c, now, w * 0.55f, h * 0.2f, w * 0.38f, h * 0.6f);
        } else if (face == 1) {
            analogue(c, now, w * 0.5f, h * 0.5f, h * 0.46f);
        } else {
            digital(c, now, w * 0.5f, h * 0.5f, h * 0.36f);
        }
    }

    private void hand(Canvas c, float cx, float cy, double turns, float length, float width, int color) {
        double a = turns * 2 * Math.PI;
        line.setColor(color);
        line.setStrokeWidth(width);
        c.drawLine(cx, cy, cx + (float) Math.sin(a) * length, cy - (float) Math.cos(a) * length, line);
    }

    private void analogue(Canvas c, Calendar now, float cx, float cy, float r) {
        for (int i = 0; i < 60; i++) {
            double a = i * Math.PI / 30;
            float inner = r * (i % 5 == 0 ? 0.93f : 0.96f);
            line.setColor(i % 5 == 0 ? Color.WHITE : 0xFF6E6E73);
            line.setStrokeWidth(i % 5 == 0 ? r * 0.012f : r * 0.006f);
            c.drawLine(cx + (float) Math.sin(a) * inner, cy - (float) Math.cos(a) * inner,
                    cx + (float) Math.sin(a) * r, cy - (float) Math.cos(a) * r, line);
        }
        text.setColor(Color.WHITE);
        text.setTextSize(r * 0.15f);
        float drop = -(text.ascent() + text.descent()) / 2;
        for (int n = 1; n <= 12; n++) {
            double a = n * Math.PI / 6;
            c.drawText(String.valueOf(n), cx + (float) Math.sin(a) * r * 0.77f, cy - (float) Math.cos(a) * r * 0.77f + drop, text);
        }
        int minute = now.get(Calendar.MINUTE), second = now.get(Calendar.SECOND);
        hand(c, cx, cy, (now.get(Calendar.HOUR) + minute / 60.0) / 12, r * 0.5f, r * 0.04f, Color.WHITE);
        hand(c, cx, cy, (minute + second / 60.0) / 60, r * 0.74f, r * 0.03f, Color.WHITE);
        hand(c, cx, cy, second / 60.0, r * 0.88f, r * 0.008f, ORANGE);
        line.setColor(ORANGE);
        c.drawCircle(cx, cy, r * 0.025f, line);
    }

    private void calendar(Canvas c, Calendar now, float x, float y, float w, float h) {
        Calendar first = (Calendar) now.clone();
        first.set(Calendar.DAY_OF_MONTH, 1);
        int offset = first.get(Calendar.DAY_OF_WEEK) - 1, days = now.getActualMaximum(Calendar.DAY_OF_MONTH);
        float cell = w / 7, row = h / 7.2f;
        text.setTextSize(row * 0.52f);
        text.setColor(RED);
        text.setTextAlign(Paint.Align.LEFT);
        c.drawText(first.getDisplayName(Calendar.MONTH, Calendar.LONG, Locale.getDefault()).toUpperCase(Locale.getDefault()),
                x + cell * 0.2f, y + row * 0.6f, text);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize(row * 0.44f);
        for (int d = 0; d < 7; d++) {
            text.setColor(0xFF8E8E93);
            c.drawText(DAYS[d], x + cell * (d + 0.5f), y + row * 1.7f, text);
        }
        int today = now.get(Calendar.DAY_OF_MONTH);
        for (int day = 1; day <= days; day++) {
            int slot = offset + day - 1;
            float cx = x + cell * (slot % 7 + 0.5f), cy = y + row * (2.7f + slot / 7);
            if (day == today) {
                line.setColor(RED);
                c.drawCircle(cx, cy - row * 0.15f, row * 0.42f, line);
            }
            text.setColor(day == today || slot % 7 != 0 && slot % 7 != 6 ? Color.WHITE : 0xFF8E8E93);
            c.drawText(String.valueOf(day), cx, cy, text);
        }
    }

    private void digital(Canvas c, Calendar now, float cx, float cy, float size) {
        CharSequence time = DateFormat.format(DateFormat.is24HourFormat(getContext()) ? "HH:mm" : "h:mm", now);
        text.setColor(RED);
        text.setTextSize(size * 0.14f);
        c.drawText(DateFormat.format("EEEE d MMMM", now).toString().toUpperCase(Locale.getDefault()), cx, cy - size * 0.5f, text);
        text.setColor(Color.WHITE);
        text.setTextSize(size);
        c.drawText(time.toString(), cx, cy + size * 0.36f, text);
    }
}
