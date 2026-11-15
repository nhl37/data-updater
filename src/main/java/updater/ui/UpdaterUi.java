package updater.ui;

import com.formdev.flatlaf.FlatLightLaf;

import javax.swing.UIManager;

/**
 * 装好外观，再顺手调几处圆角、间距和选中色。
 *
 * <p>只调这十来项，不铺一整套默认值表、不换字族、不动表头结构：这个程序
 * 只是把数据弄到本地，界面清爽、点得准就够了。</p>
 */
public final class UpdaterUi {

    private static boolean done = false;

    private UpdaterUi() {
    }

    public static synchronized void install() {
        if (done) {
            return;
        }
        done = true;
        try {
            FlatLightLaf.setup();
        } catch (Throwable t) {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {
            }
        }
        try {
            // 控件统一一点弧度，看着不扎眼；数值都给得小，保持原生控件的手感
            UIManager.put("Component.arc", UpdaterTheme.ARC);
            UIManager.put("Button.arc", UpdaterTheme.ARC);
            UIManager.put("ProgressBar.arc", 6);
            UIManager.put("TextComponent.arc", 6);
            UIManager.put("CheckBox.arc", 4);
            UIManager.put("Component.focusWidth", 1);
            UIManager.put("Component.innerFocusWidth", 0);
            UIManager.put("Table.rowHeight", UpdaterTheme.ROW_HEIGHT);
            UIManager.put("Table.selectionBackground", UpdaterTheme.ACCENT_SOFT);
            UIManager.put("Table.selectionForeground", UpdaterTheme.TEXT_STRONG);
            UIManager.put("ScrollBar.width", 10);
        } catch (Throwable ignored) {
            // 换不了就算了，界面照样能用
        }
    }
}
