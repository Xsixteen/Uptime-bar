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

    // Active time tracking
    private MenuItem activeTimeItem;
    private MenuItem activeRecordItem;
    private MenuItem activeOdometerItem;
    private long activeSeconds = 0;
    private long activeRecordSeconds = 0;
    private long activeOdometerSeconds = 0;
    private long lastActiveCheckTimeMs = 0;

    // Longest uptime record (in seconds)
    private long recordSeconds = 0;
    private static final Path RECORD_DIR = Paths.get(System.getProperty("user.home"), "Library", "uptime-bar");
    private static final Path RECORD_FILE = RECORD_DIR.resolve("longest_uptime");
    private static final Path ACTIVE_SESSION_FILE = RECORD_DIR.resolve("current_active_session");
    private static final Path ACTIVE_RECORD_FILE = RECORD_DIR.resolve("longest_active_time");
    private static final Path ACTIVE_ODOMETER_FILE = RECORD_DIR.resolve("active_odometer");

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

        // --- Active Time ---
        activeTimeItem = new MenuItem("Active: calculating...");
        activeTimeItem.setEnabled(false);
        popup.add(activeTimeItem);

        activeRecordItem = new MenuItem("☆ Active Record: —");
        activeRecordItem.setEnabled(false);
        popup.add(activeRecordItem);

        activeOdometerItem = new MenuItem("∑ Active Odometer: —");
        activeOdometerItem.setEnabled(false);
        popup.add(activeOdometerItem);

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
     * M = month, W = week, D = day, H = hour, m = minute, S = seconds
     *
     * @param category the single-character category label
     */
    private Image createTrayIcon(String category) {
        int width = 32; // Extra room for wide letters like W
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
        g2d.drawLine(arrowX, 4, arrowX, 18); // shaft
        g2d.drawLine(arrowX, 4, arrowX - 5, 10); // left head
        g2d.drawLine(arrowX, 4, arrowX + 5, 10); // right head

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
                // Load records on first run
                recordSeconds = loadRecord(RECORD_FILE);
                activeRecordSeconds = loadRecord(ACTIVE_RECORD_FILE);
                activeOdometerSeconds = loadRecord(ACTIVE_ODOMETER_FILE);
                activeSeconds = loadActiveSession(bootEpochSeconds);
            }

            if (bootEpochSeconds < 0) {
                trayIcon.setToolTip("Uptime: unavailable");
                uptimeDetailItem.setLabel("Uptime: unavailable");
                activeTimeItem.setLabel("Active: unavailable");
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
                saveRecord(RECORD_FILE, recordSeconds);
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

            // --- Active Time Calculation ---
            long nowMs = System.currentTimeMillis();
            if (lastActiveCheckTimeMs > 0) {
                long elapsedMs = nowMs - lastActiveCheckTimeMs;
                long idleNs = readIdleTimeNs();

                long addedSeconds = elapsedMs / 1000;
                if (addedSeconds > 120) {
                    addedSeconds = 60; // Cap to 1 minute if there was a large gap (e.g. sleep)
                }

                long activeThresholdNs = (addedSeconds * 1_000_000_000L) + 10_000_000_000L; // 10s buffer
                if (idleNs >= 0 && idleNs < activeThresholdNs) {
                    activeSeconds += addedSeconds;
                    activeOdometerSeconds += addedSeconds;
                    saveActiveSession(bootEpochSeconds, activeSeconds);
                    saveRecord(ACTIVE_ODOMETER_FILE, activeOdometerSeconds);
                }
            }
            lastActiveCheckTimeMs = nowMs;

            // Check and update the longest active time record
            if (activeSeconds > activeRecordSeconds) {
                activeRecordSeconds = activeSeconds;
                saveRecord(ACTIVE_RECORD_FILE, activeRecordSeconds);
            }

            long actDays = activeSeconds / 86400;
            long actHours = (activeSeconds % 86400) / 3600;
            long actMinutes = (activeSeconds % 3600) / 60;
            activeTimeItem.setLabel("Active: " + formatUptime(actDays, actHours, actMinutes));

            long actRecDays = activeRecordSeconds / 86400;
            long actRecHours = (activeRecordSeconds % 86400) / 3600;
            long actRecMinutes = (activeRecordSeconds % 3600) / 60;
            activeRecordItem.setLabel("☆ Record: " + formatUptime(actRecDays, actRecHours, actRecMinutes));

            long odoDays = activeOdometerSeconds / 86400;
            long odoHours = (activeOdometerSeconds % 86400) / 3600;
            long odoMinutes = (activeOdometerSeconds % 3600) / 60;
            activeOdometerItem.setLabel("∑ Odometer: " + formatUptime(odoDays, odoHours, odoMinutes));

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

            // Output format: { sec = 1715100000, usec = 0 } Thu May 7 12:00:00 2026
            int secStart = output.indexOf("sec = ");
            if (secStart == -1)
                return -1;

            secStart += 6; // Skip "sec = "
            int secEnd = output.indexOf(",", secStart);
            if (secEnd == -1)
                return -1;

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
     * M = month (30+ days), W = week (7+ days), D = day (1+ day),
     * H = hour (1+ hour), m = minute (1+ minute), S = seconds
     */
    private String getUptimeCategory(long totalSeconds) {
        long days = totalSeconds / 86400;
        if (days >= 30)
            return "M";
        if (days >= 7)
            return "W";
        if (days >= 1)
            return "D";
        if (totalSeconds >= 3600)
            return "H";
        if (totalSeconds >= 60)
            return "m";
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
     * Loads a recorded value (in seconds) from the specified path.
     * Returns 0 if the file does not exist or cannot be read.
     */
    private long loadRecord(Path path) {
        try {
            if (Files.exists(path)) {
                String content = Files.readString(path).trim();
                return Long.parseLong(content);
            }
        } catch (Exception e) {
            System.err.println("Failed to load record: " + e.getMessage());
        }
        return 0;
    }

    /**
     * Persists a recorded value (in seconds) to the specified path.
     * Creates the directory if it does not exist.
     */
    private void saveRecord(Path path, long seconds) {
        try {
            Files.createDirectories(RECORD_DIR);
            Files.writeString(path, Long.toString(seconds));
        } catch (Exception e) {
            System.err.println("Failed to save record: " + e.getMessage());
        }
    }

    /**
     * Loads the current active session time if the boot epoch matches.
     */
    private long loadActiveSession(long currentBootEpoch) {
        try {
            if (Files.exists(ACTIVE_SESSION_FILE)) {
                String[] parts = Files.readString(ACTIVE_SESSION_FILE).trim().split(":");
                if (parts.length == 2) {
                    long savedBootEpoch = Long.parseLong(parts[0]);
                    if (savedBootEpoch == currentBootEpoch) {
                        return Long.parseLong(parts[1]);
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Failed to load active session: " + e.getMessage());
        }
        return 0;
    }

    /**
     * Persists the current active session time, tagged with the boot epoch.
     */
    private void saveActiveSession(long currentBootEpoch, long currentActiveSeconds) {
        try {
            Files.createDirectories(RECORD_DIR);
            Files.writeString(ACTIVE_SESSION_FILE, currentBootEpoch + ":" + currentActiveSeconds);
        } catch (Exception e) {
            System.err.println("Failed to save active session: " + e.getMessage());
        }
    }

    /**
     * Reads the current idle time in nanoseconds via ioreg.
     */
    private long readIdleTimeNs() {
        Process process = null;
        try {
            ProcessBuilder pb = new ProcessBuilder("ioreg", "-c", "IOHIDSystem");
            pb.redirectErrorStream(true);
            process = pb.start();

            long idleTime = -1;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.contains("\"HIDIdleTime\"")) {
                        String[] parts = line.split("=");
                        if (parts.length == 2) {
                            idleTime = Long.parseLong(parts[1].trim());
                        }
                        break;
                    }
                }
            }

            process.waitFor(2, TimeUnit.SECONDS);
            return idleTime;
        } catch (Exception e) {
            System.err.println("Failed to read idle time: " + e.getMessage());
            return -1;
        } finally {
            if (process != null) {
                process.destroyForcibly();
            }
        }
    }
}
