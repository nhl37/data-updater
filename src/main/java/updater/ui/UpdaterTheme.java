package updater.ui;

import java.awt.Color;
import java.awt.Font;

/**
 * 界面用到的几个颜色和字号。
 *
 * <p>整体是暖白底 + 一点黛青，状态色都压低了饱和度；够清爽，但没做成一整套
 * 色板，也没给控件编编号。字体沿用外观默认字，标题只是放大加粗。</p>
 */
public final class UpdaterTheme {

    private UpdaterTheme() {
    }

    public static final Color BG = Color.decode("#F5F4F1");
    public static final Color SURFACE = Color.WHITE;
    public static final Color CARD = Color.WHITE;
    public static final Color RAISED = Color.decode("#ECEBE6");
    public static final Color BORDER = Color.decode("#E1DED7");
    public static final Color RULE = Color.decode("#E8E5DF");
    public static final Color RULE_STRONG = Color.decode("#CEC9BF");

    public static final Color INK = Color.decode("#2C2E2D");
    public static final Color INK_SOFT = Color.decode("#3B3E3C");
    public static final Color TEXT = Color.decode("#41443F");
    public static final Color TEXT_STRONG = Color.decode("#2C2E2D");
    public static final Color FAINT = Color.decode("#8B8B84");
    public static final Color MUTED = Color.decode("#7C807A");

    public static final Color SIGNAL = Color.decode("#3C6E7F");
    public static final Color ACCENT = Color.decode("#3C6E7F");
    public static final Color ACCENT_SOFT = Color.decode("#E7F0F1");
    public static final Color OK = Color.decode("#2F7A5A");
    public static final Color UP = Color.decode("#2F7A5A");
    public static final Color FAIL = Color.decode("#B4544C");
    public static final Color PENDING = Color.decode("#94958E");

    public static final Color WARN_BG = Color.decode("#FAF4E6");
    public static final Color WARN_BORDER = Color.decode("#E6D8B8");
    public static final Color WARN_TEXT = Color.decode("#7A6A3E");

    public static final int GUTTER = 12;
    public static final int ROW_HEIGHT = 24;
    /** 卡片与按钮的圆角半径 */
    public static final int ARC = 10;

    private static Font base;

    /** 取一次外观默认字就缓存住，取不到用系统无衬线。 */
    private static synchronized Font defaults() {
        if (base == null) {
            Font got = null;
            try {
                got = javax.swing.UIManager.getFont("Label.font");
            } catch (Exception ignored) {
            }
            base = got == null ? new Font(Font.SANS_SERIF, Font.PLAIN, 12) : got;
        }
        return base;
    }

    public static Font font(int style, float size) {
        return defaults().deriveFont(style, size);
    }

    public static Font regular(float size) {
        return font(Font.PLAIN, size);
    }

    public static Font bold(float size) {
        return font(Font.BOLD, size);
    }
}
