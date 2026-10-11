package fr.rayor.cosmorobot;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

/**
 * Jauge de batterie sur grille de pixels entiers, reprise de la BatteryGauge de
 * G1 Extended : corps de 17 colonnes aux coins crantés, borne de 2 colonnes,
 * intérieur de 13 × 5. Le niveau se lit en quatre barres plutôt qu'en un
 * remplissage continu (à cette taille, 60 et 70 % ne diffèrent que d'un pixel ;
 * des barres se comptent). En charge, l'éclair remplace les barres. Sous 15 %,
 * ou niveau inconnu, la jauge passe en gris : un avertissement discret.
 */
final class BatteryGauge extends View {
    private static final int COLS = 20, ROWS = 9, BARS = 4, LOW = 15;
    private static final String[] BOLT = {
            "......###....",
            ".....###.....",
            "...######....",
            ".....###.....",
            "....###......",
    };

    private final Paint ink = new Paint();
    private final int cell;
    private int pct = -1;
    private boolean charging;

    BatteryGauge(Context c) {
        super(c);
        // Pas d'anticrénelage : ce sont des carrés, et en adoucir les bords
        // ferait apparaître des coutures entre cellules voisines.
        ink.setAntiAlias(false);
        cell = Math.max(1, Math.round(c.getResources().getDisplayMetrics().density));
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    /** pct dans 0..100, ou -1 si inconnu. */
    void set(int pct, boolean charging) {
        if (pct == this.pct && charging == this.charging) return;
        this.pct = pct;
        this.charging = charging;
        invalidate();
    }

    @Override
    protected void onMeasure(int w, int h) {
        setMeasuredDimension(cell * COLS, cell * ROWS);
    }

    @Override
    protected void onDraw(Canvas c) {
        boolean known = pct >= 0;
        boolean hollow = !known || pct <= LOW;
        ink.setColor(hollow ? Ui.INK_MUTED : Ui.INK);
        int lit = !known || pct <= 0 ? 0 : Math.max(1, Math.min(BARS, (int) Math.ceil(pct / 100.0 * BARS)));
        // Des segments plutôt que des cellules : un rectangle par suite
        // horizontale, toujours pour éviter les coutures.
        for (int r = 0; r < ROWS; r++) {
            int col = 0;
            while (col < COLS) {
                if (!on(col, r, lit)) {
                    col++;
                    continue;
                }
                int end = col;
                while (end + 1 < COLS && on(end + 1, r, lit)) end++;
                c.drawRect(col * cell, r * cell, (end + 1) * cell, (r + 1) * cell, ink);
                col = end + 1;
            }
        }
    }

    private boolean on(int col, int r, int lit) {
        if (col >= 18) return r >= 3 && r <= 5;   // la borne, détachée d'une colonne
        if (col == 17) return false;
        boolean corner = (col == 0 || col == 16) && (r == 0 || r == 8);
        if (corner) return false;
        if (r == 0 || r == 8 || col == 0 || col == 16) return true;
        boolean interior = col >= 2 && col <= 14 && r >= 2 && r <= 6;
        if (!interior) return false;
        if (charging) return BOLT[r - 2].charAt(col - 2) == '#';
        int within = col - 3;
        if (within < 0) return false;
        return within / 3 < lit && within % 3 < 2;
    }
}
