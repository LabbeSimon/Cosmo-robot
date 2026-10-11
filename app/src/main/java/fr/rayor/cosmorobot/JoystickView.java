package fr.rayor.cosmorobot;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/**
 * Joystick analogique : x = rotation, y = avance, tous deux dans -1..1.
 *
 * Dessiné comme un instrument, dans la manière de G1 Extended : un puits plus
 * sombre que la tuile, la trame de points, une croisée de repères et un bouton
 * à l'encre. Le bouton revient au centre en animation, mais l'arrêt, lui, part
 * dès que le doigt se lève.
 */
final class JoystickView extends View {
    interface Listener {
        void onMove(float x, float y);
        void onRelease();
    }

    private final Paint well = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dots;
    private final Paint guide = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint vector = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint knob = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clip = new Path();
    private float kx, ky;          // position du bouton, en fraction du rayon
    private boolean held;
    private ValueAnimator back;
    private Listener listener;

    JoystickView(Context ctx) {
        super(ctx);
        well.setColor(Ui.SURFACE);
        dots = Ui.matrixPaint(ctx);
        guide.setColor(Ui.TILE_ACTIVE);
        guide.setStyle(Paint.Style.STROKE);
        guide.setStrokeWidth(Math.max(1, Ui.dp(ctx, 1)));
        vector.setColor(Ui.INK_FAINT);
        vector.setStrokeWidth(Ui.dp(ctx, 2));
        vector.setStrokeCap(Paint.Cap.ROUND);
        halo.setColor(Ui.TILE_ACTIVE);
        knob.setColor(Ui.INK);
        setContentDescription(ctx.getString(R.string.drive));
    }

    void setListener(Listener l) {
        listener = l;
    }

    @Override
    public void setEnabled(boolean on) {
        super.setEnabled(on);
        if (!on && held) release();
        invalidate();
    }

    @Override
    protected void onMeasure(int w, int h) {
        int width = MeasureSpec.getSize(w);
        int size = Math.min(width, Ui.dp(getContext(), 250));
        if (MeasureSpec.getMode(h) != MeasureSpec.UNSPECIFIED) size = Math.min(size, MeasureSpec.getSize(h));
        setMeasuredDimension(width, size);
    }

    private float radius() {
        return Math.min(getWidth(), getHeight()) / 2f - Ui.dp(getContext(), 4);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        clip.reset();
        clip.addCircle(w / 2f, h / 2f, radius(), Path.Direction.CW);
    }

    @Override
    protected void onDraw(Canvas c) {
        float cx = getWidth() / 2f, cy = getHeight() / 2f, r = radius();
        c.drawCircle(cx, cy, r, well);
        c.save();
        c.clipPath(clip);
        c.drawPaint(dots);
        c.restore();
        c.drawCircle(cx, cy, r * 0.5f, guide);
        c.drawLine(cx - r, cy, cx + r, cy, guide);
        c.drawLine(cx, cy - r, cx, cy + r, guide);

        float x = cx + kx * r * 0.74f, y = cy + ky * r * 0.74f;
        if (kx != 0f || ky != 0f) c.drawLine(cx, cy, x, y, vector);
        if (held) c.drawCircle(x, y, r * 0.34f, halo);
        knob.setColor(isEnabled() ? Ui.INK : Ui.INK_FAINT);
        c.drawCircle(x, y, r * 0.24f, knob);
    }

    @Override
    @SuppressWarnings("fallthrough")
    public boolean onTouchEvent(MotionEvent e) {
        if (!isEnabled()) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                // Sinon le ScrollView parent vole le geste dès qu'on glisse.
                getParent().requestDisallowInterceptTouchEvent(true);
                if (back != null) back.cancel();
                held = true;
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                // fall through
            case MotionEvent.ACTION_MOVE: {
                float r = radius() * 0.74f;
                float dx = (e.getX() - getWidth() / 2f) / r;
                float dy = (e.getY() - getHeight() / 2f) / r;
                float d = (float) Math.hypot(dx, dy);
                if (d > 1f) {
                    dx /= d;
                    dy /= d;
                }
                kx = dx;
                ky = dy;
                invalidate();
                // Vers le haut de l'écran = avancer.
                if (listener != null) listener.onMove(dx, -dy);
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                getParent().requestDisallowInterceptTouchEvent(false);
                release();
                return true;
            default:
                return super.onTouchEvent(e);
        }
    }

    private void release() {
        held = false;
        // L'arrêt d'abord, l'animation ensuite : elle n'est que visuelle.
        if (listener != null) listener.onRelease();
        final float sx = kx, sy = ky;
        if (back != null) back.cancel();
        back = ValueAnimator.ofFloat(1f, 0f);
        back.setDuration(180);
        back.setInterpolator(new DecelerateInterpolator(1.6f));
        back.addUpdateListener(a -> {
            float f = (float) a.getAnimatedValue();
            kx = sx * f;
            ky = sy * f;
            invalidate();
        });
        back.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (back != null) back.cancel();
        super.onDetachedFromWindow();
    }
}
