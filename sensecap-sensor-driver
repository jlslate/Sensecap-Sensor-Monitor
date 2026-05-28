/*
 * This is free and unencumbered software released into the public domain.
 *
 * Anyone is free to copy, modify, publish, use, compile, sell, or
 * distribute this software, either in source code form or as a compiled
 * binary, for any purpose, commercial or non-commercial, and by any means.
 *
 * In jurisdictions that recognize copyright laws, the author or authors
 * of this software dedicate any and all copyright interest in the software
 * to the public domain. We make this dedication for the benefit of the
 * public at large and to the detriment of our heirs and successors. We
 * intend this dedication to be an overt act of relinquishment in perpetuity
 * of all present and future rights to this software under copyright law.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
 * IN NO EVENT SHALL THE AUTHORS BE LIABLE FOR ANY CLAIM, DAMAGES OR
 * OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE,
 * ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
 * OTHER DEALINGS IN THE SOFTWARE.
 *
 * For more information, please refer to <https://unlicense.org>
 */

/**
 * SenseCAP Sensor Monitor Driver
 *
 * Controls object background colors on an openHASP device (SenseCAP Indicator)
 * via MQTT. Each sensor slot maps to a pair of openHASP objects:
 *
 *   Object ID  slot * 2 - 1  (odd)  — btn  — full-tile background + centered label text
 *   Object ID  slot * 2      (even) — label — icon only, top-left, transparent background
 *
 * This means slot 1 → objects 1 (btn) + 2 (icon label)
 *              slot 2 → objects 3 (btn) + 4 (icon label)  ... etc.
 *
 * The icon label object sits on top of the btn with a transparent background so
 * the btn's color shines through, while the btn's text remains fully centered
 * without any offset for the icon.
 *
 * openHASP MQTT topic format:
 *   hasp/<nodeName>/command/p<page>b<objectId>.bg_color  →  #RRGGBB
 *   hasp/<nodeName>/command/p<page>b<objectId>.jsonl     →  {"text":"..."}
 *
 * Author: jlslate (slate)
 * Version: 3.0.0  — state-driven icons, setGridLayout command, activeGrid helper, optimised batch layout push
 */

import groovy.transform.Field

metadata {
    definition(
        name: "SenseCAP Sensor Monitor",
        namespace: "community",
        author: "jlslate (slate)",
        description: "Sensecap Sensor Monitor driver - motion sensor color display on SenseCAP Indicator"
    ) {
        capability "Initialize"
        capability "Actuator"

        command "setMotionActive",   [[name: "sensorIndex", type: "NUMBER", description: "Sensor slot 1–16 — turns tile to active state"]]
        command "setMotionInactive", [[name: "sensorIndex", type: "NUMBER", description: "Sensor slot 1–16 — turns tile to inactive state"]]
        command "setAllInactive"
        command "reconnectMqtt"
        command "setGridLayout",  [[name: "gridLayout", type: "STRING", description: "2x2, 3x3, or 4x4"]]
        command "pushLayout"
        command "updateLabels",    [[name: "labels",    type: "JSON_OBJECT", description: "Map of slot index to label text (no icon)"]]
        command "updateSlotTypes", [[name: "slotTypes", type: "JSON_OBJECT", description: "Map of slot index to type: motion, contact, water, or smoke"]]

        attribute "mqttStatus",      "string"
        attribute "gridLayout",      "string"
        attribute "displayRebooted", "string"

        attribute "sensor1Status",   "string"
        attribute "sensor2Status",   "string"
        attribute "sensor3Status",   "string"
        attribute "sensor4Status",   "string"
        attribute "sensor5Status",   "string"
        attribute "sensor6Status",   "string"
        attribute "sensor7Status",   "string"
        attribute "sensor8Status",   "string"
        attribute "sensor9Status",   "string"
        attribute "sensor10Status",  "string"
        attribute "sensor11Status",  "string"
        attribute "sensor12Status",  "string"
        attribute "sensor13Status",  "string"
        attribute "sensor14Status",  "string"
        attribute "sensor15Status",  "string"
        attribute "sensor16Status",  "string"

        attribute "sensor1Type",     "string"
        attribute "sensor2Type",     "string"
        attribute "sensor3Type",     "string"
        attribute "sensor4Type",     "string"
        attribute "sensor5Type",     "string"
        attribute "sensor6Type",     "string"
        attribute "sensor7Type",     "string"
        attribute "sensor8Type",     "string"
        attribute "sensor9Type",     "string"
        attribute "sensor10Type",    "string"
        attribute "sensor11Type",    "string"
        attribute "sensor12Type",    "string"
        attribute "sensor13Type",    "string"
        attribute "sensor14Type",    "string"
        attribute "sensor15Type",    "string"
        attribute "sensor16Type",    "string"
    }

    preferences {
        input name: "mqttBroker",
            type: "text",
            title: "MQTT Broker (Host:Port)",
            description: "tcp://127.0.0.1:1883 for Hubitat built-in broker",
            required: true,
            defaultValue: "tcp://127.0.0.1:1883"

        input name: "mqttClientId",
            type: "text",
            title: "MQTT Client ID (unique on broker)",
            required: true,
            defaultValue: "hubitat-sensecap-driver"

        input name: "mqttUsername",
            type: "text",
            title: "MQTT Username",
            required: false

        input name: "mqttPassword",
            type: "password",
            title: "MQTT Password",
            required: false

        input name: "haspNode",
            type: "text",
            title: "openHASP Node Name",
            description: "The 'Node name' from openHASP Settings → MQTT (e.g. plate)",
            required: true,
            defaultValue: "plate"

        input name: "colorActive",
            type: "enum",
            title: "Active color (alert state)",
            options: [
                "#FF0000": "Red",
                "#FF4500": "Orange-red",
                "#FF8C00": "Dark orange",
                "#FF1493": "Deep pink",
                "#8B0000": "Dark red",
                "#FF6347": "Tomato",
                "#DC143C": "Crimson",
                "#FF0080": "Hot magenta"
            ],
            defaultValue: "#FF0000",
            required: true

        input name: "colorInactive",
            type: "enum",
            title: "Inactive color — motion sensor",
            options: [
                "#F8F8FF": "Ghost White",
                "#D3D3D3": "Light Gray",
                "#808080": "Gray",
                "#FF0000": "Red",
                "#800000": "Maroon",
                "#FF00FF": "Magenta",
                "#800080": "Purple",
                "#0000FF": "Blue",
                "#000080": "Navy",
                "#00FFFF": "Cyan",
                "#008080": "Teal",
                "#00FF00": "Lime",
                "#008000": "Green",
                "#FFFF00": "Yellow",
                "#808000": "Olive"
            ],
            defaultValue: "#008000",
            required: true

        input name: "colorContactInactive",
            type: "enum",
            title: "Inactive color — contact sensor (closed)",
            options: [
                "#F8F8FF": "Ghost White",
                "#D3D3D3": "Light Gray",
                "#808080": "Gray",
                "#FF0000": "Red",
                "#800000": "Maroon",
                "#FF00FF": "Magenta",
                "#800080": "Purple",
                "#0000FF": "Blue",
                "#000080": "Navy",
                "#00FFFF": "Cyan",
                "#008080": "Teal",
                "#00FF00": "Lime",
                "#008000": "Green",
                "#FFFF00": "Yellow",
                "#808000": "Olive"
            ],
            defaultValue: "#00FFFF",
            required: true

        input name: "colorWaterInactive",
            type: "enum",
            title: "Inactive color — water sensor (dry)",
            options: [
                "#F8F8FF": "Ghost White",
                "#D3D3D3": "Light Gray",
                "#808080": "Gray",
                "#FF0000": "Red",
                "#800000": "Maroon",
                "#FF00FF": "Magenta",
                "#800080": "Purple",
                "#0000FF": "Blue",
                "#000080": "Navy",
                "#00FFFF": "Cyan",
                "#008080": "Teal",
                "#00FF00": "Lime",
                "#008000": "Green",
                "#FFFF00": "Yellow",
                "#808000": "Olive"
            ],
            defaultValue: "#0000FF",
            required: true

        input name: "colorSmokeInactive",
            type: "enum",
            title: "Inactive color — smoke sensor (clear)",
            options: [
                "#F8F8FF": "Ghost White",
                "#D3D3D3": "Light Gray",
                "#808080": "Gray",
                "#FF0000": "Red",
                "#800000": "Maroon",
                "#FF00FF": "Magenta",
                "#800080": "Purple",
                "#0000FF": "Blue",
                "#000080": "Navy",
                "#00FFFF": "Cyan",
                "#008080": "Teal",
                "#00FF00": "Lime",
                "#008000": "Green",
                "#FFFF00": "Yellow",
                "#808000": "Olive"
            ],
            defaultValue: "#FFFF00",
            required: true

        input name: "fadeDuration",
            type: "number",
            title: "Fade duration (seconds, default 30)",
            defaultValue: 30,
            required: true

        input name: "iconFont",
            type: "number",
            title: "Icon font size (pt, default 24)",
            defaultValue: 24,
            description: "MDI glyph size on the icon overlay — 16, 24, or 32"

        input name: "backlightOnMotion",
            type: "bool",
            title: "Turn backlight ON when any sensor is active",
            defaultValue: true

        input name: "backlightOffDelay",
            type: "number",
            title: "Turn backlight OFF (seconds after all sensors clear, 0 = never)",
            defaultValue: 0

        input name: "motionBacklightTimeout",
            type: "number",
            title: "Turn backlight OFF if motion persists longer than (minutes, 0 = never)",
            defaultValue: 1

        input name: "extendedMotionBacklightOn",
            type: "number",
            title: "Turn backlight back ON if motion still active after backlight-off for (minutes, 0 = never)",
            defaultValue: 10

        input name: "touchBacklightTimeout",
            type: "number",
            title: "Turn backlight OFF (seconds after screen tap when all sensors are green, 0 = never)",
            defaultValue: 30

        input name: "logLevel",
            type: "enum",
            title: "Logging Level",
            options: [
                "0": "None",
                "1": "Info only",
                "2": "Info + Debug"
            ],
            defaultValue: "1",
            required: true
    }
}

