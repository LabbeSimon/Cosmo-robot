package fr.rayor.cosmorobot;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Petits outils d'interface : l'appli n'a ni XML de mise en page ni AndroidX. */
final class Ui {
    static final int BG = 0xFF0F1317;
    static final int CARD = 0xFF181E25;
    static final int CARD2 = 0xFF222A33;
    static final int TEXT = 0xFFE9EEF3;
    static final int MUTED = 0xFF8C98A4;
    static final int ACCENT = 0xFFE5484D;
    static final int OK = 0xFF46A758;
    static final int WARN = 0xFFF5A524;

    private Ui() { }

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    static LinearLayout col(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    static GradientDrawable rounded(Context c, int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(c, radiusDp));
        return g;
    }

    static LinearLayout card(Context c) {
        LinearLayout l = col(c);
        l.setBackground(rounded(c, CARD, 16));
        int p = dp(c, 16);
        l.setPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(c, 12);
        l.setLayoutParams(lp);
        return l;
    }

    static TextView text(Context c, CharSequence s, float sp, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        return t;
    }

    static TextView title(Context c, int res) {
        TextView t = text(c, c.getString(res), 17, TEXT);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, 0, 0, dp(c, 8));
        return t;
    }

    static Button button(Context c, int res, boolean primary) {
        Button b = new Button(c);
        b.setText(res);
        b.setAllCaps(false);
        b.setTextColor(primary ? 0xFFFFFFFF : TEXT);
        b.setBackground(rounded(c, primary ? ACCENT : CARD2, 12));
        b.setMinHeight(dp(c, 48));
        return b;
    }

    static LinearLayout.LayoutParams weight(float w) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, w);
        return lp;
    }

    static LinearLayout.LayoutParams margins(Context c, int top, int right) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(c, top);
        lp.rightMargin = dp(c, right);
        return lp;
    }

    /** Cadre au format de la caméra de Cozmo (320×240). */
    static final class Aspect extends FrameLayout {
        Aspect(Context c) {
            super(c);
        }

        @Override
        protected void onMeasure(int w, int h) {
            int width = MeasureSpec.getSize(w);
            super.onMeasure(w, MeasureSpec.makeMeasureSpec(width * 3 / 4, MeasureSpec.EXACTLY));
        }
    }
}
