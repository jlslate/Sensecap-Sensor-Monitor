/*
 * This is free and unencumbered software released into the public domain.
 * For more information, please refer to <https://unlicense.org>
 */

/**
 * SenseCAP Sensor Monitor Driver
 *
 * Five independent pages, each with its own grid layout and sensor slots.
 * Navigation buttons on every page cycle forward (▶) and back (◀).
 *
 * Author: jlslate (slate)
 * Version: 3.5.0 — five pages, consistent p1/p2/p3/p4/p5 naming
 */

import groovy.transform.Field

metadata {
    definition(
        name: "SenseCAP Sensor Monitor",
        namespace: "community",
        author: "jlslate (slate)",
        description: "SenseCAP Sensor Monitor driver — displays sensor states on SenseCAP Indicator D1 via MQTT/openHASP",
        version: "4.1.0",
        date: "2026-05-30"
    ) {
        capability "Initialize"
        capability "Actuator"

        // User-facing commands
        command "reconnectMqtt"
        command "setAllInactive"
        command "pushAllLayouts",          [[name:"numberOfPages", type:"NUMBER"]]

        // Internal commands called by the app
        command "setNumberOfPages",       [[name:"n", type:"NUMBER"]]
        command "setPage1GridLayout",     [[name:"g", type:"STRING"]]
        command "setPage2GridLayout",     [[name:"g", type:"STRING"]]
        command "setPage3GridLayout",     [[name:"g", type:"STRING"]]
        command "setPage4GridLayout",     [[name:"g", type:"STRING"]]
        command "setPage5GridLayout",     [[name:"g", type:"STRING"]]
        command "setPage1MotionActive",   [[name:"sensorIndex", type:"NUMBER"]]
        command "setPage1MotionInactive", [[name:"sensorIndex", type:"NUMBER"]]
        command "setPage1SlotEmpty",      [[name:"sensorIndex", type:"NUMBER"]]
        command "setPage2MotionActive",   [[name:"sensorIndex", type:"NUMBER"]]
        command "setPage2MotionInactive", [[name:"sensorIndex", type:"NUMBER"]]
        command "setPage2SlotEmpty",      [[name:"sensorIndex", type:"NUMBER"]]
        command "setPage3MotionActive",   [[name:"sensorIndex", type:"NUMBER"]]
        command "setPage3MotionInactive", [[name:"sensorIndex", type:"NUMBER"]]
        command "setPage3SlotEmpty",      [[name:"sensorIndex", type:"NUMBER"]]
        command "setPage4MotionActive",   [[name:"sensorIndex", type:"NUMBER"]]
        command "setPage4MotionInactive", [[name:"sensorIndex", type:"NUMBER"]]
        command "setPage4SlotEmpty",      [[name:"sensorIndex", type:"NUMBER"]]
        command "setPage5MotionActive",   [[name:"sensorIndex", type:"NUMBER"]]
        command "setPage5MotionInactive", [[name:"sensorIndex", type:"NUMBER"]]
        command "setPage5SlotEmpty",      [[name:"sensorIndex", type:"NUMBER"]]
        command "updatePage1Labels",      [[name:"labels",    type:"JSON_OBJECT"]]
        command "updatePage1SlotTypes",   [[name:"slotTypes", type:"JSON_OBJECT"]]
        command "updatePage2Labels",      [[name:"labels",    type:"JSON_OBJECT"]]
        command "updatePage2SlotTypes",   [[name:"slotTypes", type:"JSON_OBJECT"]]
        command "updatePage3Labels",      [[name:"labels",    type:"JSON_OBJECT"]]
        command "updatePage3SlotTypes",   [[name:"slotTypes", type:"JSON_OBJECT"]]
        command "updatePage4Labels",      [[name:"labels",    type:"JSON_OBJECT"]]
        command "updatePage4SlotTypes",   [[name:"slotTypes", type:"JSON_OBJECT"]]
        command "updatePage5Labels",      [[name:"labels",    type:"JSON_OBJECT"]]
        command "updatePage5SlotTypes",   [[name:"slotTypes", type:"JSON_OBJECT"]]

        attribute "mqttStatus",      "string"
        attribute "displayRebooted", "string"
        (1..5).each { pg ->
            attribute "page${pg}GridLayout", "string"
            (1..49).each { i ->
                attribute "p${pg}sensor${i}Status", "string"
                attribute "p${pg}sensor${i}Type",   "string"
            }
        }
    }

    preferences {
        // ── MQTT Connection ───────────────────────────────────────────────────
        input name: "mqttBroker",   type: "text",     title: "<b>MQTT Broker</b> (host:port)", required: true, defaultValue: "tcp://127.0.0.1:1883"
        input name: "mqttPassword", type: "password", title: "MQTT Password", required: true,
              description: "Found in Hubitat → Integrations → MQTT Broker"
        input name: "mqttClientId", type: "text",     title: "MQTT Client ID", required: true, defaultValue: "hubitat-sensecap-driver"
        input name: "haspNode",     type: "text",     title: "<b>openHASP Node Name</b>", required: true, defaultValue: "plate",
              description: "The 'Node name' from openHASP Settings → MQTT. Check logs after saving — a warning will appear if this is wrong."

        // ── Grid Layouts ──────────────────────────────────────────────────────
        input name: "page1GridLayout", type: "enum", title: "<b>Page 1</b> Grid Layout", options: gridOptions(), defaultValue: "2x2", required: true
        input name: "page2GridLayout", type: "enum", title: "<b>Page 2</b> Grid Layout", options: gridOptions(), defaultValue: "2x2", required: true
        input name: "page3GridLayout", type: "enum", title: "<b>Page 3</b> Grid Layout", options: gridOptions(), defaultValue: "2x2", required: true
        input name: "page4GridLayout", type: "enum", title: "<b>Page 4</b> Grid Layout", options: gridOptions(), defaultValue: "2x2", required: true
        input name: "page5GridLayout", type: "enum", title: "<b>Page 5</b> Grid Layout", options: gridOptions(), defaultValue: "2x2", required: true

        // ── Tile Colors ───────────────────────────────────────────────────────
        input name: "colorActive",          type: "enum", title: "<b>Active color</b> (any sensor triggered)",
              options: ["#FF0000":"Red","#FF4500":"Orange-red","#FF8C00":"Dark orange","#FF1493":"Deep pink",
                        "#8B0000":"Dark red","#FF6347":"Tomato","#DC143C":"Crimson","#FF0080":"Hot magenta"],
              defaultValue: "#FF0000", required: true
        input name: "colorInactive",        type: "enum", title: "Inactive — Motion",  options: colorOptions(), defaultValue: "#008000", required: true
        input name: "colorContactInactive", type: "enum", title: "Inactive — Contact", options: colorOptions(), defaultValue: "#00FFFF", required: true
        input name: "colorWaterInactive",   type: "enum", title: "Inactive — Water",   options: colorOptions(), defaultValue: "#0000FF", required: true
        input name: "colorSmokeInactive",   type: "enum", title: "Inactive — Smoke",   options: colorOptions(), defaultValue: "#FFFF00", required: true

        // ── Backlight ─────────────────────────────────────────────────────────
        input name: "backlightOnMotion",         type: "bool",   title: "<b>Backlight ON</b> when any sensor is active",         defaultValue: true
        input name: "motionBacklightTimeout",    type: "number", title: "Turn backlight OFF after active for (minutes, 0=never)", defaultValue: 1
        input name: "extendedMotionBacklightOn", type: "number", title: "Turn backlight back ON after off for (minutes, 0=never)", defaultValue: 10
        input name: "backlightOffDelay",         type: "number", title: "Turn backlight OFF after all clear (seconds, 0=never)",  defaultValue: 0
        input name: "touchBacklightTimeout",     type: "number", title: "Turn backlight OFF after screen tap (seconds, 0=never)", defaultValue: 30

        // ── Advanced ──────────────────────────────────────────────────────────
        input name: "fadeDuration", type: "number", title: "Fade duration (seconds)", defaultValue: 30, required: true
        input name: "iconFont",     type: "number", title: "Icon font size (pt)",     defaultValue: 24, description: "MDI glyph size — 16, 24, or 32"
        input name: "rotationInterval",  type: "number", title: "Page rotation interval (seconds, 0 = off)", defaultValue: 10, required: true
        input name: "showPageIndicator", type: "bool",   title: "Show page indicator (e.g. 1/3)", defaultValue: true
        input name: "logLevel",     type: "enum",   title: "Logging Level",
              options: ["0":"None","1":"Info only","2":"Info + Debug"], defaultValue: "1", required: true
    }
}