// ── Object ID helpers ─────────────────────────────────────────────────────────
//
//   bgId(slot)   = slot       1–16   btn — bg_color + "align":"center" text
//   iconId(slot) = slot + 16  17–32  label — page-level absolute coords, tight box
//
private int bgId(int slot)   { slot      }
private int iconId(int slot) { slot + 16 }

// ── Lifecycle ────────────────────────────────────────────────────────────────

def installed() {
    infoLog "[SenseCAP] Driver installed"
    initialize()
}

def updated() {
    infoLog "[SenseCAP] Preferences updated — reconnecting"
    initialize()
}

def initialize() {
    state.clear()
    sendEvent(name: "mqttStatus", value: "Initializing")
    sendEvent(name: "gridLayout",  value: activeGrid())
    connectMqtt()
    runIn(2, pushLayout)
    unschedule(sendHeartbeat)
    runEvery5Minutes(sendHeartbeat)
}

def setGridLayout(String gridLayout) {
    state.gridLayout = gridLayout
    sendEvent(name: "gridLayout", value: gridLayout)
    infoLog "[SenseCAP] Grid layout set to ${gridLayout}"
}

def uninstalled() {
    disconnectMqtt()
}

// ── MQTT ─────────────────────────────────────────────────────────────────────

def connectMqtt() {
    try {
        String broker   = settings.mqttBroker   ?: "tcp://127.0.0.1:1883"
        String clientId = settings.mqttClientId ?: "hubitat-sensecap-${device.id}"

        if (settings.mqttUsername) {
            interfaces.mqtt.connect(broker, clientId, settings.mqttUsername, settings.mqttPassword)
        } else {
            interfaces.mqtt.connect(broker, clientId, null, null)
        }

        infoLog "[SenseCAP] MQTT connected → ${broker}"
        sendEvent(name: "mqttStatus", value: "Connected")

        String node = settings.haspNode ?: "plate"
        interfaces.mqtt.subscribe("hasp/${node}/state/statusupdate")
        interfaces.mqtt.subscribe("hasp/${node}/state/idle")
        interfaces.mqtt.subscribe("hasp/${node}/idle")
        interfaces.mqtt.subscribe("hasp/${node}/state/backlight")
        interfaces.mqtt.subscribe("hasp/${node}/backlight")
        debugLog "Subscribed to status, idle, and backlight topics"

    } catch (Exception e) {
        infoLog "[SenseCAP] ERROR — MQTT connect failed: ${e.message}"
        sendEvent(name: "mqttStatus", value: "Error: ${e.message}")
        runIn(30, connectMqtt)
    }
}

def disconnectMqtt() {
    try { interfaces.mqtt.disconnect() } catch (Exception e) { /* ignore */ }
    sendEvent(name: "mqttStatus", value: "Disconnected")
}

def reconnectMqtt() {
    disconnectMqtt()
    pauseExecution(1000)
    connectMqtt()
}

def mqttClientStatus(String status) {
    infoLog "[SenseCAP] MQTT status: ${status}"
    sendEvent(name: "mqttStatus", value: status)
    if (status.startsWith("Error") || status.contains("lost")) {
        runIn(30, connectMqtt)
    }
}

