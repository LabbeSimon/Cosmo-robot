package fr.rayor.cosmorobot;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

/**
 * Le langage visuel de l'appli, au même endroit : celui de G1 Extended
 * (lib/theme/app_theme.dart et lib/widgets/bento.dart), transposé en vues
 * Android construites en code, sans XML de mise en page ni AndroidX.
 *
 * Sombre et strictement monochrome : anthracite plutôt que noir pur, tuiles un
 * ton plus clair que la page pour que la grille se lise sans bordure ni ombre,
 * aucune couleur d'accent. Les seules couleurs à l'écran sont celles du
 * contenu : l'image de la caméra et les pastilles des LEDs.
 */
final class Ui {
    // ------------------------------------------------------------- palette
    /** Fond de page. */
    static final int SURFACE = 0xFF1C1C1E;
    /** Tuile : plus claire que la page, jamais plus sombre. */
    static final int TILE = 0xFF2B2B2D;
    /** Tuile active ou mise en avant ; aussi le remplissage des curseurs. */
    static final int TILE_ACTIVE = 0xFF3A3A3C;
    /** Texte principal. */
    static final int INK = 0xFFF5F5F7;
    /** Texte secondaire : libellés, unités. */
    static final int INK_MUTED = 0xFF8E8E93;
    /** Contenu désactivé ou indicatif. */
    static final int INK_FAINT = 0xFF5A5A5E;
    /** Trame de points dessinée sur la tuile principale. */
    static final int MATRIX = 0xFF343436;
    /** Fond des « écrans » : la caméra, le visage de Cozmo. */
    static final int GROUND = 0xFF050506;
    /** Voile du retour tactile. */
    static final int RIPPLE = 0x22FFFFFF;

    // Anciens noms, gardés pour ne rien casser chez qui s'en servirait.
    static final int BG = SURFACE;
    static final int CARD = TILE;
    static final int CARD2 = TILE_ACTIVE;
    static final int TEXT = INK;
    static final int MUTED = INK_MUTED;

    // ------------------------------------------------------------- mesures
    static final float RADIUS = 14;
    static final float GUTTER = 10;
    static final float TILE_PAD = 18;
    static final float PAGE_PAD = 14;
    static final float SCREEN_RADIUS = 10;
    static final float CHIP_RADIUS = 8;

    /** Opacité d'une commande inactive. */
    static final float DISABLED_ALPHA = 0.38f;

    // ------------------------------------------------------------- polices
    /** La police « technique » : chiffres, relevés, en-têtes espacés. */
    static final Typeface MONO = Typeface.MONOSPACE;
    static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    static final Typeface REGULAR = Typeface.create("sans-serif", Typeface.NORMAL);