private Map gridOptions() {
    ["1x1":"1×1 (1 sensor)","2x2":"2×2 (4 sensors)","3x3":"3×3 (9 sensors)",
     "4x4":"4×4 (16 sensors)","5x5":"5×5 (25 sensors)","6x6":"6×6 (36 sensors)","7x7":"7×7 (49 sensors)"]
}
private Map colorOptions() {
    ["#F8F8FF":"Ghost White","#D3D3D3":"Light Gray","#808080":"Gray","#800000":"Maroon",
     "#FF00FF":"Magenta","#800080":"Purple","#0000FF":"Blue","#000080":"Navy","#00FFFF":"Cyan",
     "#008080":"Teal","#00FF00":"Lime","#008000":"Green","#FFFF00":"Yellow","#808000":"Olive"]
}

// ── Object ID helpers ──────────────────────────────────────────────────────────
private int bgId(int slot)   { slot }
private int iconId(int slot) { slot + 50 }

// ── Lifecycle ──────────────────────────────────────────────────────────────────
def installed() {
    log.info "[SenseCAP] Driver installed"
    try { device.setLabel("SenseCAP Sensor Display") } catch (Exception e) { }
    try { device.setName("SenseCAP Sensor Display") }  catch (Exception e) { }
}

def updated() {
    infoLog "[SenseCAP] Preferences updated — reconnecting"
    try { device.setLabel("SenseCAP Sensor Display") } catch (Exception e) { }
    try { device.setName("SenseCAP Sensor Display") }  catch (Exception e) { }
    initialize()
}

def initialize() {
    (1..5).each { pg ->
        String g = activeGrid(pg)
        state["page${pg}GridLayout"] = g
        sendEvent(name: "page${pg}GridLayout", value: g)
    }
    // Only reconnect if not already connected — avoids disrupting MQTT on app Done
    String mqttSt = device.currentValue("mqttStatus") ?: ""
    if (!mqttSt.startsWith("Connected")) {
        connectMqtt()
    } else {
        infoLog "[SenseCAP] MQTT already connected — skipping reconnect"
    }
    unschedule("sendHeartbeat")
    runEvery5Minutes("sendHeartbeat")
}

def uninstalled() { disconnectMqtt() }

def schedulePushAllPages() {
    // If pushAllLayouts was called recently (within 60s), skip — app already handling it
    long msSinceLastPush = now() - (state.lastPushMs ?: 0L)
    if (msSinceLastPush < 60000) {
        infoLog "[SenseCAP] schedulePushAllPages skipped — pushAllLayouts ran ${msSinceLastPush}ms ago"
        return
    }
    int np = (state.numberOfPages ?: 1) as int
    unschedule("pushPage1Layout")
    unschedule("pushPage2Layout")
    unschedule("pushPage3Layout")
    unschedule("pushPage4Layout")
    unschedule("pushPage5Layout")
    infoLog "[SenseCAP] schedulePushAllPages — ${np} page(s)"
    runIn(2, "pushPage1Layout")
    if (np >= 2) runIn(5,  "pushPage2Layout")
    if (np >= 3) runIn(8,  "pushPage3Layout")
    if (np >= 4) runIn(11, "pushPage4Layout")
    if (np >= 5) runIn(14, "pushPage5Layout")
}

def pushAllLayouts(numberOfPages) {
    int np = Math.min(5, Math.max(1, (numberOfPages as int)))
    // Store BEFORE any push so layoutJsonl reads the correct total
    state.numberOfPages = np
    state.np = np
    state.lastPushMs = now()
    state.rotationPage = 1
    unschedule("rotatePage")
    unschedule("schedulePushAllPages")
    unschedule("pushPage1Layout")
    unschedule("pushPage2Layout")
    unschedule("pushPage3Layout")
    unschedule("pushPage4Layout")
    unschedule("pushPage5Layout")
    infoLog "[SenseCAP] pushAllLayouts — ${np} page(s)"
    // Push page 1 immediately (synchronous) so state.numberOfPages is already set
    pushPage1Layout()
    if (np >= 2) runIn(3,  "pushPage2Layout")
    if (np >= 3) runIn(6,  "pushPage3Layout")
    if (np >= 4) runIn(9,  "pushPage4Layout")
    if (np >= 5) runIn(12, "pushPage5Layout")
}

// ── Grid helpers ───────────────────────────────────────────────────────────────
def setNumberOfPages(n) {
    int num = Math.min(5, Math.max(1, (n as int)))
    state.numberOfPages = num
    infoLog "[SenseCAP] Number of pages set to ${num}"
}

def setPage1GridLayout(String g) { state.page1GridLayout = g; sendEvent(name:"page1GridLayout", value:g); infoLog "[SenseCAP] Page 1 grid → ${g}" }
def setPage2GridLayout(String g) { state.page2GridLayout = g; sendEvent(name:"page2GridLayout", value:g); infoLog "[SenseCAP] Page 2 grid → ${g}" }
def setPage3GridLayout(String g) { state.page3GridLayout = g; sendEvent(name:"page3GridLayout", value:g); infoLog "[SenseCAP] Page 3 grid → ${g}" }
def setPage4GridLayout(String g) { state.page4GridLayout = g; sendEvent(name:"page4GridLayout", value:g); infoLog "[SenseCAP] Page 4 grid → ${g}" }
def setPage5GridLayout(String g) { state.page5GridLayout = g; sendEvent(name:"page5GridLayout", value:g); infoLog "[SenseCAP] Page 5 grid → ${g}" }

private String activeGrid(int page) {
    return (state["page${page}GridLayout"] ?: settings["page${page}GridLayout"] ?: "2x2") as String
}
private int maxSensors(int page) {
    switch (activeGrid(page)) {
        case "1x1": return 1;  case "3x3": return 9;  case "4x4": return 16
        case "5x5": return 25; case "6x6": return 36; case "7x7": return 49
        default:    return 4
    }
}
private String stateKey(int page, int idx)  { "p${page}sensor${idx}" }
private String typeKey(int page, int idx)   { "p${page}slotType${idx}" }
private String labelKey(int page, int idx)  { "p${page}label${idx}" }

// ── MQTT ───────────────────────────────────────────────────────────────────────
def connectMqtt() {
    if (!settings.mqttPassword) { infoLog "[SenseCAP] MQTT password not set — configure preferences first"; return }
    try {
        String broker   = settings.mqttBroker   ?: "tcp://127.0.0.1:1883"
        String clientId = settings.mqttClientId ?: "hubitat-sensecap-${device.id}"
        interfaces.mqtt.connect(broker, clientId, "hubitat", settings.mqttPassword)
        infoLog "[SenseCAP] MQTT connected → ${broker}"
        sendEvent(name: "mqttStatus", value: "Connected")
        String node = settings.haspNode ?: "plate"
        interfaces.mqtt.subscribe("hasp/${node}/state/statusupdate")
        interfaces.mqtt.subscribe("hasp/${node}/state/idle")
        interfaces.mqtt.subscribe("hasp/${node}/idle")
        interfaces.mqtt.subscribe("hasp/${node}/state/backlight")
        interfaces.mqtt.subscribe("hasp/${node}/backlight")
        // Wildcard to detect node name mismatch
        interfaces.mqtt.subscribe("hasp/+/LWT")
        interfaces.mqtt.subscribe("hasp/+/state/statusupdate")
        infoLog "[SenseCAP] Subscribed — node: ${node}. If tiles don't update, check the openHASP Node Name preference."
    } catch (Exception e) {
        infoLog "[SenseCAP] ERROR — MQTT connect failed: ${e.message}"
        sendEvent(name: "mqttStatus", value: "Error: ${e.message}")
        runIn(30, "connectMqtt")
    }
}