def parse(String description) {
    def msg = interfaces.mqtt.parseMessage(description)
    debugLog "Received: topic=${msg.topic} payload=${msg.payload}"

    if (msg.topic.endsWith("/LWT")) {
        if (msg.payload?.trim() == "online") {
            infoLog "[SenseCAP] LWT online — display rebooted, pushing layout and resyncing"
            runIn(2, pushLayout)
            runIn(5, resyncStates)
            runIn(7, resyncLabels)
            runIn(9, fireDisplayRebooted)
        } else {
            debugLog "LWT: ${msg.payload}"
        }

    } else if (msg.topic.contains("statusupdate")) {
        if (!msg.payload?.trim()) { debugLog "Ignoring empty statusupdate echo"; return }
        try {
            def json = new groovy.json.JsonSlurper().parseText(msg.payload)
            if (json.uptime == null) { debugLog "Ignoring statusupdate without uptime"; return }
            int uptime = (json.uptime) as int
            if (uptime < 30) {
                infoLog "[SenseCAP] Display rebooted (uptime ${uptime}s) — pushing layout and resyncing"
                runIn(2, pushLayout)
                runIn(5, resyncStates)
                runIn(7, resyncLabels)
                runIn(9, fireDisplayRebooted)
            } else {
                infoLog "[SenseCAP] Display woke from idle (uptime ${uptime}s) — resyncing"
                runIn(2, resyncStates)
                startBacklightTimer()
            }
        } catch (Exception e) {
            infoLog "[SenseCAP] WARN — Could not parse statusupdate: ${e.message}"
        }

    } else if (msg.topic.contains("state/idle") || msg.topic.endsWith("/idle")) {
        String idleVal = msg.payload?.trim()
        if (idleVal == "short" || idleVal == "long") {
            debugLog "Screen went idle (${idleVal})"
            state.screenIdle = true
        } else if (idleVal == "off") {
            long msSinceHeartbeat = now() - (state.lastHeartbeatMs ?: 0L)
            if (msSinceHeartbeat < 3000) {
                debugLog "Ignoring idle off echo from heartbeat"
            } else {
                state.screenIdle = false
                infoLog "[SenseCAP] Screen woke from touch"
                startBacklightTimer()
            }
        }

    } else if (msg.topic.contains("state/backlight") || msg.topic.endsWith("/backlight")) {
        try {
            def json = new groovy.json.JsonSlurper().parseText(msg.payload)
            if (json.state == "off") {
                state.screenIdle = true
                debugLog "Backlight off"
            } else if (json.state == "on") {
                if (state.screenIdle) {
                    state.screenIdle = false
                    debugLog "Backlight on after idle — starting timer"
                    startBacklightTimer()
                } else {
                    debugLog "Backlight on"
                }
            }
        } catch (Exception e) {
            if (msg.payload?.trim() == "off") state.screenIdle = true
        }
    }
}

private void startBacklightTimer() {
    if (!settings.backlightOnMotion) return
    unschedule(backlightOff)
    unschedule(motionTimeoutBacklightOff)

    boolean anyActive = (1..maxSensors()).any { idx -> state["sensor${idx}"] == "active" }
    if (anyActive) {
        int mins = (settings.motionBacklightTimeout ?: 1) as int
        if (mins > 0) runIn(mins * 60, motionTimeoutBacklightOff)
    } else {
        int delay = (settings.touchBacklightTimeout ?: 30) as int
        if (delay > 0) runIn(delay, backlightOff)
    }
}

// ── Commands ─────────────────────────────────────────────────────────────────

def setMotionActive(sensorIndex) {
    int idx = (sensorIndex as int)
    if (idx < 1 || idx > maxSensors()) { infoLog "[SenseCAP] WARN — sensorIndex must be 1–${maxSensors()}"; return }

    String sType = state["slotType${idx}"] ?: "motion"
    state["sensor${idx}"] = "active"
    sendEvent(name: "sensor${idx}Status", value: sType == "contact" ? "open" : sType == "water" ? "wet" : sType == "smoke" ? "detected" : "active")

    unschedule("fadeStep${idx}")
    state.remove("fadeStep${idx}")
    String activeColor = settings.colorActive ?: "#FF0000"
    publishColor(idx, activeColor)
    publishTextColor(idx, activeColor)
    publishIcon(idx, activeIconFor(idx))

    if (settings.backlightOnMotion) {
        unschedule(backlightOff)
        unschedule(motionTimeoutBacklightOff)
        unschedule(extendedMotionBacklightOn)
        unschedule(backlightOnAfterFade)
        state.screenIdle = false
        publishBacklight(true)
        int mins = (settings.motionBacklightTimeout ?: 1) as int
        if (mins > 0) {
            debugLog "Motion active — backlight off in ${mins} min if still active"
            runIn(mins * 60, motionTimeoutBacklightOff)
        }
    }
}

def setMotionInactive(sensorIndex) {
    int idx = (sensorIndex as int)
    if (idx < 1 || idx > maxSensors()) { infoLog "[SenseCAP] WARN — sensorIndex must be 1–${maxSensors()}"; return }

    String sType = state["slotType${idx}"] ?: "motion"
    boolean wasActive = (state["sensor${idx}"] == "active")
    state["sensor${idx}"] = "inactive"
    sendEvent(name: "sensor${idx}Status", value: sType == "contact" ? "closed" : sType == "water" ? "dry" : sType == "smoke" ? "clear" : "inactive")

    if (wasActive) {
        unschedule("fadeStep${idx}")
        state["fadeStep${idx}"] = 0
        publishIcon(idx, inactiveIconFor(idx))
        scheduleFadeStep(idx)

        if (settings.backlightOnMotion) {
            unschedule(motionTimeoutBacklightOff)
            boolean anyStillActive = (1..maxSensors()).any { i -> state["sensor${i}"] == "active" }
            if (anyStillActive) {
                int mins = (settings.motionBacklightTimeout ?: 1) as int
                if (mins > 0) runIn(mins * 60, motionTimeoutBacklightOff)
            } else {
                unschedule(extendedMotionBacklightOn)
                int fadeTime = (FADE_STEPS + 1) * fadeInterval() + 2
                runIn(fadeTime, backlightOnAfterFade)
            }
        }
    } else {
        String iColor = inactiveColorFor(idx)
        publishColor(idx, iColor)
        publishTextColor(idx, iColor)
        publishIcon(idx, inactiveIconFor(idx))
        if (settings.backlightOnMotion) {
            unschedule(backlightOff)
            if (allInactive()) {
                int delay = (settings.backlightOffDelay ?: 0) as int
                if (delay > 0) runIn(delay, backlightOff)
            }
        }
    }
}

// ── Fade ─────────────────────────────────────────────────────────────────────

@Field static final int FADE_STEPS = 6

private int fadeInterval() {
    int secs = (settings.fadeDuration ?: 30) as int
    return Math.max(1, Math.round(secs / FADE_STEPS) as int)
}

private String activeGrid() {
    return (state.gridLayout ?: activeGrid()) as String
}

private int maxSensors() {
    switch (activeGrid()) {
        case "4x4": return 16
        case "3x3": return 9
        default:    return 4
    }
}

