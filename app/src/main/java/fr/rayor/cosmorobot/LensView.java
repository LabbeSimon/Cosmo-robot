package fr.rayor.cosmorobot;

import android.content.Context;
import android.graphics.Bitmap;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

/**
 * L'écran de la caméra de Cozmo, à ses propres proportions (320 × 240). Même
 * traitement que le miroir des lunettes dans G1 Extended : un fond presque noir
 * cerclé du ton de la tuile, coins de 10 dp. Sans image, il dit pourquoi
 * (caméra éteinte, en attente, liaison perdue) et propose de l'allumer.
 */
final class LensView extends Ui.Aspect {
    /** Au-delà, l'image affichée est considérée comme figée. */
    private static final long STALE_MS = 2500;

    private final ImageView image;
    private final LinearLayout placeholder;
    private final TextView message;
    private final ProgressBar spinner;
    private final TextView start;
    private final TextView stop;
    private Bitmap current;
    private long lastFrameAt;

    LensView(Context c, View.OnClickListener onToggle) {
        super(c, 4f / 3f);
        setBackground(Ui.outline(c, Ui.GROUND, Ui.TILE, Ui.SCREEN_RADIUS));
        Ui.clipRounded(this, Ui.SCREEN_RADIUS);

        image = new ImageView(c);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        addView(image, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        placeholder = Ui.col(c);
        placeholder.setGravity(Gravity.CENTER_HORIZONTAL);
        spinner = Ui.spinner(c);
        LinearLayout.LayoutParams sp = (LinearLayout.LayoutParams) spinner.getLayoutParams();
        sp.bottomMargin = Ui.dp(c, 12);
        placeholder.addView(spinner, sp);
        message = Ui.mono(c, "", 12, Ui.INK_MUTED);
        message.setLetterSpacing(0.1f);
        message.setGravity(Gravity.CENTER);
        placeholder.addView(message, Ui.wrap());
        start = Ui.outlined(c, R.string.camera_start);
        start.setTextSize(13);
        start.setMinHeight(Ui.dp(c, 40));
        start.setPadding(Ui.dp(c, 16), Ui.dp(c, 9), Ui.dp(c, 16), Ui.dp(c, 9));
        start.setOnClickListener(onToggle);
        LinearLayout.LayoutParams bp = Ui.wrap();
        bp.topMargin = Ui.dp(c, 14);
        placeholder.addView(start, bp);
        addView(placeholder, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        // Sur l'image : une pastille sombre et discrète pour couper la caméra.
        stop = Ui.mono(c, c.getString(R.string.camera_stop).toUpperCase(), 11, Ui.INK);
        stop.setLetterSpacing(0.1f);
        stop.setBackground(Ui.ripple(c, Ui.rounded(c, 0xB3050506, Ui.CHIP_RADIUS), Ui.CHIP_RADIUS));
        stop.setPadding(Ui.dp(c, 10), Ui.dp(c, 7), Ui.dp(c, 10), Ui.dp(c, 7));
        stop.setClickable(true);
        stop.setFocusable(true);
        stop.setOnClickListener(onToggle);
        LayoutParams lp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.END);
        int m = Ui.dp(c, 10);
        lp.setMargins(m, m, m, m);
        addView(stop, lp);
    }

    /** Appelé sur le fil de l'interface, image déjà décodée. */
    void showFrame(Bitmap b) {
        current = b;
        lastFrameAt = System.currentTimeMillis();
        image.setImageBitmap(b);
    }

    /**
     * Met l'écran en accord avec l'état du robot.
     *
     * @param live      liaison établie
     * @param wanted    l'utilisateur veut la caméra
     * @param on        le robot dit l'envoyer
     * @param linkLabel texte à afficher quand la liaison est perdue
     */
    void render(boolean live, boolean wanted, boolean on, String linkLabel) {
        boolean fresh = current != null && System.currentTimeMillis() - lastFrameAt < STALE_MS;
        boolean streaming = live && wanted && on && fresh;
        image.setAlpha(streaming ? 1f : 0.25f);
        // Caméra coupée : on lâche la dernière image plutôt que de la laisser
        // traîner en fond comme si elle était actuelle.
        if (!wanted) clear();
        stop.setVisibility(streaming ? VISIBLE : GONE);
        placeholder.setVisibility(streaming ? GONE : VISIBLE);
        if (streaming) return;

        if (!live) {
            show(linkLabel);
            spinner.setVisibility(VISIBLE);
            start.setVisibility(GONE);
        } else if (!wanted) {
            show(getContext().getString(R.string.camera_off));
            spinner.setVisibility(GONE);
            start.setText(R.string.camera_start);
            start.setVisibility(VISIBLE);
        } else {
            // En attente : le bouton reste là, pour renoncer.
            show(getContext().getString(R.string.camera_waiting));
            spinner.setVisibility(VISIBLE);
            start.setText(R.string.camera_stop);
            start.setVisibility(VISIBLE);
        }
    }

    /** Écrit seulement si le texte change : render() tourne deux fois par seconde. */
    private void show(String text) {
        String up = text.toUpperCase();
        if (!up.contentEquals(message.getText())) message.setText(up);
    }

    /** Lâche l'image (déconnexion) pour ne pas réafficher une vue périmée. */
    void clear() {
        current = null;
        image.setImageDrawable(null);
    }
}