    private Ui() { }

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    static float sp(Context c, float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, c.getResources().getDisplayMetrics());
    }

    // ------------------------------------------------------------- dispositions
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

    static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    static LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    static LinearLayout.LayoutParams weight(float w) {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, w);
    }

    /** Marges en dp, dans l'ordre haut, droite. */
    static LinearLayout.LayoutParams margins(Context c, int top, int right) {
        LinearLayout.LayoutParams lp = matchWrap();
        lp.topMargin = dp(c, top);
        lp.rightMargin = dp(c, right);
        return lp;
    }

    static LinearLayout.LayoutParams topMargin(Context c, float top) {
        LinearLayout.LayoutParams lp = matchWrap();
        lp.topMargin = dp(c, top);
        return lp;
    }

    static View space(Context c, float w, float h) {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(c, w), dp(c, h)));
        return v;
    }

    /** Ressort horizontal : pousse ce qui suit vers la droite. */
    static View spring(Context c) {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1f));
        return v;
    }

    // ------------------------------------------------------------- fonds
    static GradientDrawable rounded(Context c, int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(c, radiusDp));
        return g;
    }

    static GradientDrawable outline(Context c, int fill, int stroke, float radiusDp) {
        GradientDrawable g = rounded(c, fill, radiusDp);
        g.setStroke(Math.max(1, dp(c, 1)), stroke);
        return g;
    }

    /** Fond touchable : la couleur au repos, le voile clair sous le doigt. */
    static Drawable ripple(Context c, Drawable content, float radiusDp) {
        return new RippleDrawable(ColorStateList.valueOf(RIPPLE), content, rounded(c, 0xFFFFFFFF, radiusDp));
    }

    /** Découpe la vue selon ses coins arrondis (image de la caméra comprise). */
    static void clipRounded(View v, float radiusDp) {
        final float r = dp(v.getContext(), radiusDp);
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline o) {
                o.setRoundRect(0, 0, view.getWidth(), view.getHeight(), r);
            }
        });
        v.setClipToOutline(true);
    }

    // ------------------------------------------------------------- textes
    static TextView text(Context c, CharSequence s, float sp, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(REGULAR);
        t.setIncludeFontPadding(false);
        t.setLineSpacing(0, 1.2f);
        return t;
    }

    /** Relevé dans la police technique, comme sur l'afficheur des lunettes. */
    static TextView mono(Context c, CharSequence s, float sp, int color) {
        TextView t = text(c, s, sp, color);
        t.setTypeface(MONO);
        return t;
    }

    /** Titre de tuile (titleMedium de G1 Extended : 17, graisse 500). */
    static TextView title(Context c, CharSequence s) {
        TextView t = text(c, s, 17, INK);
        t.setTypeface(MEDIUM);
        return t;
    }

    static TextView title(Context c, int res) {
        return title(c, c.getString(res));
    }

    static TextView body(Context c, CharSequence s) {
        return text(c, s, 14, INK);
    }

    /** bodySmall : 12, gris. */
    static TextView small(Context c, CharSequence s) {
        return text(c, s, 12, INK_MUTED);
    }

    /** En-tête de section : capitales espacées, 12, graisse 600. */
    static TextView section(Context c, int res) {
        TextView t = text(c, c.getString(res).toUpperCase(), 12, INK);
        t.setTypeface(MEDIUM);
        t.setLetterSpacing(0.1f);
        t.setPadding(dp(c, 4), dp(c, 22), dp(c, 4), dp(c, 10));
        return t;
    }

    /** Pastille d'état sous l'écran : capitales en police technique, cerclée. */
    static TextView chip(Context c, CharSequence s) {
        TextView t = mono(c, s.toString().toUpperCase(), 11, INK_MUTED);
        t.setLetterSpacing(0.1f);
        t.setBackground(outline(c, 0x00000000, INK_FAINT, CHIP_RADIUS));
        t.setPadding(dp(c, 10), dp(c, 5), dp(c, 10), dp(c, 5));
        return t;
    }

    // ------------------------------------------------------------- tuiles
    /** Tuile Bento : plate, la couleur seule la détache de la page. */
    static LinearLayout tile(Context c) {
        LinearLayout l = col(c);
        l.setBackground(rounded(c, TILE, RADIUS));
        int p = dp(c, TILE_PAD);
        l.setPadding(p, p, p, p);
        l.setLayoutParams(matchWrap());
        return l;
    }

    /** Ancien nom de {@link #tile}. */
    static LinearLayout card(Context c) {
        LinearLayout l = tile(c);
        LinearLayout.LayoutParams lp = matchWrap();
        lp.bottomMargin = dp(c, GUTTER);
        l.setLayoutParams(lp);
        return l;
    }

    // ------------------------------------------------------------- boutons
    /**
     * Bouton plein (FilledButton de G1 Extended) : fond « tuile active », rayon
     * 14. Un TextView plutôt qu'un Button : le thème Material du système y
     * ajouterait capitales, élévation et animation d'état qui ne sont pas d'ici.
     */
    static TextView filled(Context c, CharSequence label) {
        TextView b = text(c, label, 15, INK);
        b.setTypeface(MEDIUM);
        b.setGravity(Gravity.CENTER);
        b.setBackground(ripple(c, rounded(c, TILE_ACTIVE, RADIUS), RADIUS));
        int v = dp(c, 16);
        b.setPadding(dp(c, 18), v, dp(c, 18), v);
        b.setMinHeight(dp(c, 52));
        b.setClickable(true);
        b.setFocusable(true);
        return b;
    }

    static TextView filled(Context c, int res) {
        return filled(c, c.getString(res));
    }

    /** Bouton cerclé (OutlinedButton de G1 Extended) : l'action secondaire. */
    static TextView outlined(Context c, CharSequence label) {
        TextView b = text(c, label, 15, INK_MUTED);
        b.setTypeface(MEDIUM);
        b.setGravity(Gravity.CENTER);
        b.setBackground(ripple(c, outline(c, 0x00000000, TILE_ACTIVE, RADIUS), RADIUS));
        int v = dp(c, 14);
        b.setPadding(dp(c, 18), v, dp(c, 18), v);
        b.setMinHeight(dp(c, 48));
        b.setClickable(true);
        b.setFocusable(true);
        return b;
    }

    static TextView outlined(Context c, int res) {
        return outlined(c, c.getString(res));
    }

    /** Ancienne signature, conservée. */
    static TextView button(Context c, int res, boolean primary) {
        return primary ? filled(c, res) : outlined(c, res);
    }

    /** Active ou grise une commande, sans changer sa place dans la page. */
    static void enable(View v, boolean on) {
        v.setEnabled(on);
        v.setAlpha(on ? 1f : DISABLED_ALPHA);
    }

    /** Petit indicateur d'activité, 16 dp, gris, trait fin. */
    static ProgressBar spinner(Context c) {
        ProgressBar p = new ProgressBar(c, null, android.R.attr.progressBarStyleSmall);
        p.setIndeterminate(true);
        p.setIndeterminateTintList(ColorStateList.valueOf(INK_MUTED));
        int s = dp(c, 16);
        p.setLayoutParams(new LinearLayout.LayoutParams(s, s));
        return p;
    }

    // ------------------------------------------------------------- saisie
    /** Champ de saisie : un puits plus sombre que la tuile qui le porte. */
    static EditText field(Context c, int hintRes) {
        EditText e = new EditText(c);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        e.setTextColor(INK);
        e.setHintTextColor(INK_FAINT);
        e.setHint(hintRes);
        e.setTypeface(REGULAR);
        e.setHighlightColor(0x55F5F5F7);
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_focused}, outline(c, SURFACE, INK_FAINT, SCREEN_RADIUS));
        bg.addState(new int[]{}, outline(c, SURFACE, SURFACE, SCREEN_RADIUS));
        e.setBackground(bg);
        int h = dp(c, 14);
        e.setPadding(h, dp(c, 13), h, dp(c, 13));
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            GradientDrawable cursor = new GradientDrawable();
            cursor.setColor(INK);
            cursor.setSize(dp(c, 2), 1);
            e.setTextCursorDrawable(cursor);
        }
        return e;
    }

    // ------------------------------------------------------------- trame
    /**
     * Trame de points discrète (DotMatrix de G1 Extended) : 6 dp de pas, points
     * de 0,6 dp. Contraste volontairement faible : elle doit se lire comme une
     * texture, pas comme un contenu. Peinte par un motif répété, pas point par
     * point.
     */
    static Paint matrixPaint(Context c) {
        float d = c.getResources().getDisplayMetrics().density;
        int step = Math.max(3, Math.round(6 * d));
        Bitmap cell = Bitmap.createBitmap(step, step, Bitmap.Config.ARGB_8888);
        Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        dot.setColor(MATRIX);
        new Canvas(cell).drawCircle(step / 2f, step / 2f, Math.max(0.8f, 0.6f * d), dot);
        Paint p = new Paint();
        p.setShader(new BitmapShader(cell, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT));
        return p;
    }

    /** Fond de tuile arrondi, avec la trame dans sa zone intérieure. */
    static final class MatrixTile extends Drawable {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dots;
        private final float radius;
        private final int inset;
        private final RectF box = new RectF();

        MatrixTile(Context c) {
            fill.setColor(TILE);
            dots = matrixPaint(c);
            radius = dp(c, RADIUS);
            inset = dp(c, PAGE_PAD);
        }

        @Override public void draw(Canvas canvas) {
            Rect b = getBounds();
            box.set(b);
            canvas.drawRoundRect(box, radius, radius, fill);
            canvas.drawRect(b.left + inset, b.top + inset, b.right - inset, b.bottom - inset, dots);
        }

        @Override public void setAlpha(int a) { }

        @Override public void setColorFilter(ColorFilter f) { }

        @Override
        @SuppressWarnings("deprecation")
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    // ------------------------------------------------------------- cadres
    /**
     * Cadre à proportions fixes (la caméra de Cozmo est en 320×240, son visage
     * en 128×32). Pilote la hauteur depuis la largeur ; si la hauteur est
     * bornée (écran en paysage), rétrécit pour tenir dedans.
     */
    static class Aspect extends FrameLayout {
        private final float ratio;

        Aspect(Context c) {
            this(c, 4f / 3f);
        }

        Aspect(Context c, float ratio) {
            super(c);
            this.ratio = ratio;
        }

        @Override
        protected void onMeasure(int ws, int hs) {
            int w = MeasureSpec.getSize(ws);
            int h = Math.round(w / ratio);
            int hMode = MeasureSpec.getMode(hs);
            int hMax = MeasureSpec.getSize(hs);
            if (hMode != MeasureSpec.UNSPECIFIED && h > hMax) {
                h = hMax;
                w = Math.round(h * ratio);
            }
            super.onMeasure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY));
        }
    }

    /** Rangée qui passe à la ligne : les pastilles d'état sur un écran étroit. */
    static final class Flow extends android.view.ViewGroup {
        private final int gap;

        Flow(Context c, float gapDp) {
            super(c);
            gap = dp(c, gapDp);
        }

        @Override
        protected void onMeasure(int ws, int hs) {
            int max = MeasureSpec.getSize(ws);
            boolean bounded = MeasureSpec.getMode(ws) != MeasureSpec.UNSPECIFIED;
            int x = 0, y = 0, line = 0, widest = 0;
            boolean any = false;
            for (int i = 0; i < getChildCount(); i++) {
                View ch = getChildAt(i);
                if (ch.getVisibility() == GONE) continue;
                measureChild(ch, ws, hs);
                int cw = ch.getMeasuredWidth(), chh = ch.getMeasuredHeight();
                if (x > 0 && bounded && x + cw > max) {
                    x = 0;
                    y += line + gap;
                    line = 0;
                }
                x += cw + gap;
                line = Math.max(line, chh);
                widest = Math.max(widest, x - gap);
                any = true;
            }
            setMeasuredDimension(resolveSize(widest, ws), resolveSize(any ? y + line : 0, hs));
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int max = r - l;
            int x = 0, y = 0, line = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View ch = getChildAt(i);
                if (ch.getVisibility() == GONE) continue;
                int cw = ch.getMeasuredWidth(), chh = ch.getMeasuredHeight();
                if (x > 0 && x + cw > max) {
                    x = 0;
                    y += line + gap;
                    line = 0;
                }
                ch.layout(x, y, x + cw, y + chh);
                x += cw + gap;
                line = Math.max(line, chh);
            }
        }
    }

    /** Colonne à largeur plafonnée : sur tablette, le contenu reste lisible. */
    static final class Column extends LinearLayout {
        private final int max;

        Column(Context c, float maxDp) {
            super(c);
            setOrientation(VERTICAL);
            max = dp(c, maxDp);
        }

        @Override
        protected void onMeasure(int ws, int hs) {
            if (MeasureSpec.getSize(ws) > max) ws = MeasureSpec.makeMeasureSpec(max, MeasureSpec.EXACTLY);
            super.onMeasure(ws, hs);
        }
    }
}