private void scheduleFadeStep(int idx) {
    switch (idx) {
        case 1:  runIn(fadeInterval(), fadeStep1);  break
        case 2:  runIn(fadeInterval(), fadeStep2);  break
        case 3:  runIn(fadeInterval(), fadeStep3);  break
        case 4:  runIn(fadeInterval(), fadeStep4);  break
        case 5:  runIn(fadeInterval(), fadeStep5);  break
        case 6:  runIn(fadeInterval(), fadeStep6);  break
        case 7:  runIn(fadeInterval(), fadeStep7);  break
        case 8:  runIn(fadeInterval(), fadeStep8);  break
        case 9:  runIn(fadeInterval(), fadeStep9);  break
        case 10: runIn(fadeInterval(), fadeStep10); break
        case 11: runIn(fadeInterval(), fadeStep11); break
        case 12: runIn(fadeInterval(), fadeStep12); break
        case 13: runIn(fadeInterval(), fadeStep13); break
        case 14: runIn(fadeInterval(), fadeStep14); break
        case 15: runIn(fadeInterval(), fadeStep15); break
        case 16: runIn(fadeInterval(), fadeStep16); break
    }
}

def fadeStep1()  { doFadeStep(1)  }
def fadeStep2()  { doFadeStep(2)  }
def fadeStep3()  { doFadeStep(3)  }
def fadeStep4()  { doFadeStep(4)  }
def fadeStep5()  { doFadeStep(5)  }
def fadeStep6()  { doFadeStep(6)  }
def fadeStep7()  { doFadeStep(7)  }
def fadeStep8()  { doFadeStep(8)  }
def fadeStep9()  { doFadeStep(9)  }
def fadeStep10() { doFadeStep(10) }
def fadeStep11() { doFadeStep(11) }
def fadeStep12() { doFadeStep(12) }
def fadeStep13() { doFadeStep(13) }
def fadeStep14() { doFadeStep(14) }
def fadeStep15() { doFadeStep(15) }
def fadeStep16() { doFadeStep(16) }

private void doFadeStep(int idx) {
    if (state["sensor${idx}"] == "active") {
        debugLog "Fade aborted — sensor ${idx} is active again"
        return
    }

    int step     = (state["fadeStep${idx}"] ?: 0) as int
    int maxSteps = FADE_STEPS
    double t     = step / (maxSteps as double)  // 0.0 = fully active, 1.0 = fully inactive

    // Interpolate from active color to inactive color across FADE_STEPS
    String fromHex = (settings.colorActive ?: "#FF0000").replace("#", "")
    String toHex   = inactiveColorFor(idx).replace("#", "")
    int fromR = Integer.parseInt(fromHex[0..1], 16)
    int fromG = Integer.parseInt(fromHex[2..3], 16)
    int fromB = Integer.parseInt(fromHex[4..5], 16)
    int toR   = Integer.parseInt(toHex[0..1], 16)
    int toG   = Integer.parseInt(toHex[2..3], 16)
    int toB   = Integer.parseInt(toHex[4..5], 16)

    int r = Math.max(0, Math.min(255, Math.round(fromR + (toR - fromR) * t) as int))
    int g = Math.max(0, Math.min(255, Math.round(fromG + (toG - fromG) * t) as int))
    int b = Math.max(0, Math.min(255, Math.round(fromB + (toB - fromB) * t) as int))
    String hex = sprintf("#%02X%02X%02X", r, g, b)
    debugLog "Fade sensor ${idx} step ${step}/${maxSteps} → ${hex}"
    publishColor(idx, hex)

    if (step < maxSteps) {
        state["fadeStep${idx}"] = step + 1
        scheduleFadeStep(idx)
    } else {
        state.remove("fadeStep${idx}")
        debugLog "Fade sensor ${idx} complete — snapping to inactive color"
        String snapColor = inactiveColorFor(idx)
        publishColor(idx, snapColor)
        publishTextColor(idx, snapColor)

        if (settings.backlightOnMotion && allInactive() && !state.screenIdle) {
            unschedule(backlightOff)
            unschedule(backlightOnAfterFade)
            int delay = (settings.backlightOffDelay ?: 0) as int
            if (delay > 0) {
                debugLog "All sensors green — backlight off in ${delay}s"
                runIn(delay, backlightOff)
            }
        }
    }
}


// ── Text color helper ────────────────────────────────────────────────────────

/**
 * textColorFor — returns black or white depending on the luminance of the
 * given hex color, so text always remains readable against the tile background.
 * Uses the W3C relative luminance formula (sRGB).
 */
private String textColorFor(String hex) {
    String h = hex.replace("#", "")
    int r = Integer.parseInt(h[0..1], 16)
    int g = Integer.parseInt(h[2..3], 16)
    int b = Integer.parseInt(h[4..5], 16)
    // sRGB luminance
    double rL = r / 255.0
    double gL = g / 255.0
    double bL = b / 255.0
    double luminance = 0.2126 * rL + 0.7152 * gL + 0.0722 * bL
    return luminance > 0.35 ? "black" : "white"
}

/**
 * publishTextColor — updates the text_color of a btn tile to ensure
 * readability against the current background color.
 */
private void publishTextColor(int sensorIdx, String bgHex) {
    String node    = settings.haspNode ?: "plate"
    int    obj     = bgId(sensorIdx)
    String color   = textColorFor(bgHex)
    String topic   = "hasp/${node}/command/p1b${obj}.jsonl"
    String payload = "{\"text_color\":\"${color}\"}"
    debugLog "TextColor → ${topic} : ${color}"
    try {
        interfaces.mqtt.publish(topic, payload, 1, false)
    } catch (Exception e) {
        infoLog "[SenseCAP] ERROR — TextColor publish failed: ${e.message}"
    }
}

// ── Icon codepoints (MDI built-in subset) ────────────────────────────────────
// Active state (alert):
@Field static final String ICON_MOTION_ACTIVE  = "\\uE026"  // mdi:alert (confirmed working)
@Field static final String ICON_CONTACT_ACTIVE = "\\uE026"  // mdi:alert (confirmed working)
@Field static final String ICON_WATER_ACTIVE   = "\\uE026"  // mdi:alert (confirmed working - wet/alarm)
@Field static final String ICON_SMOKE_ACTIVE   = "\\uE026"  // mdi:alert (confirmed working - detected)
// Inactive state (clear):
@Field static final String ICON_MOTION_INACTIVE  = "\\uE70E"  // mdi:run (confirmed working)
@Field static final String ICON_CONTACT_INACTIVE = "\\uE2DC"  // mdi:home (confirmed working)
@Field static final String ICON_WATER_INACTIVE   = "\\uE58C"  // mdi:water (water drop - dry/watching)
@Field static final String ICON_SMOKE_INACTIVE   = "\\uE238"  // mdi:fire (clear/watching)