def disconnectMqtt() {
    try { interfaces.mqtt.disconnect() } catch (Exception e) { }
    sendEvent(name: "mqttStatus", value: "Disconnected")
}

def reconnectMqtt() { disconnectMqtt(); pauseExecution(1000); connectMqtt() }

def mqttClientStatus(String status) {
    infoLog "[SenseCAP] MQTT status: ${status}"
    sendEvent(name: "mqttStatus", value: status)
    if (status.startsWith("Error") || status.contains("lost")) runIn(30, "connectMqtt")
}

def sendHeartbeat() {
    state.lastHeartbeatMs = now()
    String node = settings.haspNode ?: "plate"
    try { interfaces.mqtt.publish("hasp/${node}/command", "statusupdate", 1, false) }
    catch (Exception e) { infoLog "[SenseCAP] Heartbeat failed — reconnecting"; reconnectMqtt() }
}

def parse(String description) {
    def msg = interfaces.mqtt.parseMessage(description)
    debugLog "MQTT: topic=${msg.topic} payload=${msg.payload}"

    if (msg.topic.endsWith("/LWT")) {
        String actualNode = msg.topic.split("/")[1]
        String configNode = settings.haspNode ?: "plate"
        if (actualNode != configNode) {
            log.warn "[SenseCAP] Node name mismatch! Device is '${actualNode}' but preference is '${configNode}' — update openHASP Node Name to '${actualNode}'"
            sendEvent(name: "mqttStatus", value: "Wrong node name — should be '${actualNode}'")
        }
        if (msg.payload?.trim() == "online") {
            infoLog "[SenseCAP] LWT online (node: ${actualNode})"
            runIn(5, "fireDisplayRebooted")
        }
    } else if (msg.topic.contains("statusupdate")) {
        if (!msg.payload?.trim()) return
        try {
            def json = new groovy.json.JsonSlurper().parseText(msg.payload)
            if (json.uptime == null) return
            int uptime = (json.uptime) as int
            if (uptime < 30) {
                infoLog "[SenseCAP] Display rebooted (uptime ${uptime}s)"
                runIn(5, "fireDisplayRebooted")
            } else {
                infoLog "[SenseCAP] Display woke from idle — resyncing"
                runIn(2, "resyncStates"); startBacklightTimer()
            }
        } catch (Exception e) { infoLog "[SenseCAP] WARN — Could not parse statusupdate: ${e.message}" }
    } else if (msg.topic.contains("state/idle") || msg.topic.endsWith("/idle")) {
        String v = msg.payload?.trim()
        if (v == "short" || v == "long") { state.screenIdle = true }
        else if (v == "off") {
            long ms = now() - (state.lastHeartbeatMs ?: 0L)
            if (ms >= 3000) { state.screenIdle = false; infoLog "[SenseCAP] Screen woke from touch"; startBacklightTimer() }
        }
    } else if (msg.topic.contains("state/backlight") || msg.topic.endsWith("/backlight")) {
        try {
            def json = new groovy.json.JsonSlurper().parseText(msg.payload)
            if (json.state == "off") { state.screenIdle = true }
            else if (json.state == "on" && state.screenIdle) { state.screenIdle = false; startBacklightTimer() }
        } catch (Exception e) { if (msg.payload?.trim() == "off") state.screenIdle = true }
    }
}

// ── Backlight ──────────────────────────────────────────────────────────────────
private void startBacklightTimer() {
    if (!settings.backlightOnMotion) return
    unschedule(backlightOff); unschedule(motionTimeoutBacklightOff)
    if (!allInactive()) {
        int mins = (settings.motionBacklightTimeout ?: 1) as int
        if (mins > 0) runIn(mins * 60, "motionTimeoutBacklightOff")
    } else {
        int delay = (settings.touchBacklightTimeout ?: 30) as int
        if (delay > 0) runIn(delay, "backlightOff")
    }
}
def backlightOff() { publishBacklight(false); state.screenIdle = true }
def backlightOnAfterFade() {
    if (!settings.backlightOnMotion || !allInactive()) return
    state.screenIdle = false; publishBacklight(true)
    int delay = (settings.backlightOffDelay ?: 0) as int
    if (delay > 0) runIn(delay, "backlightOff")
}
def motionTimeoutBacklightOff() {
    if (!settings.backlightOnMotion) return
    if (!allInactive()) {
        backlightOff()
        int ext = (settings.extendedMotionBacklightOn ?: 10) as int
        if (ext > 0) runIn(ext * 60, "extendedMotionBacklightOn")
    }
}
def extendedMotionBacklightOn() {
    if (!settings.backlightOnMotion || allInactive()) return
    state.screenIdle = false; publishBacklight(true)
    int mins = (settings.motionBacklightTimeout ?: 1) as int
    if (mins > 0) runIn(mins * 60, "motionTimeoutBacklightOff")
}
private boolean allInactive() {
    int numPg = (state.numberOfPages ?: 1) as int
    (1..numPg).every { pg -> (1..maxSensors(pg)).every { state[stateKey(pg, it)] != "active" } }
}
private void publishBacklight(boolean on) {
    String node = settings.haspNode ?: "plate"
    try { interfaces.mqtt.publish("hasp/${node}/command/backlight", on ? '{"state":"on","brightness":255}' : '{"state":"off"}', 1, false) }
    catch (Exception e) { infoLog "[SenseCAP] ERROR — Backlight publish failed: ${e.message}" }
}

// ── Resync ─────────────────────────────────────────────────────────────────────
def resyncStates() {
    infoLog "[SenseCAP] Resyncing all pages"
    int numPg = (state.numberOfPages ?: 1) as int
    (1..numPg).each { pg ->
        (1..maxSensors(pg)).each { idx ->
            String sk = stateKey(pg, idx)
            String st = state[sk] ?: "inactive"
            if (st == "empty")       { setSlotEmptyForPage(pg, idx) }
            else if (st == "active") { setMotionActiveForPage(pg, idx) }
            else {
                publishColor(pg, idx, inactiveColorFor(pg, idx))
                publishTextColor(pg, idx, inactiveColorFor(pg, idx))
                publishIcon(pg, idx, inactiveIconFor(pg, idx))
            }
        }
    }
}

def resyncLabels() {
    infoLog "[SenseCAP] Resyncing labels"
    String node = settings.haspNode ?: "plate"
    int numPgL = (state.numberOfPages ?: 1) as int
    (1..numPgL).each { pg ->
        (1..maxSensors(pg)).each { idx ->
            String lbl = state[labelKey(pg, idx)] ?: ""
            if (lbl) publishJsonl(node, pg, bgId(idx), [text: lbl])
        }
    }
}

def fireDisplayRebooted() { sendEvent(name: "displayRebooted", value: new Date().format("yyyy-MM-dd HH:mm:ss")) }

// ── Generic page commands ──────────────────────────────────────────────────────
private void setMotionActiveForPage(int page, int idx) {
    String sk = stateKey(page, idx); String tk = typeKey(page, idx)
    String sType = state[tk] ?: "none"
    state[sk] = "active"
    sendEvent(name: "${sk}Status", value: activeStatusLabel(sType))
    String fadeKey = "p${page}fadeStep${idx}"
    unschedule(fadeKey); state.remove(fadeKey)
    String ac = settings.colorActive ?: "#FF0000"
    publishColor(page, idx, ac); publishTextColor(page, idx, ac); publishIcon(page, idx, activeIconFor(page, idx))
    // Stop rotation and navigate to this page
    unschedule("rotatePage")
    state.rotationPage = page
    String node = settings.haspNode ?: "plate"
    try { interfaces.mqtt.publish("hasp/${node}/command/page", "${page}", 1, false) }
    catch (Exception e) { }

    if (settings.backlightOnMotion) {
        unschedule(backlightOff); unschedule(motionTimeoutBacklightOff)
        unschedule(extendedMotionBacklightOn); unschedule(backlightOnAfterFade)
        state.screenIdle = false; publishBacklight(true)
        int mins = (settings.motionBacklightTimeout ?: 1) as int
        if (mins > 0) runIn(mins * 60, "motionTimeoutBacklightOff")
    }
}

