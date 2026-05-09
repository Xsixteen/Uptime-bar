package com.uptimebar;

import java.awt.*;
import java.awt.event.ActionListener;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * UptimeBar — A macOS menu bar application that displays system uptime.
 *
 * Shows the current uptime as text directly in the menu bar and provides
 * a dropdown with detailed uptime information, boot time, and controls.
 */
public class UptimeBar {

    private TrayIcon trayIcon;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private PopupMenu popup;

    // Menu items that get updated
    private MenuItem uptimeDetailItem;
    private MenuItem bootTimeItem;
    private MenuItem recordItem;
    private MenuItem daysItem;
    private MenuItem hoursItem;
    private MenuItem minutesItem;

    // Longest uptime record (in seconds)
    private long recordSeconds = 0;
    private static final Path RECORD_DIR = Paths.get(System.getProperty("user.home"), "Library", "uptime-bar");
    private static final Path RECORD_FILE = RECORD_DIR.resolve("longest_uptime");

    // Cached boot epoch — read once at startup since it never changes until reboot
    private long bootEpochSeconds = -1;

    public static void main(String[] args) {
        // Enable macOS template images (adapts to dark/light mode)
        System.setProperty("apple.awt.enableTemplateImages", "true");

        // Hide from Dock — run as a menu bar-only (agent) app
        System.setProperty("apple.awt.UIElement", "true");

        new UptimeBar().start();
    }

    /**
     * Initializes and starts the menu bar application.
     */
    private void start() {
        if (!SystemTray.isSupported()) {
            System.err.println("SystemTray is not supported on this platform.");
            System.exit(1);
        }

        // Build the popup menu
        popup = new PopupMenu();

        // --- Header ---
        MenuItem titleItem = new MenuItem("⏱ Uptime Bar");
        titleItem.setEnabled(false);
        popup.add(titleItem);
        popup.addSeparator();

        // --- Uptime breakdown ---
        uptimeDetailItem = new MenuItem("Uptime: calculating...");
        uptimeDetailItem.setEnabled(false);
        popup.add(uptimeDetailItem);

        popup.addSeparator();

        daysItem = new MenuItem("  Days: —");
        daysItem.setEnabled(false);
        popup.add(daysItem);

        hoursItem = new MenuItem("  Hours: —");
        hoursItem.setEnabled(false);
        popup.add(hoursItem);

        minutesItem = new MenuItem("  Minutes: —");
        minutesItem.setEnabled(false);
        popup.add(minutesItem);

        popup.addSeparator();

        // --- Longest uptime record ---
        recordItem = new MenuItem("☆ Record: —");
        recordItem.setEnabled(false);
        popup.add(recordItem);

        // --- Boot time ---
        bootTimeItem = new MenuItem("Boot: calculating...");
        bootTimeItem.setEnabled(false);
        popup.add(bootTimeItem);

        popup.addSeparator();

        // --- Refresh ---
        MenuItem refreshItem = new MenuItem("↻ Refresh Now");
        refreshItem.addActionListener(e -> updateUptime());
        popup.add(refreshItem);

        popup.addSeparator();

        // --- Quit ---
        MenuItem quitItem = new MenuItem("Quit Uptime Bar");
        quitItem.addActionListener(e -> {
            scheduler.shutdownNow();
            SystemTray.getSystemTray().remove(trayIcon);
            System.exit(0);
        });
        popup.add(quitItem);

        // Create the initial tray icon with no category yet
        Image icon = createTrayIcon("?");
        trayIcon = new TrayIcon(icon, "Uptime Bar");
        trayIcon.setPopupMenu(popup);

        try {
            SystemTray.getSystemTray().add(trayIcon);
        } catch (AWTException e) {
            System.err.println("Failed to add tray icon: " + e.getMessage());
            System.exit(1);
        }

        // Initial update
        updateUptime();

        // Schedule periodic updates every 60 seconds
        scheduler.scheduleAtFixedRate(this::updateUptime, 60, 60, TimeUnit.SECONDS);

        System.out.println("Uptime Bar is running in the menu bar.");
    }