private String activeIconFor(int idx) {
    switch (state["slotType${idx}"]) {
        case "contact": return ICON_CONTACT_ACTIVE
        case "water":   return ICON_WATER_ACTIVE
        case "smoke":   return ICON_SMOKE_ACTIVE
        default:        return ICON_MOTION_ACTIVE
    }
}

private String inactiveIconFor(int idx) {
    switch (state["slotType${idx}"]) {
        case "contact": return ICON_CONTACT_INACTIVE
        case "water":   return ICON_WATER_INACTIVE
        case "smoke":   return ICON_SMOKE_INACTIVE
        default:        return ICON_MOTION_INACTIVE
    }
}

private void publishIcon(int sensorIdx, String glyph) {
    String node  = settings.haspNode ?: "plate"
    int    obj   = iconId(sensorIdx)
    int    fontPt = (settings.iconFont ?: 24) as int
    String topic = "hasp/${node}/command/p1b${obj}.jsonl"
    String payload = "{\"text\":\"${glyph}\",\"text_font\":${fontPt}}"
    debugLog "[SenseCAP] Icon → ${topic} : ${glyph}"
    try {
        interfaces.mqtt.publish(topic, payload, 1, false)
    } catch (Exception e) {
        infoLog "[SenseCAP] ERROR — Icon publish failed: ${e.message}"
    }
}
// ── Layout push ───────────────────────────────────────────────────────────────
//
// Each slot in the layout is now TWO objects:
//
//   Object (bgId = slot)      — "obj"  — full tile, bg_color only.
//                                           long_mode=1 (wrap) keeps multi-line centered.
//
//   Object (iconId = slot + 16) — "label" — same x/y/w/h, bg_opa=0 (transparent),
//                                           border_width=0, icon glyph only, align=0
//                                           (left), pad_left/pad_top pin it top-left.
//                                           Rendered after all btns so it sits on top.
//
@Field static final List<String> LAYOUT_2x2_BG = [
    '{"page":1,"id":1,"obj":"btn","x":2,"y":2,"w":236,"h":236,"bg_color":"#000000","border_color":"black","border_width":4,"radius":10,"text":"","text_font":32,"align":"center","text_color":"black","toggle":false,"click":false,"long_mode":1}',
    '{"page":1,"id":2,"obj":"btn","x":242,"y":2,"w":236,"h":236,"bg_color":"#000000","border_color":"black","border_width":4,"radius":10,"text":"","text_font":32,"align":"center","text_color":"black","toggle":false,"click":false,"long_mode":1}',
    '{"page":1,"id":3,"obj":"btn","x":2,"y":242,"w":236,"h":236,"bg_color":"#000000","border_color":"black","border_width":4,"radius":10,"text":"","text_font":32,"align":"center","text_color":"black","toggle":false,"click":false,"long_mode":1}',
    '{"page":1,"id":4,"obj":"btn","x":242,"y":242,"w":236,"h":236,"bg_color":"#000000","border_color":"black","border_width":4,"radius":10,"text":"","text_font":32,"align":"center","text_color":"black","toggle":false,"click":false,"long_mode":1}'
]
@Field static final List<String> LAYOUT_2x2_ICON = [
    '{"page":1,"id":17,"obj":"label","parentid":0,"x":8,"y":8,"w":36,"h":36,"bg_opa":0,"border_width":0,"text":"","text_font":24,"text_color":"black","click":false}',
    '{"page":1,"id":18,"obj":"label","parentid":0,"x":248,"y":8,"w":36,"h":36,"bg_opa":0,"border_width":0,"text":"","text_font":24,"text_color":"black","click":false}',
    '{"page":1,"id":19,"obj":"label","parentid":0,"x":8,"y":248,"w":36,"h":36,"bg_opa":0,"border_width":0,"text":"","text_font":24,"text_color":"black","click":false}',
    '{"page":1,"id":20,"obj":"label","parentid":0,"x":248,"y":248,"w":36,"h":36,"bg_opa":0,"border_width":0,"text":"","text_font":24,"text_color":"black","click":false}'
]
@Field static final List<String> LAYOUT_3x3_BG = [
    '{"page":1,"id":1,"obj":"btn","x":2,"y":2,"w":157,"h":157,"bg_color":"#000000","border_color":"black","border_width":4,"radius":10,"text":"","text_font":24,"align":"center","text_color":"black","toggle":false,"click":false,"long_mode":1}',
    '{"page":1,"id":2,"obj":"btn","x":161,"y":2,"w":157,"h":157,"bg_color":"#000000","border_color":"black","border_width":4,"radius":10,"text":"","text_font":24,"align":"center","text_color":"black","toggle":false,"click":false,"long_mode":1}',
    '{"page":1,"id":3,"obj":"btn","x":320,"y":2,"w":158,"h":157,"bg_color":"#000000","border_color":"black","border_width":4,"radius":10,"text":"","text_font":24,"align":"center","text_color":"black","toggle":false,"click":false,"long_mode":1}',
    '{"page":1,"id":4,"obj":"btn","x":2,"y":161,"w":157,"h":157,"bg_color":"#000000","border_color":"black","border_width":4,"radius":10,"text":"","text_font":24,"align":"center","text_color":"black","toggle":false,"click":false,"long_mode":1}',
    '{"page":1,"id":5,"obj":"btn","x":161,"y":161,"w":157,"h":157,"bg_color":"#000000","border_color":"black","border_width":4,"radius":10,"text":"","text_font":24,"align":"center","text_color":"black","toggle":false,"click":false,"long_mode":1}',
    '{"page":1,"id":6,"obj":"btn","x":320,"y":161,"w":158,"h":157,"bg_color":"#000000","border_color":"black","border_width":4,"radius":10,"text":"","text_font":24,"align":"center","text_color":"black","toggle":false,"click":false,"long_mode":1}',
    '{"page":1,"id":7,"obj":"btn","x":2,"y":320,"w":157,"h":158,"bg_color":"#000000","border_color":"black","border_width":4,"radius":10,"text":"","text_font":24,"align":"center","text_color":"black","toggle":false,"click":false,"long_mode":1}',
    '{"page":1,"id":8,"obj":"btn","x":161,"y":320,"w":157,"h":158,"bg_color":"#000000","border_color":"black","border_width":4,"radius":10,"text":"","text_font":24,"align":"center","text_color":"black","toggle":false,"click":false,"long_mode":1}',
    '{"page":1,"id":9,"obj":"btn","x":320,"y":320,"w":158,"h":158,"bg_color":"#000000","border_color":"black","border_width":4,"radius":10,"text":"","text_font":24,"align":"center","text_color":"black","toggle":false,"click":false,"long_mode":1}'
]
@Field static final List<String> LAYOUT_3x3_ICON = [
    '{"page":1,"id":17,"obj":"label","parentid":0,"x":8,"y":8,"w":36,"h":36,"bg_opa":0,"border_width":0,"text":"","text_font":24,"text_color":"black","click":false}',
    '{"page":1,"id":18,"obj":"label","parentid":0,"x":167,"y":8,"w":36,"h":36,"bg_opa":0,"border_width":0,"text":"","text_font":24,"text_color":"black","click":false}',
    '{"page":1,"id":19,"obj":"label","parentid":0,"x":326,"y":8,"w":36,"h":36,"bg_opa":0,"border_width":0,"text":"","text_font":24,"text_color":"black","click":false}',
    '{"page":1,"id":20,"obj":"label","parentid":0,"x":8,"y":167,"w":36,"h":36,"bg_opa":0,"border_width":0,"text":"","text_font":24,"text_color":"black","click":false}',
    '{"page":1,"id":21,"obj":"label","parentid":0,"x":167,"y":167,"w":36,"h":36,"bg_opa":0,"border_width":0,"text":"","text_font":24,"text_color":"black","click":false}',
    '{"page":1,"id":22,"obj":"label","parentid":0,"x":326,"y":167,"w":36,"h":36,"bg_opa":0,"border_width":0,"text":"","text_font":24,"text_color":"black","click":false}',
    '{"page":1,"id":23,"obj":"label","parentid":0,"x":8,"y":326,"w":36,"h":36,"bg_opa":0,"border_width":0,"text":"","text_font":24,"text_color":"black","click":false}',
    '{"page":1,"id":24,"obj":"label","parentid":0,"x":167,"y":326,"w":36,"h":36,"bg_opa":0,"border_width":0,"text":"","text_font":24,"text_color":"black","click":false}',
    '{"page":1,"id":25,"obj":"label","parentid":0,"x":326,"y":326,"w":36,"h":36,"bg_opa":0,"border_width":0,"text":"","text_font":24,"text_color":"black","click":false}'
]
@Field static final List<String> LAYOUT_4x4_BG = [
    '{"page":1,"id":1,"obj":"btn","x":2,"y":2,"w":117,"h":117,"bg_color":"#000000","border_color":"black","border_width":3,"radius":8,"text":"","text_font":16,"align":"center","text_color":"black","toggle":false,"click":false,"long_mode":1}',
    '{"page":1,"id":2,"obj":"btn","x":121,"y":2,"w":117,"h":117,"bg_color":"#000000","border_color":"black","border_width":3,"radius":8,"text":"","text_font":16,"align":"center","text_color":"black","toggle":false,"click":false,"long_mode":1}',
    '{"page":1,"id":3,"obj":"btn","x":240,"y":2,"w":117,"h":117,"bg_color":"#000000","border_color":"black","border_width":3,"radius":8,"text":"","text_font":16,"align":"center","text_color":"black","toggle":false,"click":false,"long_mode":1}',
    '{"page":1,"id":4,"obj":"btn","x":359,"y":2,"w":119,"h":117,"bg_color":"#000000","border_color":"black","border_width":3,"radius":8,"text":"","text_font":16,"align":"center","text_color":"black","toggle":false,"click":false,"long_mode":1}',
    '{"page":1,"id":5,"obj":"btn","x":2,"y":121,"w":117,"h":117,"bg_color":"#000000","border_color":"black","border_width":3,"radius":8,"text":"","text_font":16,"align":"center","text_color":"black","toggle":false,"click":false,"long_mode":1}',
    '{"page":1,"id":6,"obj":"btn","x":121,"y":121,"w":117,"h":117,"bg_color":"#000000","border_color":"black","border_width":3,"radius":8,"text":"","text_font":16,"align":"center","text_color":"black","toggle":false,"click":false,"long_mode":1}',
    '{"page":1,"id":7,"obj":"btn","x":240,"y":121,"w":117,"h":117,"bg_color":"#000000","border_color":"black","border_width":3,"radius":8,"text":"","text_font":16,"align":"center","text_color":"black","toggle":false,"click":false,"long_mode":1}',
    '{"page":1,"id":8,"obj":"btn","x":359,"y":121,"w":119,"h":117,"bg_color":"#000000","border_color":"black","border_width":3,"radius":8,"text":"","text_font":16,"align":"center","text_color":"black","toggle":false,"click":false,"long_mode":1}',
    '{"page":1,"id":9,"obj":"btn","x":2,"y":240,"w":117,"h":117,"bg_color":"#000000","border_color":"black","border_width":3,"radius":8,"text":"","text_font":16,"align":"center","text_color":"black","toggle":false,"click":false,"long_mode":1}',
    '{"page":1,"id":10,"obj":"btn","x":121,"y":240,"w":117,"h":117,"bg_color":"#000000","border_color":"black","border_width":3,"radius":8,"text":"","text_font":16,"align":"center","text_color":"black","toggle":false,"click":false,"long_mode":1}',
    '{"page":1,"id":11,"obj":"btn","x":240,"y":240,"w":117,"h":117,"bg_color":"#000000","border_color":"black","border_width":3,"radius":8,"text":"","text_font":16,"align":"center","text_color":"black","toggle":false,"click":false,"long_mode":1}',
    '{"page":1,"id":12,"obj":"btn","x":359,"y":240,"w":119,"h":117,"bg_color":"#000000","border_color":"black","border_width":3,"radius":8,"text":"","text_font":16,"align":"center","text_color":"black","toggle":false,"click":false,"long_mode":1}',
    '{"page":1,"id":13,"obj":"btn","x":2,"y":359,"w":117,"h":119,"bg_color":"#000000","border_color":"black","border_width":3,"radius":8,"text":"","text_font":16,"align":"center","text_color":"black","toggle":false,"click":false,"long_mode":1}',
    '{"page":1,"id":14,"obj":"btn","x":121,"y":359,"w":117,"h":119,"bg_color":"#000000","border_color":"black","border_width":3,"radius":8,"text":"","text_font":16,"align":"center","text_color":"black","toggle":false,"click":false,"long_mode":1}',
    '{"page":1,"id":15,"obj":"btn","x":240,"y":359,"w":117,"h":119,"bg_color":"#000000","border_color":"black","border_width":3,"radius":8,"text":"","text_font":16,"align":"center","text_color":"black","toggle":false,"click":false,"long_mode":1}',
    '{"page":1,"id":16,"obj":"btn","x":359,"y":359,"w":119,"h":119,"bg_color":"#000000","border_color":"black","border_width":3,"radius":8,"text":"","text_font":16,"align":"center","text_color":"black","toggle":false,"click":false,"long_mode":1}'
]
@Field static final List<String> LAYOUT_4x4_ICON = [
    '{"page":1,"id":17,"obj":"label","parentid":0,"x":7,"y":7,"w":26,"h":26,"bg_opa":0,"border_width":0,"text":"","text_font":16,"text_color":"black","click":false}',
    '{"page":1,"id":18,"obj":"label","parentid":0,"x":126,"y":7,"w":26,"h":26,"bg_opa":0,"border_width":0,"text":"","text_font":16,"text_color":"black","click":false}',
    '{"page":1,"id":19,"obj":"label","parentid":0,"x":245,"y":7,"w":26,"h":26,"bg_opa":0,"border_width":0,"text":"","text_font":16,"text_color":"black","click":false}',
    '{"page":1,"id":20,"obj":"label","parentid":0,"x":364,"y":7,"w":26,"h":26,"bg_opa":0,"border_width":0,"text":"","text_font":16,"text_color":"black","click":false}',
    '{"page":1,"id":21,"obj":"label","parentid":0,"x":7,"y":126,"w":26,"h":26,"bg_opa":0,"border_width":0,"text":"","text_font":16,"text_color":"black","click":false}',
    '{"page":1,"id":22,"obj":"label","parentid":0,"x":126,"y":126,"w":26,"h":26,"bg_opa":0,"border_width":0,"text":"","text_font":16,"text_color":"black","click":false}',
    '{"page":1,"id":23,"obj":"label","parentid":0,"x":245,"y":126,"w":26,"h":26,"bg_opa":0,"border_width":0,"text":"","text_font":16,"text_color":"black","click":false}',
    '{"page":1,"id":24,"obj":"label","parentid":0,"x":364,"y":126,"w":26,"h":26,"bg_opa":0,"border_width":0,"text":"","text_font":16,"text_color":"black","click":false}',
    '{"page":1,"id":25,"obj":"label","parentid":0,"x":7,"y":245,"w":26,"h":26,"bg_opa":0,"border_width":0,"text":"","text_font":16,"text_color":"black","click":false}',
    '{"page":1,"id":26,"obj":"label","parentid":0,"x":126,"y":245,"w":26,"h":26,"bg_opa":0,"border_width":0,"text":"","text_font":16,"text_color":"black","click":false}',
    '{"page":1,"id":27,"obj":"label","parentid":0,"x":245,"y":245,"w":26,"h":26,"bg_opa":0,"border_width":0,"text":"","text_font":16,"text_color":"black","click":false}',
    '{"page":1,"id":28,"obj":"label","parentid":0,"x":364,"y":245,"w":26,"h":26,"bg_opa":0,"border_width":0,"text":"","text_font":16,"text_color":"black","click":false}',
    '{"page":1,"id":29,"obj":"label","parentid":0,"x":7,"y":364,"w":26,"h":26,"bg_opa":0,"border_width":0,"text":"","text_font":16,"text_color":"black","click":false}',
    '{"page":1,"id":30,"obj":"label","parentid":0,"x":126,"y":364,"w":26,"h":26,"bg_opa":0,"border_width":0,"text":"","text_font":16,"text_color":"black","click":false}',
    '{"page":1,"id":31,"obj":"label","parentid":0,"x":245,"y":364,"w":26,"h":26,"bg_opa":0,"border_width":0,"text":"","text_font":16,"text_color":"black","click":false}',
    '{"page":1,"id":32,"obj":"label","parentid":0,"x":364,"y":364,"w":26,"h":26,"bg_opa":0,"border_width":0,"text":"","text_font":16,"text_color":"black","click":false}'
]

