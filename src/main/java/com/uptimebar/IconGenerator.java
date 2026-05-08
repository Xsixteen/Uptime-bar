package com.uptimebar;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * Generates the UptimeBar app icon at all macOS-required sizes.
 * Renders the same upward arrow used in the menu bar tray icon,
 * centered on a rounded-rect background with a gradient.
 *
 * Usage: java IconGenerator <output-directory>
 */
public class IconGenerator {

    // macOS iconset requires these sizes (filename → pixel size)
    private static final int[][] SIZES = {
        {16, 1},    // icon_16x16.png
        {16, 2},    // icon_16x16@2x.png  (32px)
        {32, 1},    // icon_32x32.png
        {32, 2},    // icon_32x32@2x.png  (64px)
        {128, 1},   // icon_128x128.png
        {128, 2},   // icon_128x128@2x.png (256px)
        {256, 1},   // icon_256x256.png
        {256, 2},   // icon_256x256@2x.png (512px)
        {512, 1},   // icon_512x512.png
        {512, 2},   // icon_512x512@2x.png (1024px)
    };

    public static void main(String[] args) throws Exception {
        String outputDir = args.length > 0 ? args[0] : "AppIcon.iconset";

        File dir = new File(outputDir);
        if (!dir.exists()) {
            dir.mkdirs();
        }

        for (int[] spec : SIZES) {
            int baseSize = spec[0];
            int scale = spec[1];
            int pixelSize = baseSize * scale;

            BufferedImage icon = renderIcon(pixelSize);

            String filename;
            if (scale == 1) {
                filename = String.format("icon_%dx%d.png", baseSize, baseSize);
            } else {
                filename = String.format("icon_%dx%d@2x.png", baseSize, baseSize);
            }

            File outputFile = new File(dir, filename);
            ImageIO.write(icon, "png", outputFile);
            System.out.println("  Created: " + outputFile.getPath() + " (" + pixelSize + "x" + pixelSize + ")");
        }

        System.out.println("Done. Iconset saved to: " + outputDir);
    }

    /**
     * Renders the app icon at the given pixel size.
     * Features a gradient background with a white upward arrow.
     */
    private static BufferedImage renderIcon(int size) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();

        // High-quality rendering
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

        // --- Background: rounded rectangle with gradient ---
        int cornerRadius = (int) (size * 0.22);
        int inset = (int) (size * 0.02);

        // Dark gradient: deep charcoal to dark blue-grey
        GradientPaint bgGradient = new GradientPaint(
            0, 0, new Color(45, 52, 65),
            0, size, new Color(25, 30, 40)
        );
        g.setPaint(bgGradient);
        g.fillRoundRect(inset, inset, size - inset * 2, size - inset * 2, cornerRadius, cornerRadius);

        // Subtle inner border
        g.setColor(new Color(255, 255, 255, 25));
        g.setStroke(new BasicStroke(Math.max(1, size * 0.01f)));
        g.drawRoundRect(inset + 1, inset + 1, size - inset * 2 - 2, size - inset * 2 - 2, cornerRadius, cornerRadius);

        // --- Arrow ---
        float scale = size / 22.0f; // Scale relative to the 22px tray icon
        float strokeWidth = Math.max(2f, 3.5f * scale);
        g.setStroke(new BasicStroke(strokeWidth, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

        // Arrow color: white with slight glow effect
        int cx = size / 2;

        // Glow behind arrow
        g.setColor(new Color(255, 255, 255, 30));
        float glowStroke = strokeWidth * 3;
        g.setStroke(new BasicStroke(glowStroke, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        drawArrow(g, cx, size, scale);

        // Main arrow in white
        g.setColor(Color.WHITE);
        g.setStroke(new BasicStroke(strokeWidth, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        drawArrow(g, cx, size, scale);

        g.dispose();
        return image;
    }

    /**
     * Draws the upward arrow centered in the icon.
     */
    private static void drawArrow(Graphics2D g, int cx, int size, float scale) {
        // Vertical positioning: center the arrow in the icon
        int topY = (int) (size * 0.25);
        int bottomY = (int) (size * 0.75);
        int headSpreadX = (int) (5 * scale);
        int headLength = (int) (6 * scale);

        // Shaft
        g.drawLine(cx, topY, cx, bottomY);

        // Arrow head
        g.drawLine(cx, topY, cx - headSpreadX, topY + headLength);
        g.drawLine(cx, topY, cx + headSpreadX, topY + headLength);
    }
}
