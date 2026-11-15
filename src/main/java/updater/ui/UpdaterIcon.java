package updater.ui;

import javax.imageio.ImageIO;
import javax.swing.ImageIcon;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Window;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 窗口图标：暖白底上一个圆角小本子，三条横线，右下角一个绿点。
 *
 * <p>手绘的东西，但线条对齐、开了抗锯齿，缩到 16 像素也看得清。资源在
 * {@code /uicons/}，读不到就现画一份，按尺寸缓存。</p>
 */
public final class UpdaterIcon {

    private static final Color PAPER = new Color(0xF5, 0xF4, 0xF1);
    private static final Color FRAME = new Color(0x3C, 0x6E, 0x7F);
    private static final Color SCRIBBLE = new Color(0x6E, 0x74, 0x70);
    private static final Color DOT = new Color(0x2F, 0x7A, 0x5A);
    private static final int[] SIZES = {16, 24, 32, 48, 64, 128};
    private static final Map<Integer, BufferedImage> CACHE = new ConcurrentHashMap<>();

    private UpdaterIcon() {
    }

    public static void install(Window window) {
        if (window == null) {
            return;
        }
        window.setIconImages(Arrays.stream(SIZES)
                .mapToObj(UpdaterIcon::at)
                .collect(Collectors.toList()));
    }

    public static ImageIcon icon(int size) {
        return new ImageIcon(at(size));
    }

    private static BufferedImage at(int size) {
        return CACHE.computeIfAbsent(size, UpdaterIcon::render);
    }

    private static BufferedImage render(int size) {
        // 各尺寸都由同一段代码画，缩放后线条与留白才能保持一致；
        // 打包里那套旧图片只留作万一画不出来时的兜底。
        try {
            return draw(size);
        } catch (Throwable ignored) {
            String path = "/uicons/updater-" + size + ".png";
            try (InputStream in = UpdaterIcon.class.getResourceAsStream(path)) {
                if (in != null) {
                    BufferedImage img = ImageIO.read(in);
                    if (img != null) {
                        return img;
                    }
                }
            } catch (Exception ignored2) {
                // 两边都失败就给一张空图，不能让启动挂在这里
            }
            return new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        }
    }

    public static BufferedImage mark(int size) {
        return draw(size);
    }

    private static BufferedImage draw(int size) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(PAPER);
            g.fillRect(0, 0, size, size);

            float pad = Math.max(1.5f, size * 0.10f);
            float side = size - pad * 2f;
            float arc = Math.max(2f, size * 0.16f);
            g.setColor(FRAME);
            g.setStroke(new BasicStroke(Math.max(1f, size / 18f)));
            g.draw(new RoundRectangle2D.Float(pad, pad, side, side, arc, arc));

            g.setColor(SCRIBBLE);
            g.setStroke(new BasicStroke(Math.max(1f, size / 22f),
                    BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            float x1 = pad + side * 0.18f;
            g.drawLine(Math.round(x1), Math.round(pad + side * 0.32f),
                    Math.round(pad + side * 0.78f), Math.round(pad + side * 0.32f));
            g.drawLine(Math.round(x1), Math.round(pad + side * 0.52f),
                    Math.round(pad + side * 0.62f), Math.round(pad + side * 0.52f));
            g.drawLine(Math.round(x1), Math.round(pad + side * 0.72f),
                    Math.round(pad + side * 0.82f), Math.round(pad + side * 0.72f));

            int d = Math.max(2, Math.round(size * 0.11f));
            g.setColor(DOT);
            g.fill(new RoundRectangle2D.Float(size - pad - d, size - pad - d, d, d, d, d));
        } finally {
            g.dispose();
        }
        return img;
    }
}