def pushLayout() {
    String node  = settings.haspNode ?: "plate"
    String topic = "hasp/${node}/command/jsonl"

    List<String> bgs, icons
    switch (activeGrid()) {
        case "4x4": bgs = LAYOUT_4x4_BG; icons = LAYOUT_4x4_ICON; break
        case "3x3": bgs = LAYOUT_3x3_BG; icons = LAYOUT_3x3_ICON; break
        default:    bgs = LAYOUT_2x2_BG; icons = LAYOUT_2x2_ICON; break
    }

    infoLog "[SenseCAP] Pushing ${activeGrid()} layout (${bgs.size() + icons.size()} objects) → ${topic}"
    try {
        // Clear page 1 before pushing new layout so stale objects from a
        // previous grid size don't remain visible underneath the new tiles
        interfaces.mqtt.publish("hasp/${node}/command/clearpage", "1", 1, false)
        pauseExecution(300)
        publishBatch(topic, bgs)
        publishBatch(topic, icons)
        infoLog "[SenseCAP] Layout push complete"
        // Layout is pushed blank — the app's initialize() will push types,
        // labels and sync sensor states on its own schedule
    } catch (Exception e) {
        infoLog "[SenseCAP] ERROR — Layout push failed: ${e.message}"
    }
}

/**
 * publishBatch — splits a list of JSONL lines into ≤1800-byte chunks and
 * publishes each chunk as a single newline-delimited MQTT payload.
 * Stays well under openHASP's 2048-byte MQTT receive buffer limit.
 */
