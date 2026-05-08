# Uptime Bar

A macOS menu bar application written in Java that displays your system's uptime.

## Features

- **Menu Bar Icon** — An upward arrow (↑) icon lives in your macOS menu bar
- **Uptime Display** — Shows days, hours, and minutes since last boot
- **Boot Time** — Displays when your Mac was last started
- **Auto-Refresh** — Updates every 60 seconds automatically
- **Manual Refresh** — Click "↻ Refresh Now" to update immediately
- **Dock-Free** — Runs as a menu bar-only agent (no Dock icon)
- **Dark Mode** — Icon adapts to light/dark mode via template images

## Requirements

- macOS
- Java 21+
- Maven 3.9+

## Build

```bash
mvn clean package
```

## Run

```bash
java -jar target/uptime-bar-1.0.0.jar
```

Or use the convenience script:

```bash
./run.sh
```

## How It Works

The app uses `sysctl kern.boottime` to read the kernel boot timestamp, calculates the elapsed duration, and displays it in the menu bar via Java's `SystemTray` API.
