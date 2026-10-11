package fr.rayor.cosmorobot;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.RippleDrawable;
import android.view.View;

/**
 * Pastille de couleur des LEDs du dos. Un anneau à l'encre marque la couleur
 * choisie ; « éteint » est une pastille creuse. Ce sont les seules couleurs de
 * l'interface : elles disent ce que le robot va afficher.
 */
final class Swatch extends View {
    private final int color;
    private final boolean off;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hollow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private boolean chosen;

    Swatch(Context c, int color, boolean off, String name) {
        super(c);
        this.color = color;
        this.off = off;
        fill.setColor(off ? Ui.SURFACE : color);
        hollow.setStyle(Paint.Style.STROKE);
        hollow.setStrokeWidth(Ui.dp(c, 1.5f));
        hollow.setColor(Ui.INK_FAINT);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(Ui.dp(c, 2));
        ring.setColor(Ui.INK);
        setContentDescription(name);
        setClickable(true);
        setFocusable(true);
        // Retour tactile circulaire, sans masque : la vue est plus large que la pastille.
        setBackground(new RippleDrawable(ColorStateList.valueOf(Ui.RIPPLE), null, null));
    }

    void setChosen(boolean on) {
        if (chosen == on) return;
        chosen = on;
        setSelected(on);
        invalidate();
    }

    @Override
    protected void onMeasure(int w, int h) {
        setMeasuredDimension(MeasureSpec.getSize(w), Ui.dp(getContext(), 48));
    }

    @Override
    protected void onDraw(Canvas c) {
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        float r = Math.min(Ui.dp(getContext(), 14), Math.min(cx, cy) - Ui.dp(getContext(), 6));
        c.drawCircle(cx, cy, r, fill);
        if (off) c.drawCircle(cx, cy, r - hollow.getStrokeWidth() / 2f, hollow);
        if (chosen) c.drawCircle(cx, cy, r + Ui.dp(getContext(), 4.5f), ring);
    }

    @Override
    public void setEnabled(boolean on) {
        super.setEnabled(on);
        setAlpha(on ? 1f : Ui.DISABLED_ALPHA);
    }
}
