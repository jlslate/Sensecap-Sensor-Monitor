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
        version: "4.0.0",
        date: "2026-05-29"
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
    (1..5).every { pg -> (1..maxSensors(pg)).every { state[stateKey(pg, it)] != "active" } }
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
    String sType = state[tk] ?: "motion"
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
    String sType = state[tk] ?: "motion"
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
        state[typeKey(page, idx)] = v?.toString() ?: "motion"
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

    // Navigate to this page so user can watch it build
    try { interfaces.mqtt.publish("hasp/${node}/command/page", "${page}", 1, false); pauseExecution(100) }
    catch (Exception e) { }

    // 1. Push tile layout
    layoutJsonl(grid, page).each { jsonl ->
        try { interfaces.mqtt.publish("hasp/${node}/command/jsonl", jsonl, 1, false); pauseExecution(30) }
        catch (Exception e) { infoLog "[SenseCAP] ERROR — pushLayout p${page}: ${e.message}" }
    }

    // 2. Push colors, icons and labels for every slot immediately after layout
    pauseExecution(100)
    (1..maxSensors(page)).each { idx ->
        String sk = stateKey(page, idx)
        String st = state[sk] ?: "inactive"
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
        if (total > 1) runIn(10, "rotatePage")
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
 * Version: 3.1.0  — 1x1 through 7x7 grids, empty slot slate tile, letter icons for dense grids, luminance text color
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

        command "setSlotEmpty",      [[name: "sensorIndex", type: "NUMBER", description: "Sensor slot — slate background, no label or icon"]]
        command "setMotionActive",   [[name: "sensorIndex", type: "NUMBER", description: "Sensor slot 1–16 — turns tile to active state"]]
        command "setMotionInactive", [[name: "sensorIndex", type: "NUMBER", description: "Sensor slot 1–16 — turns tile to inactive state"]]
        command "setAllInactive"
        command "reconnectMqtt"
        command "setGridLayout",  [[name: "gridLayout", type: "STRING", description: "2x2, 3x3, 4x4, or 5x5 — called by app, not needed manually"]]
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

        attribute "sensor17Status",  "string"
        attribute "sensor18Status",  "string"
        attribute "sensor19Status",  "string"
        attribute "sensor20Status",  "string"
        attribute "sensor21Status",  "string"
        attribute "sensor22Status",  "string"
        attribute "sensor23Status",  "string"
        attribute "sensor24Status",  "string"
        attribute "sensor25Status",  "string"

        attribute "sensor17Type",    "string"
        attribute "sensor18Type",    "string"
        attribute "sensor19Type",    "string"
        attribute "sensor20Type",    "string"
        attribute "sensor21Type",    "string"
        attribute "sensor22Type",    "string"
        attribute "sensor23Type",    "string"
        attribute "sensor24Type",    "string"
        attribute "sensor25Type",    "string"

        attribute "sensor26Status",  "string"
        attribute "sensor27Status",  "string"
        attribute "sensor28Status",  "string"
        attribute "sensor29Status",  "string"
        attribute "sensor30Status",  "string"
        attribute "sensor31Status",  "string"
        attribute "sensor32Status",  "string"
        attribute "sensor33Status",  "string"
        attribute "sensor34Status",  "string"
        attribute "sensor35Status",  "string"
        attribute "sensor36Status",  "string"

        attribute "sensor26Type",    "string"
        attribute "sensor27Type",    "string"
        attribute "sensor28Type",    "string"
        attribute "sensor29Type",    "string"
        attribute "sensor30Type",    "string"
        attribute "sensor31Type",    "string"
        attribute "sensor32Type",    "string"
        attribute "sensor33Type",    "string"
        attribute "sensor34Type",    "string"
        attribute "sensor35Type",    "string"
        attribute "sensor36Type",    "string"

        attribute "sensor37Status",  "string"
        attribute "sensor37Type",    "string"
        attribute "sensor38Status",  "string"
        attribute "sensor38Type",    "string"
        attribute "sensor39Status",  "string"
        attribute "sensor39Type",    "string"
        attribute "sensor40Status",  "string"
        attribute "sensor40Type",    "string"
        attribute "sensor41Status",  "string"
        attribute "sensor41Type",    "string"
        attribute "sensor42Status",  "string"
        attribute "sensor42Type",    "string"
        attribute "sensor43Status",  "string"
        attribute "sensor43Type",    "string"
        attribute "sensor44Status",  "string"
        attribute "sensor44Type",    "string"
        attribute "sensor45Status",  "string"
        attribute "sensor45Type",    "string"
        attribute "sensor46Status",  "string"
        attribute "sensor46Type",    "string"
        attribute "sensor47Status",  "string"
        attribute "sensor47Type",    "string"
        attribute "sensor48Status",  "string"
        attribute "sensor48Type",    "string"
        attribute "sensor49Status",  "string"
        attribute "sensor49Type",    "string"
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

        input name: "gridLayout",
            type: "enum",
            title: "Display Grid Layout",
            options: [
                "1x1": "1×1 (1 sensor)",
                "2x2": "2×2 (4 sensors)",
                "3x3": "3×3 (9 sensors)",
                "4x4": "4×4 (16 sensors)",
                "5x5": "5×5 (25 sensors)",
                "6x6": "6×6 (36 sensors)",
                "7x7": "7×7 (49 sensors)"
            ],
            defaultValue: "2x2",
            required: true

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
//   iconId(slot) = slot + 50  51–100  label — page-level absolute coords, tight box
//
private int bgId(int slot)   { slot }
private int iconId(int slot) {
    // Icons use slot+50 (IDs 51-100), btns use slot (IDs 1-50).
    // Non-overlapping ranges work for all grid sizes up to 7x7 (49 slots).
    return slot + 50
}

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
    String savedGrid = state.gridLayout  // preserve across state.clear()
    state.clear()
    if (savedGrid) state.gridLayout = savedGrid
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

def setSlotEmpty(sensorIndex) {
    int idx = (sensorIndex as int)
    if (idx < 1 || idx > maxSensors()) { infoLog "[SenseCAP] WARN — sensorIndex must be 1–${maxSensors()}"; return }
    state["sensor${idx}"] = "empty"
    sendEvent(name: "sensor${idx}Status", value: "empty")
    unschedule("fadeStep${idx}")
    state.remove("fadeStep${idx}")
    publishColor(idx, "#708090")      // slate
    publishTextColor(idx, "#708090")  // auto white text (not visible — label will be blank)
    publishIcon(idx, "")              // clear icon
    // Clear label
    String node = settings.haspNode ?: "plate"
    int    obj  = bgId(idx)
    String topic = "hasp/${node}/command/p1b${obj}.jsonl"
    try {
        interfaces.mqtt.publish(topic, "{\"text\":\"\"}", 1, false)
    } catch (Exception e) {
        infoLog "[SenseCAP] ERROR — setSlotEmpty label clear failed: ${e.message}"
    }
}

// ── Fade ─────────────────────────────────────────────────────────────────────

@Field static final int FADE_STEPS = 6

private int fadeInterval() {
    int secs = (settings.fadeDuration ?: 30) as int
    return Math.max(1, Math.round(secs / FADE_STEPS) as int)
}

private String activeGrid() {
    return (state.gridLayout ?: settings.gridLayout ?: "2x2") as String
}

private int maxSensors() {
    switch (activeGrid()) {
        case "1x1": return 1
        case "7x7": return 49
        case "6x6": return 36
        case "5x5": return 25
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
        case 17: runIn(fadeInterval(), fadeStep17); break
        case 18: runIn(fadeInterval(), fadeStep18); break
        case 19: runIn(fadeInterval(), fadeStep19); break
        case 20: runIn(fadeInterval(), fadeStep20); break
        case 21: runIn(fadeInterval(), fadeStep21); break
        case 22: runIn(fadeInterval(), fadeStep22); break
        case 23: runIn(fadeInterval(), fadeStep23); break
        case 24: runIn(fadeInterval(), fadeStep24); break
        case 25: runIn(fadeInterval(), fadeStep25); break
        case 26: runIn(fadeInterval(), fadeStep26); break
        case 27: runIn(fadeInterval(), fadeStep27); break
        case 28: runIn(fadeInterval(), fadeStep28); break
        case 29: runIn(fadeInterval(), fadeStep29); break
        case 30: runIn(fadeInterval(), fadeStep30); break
        case 31: runIn(fadeInterval(), fadeStep31); break
        case 32: runIn(fadeInterval(), fadeStep32); break
        case 33: runIn(fadeInterval(), fadeStep33); break
        case 34: runIn(fadeInterval(), fadeStep34); break
        case 35: runIn(fadeInterval(), fadeStep35); break
        case 36: runIn(fadeInterval(), fadeStep36); break
        case 37: runIn(fadeInterval(), fadeStep37); break
        case 38: runIn(fadeInterval(), fadeStep38); break
        case 39: runIn(fadeInterval(), fadeStep39); break
        case 40: runIn(fadeInterval(), fadeStep40); break
        case 41: runIn(fadeInterval(), fadeStep41); break
        case 42: runIn(fadeInterval(), fadeStep42); break
        case 43: runIn(fadeInterval(), fadeStep43); break
        case 44: runIn(fadeInterval(), fadeStep44); break
        case 45: runIn(fadeInterval(), fadeStep45); break
        case 46: runIn(fadeInterval(), fadeStep46); break
        case 47: runIn(fadeInterval(), fadeStep47); break
        case 48: runIn(fadeInterval(), fadeStep48); break
        case 49: runIn(fadeInterval(), fadeStep49); break
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
def fadeStep17() { doFadeStep(17) }
def fadeStep18() { doFadeStep(18) }
def fadeStep19() { doFadeStep(19) }
def fadeStep20() { doFadeStep(20) }
def fadeStep21() { doFadeStep(21) }
def fadeStep22() { doFadeStep(22) }
def fadeStep23() { doFadeStep(23) }
def fadeStep24() { doFadeStep(24) }
def fadeStep25() { doFadeStep(25) }
def fadeStep26() { doFadeStep(26) }
def fadeStep27() { doFadeStep(27) }
def fadeStep28() { doFadeStep(28) }
def fadeStep29() { doFadeStep(29) }
def fadeStep30() { doFadeStep(30) }
def fadeStep31() { doFadeStep(31) }
def fadeStep32() { doFadeStep(32) }
def fadeStep33() { doFadeStep(33) }
def fadeStep34() { doFadeStep(34) }
def fadeStep35() { doFadeStep(35) }
def fadeStep36() { doFadeStep(36) }
def fadeStep37() { doFadeStep(37) }
def fadeStep38() { doFadeStep(38) }
def fadeStep39() { doFadeStep(39) }
def fadeStep40() { doFadeStep(40) }
def fadeStep41() { doFadeStep(41) }
def fadeStep42() { doFadeStep(42) }
def fadeStep43() { doFadeStep(43) }
def fadeStep44() { doFadeStep(44) }
def fadeStep45() { doFadeStep(45) }
def fadeStep46() { doFadeStep(46) }
def fadeStep47() { doFadeStep(47) }
def fadeStep48() { doFadeStep(48) }
def fadeStep49() { doFadeStep(49) }

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
    String color   = textColorFor(bgHex)
    // Update btn label text color
    String btnTopic  = "hasp/${node}/command/p1b${bgId(sensorIdx)}.jsonl"
    String btnPayload = "{\"text_color\":\"${color}\"}"
    debugLog "TextColor → ${btnTopic} : ${color}"
    try {
        interfaces.mqtt.publish(btnTopic, btnPayload, 1, false)
        // Also update icon overlay text color so letter icons match label color
        if (useLetterIcon()) {
            String iconTopic   = "hasp/${node}/command/p1b${iconId(sensorIdx)}.jsonl"
            String iconPayload = "{\"text_color\":\"${color}\"}"
            interfaces.mqtt.publish(iconTopic, iconPayload, 1, false)
        }
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

private boolean useLetterIcon() {
    // For dense grids MDI glyphs are too small to read — use a single letter instead
    String g = activeGrid()
    return (g == "6x6" || g == "7x7")
}

private String activeIconFor(int idx) {
    if (useLetterIcon()) return letterIconFor(idx)
    switch (state["slotType${idx}"]) {
        case "contact": return ICON_CONTACT_ACTIVE
        case "water":   return ICON_WATER_ACTIVE
        case "smoke":   return ICON_SMOKE_ACTIVE
        default:        return ICON_MOTION_ACTIVE
    }
}

private String inactiveIconFor(int idx) {
    if (useLetterIcon()) return letterIconFor(idx)
    switch (state["slotType${idx}"]) {
        case "contact": return ICON_CONTACT_INACTIVE
        case "water":   return ICON_WATER_INACTIVE
        case "smoke":   return ICON_SMOKE_INACTIVE
        default:        return ICON_MOTION_INACTIVE
    }
}

private String letterIconFor(int idx) {
    switch (state["slotType${idx}"]) {
        case "contact": return "C"
        case "water":   return "W"
        case "smoke":   return "S"
        case "none":    return ""
        default:        return "M"  // motion
    }
}

private void publishIcon(int sensorIdx, String glyph) {
    String node  = settings.haspNode ?: "plate"
    int    obj   = iconId(sensorIdx)
    // For letter icons on dense grids use the tile font size; for MDI use iconFont pref
    int    fontPt = useLetterIcon() ? 12 : (settings.iconFont ?: 24) as int
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
//   Object (iconId = slot + 50) — "label" — page-level, tight box, top-left icon,
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
    '{"page":1,"id":51,"obj":"label","parentid":0,"x":8,"y":8,"w":36,"h":36,"bg_opa":0,"border_width":0,"text":"","text_font":24,"text_color":"black","click":false}',
    '{"page":1,"id":52,"obj":"label","parentid":0,"x":248,"y":8,"w":36,"h":36,"bg_opa":0,"border_width":0,"text":"","text_font":24,"text_color":"black","click":false}',
    '{"page":1,"id":53,"obj":"label","parentid":0,"x":8,"y":248,"w":36,"h":36,"bg_opa":0,"border_width":0,"text":"","text_font":24,"text_color":"black","click":false}',
    '{"page":1,"id":54,"obj":"label","parentid":0,"x":248,"y":248,"w":36,"h":36,"bg_opa":0,"border_width":0,"text":"","text_font":24,"text_color":"black","click":false}'
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
    '{"page":1,"id":51,"obj":"label","parentid":0,"x":8,"y":8,"w":36,"h":36,"bg_opa":0,"border_width":0,"text":"","text_font":24,"text_color":"black","click":false}',
    '{"page":1,"id":52,"obj":"label","parentid":0,"x":167,"y":8,"w":36,"h":36,"bg_opa":0,"border_width":0,"text":"","text_font":24,"text_color":"black","click":false}',
    '{"page":1,"id":53,"obj":"label","parentid":0,"x":326,"y":8,"w":36,"h":36,"bg_opa":0,"border_width":0,"text":"","text_font":24,"text_color":"black","click":false}',
    '{"page":1,"id":54,"obj":"label","parentid":0,"x":8,"y":167,"w":36,"h":36,"bg_opa":0,"border_width":0,"text":"","text_font":24,"text_color":"black","click":false}',
    '{"page":1,"id":55,"obj":"label","parentid":0,"x":167,"y":167,"w":36,"h":36,"bg_opa":0,"border_width":0,"text":"","text_font":24,"text_color":"black","click":false}',
    '{"page":1,"id":56,"obj":"label","parentid":0,"x":326,"y":167,"w":36,"h":36,"bg_opa":0,"border_width":0,"text":"","text_font":24,"text_color":"black","click":false}',
    '{"page":1,"id":57,"obj":"label","parentid":0,"x":8,"y":326,"w":36,"h":36,"bg_opa":0,"border_width":0,"text":"","text_font":24,"text_color":"black","click":false}',
    '{"page":1,"id":58,"obj":"label","parentid":0,"x":167,"y":326,"w":36,"h":36,"bg_opa":0,"border_width":0,"text":"","text_font":24,"text_color":"black","click":false}',
    '{"page":1,"id":59,"obj":"label","parentid":0,"x":326,"y":326,"w":36,"h":36,"bg_opa":0,"border_width":0,"text":"","text_font":24,"text_color":"black","click":false}'
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
    '{"page":1,"id":51,"obj":"label","parentid":0,"x":7,"y":7,"w":26,"h":26,"bg_opa":0,"border_width":0,"text":"","text_font":16,"text_color":"black","click":false}',
    '{"page":1,"id":52,"obj":"label","parentid":0,"x":126,"y":7,"w":26,"h":26,"bg_opa":0,"border_width":0,"text":"","text_font":16,"text_color":"black","click":false}',
    '{"page":1,"id":53,"obj":"label","parentid":0,"x":245,"y":7,"w":26,"h":26,"bg_opa":0,"border_width":0,"text":"","text_font":16,"text_color":"black","click":false}',
    '{"page":1,"id":54,"obj":"label","parentid":0,"x":364,"y":7,"w":26,"h":26,"bg_opa":0,"border_width":0,"text":"","text_font":16,"text_color":"black","click":false}',
    '{"page":1,"id":55,"obj":"label","parentid":0,"x":7,"y":126,"w":26,"h":26,"bg_opa":0,"border_width":0,"text":"","text_font":16,"text_color":"black","click":false}',
    '{"page":1,"id":56,"obj":"label","parentid":0,"x":126,"y":126,"w":26,"h":26,"bg_opa":0,"border_width":0,"text":"","text_font":16,"text_color":"black","click":false}',
    '{"page":1,"id":57,"obj":"label","parentid":0,"x":245,"y":126,"w":26,"h":26,"bg_opa":0,"border_width":0,"text":"","text_font":16,"text_color":"black","click":false}',
    '{"page":1,"id":58,"obj":"label","parentid":0,"x":364,"y":126,"w":26,"h":26,"bg_opa":0,"border_width":0,"text":"","text_font":16,"text_color":"black","click":false}',
    '{"page":1,"id":59,"obj":"label","parentid":0,"x":7,"y":245,"w":26,"h":26,"bg_opa":0,"border_width":0,"text":"","text_font":16,"text_color":"black","click":false}',
    '{"page":1,"id":60,"obj":"label","parentid":0,"x":126,"y":245,"w":26,"h":26,"bg_opa":0,"border_width":0,"text":"","text_font":16,"text_color":"black","click":false}',
    '{"page":1,"id":61,"obj":"label","parentid":0,"x":245,"y":245,"w":26,"h":26,"bg_opa":0,"border_width":0,"text":"","text_font":16,"text_color":"black","click":false}',
    '{"page":1,"id":62,"obj":"label","parentid":0,"x":364,"y":245,"w":26,"h":26,"bg_opa":0,"border_width":0,"text":"","text_font":16,"text_color":"black","click":false}',
    '{"page":1,"id":63,"obj":"label","parentid":0,"x":7,"y":364,"w":26,"h":26,"bg_opa":0,"border_width":0,"text":"","text_font":16,"text_color":"black","click":false}',
    '{"page":1,"id":64,"obj":"label","parentid":0,"x":126,"y":364,"w":26,"h":26,"bg_opa":0,"border_width":0,"text":"","text_font":16,"text_color":"black","click":false}',
    '{"page":1,"id":65,"obj":"label","parentid":0,"x":245,"y":364,"w":26,"h":26,"bg_opa":0,"border_width":0,"text":"","text_font":16,"text_color":"black","click":false}',
    '{"page":1,"id":66,"obj":"label","parentid":0,"x":364,"y":364,"w":26,"h":26,"bg_opa":0,"border_width":0,"text":"","text_font":16,"text_color":"black","click":false}'
]
@Field static final List<String> LAYOUT_5x5_BG = [
    '{"page":1,"id":1,"obj":"btn","x":2,"y":2,"w":95,"h":95,"bg_color":"#000000","border_color":"black","border_width":2,"radius":6,"text":"","text_font":14,"align":"center","text_color":"black","toggle":false,"click":false,"long_mode":1}',
    '{"page":1,"id":2,"obj":"btn","x":97,"y":2,"w":95,"h":95,"bg_color":"#000000","border_color":"black","border_width":2,"radius":6,"text":"","text_font":14,"align":"center","text_color":"black","toggle":false,"click":false,"long_mode":1}',
    '{"page":1,"id":3,"obj":"btn","x":192,"y":2,"w":96,"h":95,"bg_color":"#000000","border_color":"black","border_width":2,"radius":6,"text":"","text_font":14,"align":"center","text_color":"black","toggle":false,"click":false,"long_mode":1}',
    '{"page":1,"id":4,"obj":"btn","x":288,"y":2,"w":96,"h":95,"bg_color":"#000000","border_color":"black","border_width":2,"radius":6,"text":"","text_font":14,"align":"center","text_color":"black","toggle":false,"click":false,"long_mode":1}',
    '{"page":1,"id":5,"obj":"btn","x":384,"y":2,"w":96,"h":95,"bg_color":"#000000","border_color":"black","border_width":2,"radius":6,"text":"","text_font":14,"align":"center","text_color":"black","toggle":false,"click":false,"long_mode":1}',
    '{"page":1,"id":6,"obj":"btn","x":2,"y":97,"w":95,"h":95,"bg_color":"#000000","border_color":"black","border_width":2,"radius":6,"text":"","text_font":14,"align":"center","text_color":"black","toggle":false,"click":false,"long_mode":1}',
    '{"page":1,"id":7,"obj":"btn","x":97,"y":97,"w":95,"h":95,"bg_color":"#000000","border_color":"black","border_width":2,"radius":6,"text":"","text_font":14,"align":"center","text_color":"black","toggle":false,"click":false,"long_mode":1}',
    '{"page":1,"id":8,"obj":"btn","x":192,"y":97,"w":96,"h":95,"bg_color":"#000000","border_color":"black","border_width":2,"radius":6,"text":"","text_font":14,"align":"center","text_color":"black","toggle":false,"click":false,"long_mode":1}',
    '{"page":1,"id":9,"obj":"btn","x":288,"y":97,"w":96,"h":95,"bg_color":"#000000","border_color":"black","border_width":2,"radius":6,"text":"","text_font":14,"align":"center","text_color":"black","toggle":false,"click":false,"long_mode":1}',
    '{"page":1,"id":10,"obj":"btn","x":384,"y":97,"w":96,"h":95,"bg_color":"#000000","border_color":"black","border_width":2,"radius":6,"text":"","text_font":14,"align":"center","text_color":"black","toggle":false,"click":false,"long_mode":1}',
    '{"page":1,"id":11,"obj":"btn","x":2,"y":192,"w":95,"h":96,"bg_color":"#000000","border_color":"black","border_width":2,"radius":6,"text":"","text_font":14,"align":"center","text_color":"black","toggle":false,"click":false,"long_mode":1}',
    '{"page":1,"id":12,"obj":"btn","x":97,"y":192,"w":95,"h":96,"bg_color":"#000000","border_color":"black","border_width":2,"radius":6,"text":"","text_font":14,"align":"center","text_color":"black","toggle":false,"click":false,"long_mode":1}',
    '{"page":1,"id":13,"obj":"btn","x":192,"y":192,"w":96,"h":96,"bg_color":"#000000","border_color":"black","border_width":2,"radius":6,"text":"","text_font":14,"align":"center","text_color":"black","toggle":false,"click":false,"long_mode":1}',
    '{"page":1,"id":14,"obj":"btn","x":288,"y":192,"w":96,"h":96,"bg_color":"#000000","border_color":"black","border_width":2,"radius":6,"text":"","text_font":14,"align":"center","text_color":"black","toggle":false,"click":false,"long_mode":1}',
    '{"page":1,"id":15,"obj":"btn","x":384,"y":192,"w":96,"h":96,"bg_color":"#000000","border_color":"black","border_width":2,"radius":6,"text":"","text_font":14,"align":"center","text_color":"black","toggle":false,"click":false,"long_mode":1}',
    '{"page":1,"id":16,"obj":"btn","x":2,"y":288,"w":95,"h":96,"bg_color":"#000000","border_color":"black","border_width":2,"radius":6,"text":"","text_font":14,"align":"center","text_color":"black","toggle":false,"click":false,"long_mode":1}',
    '{"page":1,"id":17,"obj":"btn","x":97,"y":288,"w":95,"h":96,"bg_color":"#000000","border_color":"black","border_width":2,"radius":6,"text":"","text_font":14,"align":"center","text_color":"black","toggle":false,"click":false,"long_mode":1}',
    '{"page":1,"id":18,"obj":"btn","x":192,"y":288,"w":96,"h":96,"bg_color":"#000000","border_color":"black","border_width":2,"radius":6,"text":"","text_font":14,"align":"center","text_color":"black","toggle":false,"click":false,"long_mode":1}',
    '{"page":1,"id":19,"obj":"btn","x":288,"y":288,"w":96,"h":96,"bg_color":"#000000","border_color":"black","border_width":2,"radius":6,"text":"","text_font":14,"align":"center","text_color":"black","toggle":false,"click":false,"long_mode":1}',
    '{"page":1,"id":20,"obj":"btn","x":384,"y":288,"w":96,"h":96,"bg_color":"#000000","border_color":"black","border_width":2,"radius":6,"text":"","text_font":14,"align":"center","text_color":"black","toggle":false,"click":false,"long_mode":1}',
    '{"page":1,"id":21,"obj":"btn","x":2,"y":384,"w":95,"h":96,"bg_color":"#000000","border_color":"black","border_width":2,"radius":6,"text":"","text_font":14,"align":"center","text_color":"black","toggle":false,"click":false,"long_mode":1}',
    '{"page":1,"id":22,"obj":"btn","x":97,"y":384,"w":95,"h":96,"bg_color":"#000000","border_color":"black","border_width":2,"radius":6,"text":"","text_font":14,"align":"center","text_color":"black","toggle":false,"click":false,"long_mode":1}',
    '{"page":1,"id":23,"obj":"btn","x":192,"y":384,"w":96,"h":96,"bg_color":"#000000","border_color":"black","border_width":2,"radius":6,"text":"","text_font":14,"align":"center","text_color":"black","toggle":false,"click":false,"long_mode":1}',
    '{"page":1,"id":24,"obj":"btn","x":288,"y":384,"w":96,"h":96,"bg_color":"#000000","border_color":"black","border_width":2,"radius":6,"text":"","text_font":14,"align":"center","text_color":"black","toggle":false,"click":false,"long_mode":1}',
    '{"page":1,"id":25,"obj":"btn","x":384,"y":384,"w":96,"h":96,"bg_color":"#000000","border_color":"black","border_width":2,"radius":6,"text":"","text_font":14,"align":"center","text_color":"black","toggle":false,"click":false,"long_mode":1}'
]
@Field static final List<String> LAYOUT_5x5_ICON = [
    '{"page":1,"id":51,"obj":"label","parentid":0,"x":6,"y":6,"w":22,"h":22,"bg_opa":0,"border_width":0,"text":"","text_font":14,"text_color":"black","click":false}',
    '{"page":1,"id":52,"obj":"label","parentid":0,"x":101,"y":6,"w":22,"h":22,"bg_opa":0,"border_width":0,"text":"","text_font":14,"text_color":"black","click":false}',
    '{"page":1,"id":53,"obj":"label","parentid":0,"x":196,"y":6,"w":22,"h":22,"bg_opa":0,"border_width":0,"text":"","text_font":14,"text_color":"black","click":false}',
    '{"page":1,"id":54,"obj":"label","parentid":0,"x":292,"y":6,"w":22,"h":22,"bg_opa":0,"border_width":0,"text":"","text_font":14,"text_color":"black","click":false}',
    '{"page":1,"id":55,"obj":"label","parentid":0,"x":388,"y":6,"w":22,"h":22,"bg_opa":0,"border_width":0,"text":"","text_font":14,"text_color":"black","click":false}',
    '{"page":1,"id":56,"obj":"label","parentid":0,"x":6,"y":101,"w":22,"h":22,"bg_opa":0,"border_width":0,"text":"","text_font":14,"text_color":"black","click":false}',
    '{"page":1,"id":57,"obj":"label","parentid":0,"x":101,"y":101,"w":22,"h":22,"bg_opa":0,"border_width":0,"text":"","text_font":14,"text_color":"black","click":false}',
    '{"page":1,"id":58,"obj":"label","parentid":0,"x":196,"y":101,"w":22,"h":22,"bg_opa":0,"border_width":0,"text":"","text_font":14,"text_color":"black","click":false}',
    '{"page":1,"id":59,"obj":"label","parentid":0,"x":292,"y":101,"w":22,"h":22,"bg_opa":0,"border_width":0,"text":"","text_font":14,"text_color":"black","click":false}',
    '{"page":1,"id":60,"obj":"label","parentid":0,"x":388,"y":101,"w":22,"h":22,"bg_opa":0,"border_width":0,"text":"","text_font":14,"text_color":"black","click":false}',
    '{"page":1,"id":61,"obj":"label","parentid":0,"x":6,"y":196,"w":22,"h":22,"bg_opa":0,"border_width":0,"text":"","text_font":14,"text_color":"black","click":false}',
    '{"page":1,"id":62,"obj":"label","parentid":0,"x":101,"y":196,"w":22,"h":22,"bg_opa":0,"border_width":0,"text":"","text_font":14,"text_color":"black","click":false}',
    '{"page":1,"id":63,"obj":"label","parentid":0,"x":196,"y":196,"w":22,"h":22,"bg_opa":0,"border_width":0,"text":"","text_font":14,"text_color":"black","click":false}',
    '{"page":1,"id":64,"obj":"label","parentid":0,"x":292,"y":196,"w":22,"h":22,"bg_opa":0,"border_width":0,"text":"","text_font":14,"text_color":"black","click":false}',
    '{"page":1,"id":65,"obj":"label","parentid":0,"x":388,"y":196,"w":22,"h":22,"bg_opa":0,"border_width":0,"text":"","text_font":14,"text_color":"black","click":false}',
    '{"page":1,"id":66,"obj":"label","parentid":0,"x":6,"y":292,"w":22,"h":22,"bg_opa":0,"border_width":0,"text":"","text_font":14,"text_color":"black","click":false}',
    '{"page":1,"id":67,"obj":"label","parentid":0,"x":101,"y":292,"w":22,"h":22,"bg_opa":0,"border_width":0,"text":"","text_font":14,"text_color":"black","click":false}',
    '{"page":1,"id":68,"obj":"label","parentid":0,"x":196,"y":292,"w":22,"h":22,"bg_opa":0,"border_width":0,"text":"","text_font":14,"text_color":"black","click":false}',
    '{"page":1,"id":69,"obj":"label","parentid":0,"x":292,"y":292,"w":22,"h":22,"bg_opa":0,"border_width":0,"text":"","text_font":14,"text_color":"black","click":false}',
    '{"page":1,"id":70,"obj":"label","parentid":0,"x":388,"y":292,"w":22,"h":22,"bg_opa":0,"border_width":0,"text":"","text_font":14,"text_color":"black","click":false}',
    '{"page":1,"id":71,"obj":"label","parentid":0,"x":6,"y":388,"w":22,"h":22,"bg_opa":0,"border_width":0,"text":"","text_font":14,"text_color":"black","click":false}',
    '{"page":1,"id":72,"obj":"label","parentid":0,"x":101,"y":388,"w":22,"h":22,"bg_opa":0,"border_width":0,"text":"","text_font":14,"text_color":"black","click":false}',
    '{"page":1,"id":73,"obj":"label","parentid":0,"x":196,"y":388,"w":22,"h":22,"bg_opa":0,"border_width":0,"text":"","text_font":14,"text_color":"black","click":false}',
    '{"page":1,"id":74,"obj":"label","parentid":0,"x":292,"y":388,"w":22,"h":22,"bg_opa":0,"border_width":0,"text":"","text_font":14,"text_color":"black","click":false}',
    '{"page":1,"id":75,"obj":"label","parentid":0,"x":388,"y":388,"w":22,"h":22,"bg_opa":0,"border_width":0,"text":"","text_font":14,"text_color":"black","click":false}'
]

@Field static final List<String> LAYOUT_6x6_BG = [
    '{"page":1,"id":1,"obj":"btn","x":2,"y":2,"w":79,"h":79,"bg_color":"#000000","border_color":"black","border_width":1,"radius":4,"text":"","text_font":12,"align":"center","text_color":"black","toggle":false,"click":false,"long_mode":1}',
    '{"page":1,"id":2,"obj":"btn","x":81,"y":2,"w":79,"h":79,"bg_color":"#000000","border_color":"black","border_width":1,"radius":4,"text":"","text_font":12,"align":"center","text_color":"black","toggle":false,"click":false,"long_mode":1}',
    '{"page":1,"id":3,"obj":"btn","x":160,"y":2,"w":80,"h":79,"bg_color":"#000000","border_color":"black","border_width":1,"radius":4,"text":"","text_font":12,"align":"center","text_color":"black","toggle":false,"click":false,"long_mode":1}',
    '{"page":1,"id":4,"obj":"btn","x":240,"y":2,"w":80,"h":79,"bg_color":"#000000","border_color":"black","border_width":1,"radius":4,"text":"","text_font":12,"align":"center","text_color":"black","toggle":false,"click":false,"long_mode":1}',
    '{"page":1,"id":5,"obj":"btn","x":320,"y":2,"w":80,"h":79,"bg_color":"#000000","border_color":"black","border_width":1,"radius":4,"text":"","text_font":12,"align":"center","text_color":"black","toggle":false,"click":false,"long_mode":1}',
    '{"page":1,"id":6,"obj":"btn","x":400,"y":2,"w":80,"h":79,"bg_color":"#000000","border_color":"black","border_width":1,"radius":4,"text":"","text_font":12,"align":"center","text_color":"black","toggle":false,"click":false,"long_mode":1}',
    '{"page":1,"id":7,"obj":"btn","x":2,"y":81,"w":79,"h":79,"bg_color":"#000000","border_color":"black","border_width":1,"radius":4,"text":"","text_font":12,"align":"center","text_color":"black","toggle":false,"click":false,"long_mode":1}',
    '{"page":1,"id":8,"obj":"btn","x":81,"y":81,"w":79,"h":79,"bg_color":"#000000","border_color":"black","border_width":1,"radius":4,"text":"","text_font":12,"align":"center","text_color":"black","toggle":false,"click":false,"long_mode":1}',
    '{"page":1,"id":9,"obj":"btn","x":160,"y":81,"w":80,"h":79,"bg_color":"#000000","border_color":"black","border_width":1,"radius":4,"text":"","text_font":12,"align":"center","text_color":"black","toggle":false,"click":false,"long_mode":1}',
    '{"page":1,"id":10,"obj":"btn","x":240,"y":81,"w":80,"h":79,"bg_color":"#000000","border_color":"black","border_width":1,"radius":4,"text":"","text_font":12,"align":"center","text_color":"black","toggle":false,"click":false,"long_mode":1}',
    '{"page":1,"id":11,"obj":"btn","x":320,"y":81,"w":80,"h":79,"bg_color":"#000000","border_color":"black","border_width":1,"radius":4,"text":"","text_font":12,"align":"center","text_color":"black","toggle":false,"click":false,"long_mode":1}',
    '{"page":1,"id":12,"obj":"btn","x":400,"y":81,"w":80,"h":79,"bg_color":"#000000","border_color":"black","border_width":1,"radius":4,"text":"","text_font":12,"align":"center","text_color":"black","toggle":false,"click":false,"long_mode":1}',
    '{"page":1,"id":13,"obj":"btn","x":2,"y":160,"w":79,"h":80,"bg_color":"#000000","border_color":"black","border_width":1,"radius":4,"text":"","text_font":12,"align":"center","text_color":"black","toggle":false,"click":false,"long_mode":1}',
    '{"page":1,"id":14,"obj":"btn","x":81,"y":160,"w":79,"h":80,"bg_color":"#000000","border_color":"black","border_width":1,"radius":4,"text":"","text_font":12,"align":"center","text_color":"black","toggle":false,"click":false,"long_mode":1}',
    '{"page":1,"id":15,"obj":"btn","x":160,"y":160,"w":80,"h":80,"bg_color":"#000000","border_color":"black","border_width":1,"radius":4,"text":"","text_font":12,"align":"center","text_color":"black","toggle":false,"click":false,"long_mode":1}',
    '{"page":1,"id":16,"obj":"btn","x":240,"y":160,"w":80,"h":80,"bg_color":"#000000","border_color":"black","border_width":1,"radius":4,"text":"","text_font":12,"align":"center","text_color":"black","toggle":false,"click":false,"long_mode":1}',
    '{"page":1,"id":17,"obj":"btn","x":320,"y":160,"w":80,"h":80,"bg_color":"#000000","border_color":"black","border_width":1,"radius":4,"text":"","text_font":12,"align":"center","text_color":"black","toggle":false,"click":false,"long_mode":1}',
    '{"page":1,"id":18,"obj":"btn","x":400,"y":160,"w":80,"h":80,"bg_color":"#000000","border_color":"black","border_width":1,"radius":4,"text":"","text_font":12,"align":"center","text_color":"black","toggle":false,"click":false,"long_mode":1}',
    '{"page":1,"id":19,"obj":"btn","x":2,"y":240,"w":79,"h":80,"bg_color":"#000000","border_color":"black","border_width":1,"radius":4,"text":"","text_font":12,"align":"center","text_color":"black","toggle":false,"click":false,"long_mode":1}',
    '{"page":1,"id":20,"obj":"btn","x":81,"y":240,"w":79,"h":80,"bg_color":"#000000","border_color":"black","border_width":1,"radius":4,"text":"","text_font":12,"align":"center","text_color":"black","toggle":false,"click":false,"long_mode":1}',
    '{"page":1,"id":21,"obj":"btn","x":160,"y":240,"w":80,"h":80,"bg_color":"#000000","border_color":"black","border_width":1,"radius":4,"text":"","text_font":12,"align":"center","text_color":"black","toggle":false,"click":false,"long_mode":1}',
    '{"page":1,"id":22,"obj":"btn","x":240,"y":240,"w":80,"h":80,"bg_color":"#000000","border_color":"black","border_width":1,"radius":4,"text":"","text_font":12,"align":"center","text_color":"black","toggle":false,"click":false,"long_mode":1}',
    '{"page":1,"id":23,"obj":"btn","x":320,"y":240,"w":80,"h":80,"bg_color":"#000000","border_color":"black","border_width":1,"radius":4,"text":"","text_font":12,"align":"center","text_color":"black","toggle":false,"click":false,"long_mode":1}',
    '{"page":1,"id":24,"obj":"btn","x":400,"y":240,"w":80,"h":80,"bg_color":"#000000","border_color":"black","border_width":1,"radius":4,"text":"","text_font":12,"align":"center","text_color":"black","toggle":false,"click":false,"long_mode":1}',
    '{"page":1,"id":25,"obj":"btn","x":2,"y":320,"w":79,"h":80,"bg_color":"#000000","border_color":"black","border_width":1,"radius":4,"text":"","text_font":12,"align":"center","text_color":"black","toggle":false,"click":false,"long_mode":1}',
    '{"page":1,"id":26,"obj":"btn","x":81,"y":320,"w":79,"h":80,"bg_color":"#000000","border_color":"black","border_width":1,"radius":4,"text":"","text_font":12,"align":"center","text_color":"black","toggle":false,"click":false,"long_mode":1}',
    '{"page":1,"id":27,"obj":"btn","x":160,"y":320,"w":80,"h":80,"bg_color":"#000000","border_color":"black","border_width":1,"radius":4,"text":"","text_font":12,"align":"center","text_color":"black","toggle":false,"click":false,"long_mode":1}',
    '{"page":1,"id":28,"obj":"btn","x":240,"y":320,"w":80,"h":80,"bg_color":"#000000","border_color":"black","border_width":1,"radius":4,"text":"","text_font":12,"align":"center","text_color":"black","toggle":false,"click":false,"long_mode":1}',
    '{"page":1,"id":29,"obj":"btn","x":320,"y":320,"w":80,"h":80,"bg_color":"#000000","border_color":"black","border_width":1,"radius":4,"text":"","text_font":12,"align":"center","text_color":"black","toggle":false,"click":false,"long_mode":1}',
    '{"page":1,"id":30,"obj":"btn","x":400,"y":320,"w":80,"h":80,"bg_color":"#000000","border_color":"black","border_width":1,"radius":4,"text":"","text_font":12,"align":"center","text_color":"black","toggle":false,"click":false,"long_mode":1}',
    '{"page":1,"id":31,"obj":"btn","x":2,"y":400,"w":79,"h":80,"bg_color":"#000000","border_color":"black","border_width":1,"radius":4,"text":"","text_font":12,"align":"center","text_color":"black","toggle":false,"click":false,"long_mode":1}',
    '{"page":1,"id":32,"obj":"btn","x":81,"y":400,"w":79,"h":80,"bg_color":"#000000","border_color":"black","border_width":1,"radius":4,"text":"","text_font":12,"align":"center","text_color":"black","toggle":false,"click":false,"long_mode":1}',
    '{"page":1,"id":33,"obj":"btn","x":160,"y":400,"w":80,"h":80,"bg_color":"#000000","border_color":"black","border_width":1,"radius":4,"text":"","text_font":12,"align":"center","text_color":"black","toggle":false,"click":false,"long_mode":1}',
    '{"page":1,"id":34,"obj":"btn","x":240,"y":400,"w":80,"h":80,"bg_color":"#000000","border_color":"black","border_width":1,"radius":4,"text":"","text_font":12,"align":"center","text_color":"black","toggle":false,"click":false,"long_mode":1}',
    '{"page":1,"id":35,"obj":"btn","x":320,"y":400,"w":80,"h":80,"bg_color":"#000000","border_color":"black","border_width":1,"radius":4,"text":"","text_font":12,"align":"center","text_color":"black","toggle":false,"click":false,"long_mode":1}',
    '{"page":1,"id":36,"obj":"btn","x":400,"y":400,"w":80,"h":80,"bg_color":"#000000","border_color":"black","border_width":1,"radius":4,"text":"","text_font":12,"align":"center","text_color":"black","toggle":false,"click":false,"long_mode":1}'
]
@Field static final List<String> LAYOUT_6x6_ICON = [
    '{"page":1,"id":51,"obj":"label","parentid":0,"x":5,"y":5,"w":18,"h":18,"bg_opa":0,"border_width":0,"text":"","text_font":12,"text_color":"black","click":false}',
    '{"page":1,"id":52,"obj":"label","parentid":0,"x":84,"y":5,"w":18,"h":18,"bg_opa":0,"border_width":0,"text":"","text_font":12,"text_color":"black","click":false}',
    '{"page":1,"id":53,"obj":"label","parentid":0,"x":163,"y":5,"w":18,"h":18,"bg_opa":0,"border_width":0,"text":"","text_font":12,"text_color":"black","click":false}',
    '{"page":1,"id":54,"obj":"label","parentid":0,"x":243,"y":5,"w":18,"h":18,"bg_opa":0,"border_width":0,"text":"","text_font":12,"text_color":"black","click":false}',
    '{"page":1,"id":55,"obj":"label","parentid":0,"x":323,"y":5,"w":18,"h":18,"bg_opa":0,"border_width":0,"text":"","text_font":12,"text_color":"black","click":false}',
    '{"page":1,"id":56,"obj":"label","parentid":0,"x":403,"y":5,"w":18,"h":18,"bg_opa":0,"border_width":0,"text":"","text_font":12,"text_color":"black","click":false}',
    '{"page":1,"id":57,"obj":"label","parentid":0,"x":5,"y":84,"w":18,"h":18,"bg_opa":0,"border_width":0,"text":"","text_font":12,"text_color":"black","click":false}',
    '{"page":1,"id":58,"obj":"label","parentid":0,"x":84,"y":84,"w":18,"h":18,"bg_opa":0,"border_width":0,"text":"","text_font":12,"text_color":"black","click":false}',
    '{"page":1,"id":59,"obj":"label","parentid":0,"x":163,"y":84,"w":18,"h":18,"bg_opa":0,"border_width":0,"text":"","text_font":12,"text_color":"black","click":false}',
    '{"page":1,"id":60,"obj":"label","parentid":0,"x":243,"y":84,"w":18,"h":18,"bg_opa":0,"border_width":0,"text":"","text_font":12,"text_color":"black","click":false}',
    '{"page":1,"id":61,"obj":"label","parentid":0,"x":323,"y":84,"w":18,"h":18,"bg_opa":0,"border_width":0,"text":"","text_font":12,"text_color":"black","click":false}',
    '{"page":1,"id":62,"obj":"label","parentid":0,"x":403,"y":84,"w":18,"h":18,"bg_opa":0,"border_width":0,"text":"","text_font":12,"text_color":"black","click":false}',
    '{"page":1,"id":63,"obj":"label","parentid":0,"x":5,"y":163,"w":18,"h":18,"bg_opa":0,"border_width":0,"text":"","text_font":12,"text_color":"black","click":false}',
    '{"page":1,"id":64,"obj":"label","parentid":0,"x":84,"y":163,"w":18,"h":18,"bg_opa":0,"border_width":0,"text":"","text_font":12,"text_color":"black","click":false}',
    '{"page":1,"id":65,"obj":"label","parentid":0,"x":163,"y":163,"w":18,"h":18,"bg_opa":0,"border_width":0,"text":"","text_font":12,"text_color":"black","click":false}',
    '{"page":1,"id":66,"obj":"label","parentid":0,"x":243,"y":163,"w":18,"h":18,"bg_opa":0,"border_width":0,"text":"","text_font":12,"text_color":"black","click":false}',
    '{"page":1,"id":67,"obj":"label","parentid":0,"x":323,"y":163,"w":18,"h":18,"bg_opa":0,"border_width":0,"text":"","text_font":12,"text_color":"black","click":false}',
    '{"page":1,"id":68,"obj":"label","parentid":0,"x":403,"y":163,"w":18,"h":18,"bg_opa":0,"border_width":0,"text":"","text_font":12,"text_color":"black","click":false}',
    '{"page":1,"id":69,"obj":"label","parentid":0,"x":5,"y":243,"w":18,"h":18,"bg_opa":0,"border_width":0,"text":"","text_font":12,"text_color":"black","click":false}',
    '{"page":1,"id":70,"obj":"label","parentid":0,"x":84,"y":243,"w":18,"h":18,"bg_opa":0,"border_width":0,"text":"","text_font":12,"text_color":"black","click":false}',
    '{"page":1,"id":71,"obj":"label","parentid":0,"x":163,"y":243,"w":18,"h":18,"bg_opa":0,"border_width":0,"text":"","text_font":12,"text_color":"black","click":false}',
    '{"page":1,"id":72,"obj":"label","parentid":0,"x":243,"y":243,"w":18,"h":18,"bg_opa":0,"border_width":0,"text":"","text_font":12,"text_color":"black","click":false}',
    '{"page":1,"id":73,"obj":"label","parentid":0,"x":323,"y":243,"w":18,"h":18,"bg_opa":0,"border_width":0,"text":"","text_font":12,"text_color":"black","click":false}',
    '{"page":1,"id":74,"obj":"label","parentid":0,"x":403,"y":243,"w":18,"h":18,"bg_opa":0,"border_width":0,"text":"","text_font":12,"text_color":"black","click":false}',
    '{"page":1,"id":75,"obj":"label","parentid":0,"x":5,"y":323,"w":18,"h":18,"bg_opa":0,"border_width":0,"text":"","text_font":12,"text_color":"black","click":false}',
    '{"page":1,"id":76,"obj":"label","parentid":0,"x":84,"y":323,"w":18,"h":18,"bg_opa":0,"border_width":0,"text":"","text_font":12,"text_color":"black","click":false}',
    '{"page":1,"id":77,"obj":"label","parentid":0,"x":163,"y":323,"w":18,"h":18,"bg_opa":0,"border_width":0,"text":"","text_font":12,"text_color":"black","click":false}',
    '{"page":1,"id":78,"obj":"label","parentid":0,"x":243,"y":323,"w":18,"h":18,"bg_opa":0,"border_width":0,"text":"","text_font":12,"text_color":"black","click":false}',
    '{"page":1,"id":79,"obj":"label","parentid":0,"x":323,"y":323,"w":18,"h":18,"bg_opa":0,"border_width":0,"text":"","text_font":12,"text_color":"black","click":false}',
    '{"page":1,"id":80,"obj":"label","parentid":0,"x":403,"y":323,"w":18,"h":18,"bg_opa":0,"border_width":0,"text":"","text_font":12,"text_color":"black","click":false}',
    '{"page":1,"id":81,"obj":"label","parentid":0,"x":5,"y":403,"w":18,"h":18,"bg_opa":0,"border_width":0,"text":"","text_font":12,"text_color":"black","click":false}',
    '{"page":1,"id":82,"obj":"label","parentid":0,"x":84,"y":403,"w":18,"h":18,"bg_opa":0,"border_width":0,"text":"","text_font":12,"text_color":"black","click":false}',
    '{"page":1,"id":83,"obj":"label","parentid":0,"x":163,"y":403,"w":18,"h":18,"bg_opa":0,"border_width":0,"text":"","text_font":12,"text_color":"black","click":false}',
    '{"page":1,"id":84,"obj":"label","parentid":0,"x":243,"y":403,"w":18,"h":18,"bg_opa":0,"border_width":0,"text":"","text_font":12,"text_color":"black","click":false}',
    '{"page":1,"id":85,"obj":"label","parentid":0,"x":323,"y":403,"w":18,"h":18,"bg_opa":0,"border_width":0,"text":"","text_font":12,"text_color":"black","click":false}',
    '{"page":1,"id":86,"obj":"label","parentid":0,"x":403,"y":403,"w":18,"h":18,"bg_opa":0,"border_width":0,"text":"","text_font":12,"text_color":"black","click":false}'
]

@Field static final List<String> LAYOUT_7x7_BG = [
    '{"page":1,"id":1,"obj":"btn","x":2,"y":2,"w":68,"h":68,"bg_color":"#000000","border_color":"black","border_width":1,"radius":3,"text":"","text_font":12,"align":"center","text_color":"black","toggle":false,"click":false,"long_mode":1}',
    '{"page":1,"id":2,"obj":"btn","x":70,"y":2,"w":68,"h":68,"bg_color":"#000000","border_color":"black","border_width":1,"radius":3,"text":"","text_font":12,"align":"center","text_color":"black","toggle":false,"click":false,"long_mode":1}',
    '{"page":1,"id":3,"obj":"btn","x":138,"y":2,"w":68,"h":68,"bg_color":"#000000","border_color":"black","border_width":1,"radius":3,"text":"","text_font":12,"align":"center","text_color":"black","toggle":false,"click":false,"long_mode":1}',
    '{"page":1,"id":4,"obj":"btn","x":206,"y":2,"w":68,"h":68,"bg_color":"#000000","border_color":"black","border_width":1,"radius":3,"text":"","text_font":12,"align":"center","text_color":"black","toggle":false,"click":false,"long_mode":1}',
    '{"page":1,"id":5,"obj":"btn","x":274,"y":2,"w":68,"h":68,"bg_color":"#000000","border_color":"black","border_width":1,"radius":3,"text":"","text_font":12,"align":"center","text_color":"black","toggle":false,"click":false,"long_mode":1}',
    '{"page":1,"id":6,"obj":"btn","x":342,"y":2,"w":69,"h":68,"bg_color":"#000000","border_color":"black","border_width":1,"radius":3,"text":"","text_font":12,"align":"center","text_color":"black","toggle":false,"click":false,"long_mode":1}',
    '{"page":1,"id":7,"obj":"btn","x":411,"y":2,"w":69,"h":68,"bg_color":"#000000","border_color":"black","border_width":1,"radius":3,"text":"","text_font":12,"align":"center","text_color":"black","toggle":false,"click":false,"long_mode":1}',
    '{"page":1,"id":8,"obj":"btn","x":2,"y":70,"w":68,"h":68,"bg_color":"#000000","border_color":"black","border_width":1,"radius":3,"text":"","text_font":12,"align":"center","text_color":"black","toggle":false,"click":false,"long_mode":1}',
    '{"page":1,"id":9,"obj":"btn","x":70,"y":70,"w":68,"h":68,"bg_color":"#000000","border_color":"black","border_width":1,"radius":3,"text":"","text_font":12,"align":"center","text_color":"black","toggle":false,"click":false,"long_mode":1}',
    '{"page":1,"id":10,"obj":"btn","x":138,"y":70,"w":68,"h":68,"bg_color":"#000000","border_color":"black","border_width":1,"radius":3,"text":"","text_font":12,"align":"center","text_color":"black","toggle":false,"click":false,"long_mode":1}',
    '{"page":1,"id":11,"obj":"btn","x":206,"y":70,"w":68,"h":68,"bg_color":"#000000","border_color":"black","border_width":1,"radius":3,"text":"","text_font":12,"align":"center","text_color":"black","toggle":false,"click":false,"long_mode":1}',
    '{"page":1,"id":12,"obj":"btn","x":274,"y":70,"w":68,"h":68,"bg_color":"#000000","border_color":"black","border_width":1,"radius":3,"text":"","text_font":12,"align":"center","text_color":"black","toggle":false,"click":false,"long_mode":1}',
    '{"page":1,"id":13,"obj":"btn","x":342,"y":70,"w":69,"h":68,"bg_color":"#000000","border_color":"black","border_width":1,"radius":3,"text":"","text_font":12,"align":"center","text_color":"black","toggle":false,"click":false,"long_mode":1}',
    '{"page":1,"id":14,"obj":"btn","x":411,"y":70,"w":69,"h":68,"bg_color":"#000000","border_color":"black","border_width":1,"radius":3,"text":"","text_font":12,"align":"center","text_color":"black","toggle":false,"click":false,"long_mode":1}',
    '{"page":1,"id":15,"obj":"btn","x":2,"y":138,"w":68,"h":68,"bg_color":"#000000","border_color":"black","border_width":1,"radius":3,"text":"","text_font":12,"align":"center","text_color":"black","toggle":false,"click":false,"long_mode":1}',
    '{"page":1,"id":16,"obj":"btn","x":70,"y":138,"w":68,"h":68,"bg_color":"#000000","border_color":"black","border_width":1,"radius":3,"text":"","text_font":12,"align":"center","text_color":"black","toggle":false,"click":false,"long_mode":1}',
    '{"page":1,"id":17,"obj":"btn","x":138,"y":138,"w":68,"h":68,"bg_color":"#000000","border_color":"black","border_width":1,"radius":3,"text":"","text_font":12,"align":"center","text_color":"black","toggle":false,"click":false,"long_mode":1}',
    '{"page":1,"id":18,"obj":"btn","x":206,"y":138,"w":68,"h":68,"bg_color":"#000000","border_color":"black","border_width":1,"radius":3,"text":"","text_font":12,"align":"center","text_color":"black","toggle":false,"click":false,"long_mode":1}',
    '{"page":1,"id":19,"obj":"btn","x":274,"y":138,"w":68,"h":68,"bg_color":"#000000","border_color":"black","border_width":1,"radius":3,"text":"","text_font":12,"align":"center","text_color":"black","toggle":false,"click":false,"long_mode":1}',
    '{"page":1,"id":20,"obj":"btn","x":342,"y":138,"w":69,"h":68,"bg_color":"#000000","border_color":"black","border_width":1,"radius":3,"text":"","text_font":12,"align":"center","text_color":"black","toggle":false,"click":false,"long_mode":1}',
    '{"page":1,"id":21,"obj":"btn","x":411,"y":138,"w":69,"h":68,"bg_color":"#000000","border_color":"black","border_width":1,"radius":3,"text":"","text_font":12,"align":"center","text_color":"black","toggle":false,"click":false,"long_mode":1}',
    '{"page":1,"id":22,"obj":"btn","x":2,"y":206,"w":68,"h":68,"bg_color":"#000000","border_color":"black","border_width":1,"radius":3,"text":"","text_font":12,"align":"center","text_color":"black","toggle":false,"click":false,"long_mode":1}',
    '{"page":1,"id":23,"obj":"btn","x":70,"y":206,"w":68,"h":68,"bg_color":"#000000","border_color":"black","border_width":1,"radius":3,"text":"","text_font":12,"align":"center","text_color":"black","toggle":false,"click":false,"long_mode":1}',
    '{"page":1,"id":24,"obj":"btn","x":138,"y":206,"w":68,"h":68,"bg_color":"#000000","border_color":"black","border_width":1,"radius":3,"text":"","text_font":12,"align":"center","text_color":"black","toggle":false,"click":false,"long_mode":1}',
    '{"page":1,"id":25,"obj":"btn","x":206,"y":206,"w":68,"h":68,"bg_color":"#000000","border_color":"black","border_width":1,"radius":3,"text":"","text_font":12,"align":"center","text_color":"black","toggle":false,"click":false,"long_mode":1}',
    '{"page":1,"id":26,"obj":"btn","x":274,"y":206,"w":68,"h":68,"bg_color":"#000000","border_color":"black","border_width":1,"radius":3,"text":"","text_font":12,"align":"center","text_color":"black","toggle":false,"click":false,"long_mode":1}',
    '{"page":1,"id":27,"obj":"btn","x":342,"y":206,"w":69,"h":68,"bg_color":"#000000","border_color":"black","border_width":1,"radius":3,"text":"","text_font":12,"align":"center","text_color":"black","toggle":false,"click":false,"long_mode":1}',
    '{"page":1,"id":28,"obj":"btn","x":411,"y":206,"w":69,"h":68,"bg_color":"#000000","border_color":"black","border_width":1,"radius":3,"text":"","text_font":12,"align":"center","text_color":"black","toggle":false,"click":false,"long_mode":1}',
    '{"page":1,"id":29,"obj":"btn","x":2,"y":274,"w":68,"h":68,"bg_color":"#000000","border_color":"black","border_width":1,"radius":3,"text":"","text_font":12,"align":"center","text_color":"black","toggle":false,"click":false,"long_mode":1}',
    '{"page":1,"id":30,"obj":"btn","x":70,"y":274,"w":68,"h":68,"bg_color":"#000000","border_color":"black","border_width":1,"radius":3,"text":"","text_font":12,"align":"center","text_color":"black","toggle":false,"click":false,"long_mode":1}',
    '{"page":1,"id":31,"obj":"btn","x":138,"y":274,"w":68,"h":68,"bg_color":"#000000","border_color":"black","border_width":1,"radius":3,"text":"","text_font":12,"align":"center","text_color":"black","toggle":false,"click":false,"long_mode":1}',
    '{"page":1,"id":32,"obj":"btn","x":206,"y":274,"w":68,"h":68,"bg_color":"#000000","border_color":"black","border_width":1,"radius":3,"text":"","text_font":12,"align":"center","text_color":"black","toggle":false,"click":false,"long_mode":1}',
    '{"page":1,"id":33,"obj":"btn","x":274,"y":274,"w":68,"h":68,"bg_color":"#000000","border_color":"black","border_width":1,"radius":3,"text":"","text_font":12,"align":"center","text_color":"black","toggle":false,"click":false,"long_mode":1}',
    '{"page":1,"id":34,"obj":"btn","x":342,"y":274,"w":69,"h":68,"bg_color":"#000000","border_color":"black","border_width":1,"radius":3,"text":"","text_font":12,"align":"center","text_color":"black","toggle":false,"click":false,"long_mode":1}',
    '{"page":1,"id":35,"obj":"btn","x":411,"y":274,"w":69,"h":68,"bg_color":"#000000","border_color":"black","border_width":1,"radius":3,"text":"","text_font":12,"align":"center","text_color":"black","toggle":false,"click":false,"long_mode":1}',
    '{"page":1,"id":36,"obj":"btn","x":2,"y":342,"w":68,"h":69,"bg_color":"#000000","border_color":"black","border_width":1,"radius":3,"text":"","text_font":12,"align":"center","text_color":"black","toggle":false,"click":false,"long_mode":1}',
    '{"page":1,"id":37,"obj":"btn","x":70,"y":342,"w":68,"h":69,"bg_color":"#000000","border_color":"black","border_width":1,"radius":3,"text":"","text_font":12,"align":"center","text_color":"black","toggle":false,"click":false,"long_mode":1}',
    '{"page":1,"id":38,"obj":"btn","x":138,"y":342,"w":68,"h":69,"bg_color":"#000000","border_color":"black","border_width":1,"radius":3,"text":"","text_font":12,"align":"center","text_color":"black","toggle":false,"click":false,"long_mode":1}',
    '{"page":1,"id":39,"obj":"btn","x":206,"y":342,"w":68,"h":69,"bg_color":"#000000","border_color":"black","border_width":1,"radius":3,"text":"","text_font":12,"align":"center","text_color":"black","toggle":false,"click":false,"long_mode":1}',
    '{"page":1,"id":40,"obj":"btn","x":274,"y":342,"w":68,"h":69,"bg_color":"#000000","border_color":"black","border_width":1,"radius":3,"text":"","text_font":12,"align":"center","text_color":"black","toggle":false,"click":false,"long_mode":1}',
    '{"page":1,"id":41,"obj":"btn","x":342,"y":342,"w":69,"h":69,"bg_color":"#000000","border_color":"black","border_width":1,"radius":3,"text":"","text_font":12,"align":"center","text_color":"black","toggle":false,"click":false,"long_mode":1}',
    '{"page":1,"id":42,"obj":"btn","x":411,"y":342,"w":69,"h":69,"bg_color":"#000000","border_color":"black","border_width":1,"radius":3,"text":"","text_font":12,"align":"center","text_color":"black","toggle":false,"click":false,"long_mode":1}',
    '{"page":1,"id":43,"obj":"btn","x":2,"y":411,"w":68,"h":69,"bg_color":"#000000","border_color":"black","border_width":1,"radius":3,"text":"","text_font":12,"align":"center","text_color":"black","toggle":false,"click":false,"long_mode":1}',
    '{"page":1,"id":44,"obj":"btn","x":70,"y":411,"w":68,"h":69,"bg_color":"#000000","border_color":"black","border_width":1,"radius":3,"text":"","text_font":12,"align":"center","text_color":"black","toggle":false,"click":false,"long_mode":1}',
    '{"page":1,"id":45,"obj":"btn","x":138,"y":411,"w":68,"h":69,"bg_color":"#000000","border_color":"black","border_width":1,"radius":3,"text":"","text_font":12,"align":"center","text_color":"black","toggle":false,"click":false,"long_mode":1}',
    '{"page":1,"id":46,"obj":"btn","x":206,"y":411,"w":68,"h":69,"bg_color":"#000000","border_color":"black","border_width":1,"radius":3,"text":"","text_font":12,"align":"center","text_color":"black","toggle":false,"click":false,"long_mode":1}',
    '{"page":1,"id":47,"obj":"btn","x":274,"y":411,"w":68,"h":69,"bg_color":"#000000","border_color":"black","border_width":1,"radius":3,"text":"","text_font":12,"align":"center","text_color":"black","toggle":false,"click":false,"long_mode":1}',
    '{"page":1,"id":48,"obj":"btn","x":342,"y":411,"w":69,"h":69,"bg_color":"#000000","border_color":"black","border_width":1,"radius":3,"text":"","text_font":12,"align":"center","text_color":"black","toggle":false,"click":false,"long_mode":1}',
    '{"page":1,"id":49,"obj":"btn","x":411,"y":411,"w":69,"h":69,"bg_color":"#000000","border_color":"black","border_width":1,"radius":3,"text":"","text_font":12,"align":"center","text_color":"black","toggle":false,"click":false,"long_mode":1}'
]
@Field static final List<String> LAYOUT_7x7_ICON = [
    '{"page":1,"id":51,"obj":"label","parentid":0,"x":4,"y":4,"w":16,"h":16,"bg_opa":0,"border_width":0,"text":"","text_font":12,"text_color":"black","click":false}',
    '{"page":1,"id":52,"obj":"label","parentid":0,"x":72,"y":4,"w":16,"h":16,"bg_opa":0,"border_width":0,"text":"","text_font":12,"text_color":"black","click":false}',
    '{"page":1,"id":53,"obj":"label","parentid":0,"x":140,"y":4,"w":16,"h":16,"bg_opa":0,"border_width":0,"text":"","text_font":12,"text_color":"black","click":false}',
    '{"page":1,"id":54,"obj":"label","parentid":0,"x":208,"y":4,"w":16,"h":16,"bg_opa":0,"border_width":0,"text":"","text_font":12,"text_color":"black","click":false}',
    '{"page":1,"id":55,"obj":"label","parentid":0,"x":276,"y":4,"w":16,"h":16,"bg_opa":0,"border_width":0,"text":"","text_font":12,"text_color":"black","click":false}',
    '{"page":1,"id":56,"obj":"label","parentid":0,"x":344,"y":4,"w":16,"h":16,"bg_opa":0,"border_width":0,"text":"","text_font":12,"text_color":"black","click":false}',
    '{"page":1,"id":57,"obj":"label","parentid":0,"x":413,"y":4,"w":16,"h":16,"bg_opa":0,"border_width":0,"text":"","text_font":12,"text_color":"black","click":false}',
    '{"page":1,"id":58,"obj":"label","parentid":0,"x":4,"y":72,"w":16,"h":16,"bg_opa":0,"border_width":0,"text":"","text_font":12,"text_color":"black","click":false}',
    '{"page":1,"id":59,"obj":"label","parentid":0,"x":72,"y":72,"w":16,"h":16,"bg_opa":0,"border_width":0,"text":"","text_font":12,"text_color":"black","click":false}',
    '{"page":1,"id":60,"obj":"label","parentid":0,"x":140,"y":72,"w":16,"h":16,"bg_opa":0,"border_width":0,"text":"","text_font":12,"text_color":"black","click":false}',
    '{"page":1,"id":61,"obj":"label","parentid":0,"x":208,"y":72,"w":16,"h":16,"bg_opa":0,"border_width":0,"text":"","text_font":12,"text_color":"black","click":false}',
    '{"page":1,"id":62,"obj":"label","parentid":0,"x":276,"y":72,"w":16,"h":16,"bg_opa":0,"border_width":0,"text":"","text_font":12,"text_color":"black","click":false}',
    '{"page":1,"id":63,"obj":"label","parentid":0,"x":344,"y":72,"w":16,"h":16,"bg_opa":0,"border_width":0,"text":"","text_font":12,"text_color":"black","click":false}',
    '{"page":1,"id":64,"obj":"label","parentid":0,"x":413,"y":72,"w":16,"h":16,"bg_opa":0,"border_width":0,"text":"","text_font":12,"text_color":"black","click":false}',
    '{"page":1,"id":65,"obj":"label","parentid":0,"x":4,"y":140,"w":16,"h":16,"bg_opa":0,"border_width":0,"text":"","text_font":12,"text_color":"black","click":false}',
    '{"page":1,"id":66,"obj":"label","parentid":0,"x":72,"y":140,"w":16,"h":16,"bg_opa":0,"border_width":0,"text":"","text_font":12,"text_color":"black","click":false}',
    '{"page":1,"id":67,"obj":"label","parentid":0,"x":140,"y":140,"w":16,"h":16,"bg_opa":0,"border_width":0,"text":"","text_font":12,"text_color":"black","click":false}',
    '{"page":1,"id":68,"obj":"label","parentid":0,"x":208,"y":140,"w":16,"h":16,"bg_opa":0,"border_width":0,"text":"","text_font":12,"text_color":"black","click":false}',
    '{"page":1,"id":69,"obj":"label","parentid":0,"x":276,"y":140,"w":16,"h":16,"bg_opa":0,"border_width":0,"text":"","text_font":12,"text_color":"black","click":false}',
    '{"page":1,"id":70,"obj":"label","parentid":0,"x":344,"y":140,"w":16,"h":16,"bg_opa":0,"border_width":0,"text":"","text_font":12,"text_color":"black","click":false}',
    '{"page":1,"id":71,"obj":"label","parentid":0,"x":413,"y":140,"w":16,"h":16,"bg_opa":0,"border_width":0,"text":"","text_font":12,"text_color":"black","click":false}',
    '{"page":1,"id":72,"obj":"label","parentid":0,"x":4,"y":208,"w":16,"h":16,"bg_opa":0,"border_width":0,"text":"","text_font":12,"text_color":"black","click":false}',
    '{"page":1,"id":73,"obj":"label","parentid":0,"x":72,"y":208,"w":16,"h":16,"bg_opa":0,"border_width":0,"text":"","text_font":12,"text_color":"black","click":false}',
    '{"page":1,"id":74,"obj":"label","parentid":0,"x":140,"y":208,"w":16,"h":16,"bg_opa":0,"border_width":0,"text":"","text_font":12,"text_color":"black","click":false}',
    '{"page":1,"id":75,"obj":"label","parentid":0,"x":208,"y":208,"w":16,"h":16,"bg_opa":0,"border_width":0,"text":"","text_font":12,"text_color":"black","click":false}',
    '{"page":1,"id":76,"obj":"label","parentid":0,"x":276,"y":208,"w":16,"h":16,"bg_opa":0,"border_width":0,"text":"","text_font":12,"text_color":"black","click":false}',
    '{"page":1,"id":77,"obj":"label","parentid":0,"x":344,"y":208,"w":16,"h":16,"bg_opa":0,"border_width":0,"text":"","text_font":12,"text_color":"black","click":false}',
    '{"page":1,"id":78,"obj":"label","parentid":0,"x":413,"y":208,"w":16,"h":16,"bg_opa":0,"border_width":0,"text":"","text_font":12,"text_color":"black","click":false}',
    '{"page":1,"id":79,"obj":"label","parentid":0,"x":4,"y":276,"w":16,"h":16,"bg_opa":0,"border_width":0,"text":"","text_font":12,"text_color":"black","click":false}',
    '{"page":1,"id":80,"obj":"label","parentid":0,"x":72,"y":276,"w":16,"h":16,"bg_opa":0,"border_width":0,"text":"","text_font":12,"text_color":"black","click":false}',
    '{"page":1,"id":81,"obj":"label","parentid":0,"x":140,"y":276,"w":16,"h":16,"bg_opa":0,"border_width":0,"text":"","text_font":12,"text_color":"black","click":false}',
    '{"page":1,"id":82,"obj":"label","parentid":0,"x":208,"y":276,"w":16,"h":16,"bg_opa":0,"border_width":0,"text":"","text_font":12,"text_color":"black","click":false}',
    '{"page":1,"id":83,"obj":"label","parentid":0,"x":276,"y":276,"w":16,"h":16,"bg_opa":0,"border_width":0,"text":"","text_font":12,"text_color":"black","click":false}',
    '{"page":1,"id":84,"obj":"label","parentid":0,"x":344,"y":276,"w":16,"h":16,"bg_opa":0,"border_width":0,"text":"","text_font":12,"text_color":"black","click":false}',
    '{"page":1,"id":85,"obj":"label","parentid":0,"x":413,"y":276,"w":16,"h":16,"bg_opa":0,"border_width":0,"text":"","text_font":12,"text_color":"black","click":false}',
    '{"page":1,"id":86,"obj":"label","parentid":0,"x":4,"y":344,"w":16,"h":16,"bg_opa":0,"border_width":0,"text":"","text_font":12,"text_color":"black","click":false}',
    '{"page":1,"id":87,"obj":"label","parentid":0,"x":72,"y":344,"w":16,"h":16,"bg_opa":0,"border_width":0,"text":"","text_font":12,"text_color":"black","click":false}',
    '{"page":1,"id":88,"obj":"label","parentid":0,"x":140,"y":344,"w":16,"h":16,"bg_opa":0,"border_width":0,"text":"","text_font":12,"text_color":"black","click":false}',
    '{"page":1,"id":89,"obj":"label","parentid":0,"x":208,"y":344,"w":16,"h":16,"bg_opa":0,"border_width":0,"text":"","text_font":12,"text_color":"black","click":false}',
    '{"page":1,"id":90,"obj":"label","parentid":0,"x":276,"y":344,"w":16,"h":16,"bg_opa":0,"border_width":0,"text":"","text_font":12,"text_color":"black","click":false}',
    '{"page":1,"id":91,"obj":"label","parentid":0,"x":344,"y":344,"w":16,"h":16,"bg_opa":0,"border_width":0,"text":"","text_font":12,"text_color":"black","click":false}',
    '{"page":1,"id":92,"obj":"label","parentid":0,"x":413,"y":344,"w":16,"h":16,"bg_opa":0,"border_width":0,"text":"","text_font":12,"text_color":"black","click":false}',
    '{"page":1,"id":93,"obj":"label","parentid":0,"x":4,"y":413,"w":16,"h":16,"bg_opa":0,"border_width":0,"text":"","text_font":12,"text_color":"black","click":false}',
    '{"page":1,"id":94,"obj":"label","parentid":0,"x":72,"y":413,"w":16,"h":16,"bg_opa":0,"border_width":0,"text":"","text_font":12,"text_color":"black","click":false}',
    '{"page":1,"id":95,"obj":"label","parentid":0,"x":140,"y":413,"w":16,"h":16,"bg_opa":0,"border_width":0,"text":"","text_font":12,"text_color":"black","click":false}',
    '{"page":1,"id":96,"obj":"label","parentid":0,"x":208,"y":413,"w":16,"h":16,"bg_opa":0,"border_width":0,"text":"","text_font":12,"text_color":"black","click":false}',
    '{"page":1,"id":97,"obj":"label","parentid":0,"x":276,"y":413,"w":16,"h":16,"bg_opa":0,"border_width":0,"text":"","text_font":12,"text_color":"black","click":false}',
    '{"page":1,"id":98,"obj":"label","parentid":0,"x":344,"y":413,"w":16,"h":16,"bg_opa":0,"border_width":0,"text":"","text_font":12,"text_color":"black","click":false}',
    '{"page":1,"id":99,"obj":"label","parentid":0,"x":413,"y":413,"w":16,"h":16,"bg_opa":0,"border_width":0,"text":"","text_font":12,"text_color":"black","click":false}'
]

@Field static final List<String> LAYOUT_1x1_BG = [
    '{"page":1,"id":1,"obj":"btn","x":2,"y":2,"w":476,"h":476,"bg_color":"#000000","border_color":"black","border_width":4,"radius":12,"text":"","text_font":48,"align":"center","text_color":"black","toggle":false,"click":false,"long_mode":1}'
]
@Field static final List<String> LAYOUT_1x1_ICON = [
    '{"page":1,"id":51,"obj":"label","parentid":0,"x":10,"y":10,"w":48,"h":48,"bg_opa":0,"border_width":0,"text":"","text_font":32,"text_color":"black","click":false}'
]

def pushLayout() {
    String node  = settings.haspNode ?: "plate"
    String topic = "hasp/${node}/command/jsonl"

    List<String> bgs, icons
    switch (activeGrid()) {
        case "1x1": bgs = LAYOUT_1x1_BG; icons = LAYOUT_1x1_ICON; break
        case "7x7": bgs = LAYOUT_7x7_BG; icons = LAYOUT_7x7_ICON; break
        case "6x6": bgs = LAYOUT_6x6_BG; icons = LAYOUT_6x6_ICON; break
        case "5x5": bgs = LAYOUT_5x5_BG; icons = LAYOUT_5x5_ICON; break
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
    infoLog "[SenseCAP] publishBatch — ${lines.size()} lines to ${topic}"
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
    infoLog "[SenseCAP] updateLabels called — ${labels.size()} slots, grid=${activeGrid()}"
    try {
        // Build individual jsonl lines then batch-publish under 1800 byte limit
        List<String> lines = labels.collect { idx, text ->
            int obj = bgId(idx as int)
            String escaped = text.toString()
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
            "{\"page\":1,\"id\":${obj},\"text\":\"${escaped}\",\"align\":\"center\"}"
        }
        publishBatch("hasp/${node}/command/jsonl", lines)
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
    String node  = settings.haspNode ?: "plate"
    int    fontPt = (settings.iconFont ?: 24) as int
    List<String> lines = []
    (1..maxSensors()).each { idx ->
        String currentState = state["sensor${idx}"] ?: "inactive"
        String glyph = (currentState == "active") ? activeIconFor(idx) : inactiveIconFor(idx)
        int obj = iconId(idx)
        lines << "{\"page\":1,\"id\":${obj},\"text\":\"${glyph}\",\"text_font\":${fontPt}}"
    }
    publishBatch("hasp/${node}/command/jsonl", lines)
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
        if (type == "none") {
            // Pre-mark slot empty so resyncStates/syncAllSensors paints it slate
            state["sensor${idx as int}"] = "empty"
        }
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
        if (currentState == "empty") {
            setSlotEmpty(idx)
        } else if (currentState == "active") {
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