private void publishBatch(String topic, List<String> lines) {
    String node = settings.haspNode ?: "plate"
    List<String> chunk = []
    int chunkSize = 0
    lines.each { line ->
        int lineSize = line.getBytes("UTF-8").length + 1  // +1 for newline
        if (chunkSize + lineSize > 1800 && chunk) {
            interfaces.mqtt.publish(topic, chunk.join("\n"), 1, false)
            pauseExecution(150)
            chunk = []
            chunkSize = 0
        }
        chunk << line
        chunkSize += lineSize
    }
    if (chunk) {
        interfaces.mqtt.publish(topic, chunk.join("\n"), 1, false)
        pauseExecution(150)
    }
}

// ── Labels, Icons & Resync ────────────────────────────────────────────────────

def fireDisplayRebooted() {
    infoLog "[SenseCAP] Firing displayRebooted event — app will sync sensor states"
    sendEvent(name: "displayRebooted", value: new Date().toString(), isStateChange: true)
}

def resyncLabels() {
    if (state.labels) updateLabels(state.labels)
    resyncIcons()
}

/**
 * updateLabels — publishes centered label text to each btn object.
 * Map format: [ 1: "Living room", 2: "Front door", ... ]
 */
def updateLabels(Map labels) {
    String node = settings.haspNode ?: "plate"
    state.labels = labels
    debugLog "Updating labels: ${labels}"
    try {
        String batch = labels.collect { idx, text ->
            int obj = bgId(idx as int)
            String escaped = text.toString()
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
            "{\"p1b${obj}.text\":\"${escaped}\",\"p1b${obj}.align\":\"center\"}"
        }.join("\n")
        // Use the command/update topic which accepts property key:value pairs
        // Fall back to individual jsonl publishes if batch update not available
        labels.each { idx, text ->
            int obj = bgId(idx as int)
            String escaped = text.toString()
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
            String topic   = "hasp/${node}/command/p1b${obj}.jsonl"
            String payload = "{\"text\":\"${escaped}\",\"align\":\"center\"}"
            interfaces.mqtt.publish(topic, payload, 1, false)
        }
        debugLog "Labels updated (${labels.size()} slots)"
    } catch (Exception e) {
        infoLog "[SenseCAP] ERROR — label update failed: ${e.message}"
    }
}