private void setMotionInactiveForPage(int page, int idx) {
    String sk = stateKey(page, idx); String tk = typeKey(page, idx)
    String sType = state[tk] ?: "none"
    String fadeKey = "p${page}fadeStep${idx}"
    boolean wasActive = (state[sk] == "active")
    state[sk] = "inactive"
    sendEvent(name: "${sk}Status", value: inactiveStatusLabel(sType))
    if (wasActive) {
        unschedule(fadeKey); state[fadeKey] = 0
        publishIcon(page, idx, inactiveIconFor(page, idx))
        scheduleFadeStep(page, idx)
        if (settings.backlightOnMotion) {
            unschedule(motionTimeoutBacklightOff)
            if (!allInactive()) {
                int mins = (settings.motionBacklightTimeout ?: 1) as int
                if (mins > 0) runIn(mins * 60, "motionTimeoutBacklightOff")
            } else {
                unschedule(extendedMotionBacklightOn)
                runIn((FADE_STEPS + 1) * fadeInterval() + 2, "backlightOnAfterFade")
            }
        }
    } else {
        String ic = inactiveColorFor(page, idx)
        publishColor(page, idx, ic); publishTextColor(page, idx, ic); publishIcon(page, idx, inactiveIconFor(page, idx))
        if (settings.backlightOnMotion && allInactive()) {
            int delay = (settings.backlightOffDelay ?: 0) as int
            if (delay > 0) runIn(delay, "backlightOff")
        }
    }
}

private void setSlotEmptyForPage(int page, int idx) {
    String sk = stateKey(page, idx); String fadeKey = "p${page}fadeStep${idx}"
    state[sk] = "empty"; sendEvent(name: "${sk}Status", value: "empty")
    unschedule(fadeKey); state.remove(fadeKey)
    publishColor(page, idx, "#708090"); publishTextColor(page, idx, "#708090"); publishIcon(page, idx, "")
    String node = settings.haspNode ?: "plate"
    publishJsonl(node, page, bgId(idx), [text: ""])
}

// ── Page commands ──────────────────────────────────────────────────────────────
def setPage1MotionActive(n)   { int i = n as int; if (i>=1 && i<=maxSensors(1)) setMotionActiveForPage(1,i) }
def setPage1MotionInactive(n) { int i = n as int; if (i>=1 && i<=maxSensors(1)) setMotionInactiveForPage(1,i) }
def setPage1SlotEmpty(n)      { int i = n as int; if (i>=1 && i<=maxSensors(1)) setSlotEmptyForPage(1,i) }
def setPage2MotionActive(n)   { int i = n as int; if (i>=1 && i<=maxSensors(2)) setMotionActiveForPage(2,i) }
def setPage2MotionInactive(n) { int i = n as int; if (i>=1 && i<=maxSensors(2)) setMotionInactiveForPage(2,i) }
def setPage2SlotEmpty(n)      { int i = n as int; if (i>=1 && i<=maxSensors(2)) setSlotEmptyForPage(2,i) }
def setPage3MotionActive(n)   { int i = n as int; if (i>=1 && i<=maxSensors(3)) setMotionActiveForPage(3,i) }
def setPage3MotionInactive(n) { int i = n as int; if (i>=1 && i<=maxSensors(3)) setMotionInactiveForPage(3,i) }
def setPage3SlotEmpty(n)      { int i = n as int; if (i>=1 && i<=maxSensors(3)) setSlotEmptyForPage(3,i) }
def setPage4MotionActive(n)   { int i = n as int; if (i>=1 && i<=maxSensors(4)) setMotionActiveForPage(4,i) }
def setPage4MotionInactive(n) { int i = n as int; if (i>=1 && i<=maxSensors(4)) setMotionInactiveForPage(4,i) }
def setPage4SlotEmpty(n)      { int i = n as int; if (i>=1 && i<=maxSensors(4)) setSlotEmptyForPage(4,i) }
def setPage5MotionActive(n)   { int i = n as int; if (i>=1 && i<=maxSensors(5)) setMotionActiveForPage(5,i) }
def setPage5MotionInactive(n) { int i = n as int; if (i>=1 && i<=maxSensors(5)) setMotionInactiveForPage(5,i) }
def setPage5SlotEmpty(n)      { int i = n as int; if (i>=1 && i<=maxSensors(5)) setSlotEmptyForPage(5,i) }

def setAllInactive() {
    int numPgA = (state.numberOfPages ?: 1) as int
    (1..numPgA).each { pg -> (1..maxSensors(pg)).each { idx ->
        if (state[stateKey(pg,idx)] != "empty") setMotionInactiveForPage(pg, idx)
    }}
}

// ── updateLabels / updateSlotTypes ─────────────────────────────────────────────
def updatePage1Labels(labels)    { applyLabels(labels, 1) }
def updatePage2Labels(labels)    { applyLabels(labels, 2) }
def updatePage3Labels(labels)    { applyLabels(labels, 3) }
def updatePage4Labels(labels)    { applyLabels(labels, 4) }
def updatePage5Labels(labels)    { applyLabels(labels, 5) }
def updatePage1SlotTypes(types)  { applySlotTypes(types, 1) }
def updatePage2SlotTypes(types)  { applySlotTypes(types, 2) }
def updatePage3SlotTypes(types)  { applySlotTypes(types, 3) }
def updatePage4SlotTypes(types)  { applySlotTypes(types, 4) }
def updatePage5SlotTypes(types)  { applySlotTypes(types, 5) }

private void applyLabels(labels, int page) {
    String node = settings.haspNode ?: "plate"
    labels.each { k, v ->
        int idx = (k as String).toInteger()
        if (idx < 1 || idx > maxSensors(page)) return
        String lbl = v?.toString() ?: ""; state[labelKey(page, idx)] = lbl
        publishJsonl(node, page, bgId(idx), [text: lbl])
    }
}

private void applySlotTypes(slotTypes, int page) {
    slotTypes.each { k, v ->
        int idx = (k as String).toInteger()
        if (idx < 1 || idx > maxSensors(page)) return
        state[typeKey(page, idx)] = (v?.toString() ?: "none")
        sendEvent(name: "${stateKey(page, idx)}Type", value: state[typeKey(page, idx)])
        // Re-publish icon and color now that type is known
        String st = state[stateKey(page, idx)] ?: "inactive"
        if (st != "active") {
            publishColor(page, idx, inactiveColorFor(page, idx))
            publishTextColor(page, idx, inactiveColorFor(page, idx))
            publishIcon(page, idx, inactiveIconFor(page, idx))
        }
    }
}

// ── Layout push ────────────────────────────────────────────────────────────────
def pushPage1Layout() {
    String node = settings.haspNode ?: "plate"
    // Turn on backlight
    publishBacklight(true)
    // Clear ALL pages first
    try { interfaces.mqtt.publish("hasp/${node}/command", "clearpage 0", 1, false); pauseExecution(500) }
    catch (Exception e) { infoLog "[SenseCAP] WARN — clearpage 0 failed: ${e.message}" }

    pushPageLayout(1)
}
def pushPage2Layout() { if (maxConfiguredPages() >= 2) pushPageLayout(2) }
def pushPage3Layout() { if (maxConfiguredPages() >= 3) pushPageLayout(3) }
def pushPage4Layout() { if (maxConfiguredPages() >= 4) pushPageLayout(4) }
def pushPage5Layout() { if (maxConfiguredPages() >= 5) pushPageLayout(5) }

private int maxConfiguredPages() { (state.numberOfPages ?: 1) as int }

