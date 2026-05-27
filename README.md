# OpenCAP Sensor Monitor

Displays up to 16 Hubitat sensors on a [SenseCAP Indicator](https://www.seeedstudio.com/SenseCAP-Indicator-D1-p-5643.html) running [openHASP](https://openhasp.haswitchplate.com/) via MQTT.

## What it does

Each sensor gets a color-coded tile with a label and a state-driven icon:

| Sensor type | Inactive (clear) | Active (alert) |
|---|---|---|
| Motion | Green — run icon | Red → fades to green |
| Contact | Cyan — home icon | Red — alert icon |
| Water / leak | Blue — water icon | Red — alert icon |
| Smoke | Yellow — fire icon | Red — alert icon |

Icons update automatically when sensor state changes. No manual icon selection needed.

## Requirements

- Hubitat Elevation hub
- SenseCAP Indicator D1 (480×480, running openHASP 0.7+)
- MQTT broker (Hubitat's built-in broker works)

## Installation

1. In Hubitat → **Drivers Code** → New Driver → paste `SenseCAP_Indicator_Motion_Driver.groovy` → Save
2. In Hubitat → **Devices** → Add Virtual Device → select the driver → Save
3. Configure the device: set MQTT broker, client ID, and openHASP node name
4. In Hubitat → **Apps Code** → New App → paste `SenseCAP_Indicator_Motion_App.groovy` → Save
5. In Hubitat → **Apps** → Add User App → SenseCAP Indicator Motion → configure and save

## Configuration

All settings are in the **app**:

- **Grid Layout** — 2×2 (4 sensors), 3×3 (9 sensors), or 4×4 (16 sensors)
- **Sensor slots** — assign a sensor type, device, and label to each slot
- **Sync on startup** — push all sensor states to the display on hub reboot

The grid layout is set once in the app and automatically synced to the driver. There is no separate grid setting in the device.

## How it works

```
Hubitat sensors
      ↓  events
   Groovy App
      ↓  setMotionActive / setMotionInactive
   Groovy Driver
      ↓  MQTT
   openHASP (SenseCAP)
      ↓  LVGL
   Display tiles
```

The driver manages the display layout via openHASP's JSONL protocol. Each tile is two overlapping objects — a `btn` for background color and centered label, and a `label` for the top-left icon. Colors fade from red back to their inactive color over 30 seconds (configurable).

## Object ID scheme

| Range | Purpose |
|---|---|
| 1–16 | Background `btn` — color + label text |
| 17–32 | Icon `label` — MDI glyph, top-left corner |

## Confirmed working MDI icons (openHASP built-in font)

| Codepoint | Icon |
|---|---|
| `\uE004` | account |
| `\uE026` | alert |
| `\uE09A` | bell |
| `\uE12C` | check |
| `\uE2DC` | home |
| `\uE565` | shield-check |
| `\uE68A` | shield-home |
| `\uE70E` | run |
| `\uE7AE` | cctv |

## License

Released into the public domain under the [Unlicense](https://unlicense.org).
