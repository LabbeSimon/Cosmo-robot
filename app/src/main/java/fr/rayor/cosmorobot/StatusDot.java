package fr.rayor.cosmorobot;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

/** Le voyant de l'en-tête : plein si connecté, pulsé tant que ça cherche. */
final class StatusDot extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private ObjectAnimator pulse;

    StatusDot(Context c) {
        super(c);
        paint.setColor(Ui.INK_FAINT);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    void set(int color, boolean busy) {
        paint.setColor(color);
        invalidate();
        if (busy && pulse == null) {
            pulse = ObjectAnimator.ofFloat(this, View.ALPHA, 1f, 0.25f);
            pulse.setDuration(700);
            pulse.setRepeatMode(ValueAnimator.REVERSE);
            pulse.setRepeatCount(ValueAnimator.INFINITE);
            pulse.start();
        } else if (!busy && pulse != null) {
            pulse.cancel();
            pulse = null;
            setAlpha(1f);
        }
    }

    @Override
    protected void onMeasure(int w, int h) {
        int s = Ui.dp(getContext(), 8);
        setMeasuredDimension(s, s);
    }

    @Override
    protected void onDraw(Canvas c) {
        float r = getWidth() / 2f;
        c.drawCircle(r, r, r, paint);
    }

    @Override
    protected void onDetachedFromWindow() {
        if (pulse != null) pulse.cancel();
        pulse = null;
        super.onDetachedFromWindow();
    }
}