/**
 * resyncIcons — publishes the correct icon for every slot based on current
 * sensor state. Called after layout push and on display reboot. Does NOT
 * store anything in state — icons are always derived fresh from sensor state
 * and slot type, so stale stored values can never cause a flash.
 */
def resyncIcons() {
    debugLog "resyncIcons — repainting ${maxSensors()} icon slots"
    (1..maxSensors()).each { idx ->
        String currentState = state["sensor${idx}"] ?: "inactive"
        String glyph = (currentState == "active") ? activeIconFor(idx) : inactiveIconFor(idx)
        publishIcon(idx, glyph)
    }
}

/**
 * updateSlotTypes — records sensor type per slot so the driver uses the correct
 * inactive color. Triggers a color resync once types are stored.
 * Map format: [ 1: "motion", 2: "contact", 3: "water", ... ]
 */
def updateSlotTypes(Map slotTypes) {
    debugLog "Updating slot types: ${slotTypes}"
    slotTypes.each { idx, type ->
        state["slotType${idx}"] = type
        sendEvent(name: "sensor${idx}Type", value: type)
    }
    // resyncStates() intentionally omitted — syncAllSensors (called by app
    // after pushLayout completes) handles color+icon sync in the correct order.
}

def setAllInactive() {
    (1..maxSensors()).each { idx -> setMotionInactive(idx) }
}

def resyncStates() {
    (1..maxSensors()).each { idx ->
        String currentState = state["sensor${idx}"] ?: "inactive"
        if (currentState == "active") {
            String aC = settings.colorActive ?: "#FF0000"
            publishColor(idx, aC)
            publishTextColor(idx, aC)
            publishIcon(idx, activeIconFor(idx))
        } else if (state["fadeStep${idx}"] != null) {
            // Resyncing mid-fade — show active color; fade will resume naturally
            publishColor(idx, settings.colorActive ?: "#FF0000")
            publishTextColor(idx, settings.colorActive ?: "#FF0000")
            publishIcon(idx, inactiveIconFor(idx))
        } else {
            String iC = inactiveColorFor(idx)
            publishColor(idx, iC)
            publishTextColor(idx, iC)
            publishIcon(idx, inactiveIconFor(idx))
        }
    }
}

// ── Heartbeat ─────────────────────────────────────────────────────────────────

def sendHeartbeat() {
    String node  = settings.haspNode ?: "plate"
    String topic = "hasp/${node}/command/page"
    try {
        interfaces.mqtt.publish(topic, "", 1, false)
        state.lastHeartbeatMs = now()
        debugLog "Heartbeat sent"
    } catch (Exception e) {
        infoLog "[SenseCAP] WARN — Heartbeat failed: ${e.message}"
        runIn(5, connectMqtt)
    }
}

// ── Backlight helpers ────────────────────────────────────────────────────────

def backlightOff() {
    if (allInactive()) {
        debugLog "All sensors green — turning backlight off"
        publishBacklight(false)
    } else {
        debugLog "backlightOff skipped — motion still active"
    }
}

def backlightOnAfterFade() {
    boolean anyActive = (1..maxSensors()).any { idx -> state["sensor${idx}"] == "active" }
    if (anyActive) { debugLog "backlightOnAfterFade skipped — motion became active again"; return }
    infoLog "[SenseCAP] All sensors green after fade — turning backlight on"
    state.screenIdle = false
    publishBacklight(true)
    int delay = (settings.touchBacklightTimeout ?: 30) as int
    if (delay > 0) runIn(delay, backlightOff)
}

def motionTimeoutBacklightOff() {
    boolean anyTrulyActive = (1..maxSensors()).any { idx -> state["sensor${idx}"] == "active" }
    if (anyTrulyActive) {
        infoLog "[SenseCAP] Motion persisted past timeout — turning backlight off"
        publishBacklight(false)
        unschedule(extendedMotionBacklightOn)
        int mins = (settings.extendedMotionBacklightOn ?: 10) as int
        if (mins > 0) {
            debugLog "Scheduling backlight back on in ${mins} min if motion still active"
            runIn(mins * 60, extendedMotionBacklightOn)
        }
    } else {
        debugLog "motionTimeoutBacklightOff skipped — no sensors truly active"
    }
}

def extendedMotionBacklightOn() {
    boolean anyTrulyActive = (1..maxSensors()).any { idx -> state["sensor${idx}"] == "active" }
    if (anyTrulyActive) {
        infoLog "[SenseCAP] Motion still active after extended period — turning backlight back on"
        publishBacklight(true)
        int mins = (settings.motionBacklightTimeout ?: 1) as int
        if (mins > 0) runIn(mins * 60, motionTimeoutBacklightOff)
    } else {
        debugLog "extendedMotionBacklightOn skipped — no sensors truly active"
    }
}

private void publishBacklight(boolean on) {
    String node    = settings.haspNode ?: "plate"
    String topic   = "hasp/${node}/command/backlight"
    String payload = on ? "on" : "off"
    debugLog "Backlight → ${payload}"
    try {
        interfaces.mqtt.publish(topic, payload, 1, false)
    } catch (Exception e) {
        infoLog "[SenseCAP] ERROR — Backlight publish failed: ${e.message}"
    }
}

private boolean allInactive() {
    return (1..maxSensors()).every { idx ->
        (state["sensor${idx}"] ?: "inactive") == "inactive" &&
        state["fadeStep${idx}"] == null
    }
}

private String inactiveColorFor(int idx) {
    switch (state["slotType${idx}"]) {
        case "water":   return settings.colorWaterInactive   ?: "#0000FF"
        case "contact": return settings.colorContactInactive ?: "#00FFFF"
        case "smoke":   return settings.colorSmokeInactive   ?: "#FFFF00"
        default:        return settings.colorInactive        ?: "#008000"
    }
}

/**
 * publishColor — sends bg_color to the bg obj for the given slot.
 * Text and icon label children have bg_opa=0 so they never need color updates.
 */
private void publishColor(int sensorIdx, String colorHex) {
    int    page  = 1
    String node  = settings.haspNode ?: "plate"
    String color = colorHex.startsWith("#") ? colorHex : "#${colorHex}"
    int    obj   = bgId(sensorIdx)
    String topic = "hasp/${node}/command/p${page}b${obj}.bg_color"
    debugLog "Publish → ${topic} : ${color}"
    try {
        interfaces.mqtt.publish(topic, color, 1, false)
    } catch (Exception e) {
        infoLog "[SenseCAP] ERROR — Publish failed: ${e.message}"
        sendEvent(name: "mqttStatus", value: "Publish Error")
        runIn(5, connectMqtt)
    }
}

private void infoLog(String msg) {
    if ((settings.logLevel ?: "1") != "0") log.info msg
}

private void debugLog(String msg) {
    if ((settings.logLevel ?: "1") == "2") log.debug msg
}