    /**
     * Creates a tray icon with an upward arrow and a category letter.
     * The letter indicates the largest uptime unit:
     *   M = month, W = week, D = day, H = hour, m = minute, S = seconds
     *
     * @param category the single-character category label
     */
    private Image createTrayIcon(String category) {
        int width = 30;  // Tight fit for arrow + letter
        int height = 22; // Standard macOS menu bar icon height
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2d = image.createGraphics();

        // Enable anti-aliasing
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        // Draw an upward arrow on the left side
        g2d.setColor(Color.BLACK);
        g2d.setStroke(new BasicStroke(2.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

        int arrowX = 6;
        g2d.drawLine(arrowX, 4, arrowX, 18);       // shaft
        g2d.drawLine(arrowX, 4, arrowX - 5, 10);   // left head
        g2d.drawLine(arrowX, 4, arrowX + 5, 10);   // right head

        // Draw the category letter to the right of the arrow
        g2d.setFont(new Font("Futura", Font.BOLD, 14));
        g2d.drawString(category, 16, 17);

        g2d.dispose();
        return image;
    }

    /**
     * Calculates uptime from the cached boot epoch and updates
     * the tray icon tooltip and menu items.
     */
    private void updateUptime() {
        try {
            // Ensure boot time is cached (reads sysctl once, then never again)
            if (bootEpochSeconds < 0) {
                bootEpochSeconds = readBootEpoch();
                // Load record on first run
                recordSeconds = loadRecord();
            }

            if (bootEpochSeconds < 0) {
                trayIcon.setToolTip("Uptime: unavailable");
                uptimeDetailItem.setLabel("Uptime: unavailable");
                return;
            }

            // Pure arithmetic — no subprocess needed
            long totalSeconds = Instant.now().getEpochSecond() - bootEpochSeconds;
            long days = totalSeconds / 86400;
            long hours = (totalSeconds % 86400) / 3600;
            long minutes = (totalSeconds % 3600) / 60;

            // Determine the max category and update the tray icon
            String category = getUptimeCategory(totalSeconds);
            trayIcon.setImage(createTrayIcon(category));

            // Format the uptime string
            String uptimeStr = formatUptime(days, hours, minutes);

            // Update tooltip (shown on hover)
            trayIcon.setToolTip("Uptime: " + uptimeStr);

            // Update menu items
            uptimeDetailItem.setLabel("Uptime: " + uptimeStr);
            daysItem.setLabel("  Days: " + days);
            hoursItem.setLabel("  Hours: " + hours);
            minutesItem.setLabel("  Minutes: " + minutes);

            // Check and update the longest uptime record
            if (totalSeconds > recordSeconds) {
                recordSeconds = totalSeconds;
                saveRecord(recordSeconds);
            }
            long recDays = recordSeconds / 86400;
            long recHours = (recordSeconds % 86400) / 3600;
            long recMinutes = (recordSeconds % 3600) / 60;
            recordItem.setLabel("☆ Record: " + formatUptime(recDays, recHours, recMinutes));

            // Display boot time from the cached epoch
            Instant bootInstant = Instant.ofEpochSecond(bootEpochSeconds);
            LocalDateTime bootTime = LocalDateTime.ofInstant(bootInstant, ZoneId.systemDefault());
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("EEE, MMM d 'at' h:mm a");
            bootTimeItem.setLabel("Boot: " + bootTime.format(formatter));

        } catch (Exception e) {
            System.err.println("Error updating uptime: " + e.getMessage());
            trayIcon.setToolTip("Uptime: error");
        }
    }

    /**
     * Reads the kernel boot epoch via sysctl. Called once and cached.
     * Uses a 5-second timeout and always destroys the process.
     *
     * @return boot time as epoch seconds, or -1 if unavailable
     */
    private long readBootEpoch() {
        Process process = null;
        try {
            ProcessBuilder pb = new ProcessBuilder("sysctl", "-n", "kern.boottime");
            pb.redirectErrorStream(true);
            process = pb.start();

            String output;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                output = reader.readLine();
            }

            // Wait with a timeout so we never block forever
            boolean finished = process.waitFor(5, TimeUnit.SECONDS);
            if (!finished) {
                System.err.println("sysctl timed out after 5 seconds");
                return -1;
            }

            if (output == null || output.isBlank()) {
                return -1;
            }

            // Output format: { sec = 1715100000, usec = 0 } Thu May  7 12:00:00 2026
            int secStart = output.indexOf("sec = ");
            if (secStart == -1) return -1;

            secStart += 6; // Skip "sec = "
            int secEnd = output.indexOf(",", secStart);
            if (secEnd == -1) return -1;

            return Long.parseLong(output.substring(secStart, secEnd).trim());

        } catch (Exception e) {
            System.err.println("Failed to read boot time: " + e.getMessage());
            return -1;
        } finally {
            // Always destroy the process to prevent leaks
            if (process != null) {
                process.destroyForcibly();
            }
        }
    }

    /**
     * Returns the max uptime category letter based on total elapsed seconds.
     *   M = month (30+ days), W = week (7+ days), D = day (1+ day),
     *   H = hour (1+ hour), m = minute (1+ minute), S = seconds
     */
    private String getUptimeCategory(long totalSeconds) {
        long days = totalSeconds / 86400;
        if (days >= 30) return "M";
        if (days >= 7)  return "W";
        if (days >= 1)  return "D";
        if (totalSeconds >= 3600) return "H";
        if (totalSeconds >= 60)   return "m";
        return "S";
    }

    /**
     * Formats uptime into a human-readable string.
     */
    private String formatUptime(long days, long hours, long minutes) {
        StringBuilder sb = new StringBuilder();

        if (days > 0) {
            sb.append(days).append("d ");
        }
        if (hours > 0 || days > 0) {
            sb.append(hours).append("h ");
        }
        sb.append(minutes).append("m");

        return sb.toString().trim();
    }

    /**
     * Loads the longest recorded uptime (in seconds) from ~/Library/uptime-bar/longest_uptime.
     * Returns 0 if the file does not exist or cannot be read.
     */
    private long loadRecord() {
        try {
            if (Files.exists(RECORD_FILE)) {
                String content = Files.readString(RECORD_FILE).trim();
                return Long.parseLong(content);
            }
        } catch (Exception e) {
            System.err.println("Failed to load uptime record: " + e.getMessage());
        }
        return 0;
    }

    /**
     * Persists the longest recorded uptime (in seconds) to ~/Library/uptime-bar/longest_uptime.
     * Creates the directory if it does not exist.
     */
    private void saveRecord(long seconds) {
        try {
            Files.createDirectories(RECORD_DIR);
            Files.writeString(RECORD_FILE, Long.toString(seconds));
        } catch (Exception e) {
            System.err.println("Failed to save uptime record: " + e.getMessage());
        }
    }
}
