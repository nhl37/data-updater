package updater.ui;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ScrollPaneConstants;
import javax.swing.Scrollable;
import javax.swing.border.AbstractBorder;
import javax.swing.border.Border;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Insets;
import java.awt.LayoutManager;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Window;
import java.awt.event.ActionListener;
import java.awt.geom.RoundRectangle2D;

/**
 * 拼界面用的几个小方法：白底卡片、淡描边、留白宽松一点，控件仍是 Swing 自带的那几样。
 *
 * <p>没有成套的间距表，也没有自定义控件体系；只有一张共用的圆角边，让分区看起来
 * 干净些。哪块松一点紧一点都不影响用。</p>
 */
public final class UpdaterKit {

    private UpdaterKit() {
    }

    // ---------------- 卡片边框 ----------------

    /** 所有分区共用的一张淡描边圆角卡；内容面板自己保持不透明，边框才看得出来 */
    public static Border cardBorder() {
        return new AbstractBorder() {
            @Override
            public void paintBorder(Component c, Graphics g, int x, int y, int width, int height) {
                Graphics2D g2 = (Graphics2D) g.create();
                try {
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                            RenderingHints.VALUE_ANTIALIAS_ON);
                    g2.setColor(UpdaterTheme.BORDER);
                    g2.draw(new RoundRectangle2D.Float(x + 0.5f, y + 0.5f,
                            width - 1f, height - 1f, UpdaterTheme.ARC, UpdaterTheme.ARC));
                } finally {
                    g2.dispose();
                }
            }

            @Override
            public Insets getBorderInsets(Component c) {
                return new Insets(10, 12, 10, 12);
            }

            @Override
            public boolean isBorderOpaque() {
                return false;
            }
        };
    }

    // ---------------- 分区 ----------------

    /** 普通加粗标题，没有编号、装饰块和横线。 */
    public static JComponent sectionTitle(String text) {
        JLabel label = new JLabel(text);
        label.setFont(UpdaterTheme.bold(13f));
        label.setForeground(UpdaterTheme.TEXT_STRONG);
        return label;
    }

    public static JComponent sectionTitle(String text, int number) {
        return sectionTitle(text);
    }

    public static JLabel hint(String text) {
        JLabel label = new JLabel(text);
        label.setFont(UpdaterTheme.regular(11f));
        label.setForeground(UpdaterTheme.MUTED);
        return label;
    }

    public static JPanel section(String title, String hint, JComponent body) {
        return section(title, hint == null ? null : hint(hint), body, false);
    }

    public static JPanel section(String title, JComponent right, JComponent body) {
        return section(title, right, body, false);
    }

    public static JPanel compactSection(String title, JComponent right, JComponent body) {
        return section(title, right, body, true);
    }

    public static JPanel compactSection(String title, String hint, JComponent body) {
        return compactSection(title, hint == null ? null : hint(hint), body);
    }

    private static JPanel section(String title, JComponent right, JComponent body, boolean tight) {
        JPanel panel = new JPanel(new BorderLayout(0, tight ? 6 : 9));
        panel.setOpaque(true);
        panel.setBackground(UpdaterTheme.CARD);
        panel.setBorder(cardBorder());

        JPanel header = new JPanel(new BorderLayout(8, 0));
        header.setOpaque(false);
        JLabel label = new JLabel(title);
        label.setFont(UpdaterTheme.bold(12f));
        label.setForeground(UpdaterTheme.TEXT_STRONG);
        header.add(label, BorderLayout.WEST);
        if (right != null) {
            header.add(right, BorderLayout.EAST);
        }
        header.setBorder(new EmptyBorder(0, 0, tight ? 5 : 7, 0));
        panel.add(header, BorderLayout.NORTH);

        body.setOpaque(false);
        panel.add(body, BorderLayout.CENTER);
        return panel;
    }

    // ---------------- 按钮 ----------------

    public static JButton button(String text) {
        return button(text, null);
    }

    public static JButton button(String text, ActionListener action) {
        JButton button = new JButton(text);
        styleButton(button);
        if (action != null) {
            button.addActionListener(action);
        }
        return button;
    }

    public static void styleButton(JButton button) {
        if (button != null) {
            button.setFont(UpdaterTheme.regular(12f));
            button.setForeground(UpdaterTheme.TEXT);
            button.setBackground(UpdaterTheme.CARD);
            button.setFocusPainted(false);
        }
    }

    public static JButton primaryButton(String text) {
        return primaryButton(text, null);
    }

    /** 主操作用淡色底加粗字，不做整屏实心高亮。 */
    public static JButton primaryButton(String text, ActionListener action) {
        JButton button = button(text, action);
        stylePrimaryButton(button);
        return button;
    }

    public static void stylePrimaryButton(JButton button) {
        if (button != null) {
            styleButton(button);
            button.setFont(UpdaterTheme.bold(12f));
            button.setForeground(UpdaterTheme.ACCENT);
            button.setBackground(UpdaterTheme.ACCENT_SOFT);
        }
    }

    // ---------------- 表格 ----------------

    public static void styleTable(JTable table) {
        table.setRowHeight(UpdaterTheme.ROW_HEIGHT);
        table.getTableHeader().setReorderingAllowed(false);
        table.getTableHeader().setFont(UpdaterTheme.bold(11f));
        table.setShowHorizontalLines(false);
        table.setShowVerticalLines(false);
        table.setIntercellSpacing(new Dimension(0, 0));
        table.setSelectionBackground(UpdaterTheme.ACCENT_SOFT);
        table.setSelectionForeground(UpdaterTheme.TEXT_STRONG);
    }

    public static JScrollPane tableScroll(JComponent view) {
        return new JScrollPane(view);
    }

    public static void styleTableScroll(JScrollPane scroll) {
        if (scroll != null) {
            scroll.setBorder(BorderFactory.createLineBorder(UpdaterTheme.RULE));
        }
    }

    /** 底部状态栏：一条淡线上方留白，不再用老式下凹边。 */
    public static JPanel statusBar() {
        JPanel bar = new JPanel(new BorderLayout(8, 0));
        bar.setBackground(UpdaterTheme.SURFACE);
        bar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, UpdaterTheme.RULE),
                new EmptyBorder(7, 12, 7, 12)));
        return bar;
    }

    // ---------------- 滚动页 ----------------

    public static JScrollPane pageScroll(JComponent content) {
        ScrollPage page = new ScrollPage(new BorderLayout());
        page.setOpaque(false);
        page.add(content, BorderLayout.CENTER);
        JScrollPane scroll = new JScrollPane(page,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.getVerticalScrollBar().setUnitIncrement(24);
        return scroll;
    }

    private static final class ScrollPage extends JPanel implements Scrollable {
        ScrollPage(LayoutManager layout) {
            super(layout);
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
            return 24;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
            return Math.max(80, visibleRect.height - 40);
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            Container parent = getParent();
            return parent instanceof javax.swing.JViewport
                    && getPreferredSize().height <= parent.getHeight();
        }
    }

    // ---------------- 占位输入框 ----------------

    public static JTextField ghostField(String placeholder, int columns) {
        return new GhostField(placeholder, columns);
    }

    private static final class GhostField extends JTextField {
        private final String placeholder;

        GhostField(String placeholder, int columns) {
            super(columns);
            this.placeholder = placeholder == null ? "" : placeholder;
            setFont(UpdaterTheme.regular(12f));
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (!getText().isEmpty() || placeholder.isEmpty()) {
                return;
            }
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setColor(UpdaterTheme.MUTED);
                g2.setFont(UpdaterTheme.regular(11f));
                FontMetrics fm = g2.getFontMetrics();
                g2.drawString(placeholder, 6, (getHeight() - fm.getHeight()) / 2 + fm.getAscent());
            } finally {
                g2.dispose();
            }
        }
    }

    // ---------------- 窗口尺寸 ----------------

    public static void fitToScreen(Window window, int wantWidth, int wantHeight,
                                   int minWidth, int minHeight) {
        fitSize(window, wantWidth, wantHeight, minWidth, minHeight);
        window.setLocationRelativeTo(window.getOwner());
        Rectangle area = GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getMaximumWindowBounds();
        Rectangle bounds = window.getBounds();
        int x = Math.min(Math.max(bounds.x, area.x), area.x + area.width - bounds.width);
        int y = Math.min(Math.max(bounds.y, area.y), area.y + area.height - bounds.height);
        window.setLocation(x, y);
    }

    public static void fitSize(Window window, int wantWidth, int wantHeight,
                               int minWidth, int minHeight) {
        ScreenFit fit = fitFor(GraphicsEnvironment.getLocalGraphicsEnvironment()
                        .getMaximumWindowBounds(),
                wantWidth, wantHeight, minWidth, minHeight);
        window.setMinimumSize(new Dimension(fit.minWidth, fit.minHeight));
        window.setSize(fit.width, fit.height);
    }

    public static ScreenFit fitFor(Rectangle area, int wantWidth, int wantHeight,
                                   int minWidth, int minHeight) {
        int maxWidth = Math.max(400, area.width - 32);
        int maxHeight = Math.max(320, area.height - 72);
        return new ScreenFit(Math.min(wantWidth, maxWidth), Math.min(wantHeight, maxHeight),
                Math.min(minWidth, maxWidth), Math.min(minHeight, maxHeight));
    }

    public static final class ScreenFit {
        public final int width;
        public final int height;
        public final int minWidth;
        public final int minHeight;

        ScreenFit(int width, int height, int minWidth, int minHeight) {
            this.width = width;
            this.height = height;
            this.minWidth = minWidth;
            this.minHeight = minHeight;
        }

        @Override
        public String toString() {
            return width + "×" + height + "（最小 " + minWidth + "×" + minHeight + "）";
        }
    }

    public static String ellipsizeMiddle(String text, FontMetrics fm, int maxWidth) {
        if (text == null || text.isEmpty() || maxWidth <= 0) {
            return text == null ? "" : text;
        }
        if (fm.stringWidth(text) <= maxWidth) {
            return text;
        }
        String ellipsis = "…";
        int budget = maxWidth - fm.stringWidth(ellipsis);
        if (budget <= 0) {
            return ellipsis;
        }
        int head = budget / 2;
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < text.length() && fm.stringWidth(out.toString()) < head; i++) {
            out.append(text.charAt(i));
        }
        String prefix = out.toString();
        StringBuilder tail = new StringBuilder();
        for (int i = text.length() - 1; i >= 0
                && fm.stringWidth(prefix + ellipsis + tail) < maxWidth; i--) {
            tail.insert(0, text.charAt(i));
        }
        return prefix + ellipsis + tail;
    }

    /** 状态就是一行普通文字，没有方括号徽标。 */
    public static JLabel badge(String text, Color color) {
        JLabel label = new JLabel(text);
        label.setFont(UpdaterTheme.regular(11f));
        label.setForeground(color);
        return label;
    }

    public static JComponent rule() {
        JPanel line = new JPanel();
        line.setBackground(UpdaterTheme.RULE);
        line.setPreferredSize(new Dimension(1, 1));
        return line;
    }
}