private void pushPageLayout(int page) {
    String grid = activeGrid(page)
    int total = (state.numberOfPages ?: 1) as int
    infoLog "[SenseCAP] Pushing page ${page}/${total}: ${grid}"
    sendEvent(name: "mqttStatus", value: "Pushing page ${page}/${total}...")
    String node = settings.haspNode ?: "plate"

    // Clear this page first to remove any stale objects (especially old nav button types)
    try { interfaces.mqtt.publish("hasp/${node}/command", "clearpage ${page}", 1, false); pauseExecution(200) }
    catch (Exception e) { }

    // Navigate to this page so user can watch it build
    try { interfaces.mqtt.publish("hasp/${node}/command/page", "${page}", 1, false); pauseExecution(100) }
    catch (Exception e) { }

    // 1. Push tile layout
    layoutJsonl(grid, page).each { jsonl ->
        try { interfaces.mqtt.publish("hasp/${node}/command/jsonl", jsonl, 1, false); pauseExecution(50) }
        catch (Exception e) { infoLog "[SenseCAP] ERROR — pushLayout p${page}: ${e.message}" }
    }

    // 2. Push colors, icons and labels for every slot immediately after layout
    pauseExecution(300)
    (1..maxSensors(page)).each { idx ->
        String sk = stateKey(page, idx)
        String tk = typeKey(page, idx)
        String slotType = state[tk] ?: "none"
        String st = state[sk] ?: "inactive"
        // If slot has no type, it's empty; if it has a type but cached as empty, treat as inactive
        if (!slotType || slotType == "none") {
            st = "empty"
        } else if (st == "empty") {
            st = "inactive"
        }
        if (st == "active") {
            String ac = settings.colorActive ?: "#FF0000"
            publishColor(page, idx, ac)
            publishTextColor(page, idx, ac)
            publishIcon(page, idx, activeIconFor(page, idx))
        } else if (st == "empty") {
            publishColor(page, idx, "#708090")
            publishTextColor(page, idx, "#708090")
            publishIcon(page, idx, "")
        } else {
            String ic = inactiveColorFor(page, idx)
            publishColor(page, idx, ic)
            publishTextColor(page, idx, ic)
            publishIcon(page, idx, inactiveIconFor(page, idx))
        }
        // Push label
        String lbl = state[labelKey(page, idx)] ?: ""
        if (lbl) publishJsonl(node, page, bgId(idx), [text: lbl])
    }

    if (page == total) {
        // Pause 2 seconds on last page so user can see it, then return to page 1
        pauseExecution(2000)
        try { interfaces.mqtt.publish("hasp/${node}/command/page", "1", 1, false) } catch (Exception e) { }
        sendEvent(name: "mqttStatus", value: "Connected")
        infoLog "[SenseCAP] All ${total} page(s) pushed"
        // Start page rotation if more than one page
        if (total > 1) {
            int rotStart = (settings.rotationInterval ?: 10) as int
            if (rotStart > 0) runIn(rotStart, "rotatePage")
        }
    }
}

// ── Layout JSONL generators ────────────────────────────────────────────────────
private List<String> layoutJsonl(String grid, int page) {
    List<String> out
    switch (grid) {
        case "1x1": out = layout1x1(page); break
        case "3x3": out = layout3x3(page); break
        case "4x4": out = layout4x4(page); break
        case "5x5": out = layoutNxN(page, 5, 94, 2, 12, 2, 16); break
        case "6x6": out = layoutNxN(page, 6, 78, 2, 12, 2, 12); break
        case "7x7": out = layoutNxN(page, 7, 67, 1, 12, 1, 12); break
        default:    out = layout2x2(page)
    }
    int totalPages = (state.numberOfPages ?: 1) as int
    if (totalPages > 1) {
        int prevPage = (page == 1) ? totalPages : page - 1
        int nextPage = (page == totalPages) ? 1 : page + 1
        out << navBtn(page, 201, prevPage, "\u25C0", 2,   448)
        out << navBtn(page, 202, nextPage, "\u25B6", 436, 448)
        // Page indicator
        if (settings.showPageIndicator != false) {
            out << """{"page":${page},"id":200,"obj":"label","x":430,"y":2,"w":48,"h":22,"bg_color":"#000000","bg_opa":180,"border_width":0,"radius":4,"text":"${page}/${totalPages}","text_font":16,"text_color":"white","align":"center","click":false}"""
        }
    } else {
        // Single page — explicitly push hidden objects over any stale nav buttons/indicator
        out << """{"page":${page},"id":200,"obj":"label","x":0,"y":0,"w":1,"h":1,"bg_opa":0,"border_width":0,"text":"","text_font":8,"text_color":"black","click":false}"""
        out << """{"page":${page},"id":201,"obj":"label","x":0,"y":0,"w":1,"h":1,"bg_opa":0,"border_width":0,"text":"","text_font":8,"text_color":"black","click":false}"""
        out << """{"page":${page},"id":202,"obj":"label","x":0,"y":0,"w":1,"h":1,"bg_opa":0,"border_width":0,"text":"","text_font":8,"text_color":"black","click":false}"""
    }
    return out
}

private String navBtn(int page, int id, int dest, String label, int x, int y) {
    """{"page":${page},"id":${id},"obj":"btn","x":${x},"y":${y},"w":44,"h":28,"bg_color":"#333333","bg_opa":200,"border_width":0,"radius":6,"text":"${label}","text_font":16,"text_color":"white","toggle":false,"action":"p${dest}"}"""
}

private List<String> layout1x1(int page) {[
    """{"page":${page},"id":1,"obj":"btn","x":2,"y":2,"w":476,"h":476,"bg_color":"#000000","border_color":"black","border_width":4,"radius":10,"text":"","text_font":32,"align":"center","text_color":"black","toggle":false,"click":false}""",
    """{"page":${page},"id":51,"obj":"label","parentid":0,"x":8,"y":8,"w":36,"h":36,"bg_opa":0,"border_width":0,"text":"","text_font":24,"text_color":"black","click":false}"""
]}

private List<String> layout2x2(int page) {
    List<String> out = []
    [[1,2,2,236,236],[2,242,2,236,236],[3,2,242,236,236],[4,242,242,236,236]].each { r ->
        out << """{"page":${page},"id":${r[0]},"obj":"btn","x":${r[1]},"y":${r[2]},"w":${r[3]},"h":${r[4]},"bg_color":"#000000","border_color":"black","border_width":4,"radius":10,"text":"","text_font":32,"align":"center","text_color":"black","toggle":false,"click":false}"""
    }
    [[51,8,8],[52,248,8],[53,8,248],[54,248,248]].each { r ->
        out << """{"page":${page},"id":${r[0]},"obj":"label","parentid":0,"x":${r[1]},"y":${r[2]},"w":36,"h":36,"bg_opa":0,"border_width":0,"text":"","text_font":24,"text_color":"black","click":false}"""
    }
    return out
}

private List<String> layout3x3(int page) {
    List<String> out = []
    int[][] cells = [[2,2,157,157],[161,2,157,157],[320,2,158,157],[2,161,157,157],[161,161,157,157],[320,161,158,157],[2,320,157,158],[161,320,157,158],[320,320,158,158]]
    cells.eachWithIndex { c, i ->
        out << """{"page":${page},"id":${i+1},"obj":"btn","x":${c[0]},"y":${c[1]},"w":${c[2]},"h":${c[3]},"bg_color":"#000000","border_color":"black","border_width":4,"radius":10,"text":"","text_font":24,"align":"center","text_color":"black","toggle":false,"click":false}"""
    }
    int[][] icons = [[8,8],[167,8],[326,8],[8,167],[167,167],[326,167],[8,326],[167,326],[326,326]]
    icons.eachWithIndex { c, i ->
        out << """{"page":${page},"id":${i+51},"obj":"label","parentid":0,"x":${c[0]},"y":${c[1]},"w":36,"h":36,"bg_opa":0,"border_width":0,"text":"","text_font":24,"text_color":"black","click":false}"""
    }
    return out
}

