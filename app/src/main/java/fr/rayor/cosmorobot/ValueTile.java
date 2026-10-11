package fr.rayor.cosmorobot;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.Bundle;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.animation.DecelerateInterpolator;

/**
 * Une tuile qui est elle-même le curseur, comme la tuile de luminosité de G1
 * Extended : le niveau est peint en fond (le ton « tuile active »), et glisser
 * horizontalement sur la tuile le règle. Le glissement est relatif, pas absolu :
 * poser le doigt ne fait jamais sauter la valeur, et un geste vertical reste
 * au défilement de la page.
 */
final class ValueTile extends View {
    interface Listener {
        /** done = le doigt vient de se lever (valeur à mémoriser). */
        void onChange(int value, boolean done);
    }

    interface Format {
        String of(int value);
    }

    private final String label;
    private final int min, max, step;
    private final Format format;
    private final Listener listener;

    private final Paint ground = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint level = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint valuePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint press = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path shape = new Path();
    private final RectF box = new RectF();
    private final int pad, slop;

    private float exact;           // valeur continue pendant le glissement
    private int value;
    private float shown;           // fraction affichée (animée)
    private ValueAnimator anim;
    private float downX, downY, lastX;
    private boolean dragging, pressed;

    ValueTile(Context c, String label, int min, int max, int step, int initial, Format format, Listener l) {
        super(c);
        this.label = label;
        this.min = min;
        this.max = max;
        this.step = Math.max(1, step);
        this.format = format;
        this.listener = l;
        ground.setColor(Ui.TILE);
        level.setColor(Ui.TILE_ACTIVE);
        press.setColor(Ui.RIPPLE);
        labelPaint.setColor(Ui.INK_MUTED);
        labelPaint.setTypeface(Ui.REGULAR);
        labelPaint.setTextSize(Ui.sp(c, 13));
        valuePaint.setColor(Ui.INK);
        valuePaint.setTypeface(Ui.MONO);
        valuePaint.setTextSize(Ui.sp(c, 20));
        pad = Ui.dp(c, Ui.TILE_PAD);
        slop = ViewConfiguration.get(c).getScaledTouchSlop();
        value = clamp(initial);
        exact = value;
        shown = fraction(value);
        setFocusable(true);
        setContentDescription(label);
    }

    int value() {
        return value;
    }

    /** Valeur imposée de l'extérieur ; ignorée pendant un glissement. */
    void setValue(int v, boolean animate) {
        if (dragging) return;
        value = clamp(v);
        exact = value;
        float to = fraction(value);
        if (anim != null) anim.cancel();
        if (!animate) {
            shown = to;
            invalidate();
            return;
        }
        anim = ValueAnimator.ofFloat(shown, to);
        anim.setDuration(220);
        anim.setInterpolator(new DecelerateInterpolator());
        anim.addUpdateListener(a -> {
            shown = (float) a.getAnimatedValue();
            invalidate();
        });
        anim.start();
    }

    private int clamp(float v) {
        int s = Math.round((v - min) / step) * step + min;
        return Math.max(min, Math.min(max, s));
    }

    private float fraction(int v) {
        return (v - min) / (float) (max - min);
    }

    @Override
    protected void onMeasure(int w, int h) {
        setMeasuredDimension(MeasureSpec.getSize(w), resolveSize(Ui.dp(getContext(), 92), h));
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        float r = Ui.dp(getContext(), Ui.RADIUS);
        shape.reset();
        box.set(0, 0, w, h);
        shape.addRoundRect(box, r, r, Path.Direction.CW);
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        c.save();
        c.clipPath(shape);
        c.drawRect(0, 0, w, h, ground);
        c.drawRect(0, 0, w * shown, h, level);
        if (pressed) c.drawRect(0, 0, w, h, press);
        c.restore();
        Paint.FontMetrics lm = labelPaint.getFontMetrics();
        c.drawText(label, pad, pad - lm.ascent - lm.descent * 0.2f, labelPaint);
        Paint.FontMetrics vm = valuePaint.getFontMetrics();
        c.drawText(format.of(value), pad, h - pad - vm.descent * 0.5f, valuePaint);
    }

    @Override
    public void setEnabled(boolean on) {
        super.setEnabled(on);
        setAlpha(on ? 1f : Ui.DISABLED_ALPHA);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (!isEnabled()) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = lastX = e.getX();
                downY = e.getY();
                dragging = false;
                pressed = true;
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE: {
                float x = e.getX();
                if (!dragging) {
                    float dx = Math.abs(x - downX), dy = Math.abs(e.getY() - downY);
                    if (dx > slop && dx > dy) {
                        dragging = true;
                        lastX = x;
                        getParent().requestDisallowInterceptTouchEvent(true);
                        if (anim != null) anim.cancel();
                    }
                    return true;
                }
                // Toute la largeur de la tuile vaut toute la plage.
                exact += (x - lastX) / Math.max(1, getWidth()) * (max - min);
                exact = Math.max(min, Math.min(max, exact));
                lastX = x;
                int v = clamp(exact);
                if (v != value) {
                    boolean edge = (v == min || v == max);
                    value = v;
                    shown = fraction(v);
                    invalidate();
                    if (edge) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                    if (listener != null) listener.onChange(v, false);
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                pressed = false;
                if (dragging) {
                    dragging = false;
                    getParent().requestDisallowInterceptTouchEvent(false);
                    exact = value;
                    if (listener != null) listener.onChange(value, true);
                }
                invalidate();
                return true;
            default:
                return super.onTouchEvent(e);
        }
    }

    // ------------------------------------------------------------- accessibilité
    // Lu comme un curseur, et réglable par les gestes de TalkBack.

    @Override
    public CharSequence getAccessibilityClassName() {
        return "android.widget.SeekBar";
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setRangeInfo(range());
        if (android.os.Build.VERSION.SDK_INT >= 30) info.setStateDescription(format.of(value));
        if (isEnabled()) {
            if (value > min) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
            if (value < max) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
        }
    }

    @SuppressWarnings("deprecation")
    private AccessibilityNodeInfo.RangeInfo range() {
        int t = AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT;
        return android.os.Build.VERSION.SDK_INT >= 30
                ? new AccessibilityNodeInfo.RangeInfo(t, min, max, value)
                : AccessibilityNodeInfo.RangeInfo.obtain(t, min, max, value);
    }

    @Override
    public boolean performAccessibilityAction(int action, Bundle args) {
        int big = Math.max(step, Math.round((max - min) / 10f / step) * step);
        if (isEnabled() && (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                || action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) {
            int v = clamp(value + (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD ? big : -big));
            setValue(v, true);
            if (listener != null) listener.onChange(v, true);
            return true;
        }
        return super.performAccessibilityAction(action, args);
    }
}
