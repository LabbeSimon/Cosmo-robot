package fr.rayor.cosmorobot;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;

/** Joystick analogique : x = rotation, y = avance, tous deux dans -1..1. */
final class JoystickView extends View {
    interface Listener {
        void onMove(float x, float y);
        void onRelease();
    }

    private final Paint base = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint knob = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float kx, ky;          // position du bouton, en fraction du rayon
    private Listener listener;

    JoystickView(Context ctx) {
        super(ctx);
        base.setColor(Ui.CARD2);
        ring.setColor(Ui.MUTED);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(Ui.dp(ctx, 2));
        knob.setColor(Ui.ACCENT);
    }

    void setListener(Listener l) {
        listener = l;
    }

    @Override
    protected void onMeasure(int w, int h) {
        int size = Math.min(MeasureSpec.getSize(w), Ui.dp(getContext(), 240));
        setMeasuredDimension(MeasureSpec.getSize(w), size);
    }

    private float radius() {
        return Math.min(getWidth(), getHeight()) / 2f - Ui.dp(getContext(), 8);
    }

    @Override
    protected void onDraw(Canvas c) {
        float cx = getWidth() / 2f, cy = getHeight() / 2f, r = radius();
        c.drawCircle(cx, cy, r, base);
        c.drawCircle(cx, cy, r * 0.5f, ring);
        c.drawCircle(cx + kx * r, cy + ky * r, r * 0.3f, knob);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                // Sinon le ScrollView parent vole le geste dès qu'on glisse.
                getParent().requestDisallowInterceptTouchEvent(true);
                // fall through
            case MotionEvent.ACTION_MOVE: {
                float r = radius();
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
                kx = ky = 0f;
                invalidate();
                if (listener != null) listener.onRelease();
                return true;
            default:
                return super.onTouchEvent(e);
        }
    }
}