private List<String> layout4x4(int page) {
    List<String> out = []; int cols = 4; int w = 117; int gap = 2
    (0..<cols).each { row -> (0..<cols).each { col ->
        int id = row*cols+col+1; int x = col*(w+gap)+gap; int y = row*(w+gap)+gap
        int tw = (col==cols-1) ? (480-x-gap) : w; int th = (row==cols-1) ? (480-y-gap) : w
        out << """{"page":${page},"id":${id},"obj":"btn","x":${x},"y":${y},"w":${tw},"h":${th},"bg_color":"#000000","border_color":"black","border_width":3,"radius":8,"text":"","text_font":16,"align":"center","text_color":"black","toggle":false,"click":false}"""
    }}
    int id2 = 51
    (0..<cols).each { row -> (0..<cols).each { col ->
        int x = col*(w+gap)+gap+4; int y = row*(w+gap)+gap+4
        out << """{"page":${page},"id":${id2},"obj":"label","parentid":0,"x":${x},"y":${y},"w":20,"h":20,"bg_opa":0,"border_width":0,"text":"","text_font":16,"text_color":"black","click":false}"""
        id2++
    }}
    return out
}

private List<String> layoutNxN(int page, int cols, int w, int gap, int tf, int iconOff, int iconFont) {
    List<String> out = []
    (0..<cols).each { row -> (0..<cols).each { col ->
        int id = row*cols+col+1; int x = col*(w+gap)+gap; int y = row*(w+gap)+gap
        int tw = (col==cols-1) ? (480-x-gap) : w; int th = (row==cols-1) ? (480-y-gap) : w
        out << """{"page":${page},"id":${id},"obj":"btn","x":${x},"y":${y},"w":${tw},"h":${th},"bg_color":"#000000","border_color":"black","border_width":1,"radius":4,"text":"","text_font":${tf},"align":"center","text_color":"black","toggle":false,"click":false}"""
    }}
    int id2 = 51
    (0..<cols).each { row -> (0..<cols).each { col ->
        int x = col*(w+gap)+gap+iconOff; int y = row*(w+gap)+gap+iconOff
        out << """{"page":${page},"id":${id2},"obj":"label","parentid":0,"x":${x},"y":${y},"w":${iconFont},"h":${iconFont},"bg_opa":0,"border_width":0,"text":"","text_font":${iconFont},"text_color":"black","click":false}"""
        id2++
    }}
    return out
}

// ── MQTT publish helpers ───────────────────────────────────────────────────────
private void publishColor(int page, int slot, String hex) {
    String node = settings.haspNode ?: "plate"
    try { interfaces.mqtt.publish("hasp/${node}/command/p${page}b${bgId(slot)}.bg_color", hex, 1, false) }
    catch (Exception e) { infoLog "[SenseCAP] ERROR — Color publish failed: ${e.message}" }
}
private void publishTextColor(int page, int slot, String bgHex) {
    String node = settings.haspNode ?: "plate"; String color = textColorFor(bgHex)
    // Include existing label in payload so it doesn't get wiped by the jsonl update
    String lbl = state[labelKey(page, slot)] ?: ""
    if (lbl) {
        publishJsonl(node, page, bgId(slot), [text_color: color, text: lbl])
    } else {
        publishJsonl(node, page, bgId(slot), [text_color: color])
    }
    if (useLetterIcon(page)) publishJsonl(node, page, iconId(slot), [text_color: color])
}
private void publishIcon(int page, int slot, String glyph) {
    String node = settings.haspNode ?: "plate"
    int fontPt = useLetterIcon(page) ? 12 : (settings.iconFont ?: 24) as int
    publishJsonl(node, page, iconId(slot), [text: glyph, text_font: fontPt])
}
private void publishJsonl(String node, int page, int objId, Map props) {
    String json  = groovy.json.JsonOutput.toJson(props)
    String topic = "hasp/${node}/command/p${page}b${objId}.jsonl"
    try { interfaces.mqtt.publish(topic, json, 1, false) }
    catch (Exception e) { infoLog "[SenseCAP] ERROR — JSONL publish failed: ${e.message}" }
}

// ── Color / icon helpers ───────────────────────────────────────────────────────
private String inactiveColorFor(int page, int idx) {
    switch (state[typeKey(page, idx)] ?: "motion") {
        case "contact": return settings.colorContactInactive ?: "#00FFFF"
        case "water":   return settings.colorWaterInactive   ?: "#0000FF"
        case "smoke":   return settings.colorSmokeInactive   ?: "#FFFF00"
        default:        return settings.colorInactive        ?: "#008000"
    }
}
private String textColorFor(String hex) {
    String h = hex.replace("#","")
    int r = Integer.parseInt(h[0..1],16); int g = Integer.parseInt(h[2..3],16); int b = Integer.parseInt(h[4..5],16)
    return (0.2126*(r/255.0) + 0.7152*(g/255.0) + 0.0722*(b/255.0)) > 0.35 ? "black" : "white"
}

@Field static final String ICON_ALERT   = "\uE026"
@Field static final String ICON_MOTION  = "\uE70E"
@Field static final String ICON_CONTACT = "\uE2DC"
@Field static final String ICON_WATER   = "\uE58C"
@Field static final String ICON_SMOKE   = "\uE238"

private boolean useLetterIcon(int page) { activeGrid(page) in ["6x6","7x7"] }

private String activeIconFor(int page, int idx) {
    if (useLetterIcon(page)) return letterIconFor(page, idx)
    return ICON_ALERT
}
private String inactiveIconFor(int page, int idx) {
    if (useLetterIcon(page)) return letterIconFor(page, idx)
    switch (state[typeKey(page, idx)] ?: "motion") {
        case "contact": return ICON_CONTACT
        case "water":   return ICON_WATER
        case "smoke":   return ICON_SMOKE
        default:        return ICON_MOTION
    }
}
private String letterIconFor(int page, int idx) {
    switch (state[typeKey(page, idx)] ?: "motion") {
        case "contact": return "C"; case "water": return "W"
        case "smoke":   return "S"; case "none":  return ""
        default:        return "M"
    }
}
private String activeStatusLabel(String t) {
    switch (t) { case "contact": return "open"; case "water": return "wet"; case "smoke": return "detected"; default: return "active" }
}
private String inactiveStatusLabel(String t) {
    switch (t) { case "contact": return "closed"; case "water": return "dry"; case "smoke": return "clear"; default: return "inactive" }
}

// ── Fade ───────────────────────────────────────────────────────────────────────
@Field static final int FADE_STEPS = 6
private int fadeInterval() { Math.max(1, Math.round(((settings.fadeDuration ?: 30) as int) / FADE_STEPS) as int) }

private void scheduleFadeStep(int page, int idx) {
    String method = "p${page}fadeStep${idx}"
    switch (idx) {
        case 1:  runIn(fadeInterval(), "${method}1".replace("${method}1","${method}")); break
        default: runIn(fadeInterval(), method)
    }
    runIn(fadeInterval(), method)
}

private void doFadeStep(int page, int idx) {
    String sk = stateKey(page, idx); String fadeKey = "p${page}fadeStep${idx}"
    if (state[sk] == "active") return
    int step = (state[fadeKey] ?: 0) as int
    double t = step / (FADE_STEPS as double)
    String fromHex = (settings.colorActive ?: "#FF0000").replace("#","")
    String toHex   = inactiveColorFor(page, idx).replace("#","")
    int fR = Integer.parseInt(fromHex[0..1],16); int fG = Integer.parseInt(fromHex[2..3],16); int fB = Integer.parseInt(fromHex[4..5],16)
    int tR = Integer.parseInt(toHex[0..1],16);   int tG = Integer.parseInt(toHex[2..3],16);   int tB = Integer.parseInt(toHex[4..5],16)
    int r = Math.max(0,Math.min(255,Math.round(fR+(tR-fR)*t) as int))
    int g = Math.max(0,Math.min(255,Math.round(fG+(tG-fG)*t) as int))
    int b = Math.max(0,Math.min(255,Math.round(fB+(tB-fB)*t) as int))
    publishColor(page, idx, sprintf("#%02X%02X%02X", r, g, b))
    if (step < FADE_STEPS) {
        state[fadeKey] = step + 1; scheduleFadeStep(page, idx)
    } else {
        state.remove(fadeKey)
        String snap = inactiveColorFor(page, idx)
        publishColor(page, idx, snap); publishTextColor(page, idx, snap)
        if (allInactive()) {
            if (settings.backlightOnMotion && !state.screenIdle) {
                int delay = (settings.backlightOffDelay ?: 0) as int
                if (delay > 0) runIn(delay, "backlightOff")
            }
            // All sensors now clear — restart page rotation
            int total = (state.numberOfPages ?: 1) as int
            int rotInt = (settings.rotationInterval ?: 10) as int
            if (total > 1 && rotInt > 0) {
                unschedule("rotatePage")
                runIn(rotInt, "rotatePage")
            }
        }
    }
}

// Fade step methods — one per page per slot (5 pages × 49 slots)
def p1fadeStep1(){doFadeStep(1,1)}
def p1fadeStep2(){doFadeStep(1,2)}
def p1fadeStep3(){doFadeStep(1,3)}
def p1fadeStep4(){doFadeStep(1,4)}
def p1fadeStep5(){doFadeStep(1,5)}
def p1fadeStep6(){doFadeStep(1,6)}
def p1fadeStep7(){doFadeStep(1,7)}
def p1fadeStep8(){doFadeStep(1,8)}
def p1fadeStep9(){doFadeStep(1,9)}
def p1fadeStep10(){doFadeStep(1,10)}
def p1fadeStep11(){doFadeStep(1,11)}
def p1fadeStep12(){doFadeStep(1,12)}
def p1fadeStep13(){doFadeStep(1,13)}
def p1fadeStep14(){doFadeStep(1,14)}
def p1fadeStep15(){doFadeStep(1,15)}
def p1fadeStep16(){doFadeStep(1,16)}
def p1fadeStep17(){doFadeStep(1,17)}
def p1fadeStep18(){doFadeStep(1,18)}
def p1fadeStep19(){doFadeStep(1,19)}
def p1fadeStep20(){doFadeStep(1,20)}
def p1fadeStep21(){doFadeStep(1,21)}
def p1fadeStep22(){doFadeStep(1,22)}
def p1fadeStep23(){doFadeStep(1,23)}
def p1fadeStep24(){doFadeStep(1,24)}
def p1fadeStep25(){doFadeStep(1,25)}
def p1fadeStep26(){doFadeStep(1,26)}
def p1fadeStep27(){doFadeStep(1,27)}
def p1fadeStep28(){doFadeStep(1,28)}
def p1fadeStep29(){doFadeStep(1,29)}
def p1fadeStep30(){doFadeStep(1,30)}
def p1fadeStep31(){doFadeStep(1,31)}
def p1fadeStep32(){doFadeStep(1,32)}
def p1fadeStep33(){doFadeStep(1,33)}
def p1fadeStep34(){doFadeStep(1,34)}
def p1fadeStep35(){doFadeStep(1,35)}
def p1fadeStep36(){doFadeStep(1,36)}
def p1fadeStep37(){doFadeStep(1,37)}
def p1fadeStep38(){doFadeStep(1,38)}
def p1fadeStep39(){doFadeStep(1,39)}
def p1fadeStep40(){doFadeStep(1,40)}
def p1fadeStep41(){doFadeStep(1,41)}
def p1fadeStep42(){doFadeStep(1,42)}
def p1fadeStep43(){doFadeStep(1,43)}
def p1fadeStep44(){doFadeStep(1,44)}
def p1fadeStep45(){doFadeStep(1,45)}
def p1fadeStep46(){doFadeStep(1,46)}
def p1fadeStep47(){doFadeStep(1,47)}
def p1fadeStep48(){doFadeStep(1,48)}
def p1fadeStep49(){doFadeStep(1,49)}

def p2fadeStep1(){doFadeStep(2,1)}
def p2fadeStep2(){doFadeStep(2,2)}
def p2fadeStep3(){doFadeStep(2,3)}
def p2fadeStep4(){doFadeStep(2,4)}
def p2fadeStep5(){doFadeStep(2,5)}
def p2fadeStep6(){doFadeStep(2,6)}
def p2fadeStep7(){doFadeStep(2,7)}
def p2fadeStep8(){doFadeStep(2,8)}
def p2fadeStep9(){doFadeStep(2,9)}
def p2fadeStep10(){doFadeStep(2,10)}
def p2fadeStep11(){doFadeStep(2,11)}
def p2fadeStep12(){doFadeStep(2,12)}
def p2fadeStep13(){doFadeStep(2,13)}
def p2fadeStep14(){doFadeStep(2,14)}
def p2fadeStep15(){doFadeStep(2,15)}
def p2fadeStep16(){doFadeStep(2,16)}
def p2fadeStep17(){doFadeStep(2,17)}
def p2fadeStep18(){doFadeStep(2,18)}
def p2fadeStep19(){doFadeStep(2,19)}
def p2fadeStep20(){doFadeStep(2,20)}
def p2fadeStep21(){doFadeStep(2,21)}
def p2fadeStep22(){doFadeStep(2,22)}
def p2fadeStep23(){doFadeStep(2,23)}
def p2fadeStep24(){doFadeStep(2,24)}
def p2fadeStep25(){doFadeStep(2,25)}
def p2fadeStep26(){doFadeStep(2,26)}
def p2fadeStep27(){doFadeStep(2,27)}
def p2fadeStep28(){doFadeStep(2,28)}
def p2fadeStep29(){doFadeStep(2,29)}
def p2fadeStep30(){doFadeStep(2,30)}
def p2fadeStep31(){doFadeStep(2,31)}
def p2fadeStep32(){doFadeStep(2,32)}
def p2fadeStep33(){doFadeStep(2,33)}
def p2fadeStep34(){doFadeStep(2,34)}
def p2fadeStep35(){doFadeStep(2,35)}
def p2fadeStep36(){doFadeStep(2,36)}
def p2fadeStep37(){doFadeStep(2,37)}
def p2fadeStep38(){doFadeStep(2,38)}
def p2fadeStep39(){doFadeStep(2,39)}
def p2fadeStep40(){doFadeStep(2,40)}
def p2fadeStep41(){doFadeStep(2,41)}
def p2fadeStep42(){doFadeStep(2,42)}
def p2fadeStep43(){doFadeStep(2,43)}
def p2fadeStep44(){doFadeStep(2,44)}
def p2fadeStep45(){doFadeStep(2,45)}
def p2fadeStep46(){doFadeStep(2,46)}
def p2fadeStep47(){doFadeStep(2,47)}
def p2fadeStep48(){doFadeStep(2,48)}
def p2fadeStep49(){doFadeStep(2,49)}

def p3fadeStep1(){doFadeStep(3,1)}
def p3fadeStep2(){doFadeStep(3,2)}
def p3fadeStep3(){doFadeStep(3,3)}
def p3fadeStep4(){doFadeStep(3,4)}
def p3fadeStep5(){doFadeStep(3,5)}
def p3fadeStep6(){doFadeStep(3,6)}
def p3fadeStep7(){doFadeStep(3,7)}
def p3fadeStep8(){doFadeStep(3,8)}
def p3fadeStep9(){doFadeStep(3,9)}
def p3fadeStep10(){doFadeStep(3,10)}
def p3fadeStep11(){doFadeStep(3,11)}
def p3fadeStep12(){doFadeStep(3,12)}
def p3fadeStep13(){doFadeStep(3,13)}
def p3fadeStep14(){doFadeStep(3,14)}
def p3fadeStep15(){doFadeStep(3,15)}
def p3fadeStep16(){doFadeStep(3,16)}
def p3fadeStep17(){doFadeStep(3,17)}
def p3fadeStep18(){doFadeStep(3,18)}
def p3fadeStep19(){doFadeStep(3,19)}
def p3fadeStep20(){doFadeStep(3,20)}
def p3fadeStep21(){doFadeStep(3,21)}
def p3fadeStep22(){doFadeStep(3,22)}
def p3fadeStep23(){doFadeStep(3,23)}
def p3fadeStep24(){doFadeStep(3,24)}
def p3fadeStep25(){doFadeStep(3,25)}
def p3fadeStep26(){doFadeStep(3,26)}
def p3fadeStep27(){doFadeStep(3,27)}
def p3fadeStep28(){doFadeStep(3,28)}
def p3fadeStep29(){doFadeStep(3,29)}
def p3fadeStep30(){doFadeStep(3,30)}
def p3fadeStep31(){doFadeStep(3,31)}
def p3fadeStep32(){doFadeStep(3,32)}
def p3fadeStep33(){doFadeStep(3,33)}
def p3fadeStep34(){doFadeStep(3,34)}
def p3fadeStep35(){doFadeStep(3,35)}
def p3fadeStep36(){doFadeStep(3,36)}
def p3fadeStep37(){doFadeStep(3,37)}
def p3fadeStep38(){doFadeStep(3,38)}
def p3fadeStep39(){doFadeStep(3,39)}
def p3fadeStep40(){doFadeStep(3,40)}
def p3fadeStep41(){doFadeStep(3,41)}
def p3fadeStep42(){doFadeStep(3,42)}
def p3fadeStep43(){doFadeStep(3,43)}
def p3fadeStep44(){doFadeStep(3,44)}
def p3fadeStep45(){doFadeStep(3,45)}
def p3fadeStep46(){doFadeStep(3,46)}
def p3fadeStep47(){doFadeStep(3,47)}
def p3fadeStep48(){doFadeStep(3,48)}
def p3fadeStep49(){doFadeStep(3,49)}

def p4fadeStep1(){doFadeStep(4,1)}
def p4fadeStep2(){doFadeStep(4,2)}
def p4fadeStep3(){doFadeStep(4,3)}
def p4fadeStep4(){doFadeStep(4,4)}
def p4fadeStep5(){doFadeStep(4,5)}
def p4fadeStep6(){doFadeStep(4,6)}
def p4fadeStep7(){doFadeStep(4,7)}
def p4fadeStep8(){doFadeStep(4,8)}
def p4fadeStep9(){doFadeStep(4,9)}
def p4fadeStep10(){doFadeStep(4,10)}
def p4fadeStep11(){doFadeStep(4,11)}
def p4fadeStep12(){doFadeStep(4,12)}
def p4fadeStep13(){doFadeStep(4,13)}
def p4fadeStep14(){doFadeStep(4,14)}
def p4fadeStep15(){doFadeStep(4,15)}
def p4fadeStep16(){doFadeStep(4,16)}
def p4fadeStep17(){doFadeStep(4,17)}
def p4fadeStep18(){doFadeStep(4,18)}
def p4fadeStep19(){doFadeStep(4,19)}
def p4fadeStep20(){doFadeStep(4,20)}
def p4fadeStep21(){doFadeStep(4,21)}
def p4fadeStep22(){doFadeStep(4,22)}
def p4fadeStep23(){doFadeStep(4,23)}
def p4fadeStep24(){doFadeStep(4,24)}
def p4fadeStep25(){doFadeStep(4,25)}
def p4fadeStep26(){doFadeStep(4,26)}
def p4fadeStep27(){doFadeStep(4,27)}
def p4fadeStep28(){doFadeStep(4,28)}
def p4fadeStep29(){doFadeStep(4,29)}
def p4fadeStep30(){doFadeStep(4,30)}
def p4fadeStep31(){doFadeStep(4,31)}
def p4fadeStep32(){doFadeStep(4,32)}
def p4fadeStep33(){doFadeStep(4,33)}
def p4fadeStep34(){doFadeStep(4,34)}
def p4fadeStep35(){doFadeStep(4,35)}
def p4fadeStep36(){doFadeStep(4,36)}
def p4fadeStep37(){doFadeStep(4,37)}
def p4fadeStep38(){doFadeStep(4,38)}
def p4fadeStep39(){doFadeStep(4,39)}
def p4fadeStep40(){doFadeStep(4,40)}
def p4fadeStep41(){doFadeStep(4,41)}
def p4fadeStep42(){doFadeStep(4,42)}
def p4fadeStep43(){doFadeStep(4,43)}
def p4fadeStep44(){doFadeStep(4,44)}
def p4fadeStep45(){doFadeStep(4,45)}
def p4fadeStep46(){doFadeStep(4,46)}
def p4fadeStep47(){doFadeStep(4,47)}
def p4fadeStep48(){doFadeStep(4,48)}
def p4fadeStep49(){doFadeStep(4,49)}

def p5fadeStep1(){doFadeStep(5,1)}
def p5fadeStep2(){doFadeStep(5,2)}
def p5fadeStep3(){doFadeStep(5,3)}
def p5fadeStep4(){doFadeStep(5,4)}
def p5fadeStep5(){doFadeStep(5,5)}
def p5fadeStep6(){doFadeStep(5,6)}
def p5fadeStep7(){doFadeStep(5,7)}
def p5fadeStep8(){doFadeStep(5,8)}
def p5fadeStep9(){doFadeStep(5,9)}
def p5fadeStep10(){doFadeStep(5,10)}
def p5fadeStep11(){doFadeStep(5,11)}
def p5fadeStep12(){doFadeStep(5,12)}
def p5fadeStep13(){doFadeStep(5,13)}
def p5fadeStep14(){doFadeStep(5,14)}
def p5fadeStep15(){doFadeStep(5,15)}
def p5fadeStep16(){doFadeStep(5,16)}
def p5fadeStep17(){doFadeStep(5,17)}
def p5fadeStep18(){doFadeStep(5,18)}
def p5fadeStep19(){doFadeStep(5,19)}
def p5fadeStep20(){doFadeStep(5,20)}
def p5fadeStep21(){doFadeStep(5,21)}
def p5fadeStep22(){doFadeStep(5,22)}
def p5fadeStep23(){doFadeStep(5,23)}
def p5fadeStep24(){doFadeStep(5,24)}
def p5fadeStep25(){doFadeStep(5,25)}
def p5fadeStep26(){doFadeStep(5,26)}
def p5fadeStep27(){doFadeStep(5,27)}
def p5fadeStep28(){doFadeStep(5,28)}
def p5fadeStep29(){doFadeStep(5,29)}
def p5fadeStep30(){doFadeStep(5,30)}
def p5fadeStep31(){doFadeStep(5,31)}
def p5fadeStep32(){doFadeStep(5,32)}
def p5fadeStep33(){doFadeStep(5,33)}
def p5fadeStep34(){doFadeStep(5,34)}
def p5fadeStep35(){doFadeStep(5,35)}
def p5fadeStep36(){doFadeStep(5,36)}
def p5fadeStep37(){doFadeStep(5,37)}
def p5fadeStep38(){doFadeStep(5,38)}
def p5fadeStep39(){doFadeStep(5,39)}
def p5fadeStep40(){doFadeStep(5,40)}
def p5fadeStep41(){doFadeStep(5,41)}
def p5fadeStep42(){doFadeStep(5,42)}
def p5fadeStep43(){doFadeStep(5,43)}
def p5fadeStep44(){doFadeStep(5,44)}
def p5fadeStep45(){doFadeStep(5,45)}
def p5fadeStep46(){doFadeStep(5,46)}
def p5fadeStep47(){doFadeStep(5,47)}
def p5fadeStep48(){doFadeStep(5,48)}
def p5fadeStep49(){doFadeStep(5,49)}

// ── Page rotation ─────────────────────────────────────────────────────────────
def rotatePage() {
    int rotInterval = (settings.rotationInterval ?: 10) as int
    if (rotInterval <= 0) return
    // Stop rotating if any sensor is active
    if (!allInactive()) {
        infoLog "[SenseCAP] Rotation paused — sensor active"
        return
    }
    int total = (state.numberOfPages ?: 1) as int
    if (total <= 1) return
    // Advance to next page, wrapping around
    int current = (state.rotationPage ?: 1) as int
    int next = (current >= total) ? 1 : current + 1
    state.rotationPage = next
    String node = settings.haspNode ?: "plate"
    try { interfaces.mqtt.publish("hasp/${node}/command/page", "${next}", 1, false) }
    catch (Exception e) { infoLog "[SenseCAP] Rotation publish failed: ${e.message}" }
    // Schedule next rotation
    runIn(rotInterval, "rotatePage")
}

// ── Logging ────────────────────────────────────────────────────────────────────
private void infoLog(String msg)  { if ((settings.logLevel ?: "1") != "0") log.info msg }
private void debugLog(String msg) { if ((settings.logLevel ?: "1") == "2") log.debug msg }
