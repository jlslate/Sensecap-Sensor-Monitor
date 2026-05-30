/*
 * This is free and unencumbered software released into the public domain.
 * For more information, please refer to <https://unlicense.org>
 */

/**
 * SenseCAP Sensor Monitor App
 *
 * Five independent pages, each with its own grid layout and sensor slots.
 * The SenseCAP Sensor Display device is found via the device picker.
 *
 * Author: jlslate (slate)
 * Version: 4.1.0 — five pages, consistent p1/p2/p3/p4/p5 naming
 */

definition(
    name: "SenseCAP Sensor Monitor",
    namespace: "community",
    author: "jlslate (slate)",
    description: "SenseCAP Sensor Monitor app — configures sensors and pages for SenseCAP Indicator D1 display",
    version: "4.1.0",
    date: "2026-05-30",
    category: "Integration",
    iconUrl: "", iconX2Url: "",
    singleInstance: false
)

preferences {
    page(name: "mainPage")
}

def mainPage() {
    dynamicPage(name: "mainPage", title: "SenseCAP Sensor Monitor", install: true, uninstall: true) {

        section("<b>App Name</b>") {
            label title: "Rename this app (optional)", required: false
        }

        section("<b>SenseCAP Device</b>") {
            input name: "indicatorDevice", type: "capability.initialize",
                  title: "SenseCAP Sensor Display device", required: true, multiple: false
            paragraph ""
            input name: "numberOfPages", type: "enum", title: "Number of Pages",
                  options: ["1":"1","2":"2","3":"3","4":"4","5":"5"],
                  defaultValue: "1", required: true, submitOnChange: true, width: 3
        }

        int numPages = (settings.numberOfPages ?: "1") as int

        def pageColors = [1:"#1a73e8", 2:"#0d8a5e", 3:"#7b2d9e", 4:"#b5520a", 5:"#1a5e8a"]

        (1..numPages).each { pg ->
            String color   = pageColors[pg]
            String gridKey = "page${pg}GridLayout"
            String prefix  = "p${pg}"

            section("""<div style='background:${color};color:white;padding:10px 14px;border-radius:6px;font-size:1.2em;font-weight:bold;letter-spacing:0.5px'>&#9616; PAGE ${pg}</div>""") {
                input name: gridKey, type: "enum", title: "Page ${pg} Grid Layout",
                      options: gridOptions(), defaultValue: "2x2", required: true, submitOnChange: true, width: 4
                if (pg > 1)        input name: "movePage${pg}Up",   type: "button", title: "⬆ Move Up",   width: 2
                if (pg < numPages) input name: "movePage${pg}Down", type: "button", title: "⬇ Move Down", width: 2
                paragraph "<b>Sensor → Slot Mapping</b>"
                sensorSlotSection(1, maxSlots(settings[gridKey] ?: "2x2"), prefix)
            }
        }

        section("<b>Options</b>") {
            input name: "syncOnStartup", type: "bool",
                  title: "Sync all sensor states to display on startup / save", defaultValue: true
            input name: "logLevel", type: "enum", title: "Logging Level",
                  options: ["0":"None","1":"Info only","2":"Info + Debug"], defaultValue: "1", required: true
        }

        section("<b>Status</b>") {
            def ind = settings.indicatorDevice
            if (ind) {
                paragraph "Indicator device: <b>${ind.displayName}</b>"
                paragraph "MQTT status: <b>${ind.currentValue('mqttStatus') ?: 'unknown'}</b>"
            }
            int np = (settings.numberOfPages ?: "1") as int
            (1..np).each { pg ->
                String gridKey = "page${pg}GridLayout"
                paragraph "Page ${pg}: <b>${subscribedCount("p${pg}")}</b> / ${maxSlots(settings[gridKey] ?: "2x2")} slots configured"
            }
        }
    }
}

def gridOptions() {
    ["1x1":"1×1 (1 sensor)","2x2":"2×2 (4 sensors)","3x3":"3×3 (9 sensors)",
     "4x4":"4×4 (16 sensors)","5x5":"5×5 (25 sensors)","6x6":"6×6 (36 sensors)","7x7":"7×7 (49 sensors)"]
}

def sensorSlotSection(int from, int to, String prefix) {
    (from..to).each { idx ->
        String typeKey   = "${prefix}sensorType${idx}"
        String deviceKey = "${prefix}sensor${idx}"
        String labelKey  = "${prefix}label${idx}"
        String type      = settings[typeKey] ?: "none"
        paragraph "<hr style='margin:8px 0'><b style='font-size:1.05em'>Slot ${idx}</b>", width: 12
        input name: typeKey, type: "enum", title: "Type",
              options: ["none":"— None —","motion":"Motion","contact":"Contact","water":"Water","smoke":"Smoke"],
              defaultValue: "none", required: true, submitOnChange: true, width: 3
        if (type != "none") {
            String cap = type == "contact" ? "capability.contactSensor" :
                         type == "water"   ? "capability.waterSensor"   :
                         type == "smoke"   ? "capability.smokeDetector" : "capability.motionSensor"
            input name: deviceKey, type: cap, title: "Device",
                  required: false, multiple: false, width: 5
            input name: labelKey, type: "text", title: "Label (optional)",
                  required: false, width: 4
        }
    }
}

// ── Lifecycle ──────────────────────────────────────────────────────────────────
def installed() { initialize() }
def updated()   { clearMismatchedDevices(); unsubscribe(); initialize() }
def uninstalled() { unsubscribe() }

def initialize() {
    infoLog "[SensorMonitor] initialize() starting"
    state.lastInitMs = now()

    int numPg = (settings.numberOfPages ?: "1") as int
    try {
        (1..numPg).each { pg ->
            String prefix = "p${pg}"
            String grid   = settings["page${pg}GridLayout"] ?: "2x2"
            (1..maxSlots(grid)).each { idx -> subscribeSlot(idx, prefix) }
        }
    } catch (Exception e) {
        infoLog "[SensorMonitor] ERROR during subscribe: ${e.message}"
    }

    infoLog "[SensorMonitor] Subscriptions done — ${(1..numPg).collect { "p${it}:${subscribedCount("p${it}")}" }.join(" ")}"

    def ind = settings.indicatorDevice
    if (ind) {
        infoLog "[SensorMonitor] Indicator device: ${ind.displayName}"
        try {
            // Set page count and grid layouts
            ind.setNumberOfPages(numPg)
            (1..numPg).each { pg -> ind."setPage${pg}GridLayout"(settings["page${pg}GridLayout"] ?: "2x2") }
        } catch (Exception e) {
            infoLog "[SensorMonitor] WARN — indicator device call failed: ${e.message}"
        }
        // Push labels/types first, then push layouts
        runIn(1,  pushPage1LabelsAndTypes)
        if (numPg >= 2) runIn(2, pushPage2LabelsAndTypes)
        if (numPg >= 3) runIn(3, pushPage3LabelsAndTypes)
        if (numPg >= 4) runIn(4, pushPage4LabelsAndTypes)
        if (numPg >= 5) runIn(5, pushPage5LabelsAndTypes)
        runIn(8, pushAllLayoutsDeferred)
        try { subscribe(ind, "displayRebooted", displayRebootedHandler) }
        catch (Exception e) { infoLog "[SensorMonitor] WARN — displayRebooted subscribe failed: ${e.message}" }
    } else {
        infoLog "[SensorMonitor] WARNING — SenseCAP Sensor Display device not set"
    }

    // Sync actual sensor states after layouts push to catch any that changed during build
    runIn(35, syncAllSensors)
    infoLog "[SensorMonitor] initialize() complete"
}






def subscribeSlot(int idx, String prefix) {
    def dev  = settings["${prefix}sensor${idx}"]
    String t = settings["${prefix}sensorType${idx}"] ?: "none"
    if (!dev || t == "none") return
    String attr, handlerName
    if (t == "contact")     { attr = "contact"; handlerName = "${prefix}contactHandler${idx}" }
    else if (t == "water")  { attr = "water";   handlerName = "${prefix}waterHandler${idx}" }
    else if (t == "smoke")  { attr = "smoke";   handlerName = "${prefix}smokeHandler${idx}" }
    else                    { attr = "motion";  handlerName = "${prefix}motionHandler${idx}" }
    infoLog "[SensorMonitor] Subscribing slot ${prefix}:${idx} → ${dev.displayName} [${attr}] → ${handlerName}"
    subscribe(dev, attr, handlerName)
}

// ── Label / type push ──────────────────────────────────────────────────────────
def pushAllLayoutsDeferred() {
    int numPg = (settings.numberOfPages ?: "1") as int
    try { settings.indicatorDevice?.pushAllLayouts(numPg) }
    catch (Exception e) { infoLog "[SensorMonitor] WARN — pushAllLayouts: ${e.message}" }
}

def pushPage1LabelsAndTypes() { pushLabelsAndTypes("p1", settings.page1GridLayout ?: "2x2") }
def pushPage2LabelsAndTypes() { pushLabelsAndTypes("p2", settings.page2GridLayout ?: "2x2") }
def pushPage3LabelsAndTypes() { pushLabelsAndTypes("p3", settings.page3GridLayout ?: "2x2") }
def pushPage4LabelsAndTypes() { pushLabelsAndTypes("p4", settings.page4GridLayout ?: "2x2") }
def pushPage5LabelsAndTypes() { pushLabelsAndTypes("p5", settings.page5GridLayout ?: "2x2") }

def pushLabelsAndTypes(String prefix, String grid) {
    // Only push slot types - labels are handled inside pushPageLayout on the driver
    // to avoid duplicate label sends
    def ind = settings.indicatorDevice
    if (!ind) return
    int pg = (prefix.replace("p","")) as int
    def slotTypes = [:]
    (1..maxSlots(grid)).each { idx ->
        // Always send explicit value including "none" so driver clears stale types
        String t = settings["${prefix}sensorType${idx}"] ?: "none"
        slotTypes[idx] = t
    }
    try {
        ind."updatePage${pg}SlotTypes"(slotTypes)
    } catch (Exception e) {
        infoLog "[SensorMonitor] WARN — pushLabelsAndTypes p${pg}: ${e.message}"
    }
    // Also store labels in driver state so pushPageLayout can use them
    int maxChars = gridMaxChars(grid)
    def labels = [:]
    (1..maxSlots(grid)).each { idx ->
        String slotT  = settings["${prefix}sensorType${idx}"] ?: "none"
        def slotDev   = settings["${prefix}sensor${idx}"]
        boolean has   = slotDev != null && slotT != "none"
        String custom = settings["${prefix}label${idx}"]?.toString()?.trim() ?: ""
        boolean isDefault = custom ==~ /Sensor \d+/
        String raw = has ? ((!custom || isDefault) ? stripEmoji(slotDev.displayName ?: "") : stripEmoji(custom)) : ""
        if (!raw && has) {
            // All chars were emoji — use last word of original or "Sensor N"
            String[] words = slotDev.displayName?.split(" ") ?: []
            raw = words ? words[-1].replaceAll(/[^\x20-\x7E]/, "").trim() : ""
            if (!raw) raw = "Sensor ${idx}"
        }
        labels[idx] = wrapLabel(raw, maxChars)
    }
    try {
        ind."updatePage${pg}Labels"(labels)
    } catch (Exception e) {
        infoLog "[SensorMonitor] WARN — updateLabels p${pg}: ${e.message}"
    }
}

// ── Event handlers — Page 1 ────────────────────────────────────────────────────
def p1motionHandler1(evt) { handleEvent(evt, 1, "p1") }
def p1motionHandler2(evt) { handleEvent(evt, 2, "p1") }
def p1motionHandler3(evt) { handleEvent(evt, 3, "p1") }
def p1motionHandler4(evt) { handleEvent(evt, 4, "p1") }
def p1motionHandler5(evt) { handleEvent(evt, 5, "p1") }
def p1motionHandler6(evt) { handleEvent(evt, 6, "p1") }
def p1motionHandler7(evt) { handleEvent(evt, 7, "p1") }
def p1motionHandler8(evt) { handleEvent(evt, 8, "p1") }
def p1motionHandler9(evt) { handleEvent(evt, 9, "p1") }
def p1motionHandler10(evt) { handleEvent(evt, 10, "p1") }
def p1motionHandler11(evt) { handleEvent(evt, 11, "p1") }
def p1motionHandler12(evt) { handleEvent(evt, 12, "p1") }
def p1motionHandler13(evt) { handleEvent(evt, 13, "p1") }
def p1motionHandler14(evt) { handleEvent(evt, 14, "p1") }
def p1motionHandler15(evt) { handleEvent(evt, 15, "p1") }
def p1motionHandler16(evt) { handleEvent(evt, 16, "p1") }
def p1motionHandler17(evt) { handleEvent(evt, 17, "p1") }
def p1motionHandler18(evt) { handleEvent(evt, 18, "p1") }
def p1motionHandler19(evt) { handleEvent(evt, 19, "p1") }
def p1motionHandler20(evt) { handleEvent(evt, 20, "p1") }
def p1motionHandler21(evt) { handleEvent(evt, 21, "p1") }
def p1motionHandler22(evt) { handleEvent(evt, 22, "p1") }
def p1motionHandler23(evt) { handleEvent(evt, 23, "p1") }
def p1motionHandler24(evt) { handleEvent(evt, 24, "p1") }
def p1motionHandler25(evt) { handleEvent(evt, 25, "p1") }
def p1motionHandler26(evt) { handleEvent(evt, 26, "p1") }
def p1motionHandler27(evt) { handleEvent(evt, 27, "p1") }
def p1motionHandler28(evt) { handleEvent(evt, 28, "p1") }
def p1motionHandler29(evt) { handleEvent(evt, 29, "p1") }
def p1motionHandler30(evt) { handleEvent(evt, 30, "p1") }
def p1motionHandler31(evt) { handleEvent(evt, 31, "p1") }
def p1motionHandler32(evt) { handleEvent(evt, 32, "p1") }
def p1motionHandler33(evt) { handleEvent(evt, 33, "p1") }
def p1motionHandler34(evt) { handleEvent(evt, 34, "p1") }
def p1motionHandler35(evt) { handleEvent(evt, 35, "p1") }
def p1motionHandler36(evt) { handleEvent(evt, 36, "p1") }
def p1motionHandler37(evt) { handleEvent(evt, 37, "p1") }
def p1motionHandler38(evt) { handleEvent(evt, 38, "p1") }
def p1motionHandler39(evt) { handleEvent(evt, 39, "p1") }
def p1motionHandler40(evt) { handleEvent(evt, 40, "p1") }
def p1motionHandler41(evt) { handleEvent(evt, 41, "p1") }
def p1motionHandler42(evt) { handleEvent(evt, 42, "p1") }
def p1motionHandler43(evt) { handleEvent(evt, 43, "p1") }
def p1motionHandler44(evt) { handleEvent(evt, 44, "p1") }
def p1motionHandler45(evt) { handleEvent(evt, 45, "p1") }
def p1motionHandler46(evt) { handleEvent(evt, 46, "p1") }
def p1motionHandler47(evt) { handleEvent(evt, 47, "p1") }
def p1motionHandler48(evt) { handleEvent(evt, 48, "p1") }
def p1motionHandler49(evt) { handleEvent(evt, 49, "p1") }
def p1contactHandler1(evt) { handleEvent(evt, 1, "p1") }
def p1contactHandler2(evt) { handleEvent(evt, 2, "p1") }
def p1contactHandler3(evt) { handleEvent(evt, 3, "p1") }
def p1contactHandler4(evt) { handleEvent(evt, 4, "p1") }
def p1contactHandler5(evt) { handleEvent(evt, 5, "p1") }
def p1contactHandler6(evt) { handleEvent(evt, 6, "p1") }
def p1contactHandler7(evt) { handleEvent(evt, 7, "p1") }
def p1contactHandler8(evt) { handleEvent(evt, 8, "p1") }
def p1contactHandler9(evt) { handleEvent(evt, 9, "p1") }
def p1contactHandler10(evt) { handleEvent(evt, 10, "p1") }
def p1contactHandler11(evt) { handleEvent(evt, 11, "p1") }
def p1contactHandler12(evt) { handleEvent(evt, 12, "p1") }
def p1contactHandler13(evt) { handleEvent(evt, 13, "p1") }
def p1contactHandler14(evt) { handleEvent(evt, 14, "p1") }
def p1contactHandler15(evt) { handleEvent(evt, 15, "p1") }
def p1contactHandler16(evt) { handleEvent(evt, 16, "p1") }
def p1contactHandler17(evt) { handleEvent(evt, 17, "p1") }
def p1contactHandler18(evt) { handleEvent(evt, 18, "p1") }
def p1contactHandler19(evt) { handleEvent(evt, 19, "p1") }
def p1contactHandler20(evt) { handleEvent(evt, 20, "p1") }
def p1contactHandler21(evt) { handleEvent(evt, 21, "p1") }
def p1contactHandler22(evt) { handleEvent(evt, 22, "p1") }
def p1contactHandler23(evt) { handleEvent(evt, 23, "p1") }
def p1contactHandler24(evt) { handleEvent(evt, 24, "p1") }
def p1contactHandler25(evt) { handleEvent(evt, 25, "p1") }
def p1contactHandler26(evt) { handleEvent(evt, 26, "p1") }
def p1contactHandler27(evt) { handleEvent(evt, 27, "p1") }
def p1contactHandler28(evt) { handleEvent(evt, 28, "p1") }
def p1contactHandler29(evt) { handleEvent(evt, 29, "p1") }
def p1contactHandler30(evt) { handleEvent(evt, 30, "p1") }
def p1contactHandler31(evt) { handleEvent(evt, 31, "p1") }
def p1contactHandler32(evt) { handleEvent(evt, 32, "p1") }
def p1contactHandler33(evt) { handleEvent(evt, 33, "p1") }
def p1contactHandler34(evt) { handleEvent(evt, 34, "p1") }
def p1contactHandler35(evt) { handleEvent(evt, 35, "p1") }
def p1contactHandler36(evt) { handleEvent(evt, 36, "p1") }
def p1contactHandler37(evt) { handleEvent(evt, 37, "p1") }
def p1contactHandler38(evt) { handleEvent(evt, 38, "p1") }
def p1contactHandler39(evt) { handleEvent(evt, 39, "p1") }
def p1contactHandler40(evt) { handleEvent(evt, 40, "p1") }
def p1contactHandler41(evt) { handleEvent(evt, 41, "p1") }
def p1contactHandler42(evt) { handleEvent(evt, 42, "p1") }
def p1contactHandler43(evt) { handleEvent(evt, 43, "p1") }
def p1contactHandler44(evt) { handleEvent(evt, 44, "p1") }
def p1contactHandler45(evt) { handleEvent(evt, 45, "p1") }
def p1contactHandler46(evt) { handleEvent(evt, 46, "p1") }
def p1contactHandler47(evt) { handleEvent(evt, 47, "p1") }
def p1contactHandler48(evt) { handleEvent(evt, 48, "p1") }
def p1contactHandler49(evt) { handleEvent(evt, 49, "p1") }
def p1waterHandler1(evt) { handleEvent(evt, 1, "p1") }
def p1waterHandler2(evt) { handleEvent(evt, 2, "p1") }
def p1waterHandler3(evt) { handleEvent(evt, 3, "p1") }
def p1waterHandler4(evt) { handleEvent(evt, 4, "p1") }
def p1waterHandler5(evt) { handleEvent(evt, 5, "p1") }
def p1waterHandler6(evt) { handleEvent(evt, 6, "p1") }
def p1waterHandler7(evt) { handleEvent(evt, 7, "p1") }
def p1waterHandler8(evt) { handleEvent(evt, 8, "p1") }
def p1waterHandler9(evt) { handleEvent(evt, 9, "p1") }
def p1waterHandler10(evt) { handleEvent(evt, 10, "p1") }
def p1waterHandler11(evt) { handleEvent(evt, 11, "p1") }
def p1waterHandler12(evt) { handleEvent(evt, 12, "p1") }
def p1waterHandler13(evt) { handleEvent(evt, 13, "p1") }
def p1waterHandler14(evt) { handleEvent(evt, 14, "p1") }
def p1waterHandler15(evt) { handleEvent(evt, 15, "p1") }
def p1waterHandler16(evt) { handleEvent(evt, 16, "p1") }
def p1waterHandler17(evt) { handleEvent(evt, 17, "p1") }
def p1waterHandler18(evt) { handleEvent(evt, 18, "p1") }
def p1waterHandler19(evt) { handleEvent(evt, 19, "p1") }
def p1waterHandler20(evt) { handleEvent(evt, 20, "p1") }
def p1waterHandler21(evt) { handleEvent(evt, 21, "p1") }
def p1waterHandler22(evt) { handleEvent(evt, 22, "p1") }
def p1waterHandler23(evt) { handleEvent(evt, 23, "p1") }
def p1waterHandler24(evt) { handleEvent(evt, 24, "p1") }
def p1waterHandler25(evt) { handleEvent(evt, 25, "p1") }
def p1waterHandler26(evt) { handleEvent(evt, 26, "p1") }
def p1waterHandler27(evt) { handleEvent(evt, 27, "p1") }
def p1waterHandler28(evt) { handleEvent(evt, 28, "p1") }
def p1waterHandler29(evt) { handleEvent(evt, 29, "p1") }
def p1waterHandler30(evt) { handleEvent(evt, 30, "p1") }
def p1waterHandler31(evt) { handleEvent(evt, 31, "p1") }
def p1waterHandler32(evt) { handleEvent(evt, 32, "p1") }
def p1waterHandler33(evt) { handleEvent(evt, 33, "p1") }
def p1waterHandler34(evt) { handleEvent(evt, 34, "p1") }
def p1waterHandler35(evt) { handleEvent(evt, 35, "p1") }
def p1waterHandler36(evt) { handleEvent(evt, 36, "p1") }
def p1waterHandler37(evt) { handleEvent(evt, 37, "p1") }
def p1waterHandler38(evt) { handleEvent(evt, 38, "p1") }
def p1waterHandler39(evt) { handleEvent(evt, 39, "p1") }
def p1waterHandler40(evt) { handleEvent(evt, 40, "p1") }
def p1waterHandler41(evt) { handleEvent(evt, 41, "p1") }
def p1waterHandler42(evt) { handleEvent(evt, 42, "p1") }
def p1waterHandler43(evt) { handleEvent(evt, 43, "p1") }
def p1waterHandler44(evt) { handleEvent(evt, 44, "p1") }
def p1waterHandler45(evt) { handleEvent(evt, 45, "p1") }
def p1waterHandler46(evt) { handleEvent(evt, 46, "p1") }
def p1waterHandler47(evt) { handleEvent(evt, 47, "p1") }
def p1waterHandler48(evt) { handleEvent(evt, 48, "p1") }
def p1waterHandler49(evt) { handleEvent(evt, 49, "p1") }
def p1smokeHandler1(evt) { handleEvent(evt, 1, "p1") }
def p1smokeHandler2(evt) { handleEvent(evt, 2, "p1") }
def p1smokeHandler3(evt) { handleEvent(evt, 3, "p1") }
def p1smokeHandler4(evt) { handleEvent(evt, 4, "p1") }
def p1smokeHandler5(evt) { handleEvent(evt, 5, "p1") }
def p1smokeHandler6(evt) { handleEvent(evt, 6, "p1") }
def p1smokeHandler7(evt) { handleEvent(evt, 7, "p1") }
def p1smokeHandler8(evt) { handleEvent(evt, 8, "p1") }
def p1smokeHandler9(evt) { handleEvent(evt, 9, "p1") }
def p1smokeHandler10(evt) { handleEvent(evt, 10, "p1") }
def p1smokeHandler11(evt) { handleEvent(evt, 11, "p1") }
def p1smokeHandler12(evt) { handleEvent(evt, 12, "p1") }
def p1smokeHandler13(evt) { handleEvent(evt, 13, "p1") }
def p1smokeHandler14(evt) { handleEvent(evt, 14, "p1") }
def p1smokeHandler15(evt) { handleEvent(evt, 15, "p1") }
def p1smokeHandler16(evt) { handleEvent(evt, 16, "p1") }
def p1smokeHandler17(evt) { handleEvent(evt, 17, "p1") }
def p1smokeHandler18(evt) { handleEvent(evt, 18, "p1") }
def p1smokeHandler19(evt) { handleEvent(evt, 19, "p1") }
def p1smokeHandler20(evt) { handleEvent(evt, 20, "p1") }
def p1smokeHandler21(evt) { handleEvent(evt, 21, "p1") }
def p1smokeHandler22(evt) { handleEvent(evt, 22, "p1") }
def p1smokeHandler23(evt) { handleEvent(evt, 23, "p1") }
def p1smokeHandler24(evt) { handleEvent(evt, 24, "p1") }
def p1smokeHandler25(evt) { handleEvent(evt, 25, "p1") }
def p1smokeHandler26(evt) { handleEvent(evt, 26, "p1") }
def p1smokeHandler27(evt) { handleEvent(evt, 27, "p1") }
def p1smokeHandler28(evt) { handleEvent(evt, 28, "p1") }
def p1smokeHandler29(evt) { handleEvent(evt, 29, "p1") }
def p1smokeHandler30(evt) { handleEvent(evt, 30, "p1") }
def p1smokeHandler31(evt) { handleEvent(evt, 31, "p1") }
def p1smokeHandler32(evt) { handleEvent(evt, 32, "p1") }
def p1smokeHandler33(evt) { handleEvent(evt, 33, "p1") }
def p1smokeHandler34(evt) { handleEvent(evt, 34, "p1") }
def p1smokeHandler35(evt) { handleEvent(evt, 35, "p1") }
def p1smokeHandler36(evt) { handleEvent(evt, 36, "p1") }
def p1smokeHandler37(evt) { handleEvent(evt, 37, "p1") }
def p1smokeHandler38(evt) { handleEvent(evt, 38, "p1") }
def p1smokeHandler39(evt) { handleEvent(evt, 39, "p1") }
def p1smokeHandler40(evt) { handleEvent(evt, 40, "p1") }
def p1smokeHandler41(evt) { handleEvent(evt, 41, "p1") }
def p1smokeHandler42(evt) { handleEvent(evt, 42, "p1") }
def p1smokeHandler43(evt) { handleEvent(evt, 43, "p1") }
def p1smokeHandler44(evt) { handleEvent(evt, 44, "p1") }
def p1smokeHandler45(evt) { handleEvent(evt, 45, "p1") }
def p1smokeHandler46(evt) { handleEvent(evt, 46, "p1") }
def p1smokeHandler47(evt) { handleEvent(evt, 47, "p1") }
def p1smokeHandler48(evt) { handleEvent(evt, 48, "p1") }
def p1smokeHandler49(evt) { handleEvent(evt, 49, "p1") }

// ── Event handlers — Page 2 ────────────────────────────────────────────────────
def p2motionHandler1(evt) { handleEvent(evt, 1, "p2") }
def p2motionHandler2(evt) { handleEvent(evt, 2, "p2") }
def p2motionHandler3(evt) { handleEvent(evt, 3, "p2") }
def p2motionHandler4(evt) { handleEvent(evt, 4, "p2") }
def p2motionHandler5(evt) { handleEvent(evt, 5, "p2") }
def p2motionHandler6(evt) { handleEvent(evt, 6, "p2") }
def p2motionHandler7(evt) { handleEvent(evt, 7, "p2") }
def p2motionHandler8(evt) { handleEvent(evt, 8, "p2") }
def p2motionHandler9(evt) { handleEvent(evt, 9, "p2") }
def p2motionHandler10(evt) { handleEvent(evt, 10, "p2") }
def p2motionHandler11(evt) { handleEvent(evt, 11, "p2") }
def p2motionHandler12(evt) { handleEvent(evt, 12, "p2") }
def p2motionHandler13(evt) { handleEvent(evt, 13, "p2") }
def p2motionHandler14(evt) { handleEvent(evt, 14, "p2") }
def p2motionHandler15(evt) { handleEvent(evt, 15, "p2") }
def p2motionHandler16(evt) { handleEvent(evt, 16, "p2") }
def p2motionHandler17(evt) { handleEvent(evt, 17, "p2") }
def p2motionHandler18(evt) { handleEvent(evt, 18, "p2") }
def p2motionHandler19(evt) { handleEvent(evt, 19, "p2") }
def p2motionHandler20(evt) { handleEvent(evt, 20, "p2") }
def p2motionHandler21(evt) { handleEvent(evt, 21, "p2") }
def p2motionHandler22(evt) { handleEvent(evt, 22, "p2") }
def p2motionHandler23(evt) { handleEvent(evt, 23, "p2") }
def p2motionHandler24(evt) { handleEvent(evt, 24, "p2") }
def p2motionHandler25(evt) { handleEvent(evt, 25, "p2") }
def p2motionHandler26(evt) { handleEvent(evt, 26, "p2") }
def p2motionHandler27(evt) { handleEvent(evt, 27, "p2") }
def p2motionHandler28(evt) { handleEvent(evt, 28, "p2") }
def p2motionHandler29(evt) { handleEvent(evt, 29, "p2") }
def p2motionHandler30(evt) { handleEvent(evt, 30, "p2") }
def p2motionHandler31(evt) { handleEvent(evt, 31, "p2") }
def p2motionHandler32(evt) { handleEvent(evt, 32, "p2") }
def p2motionHandler33(evt) { handleEvent(evt, 33, "p2") }
def p2motionHandler34(evt) { handleEvent(evt, 34, "p2") }
def p2motionHandler35(evt) { handleEvent(evt, 35, "p2") }
def p2motionHandler36(evt) { handleEvent(evt, 36, "p2") }
def p2motionHandler37(evt) { handleEvent(evt, 37, "p2") }
def p2motionHandler38(evt) { handleEvent(evt, 38, "p2") }
def p2motionHandler39(evt) { handleEvent(evt, 39, "p2") }
def p2motionHandler40(evt) { handleEvent(evt, 40, "p2") }
def p2motionHandler41(evt) { handleEvent(evt, 41, "p2") }
def p2motionHandler42(evt) { handleEvent(evt, 42, "p2") }
def p2motionHandler43(evt) { handleEvent(evt, 43, "p2") }
def p2motionHandler44(evt) { handleEvent(evt, 44, "p2") }
def p2motionHandler45(evt) { handleEvent(evt, 45, "p2") }
def p2motionHandler46(evt) { handleEvent(evt, 46, "p2") }
def p2motionHandler47(evt) { handleEvent(evt, 47, "p2") }
def p2motionHandler48(evt) { handleEvent(evt, 48, "p2") }
def p2motionHandler49(evt) { handleEvent(evt, 49, "p2") }
def p2contactHandler1(evt) { handleEvent(evt, 1, "p2") }
def p2contactHandler2(evt) { handleEvent(evt, 2, "p2") }
def p2contactHandler3(evt) { handleEvent(evt, 3, "p2") }
def p2contactHandler4(evt) { handleEvent(evt, 4, "p2") }
def p2contactHandler5(evt) { handleEvent(evt, 5, "p2") }
def p2contactHandler6(evt) { handleEvent(evt, 6, "p2") }
def p2contactHandler7(evt) { handleEvent(evt, 7, "p2") }
def p2contactHandler8(evt) { handleEvent(evt, 8, "p2") }
def p2contactHandler9(evt) { handleEvent(evt, 9, "p2") }
def p2contactHandler10(evt) { handleEvent(evt, 10, "p2") }
def p2contactHandler11(evt) { handleEvent(evt, 11, "p2") }
def p2contactHandler12(evt) { handleEvent(evt, 12, "p2") }
def p2contactHandler13(evt) { handleEvent(evt, 13, "p2") }
def p2contactHandler14(evt) { handleEvent(evt, 14, "p2") }
def p2contactHandler15(evt) { handleEvent(evt, 15, "p2") }
def p2contactHandler16(evt) { handleEvent(evt, 16, "p2") }
def p2contactHandler17(evt) { handleEvent(evt, 17, "p2") }
def p2contactHandler18(evt) { handleEvent(evt, 18, "p2") }
def p2contactHandler19(evt) { handleEvent(evt, 19, "p2") }
def p2contactHandler20(evt) { handleEvent(evt, 20, "p2") }
def p2contactHandler21(evt) { handleEvent(evt, 21, "p2") }
def p2contactHandler22(evt) { handleEvent(evt, 22, "p2") }
def p2contactHandler23(evt) { handleEvent(evt, 23, "p2") }
def p2contactHandler24(evt) { handleEvent(evt, 24, "p2") }
def p2contactHandler25(evt) { handleEvent(evt, 25, "p2") }
def p2contactHandler26(evt) { handleEvent(evt, 26, "p2") }
def p2contactHandler27(evt) { handleEvent(evt, 27, "p2") }
def p2contactHandler28(evt) { handleEvent(evt, 28, "p2") }
def p2contactHandler29(evt) { handleEvent(evt, 29, "p2") }
def p2contactHandler30(evt) { handleEvent(evt, 30, "p2") }
def p2contactHandler31(evt) { handleEvent(evt, 31, "p2") }
def p2contactHandler32(evt) { handleEvent(evt, 32, "p2") }
def p2contactHandler33(evt) { handleEvent(evt, 33, "p2") }
def p2contactHandler34(evt) { handleEvent(evt, 34, "p2") }
def p2contactHandler35(evt) { handleEvent(evt, 35, "p2") }
def p2contactHandler36(evt) { handleEvent(evt, 36, "p2") }
def p2contactHandler37(evt) { handleEvent(evt, 37, "p2") }
def p2contactHandler38(evt) { handleEvent(evt, 38, "p2") }
def p2contactHandler39(evt) { handleEvent(evt, 39, "p2") }
def p2contactHandler40(evt) { handleEvent(evt, 40, "p2") }
def p2contactHandler41(evt) { handleEvent(evt, 41, "p2") }
def p2contactHandler42(evt) { handleEvent(evt, 42, "p2") }
def p2contactHandler43(evt) { handleEvent(evt, 43, "p2") }
def p2contactHandler44(evt) { handleEvent(evt, 44, "p2") }
def p2contactHandler45(evt) { handleEvent(evt, 45, "p2") }
def p2contactHandler46(evt) { handleEvent(evt, 46, "p2") }
def p2contactHandler47(evt) { handleEvent(evt, 47, "p2") }
def p2contactHandler48(evt) { handleEvent(evt, 48, "p2") }
def p2contactHandler49(evt) { handleEvent(evt, 49, "p2") }
def p2waterHandler1(evt) { handleEvent(evt, 1, "p2") }
def p2waterHandler2(evt) { handleEvent(evt, 2, "p2") }
def p2waterHandler3(evt) { handleEvent(evt, 3, "p2") }
def p2waterHandler4(evt) { handleEvent(evt, 4, "p2") }
def p2waterHandler5(evt) { handleEvent(evt, 5, "p2") }
def p2waterHandler6(evt) { handleEvent(evt, 6, "p2") }
def p2waterHandler7(evt) { handleEvent(evt, 7, "p2") }
def p2waterHandler8(evt) { handleEvent(evt, 8, "p2") }
def p2waterHandler9(evt) { handleEvent(evt, 9, "p2") }
def p2waterHandler10(evt) { handleEvent(evt, 10, "p2") }
def p2waterHandler11(evt) { handleEvent(evt, 11, "p2") }
def p2waterHandler12(evt) { handleEvent(evt, 12, "p2") }
def p2waterHandler13(evt) { handleEvent(evt, 13, "p2") }
def p2waterHandler14(evt) { handleEvent(evt, 14, "p2") }
def p2waterHandler15(evt) { handleEvent(evt, 15, "p2") }
def p2waterHandler16(evt) { handleEvent(evt, 16, "p2") }
def p2waterHandler17(evt) { handleEvent(evt, 17, "p2") }
def p2waterHandler18(evt) { handleEvent(evt, 18, "p2") }
def p2waterHandler19(evt) { handleEvent(evt, 19, "p2") }
def p2waterHandler20(evt) { handleEvent(evt, 20, "p2") }
def p2waterHandler21(evt) { handleEvent(evt, 21, "p2") }
def p2waterHandler22(evt) { handleEvent(evt, 22, "p2") }
def p2waterHandler23(evt) { handleEvent(evt, 23, "p2") }
def p2waterHandler24(evt) { handleEvent(evt, 24, "p2") }
def p2waterHandler25(evt) { handleEvent(evt, 25, "p2") }
def p2waterHandler26(evt) { handleEvent(evt, 26, "p2") }
def p2waterHandler27(evt) { handleEvent(evt, 27, "p2") }
def p2waterHandler28(evt) { handleEvent(evt, 28, "p2") }
def p2waterHandler29(evt) { handleEvent(evt, 29, "p2") }
def p2waterHandler30(evt) { handleEvent(evt, 30, "p2") }
def p2waterHandler31(evt) { handleEvent(evt, 31, "p2") }
def p2waterHandler32(evt) { handleEvent(evt, 32, "p2") }
def p2waterHandler33(evt) { handleEvent(evt, 33, "p2") }
def p2waterHandler34(evt) { handleEvent(evt, 34, "p2") }
def p2waterHandler35(evt) { handleEvent(evt, 35, "p2") }
def p2waterHandler36(evt) { handleEvent(evt, 36, "p2") }
def p2waterHandler37(evt) { handleEvent(evt, 37, "p2") }
def p2waterHandler38(evt) { handleEvent(evt, 38, "p2") }
def p2waterHandler39(evt) { handleEvent(evt, 39, "p2") }
def p2waterHandler40(evt) { handleEvent(evt, 40, "p2") }
def p2waterHandler41(evt) { handleEvent(evt, 41, "p2") }
def p2waterHandler42(evt) { handleEvent(evt, 42, "p2") }
def p2waterHandler43(evt) { handleEvent(evt, 43, "p2") }
def p2waterHandler44(evt) { handleEvent(evt, 44, "p2") }
def p2waterHandler45(evt) { handleEvent(evt, 45, "p2") }
def p2waterHandler46(evt) { handleEvent(evt, 46, "p2") }
def p2waterHandler47(evt) { handleEvent(evt, 47, "p2") }
def p2waterHandler48(evt) { handleEvent(evt, 48, "p2") }
def p2waterHandler49(evt) { handleEvent(evt, 49, "p2") }
def p2smokeHandler1(evt) { handleEvent(evt, 1, "p2") }
def p2smokeHandler2(evt) { handleEvent(evt, 2, "p2") }
def p2smokeHandler3(evt) { handleEvent(evt, 3, "p2") }
def p2smokeHandler4(evt) { handleEvent(evt, 4, "p2") }
def p2smokeHandler5(evt) { handleEvent(evt, 5, "p2") }
def p2smokeHandler6(evt) { handleEvent(evt, 6, "p2") }
def p2smokeHandler7(evt) { handleEvent(evt, 7, "p2") }
def p2smokeHandler8(evt) { handleEvent(evt, 8, "p2") }
def p2smokeHandler9(evt) { handleEvent(evt, 9, "p2") }
def p2smokeHandler10(evt) { handleEvent(evt, 10, "p2") }
def p2smokeHandler11(evt) { handleEvent(evt, 11, "p2") }
def p2smokeHandler12(evt) { handleEvent(evt, 12, "p2") }
def p2smokeHandler13(evt) { handleEvent(evt, 13, "p2") }
def p2smokeHandler14(evt) { handleEvent(evt, 14, "p2") }
def p2smokeHandler15(evt) { handleEvent(evt, 15, "p2") }
def p2smokeHandler16(evt) { handleEvent(evt, 16, "p2") }
def p2smokeHandler17(evt) { handleEvent(evt, 17, "p2") }
def p2smokeHandler18(evt) { handleEvent(evt, 18, "p2") }
def p2smokeHandler19(evt) { handleEvent(evt, 19, "p2") }
def p2smokeHandler20(evt) { handleEvent(evt, 20, "p2") }
def p2smokeHandler21(evt) { handleEvent(evt, 21, "p2") }
def p2smokeHandler22(evt) { handleEvent(evt, 22, "p2") }
def p2smokeHandler23(evt) { handleEvent(evt, 23, "p2") }
def p2smokeHandler24(evt) { handleEvent(evt, 24, "p2") }
def p2smokeHandler25(evt) { handleEvent(evt, 25, "p2") }
def p2smokeHandler26(evt) { handleEvent(evt, 26, "p2") }
def p2smokeHandler27(evt) { handleEvent(evt, 27, "p2") }
def p2smokeHandler28(evt) { handleEvent(evt, 28, "p2") }
def p2smokeHandler29(evt) { handleEvent(evt, 29, "p2") }
def p2smokeHandler30(evt) { handleEvent(evt, 30, "p2") }
def p2smokeHandler31(evt) { handleEvent(evt, 31, "p2") }
def p2smokeHandler32(evt) { handleEvent(evt, 32, "p2") }
def p2smokeHandler33(evt) { handleEvent(evt, 33, "p2") }
def p2smokeHandler34(evt) { handleEvent(evt, 34, "p2") }
def p2smokeHandler35(evt) { handleEvent(evt, 35, "p2") }
def p2smokeHandler36(evt) { handleEvent(evt, 36, "p2") }
def p2smokeHandler37(evt) { handleEvent(evt, 37, "p2") }
def p2smokeHandler38(evt) { handleEvent(evt, 38, "p2") }
def p2smokeHandler39(evt) { handleEvent(evt, 39, "p2") }
def p2smokeHandler40(evt) { handleEvent(evt, 40, "p2") }
def p2smokeHandler41(evt) { handleEvent(evt, 41, "p2") }
def p2smokeHandler42(evt) { handleEvent(evt, 42, "p2") }
def p2smokeHandler43(evt) { handleEvent(evt, 43, "p2") }
def p2smokeHandler44(evt) { handleEvent(evt, 44, "p2") }
def p2smokeHandler45(evt) { handleEvent(evt, 45, "p2") }
def p2smokeHandler46(evt) { handleEvent(evt, 46, "p2") }
def p2smokeHandler47(evt) { handleEvent(evt, 47, "p2") }
def p2smokeHandler48(evt) { handleEvent(evt, 48, "p2") }
def p2smokeHandler49(evt) { handleEvent(evt, 49, "p2") }

// ── Event handlers — Page 3 ────────────────────────────────────────────────────
def p3motionHandler1(evt) { handleEvent(evt, 1, "p3") }
def p3motionHandler2(evt) { handleEvent(evt, 2, "p3") }
def p3motionHandler3(evt) { handleEvent(evt, 3, "p3") }
def p3motionHandler4(evt) { handleEvent(evt, 4, "p3") }
def p3motionHandler5(evt) { handleEvent(evt, 5, "p3") }
def p3motionHandler6(evt) { handleEvent(evt, 6, "p3") }
def p3motionHandler7(evt) { handleEvent(evt, 7, "p3") }
def p3motionHandler8(evt) { handleEvent(evt, 8, "p3") }
def p3motionHandler9(evt) { handleEvent(evt, 9, "p3") }
def p3motionHandler10(evt) { handleEvent(evt, 10, "p3") }
def p3motionHandler11(evt) { handleEvent(evt, 11, "p3") }
def p3motionHandler12(evt) { handleEvent(evt, 12, "p3") }
def p3motionHandler13(evt) { handleEvent(evt, 13, "p3") }
def p3motionHandler14(evt) { handleEvent(evt, 14, "p3") }
def p3motionHandler15(evt) { handleEvent(evt, 15, "p3") }
def p3motionHandler16(evt) { handleEvent(evt, 16, "p3") }
def p3motionHandler17(evt) { handleEvent(evt, 17, "p3") }
def p3motionHandler18(evt) { handleEvent(evt, 18, "p3") }
def p3motionHandler19(evt) { handleEvent(evt, 19, "p3") }
def p3motionHandler20(evt) { handleEvent(evt, 20, "p3") }
def p3motionHandler21(evt) { handleEvent(evt, 21, "p3") }
def p3motionHandler22(evt) { handleEvent(evt, 22, "p3") }
def p3motionHandler23(evt) { handleEvent(evt, 23, "p3") }
def p3motionHandler24(evt) { handleEvent(evt, 24, "p3") }
def p3motionHandler25(evt) { handleEvent(evt, 25, "p3") }
def p3motionHandler26(evt) { handleEvent(evt, 26, "p3") }
def p3motionHandler27(evt) { handleEvent(evt, 27, "p3") }
def p3motionHandler28(evt) { handleEvent(evt, 28, "p3") }
def p3motionHandler29(evt) { handleEvent(evt, 29, "p3") }
def p3motionHandler30(evt) { handleEvent(evt, 30, "p3") }
def p3motionHandler31(evt) { handleEvent(evt, 31, "p3") }
def p3motionHandler32(evt) { handleEvent(evt, 32, "p3") }
def p3motionHandler33(evt) { handleEvent(evt, 33, "p3") }
def p3motionHandler34(evt) { handleEvent(evt, 34, "p3") }
def p3motionHandler35(evt) { handleEvent(evt, 35, "p3") }
def p3motionHandler36(evt) { handleEvent(evt, 36, "p3") }
def p3motionHandler37(evt) { handleEvent(evt, 37, "p3") }
def p3motionHandler38(evt) { handleEvent(evt, 38, "p3") }
def p3motionHandler39(evt) { handleEvent(evt, 39, "p3") }
def p3motionHandler40(evt) { handleEvent(evt, 40, "p3") }
def p3motionHandler41(evt) { handleEvent(evt, 41, "p3") }
def p3motionHandler42(evt) { handleEvent(evt, 42, "p3") }
def p3motionHandler43(evt) { handleEvent(evt, 43, "p3") }
def p3motionHandler44(evt) { handleEvent(evt, 44, "p3") }
def p3motionHandler45(evt) { handleEvent(evt, 45, "p3") }
def p3motionHandler46(evt) { handleEvent(evt, 46, "p3") }
def p3motionHandler47(evt) { handleEvent(evt, 47, "p3") }
def p3motionHandler48(evt) { handleEvent(evt, 48, "p3") }
def p3motionHandler49(evt) { handleEvent(evt, 49, "p3") }
def p3contactHandler1(evt) { handleEvent(evt, 1, "p3") }
def p3contactHandler2(evt) { handleEvent(evt, 2, "p3") }
def p3contactHandler3(evt) { handleEvent(evt, 3, "p3") }
def p3contactHandler4(evt) { handleEvent(evt, 4, "p3") }
def p3contactHandler5(evt) { handleEvent(evt, 5, "p3") }
def p3contactHandler6(evt) { handleEvent(evt, 6, "p3") }
def p3contactHandler7(evt) { handleEvent(evt, 7, "p3") }
def p3contactHandler8(evt) { handleEvent(evt, 8, "p3") }
def p3contactHandler9(evt) { handleEvent(evt, 9, "p3") }
def p3contactHandler10(evt) { handleEvent(evt, 10, "p3") }
def p3contactHandler11(evt) { handleEvent(evt, 11, "p3") }
def p3contactHandler12(evt) { handleEvent(evt, 12, "p3") }
def p3contactHandler13(evt) { handleEvent(evt, 13, "p3") }
def p3contactHandler14(evt) { handleEvent(evt, 14, "p3") }
def p3contactHandler15(evt) { handleEvent(evt, 15, "p3") }
def p3contactHandler16(evt) { handleEvent(evt, 16, "p3") }
def p3contactHandler17(evt) { handleEvent(evt, 17, "p3") }
def p3contactHandler18(evt) { handleEvent(evt, 18, "p3") }
def p3contactHandler19(evt) { handleEvent(evt, 19, "p3") }
def p3contactHandler20(evt) { handleEvent(evt, 20, "p3") }
def p3contactHandler21(evt) { handleEvent(evt, 21, "p3") }
def p3contactHandler22(evt) { handleEvent(evt, 22, "p3") }
def p3contactHandler23(evt) { handleEvent(evt, 23, "p3") }
def p3contactHandler24(evt) { handleEvent(evt, 24, "p3") }
def p3contactHandler25(evt) { handleEvent(evt, 25, "p3") }
def p3contactHandler26(evt) { handleEvent(evt, 26, "p3") }
def p3contactHandler27(evt) { handleEvent(evt, 27, "p3") }
def p3contactHandler28(evt) { handleEvent(evt, 28, "p3") }
def p3contactHandler29(evt) { handleEvent(evt, 29, "p3") }
def p3contactHandler30(evt) { handleEvent(evt, 30, "p3") }
def p3contactHandler31(evt) { handleEvent(evt, 31, "p3") }
def p3contactHandler32(evt) { handleEvent(evt, 32, "p3") }
def p3contactHandler33(evt) { handleEvent(evt, 33, "p3") }
def p3contactHandler34(evt) { handleEvent(evt, 34, "p3") }
def p3contactHandler35(evt) { handleEvent(evt, 35, "p3") }
def p3contactHandler36(evt) { handleEvent(evt, 36, "p3") }
def p3contactHandler37(evt) { handleEvent(evt, 37, "p3") }
def p3contactHandler38(evt) { handleEvent(evt, 38, "p3") }
def p3contactHandler39(evt) { handleEvent(evt, 39, "p3") }
def p3contactHandler40(evt) { handleEvent(evt, 40, "p3") }
def p3contactHandler41(evt) { handleEvent(evt, 41, "p3") }
def p3contactHandler42(evt) { handleEvent(evt, 42, "p3") }
def p3contactHandler43(evt) { handleEvent(evt, 43, "p3") }
def p3contactHandler44(evt) { handleEvent(evt, 44, "p3") }
def p3contactHandler45(evt) { handleEvent(evt, 45, "p3") }
def p3contactHandler46(evt) { handleEvent(evt, 46, "p3") }
def p3contactHandler47(evt) { handleEvent(evt, 47, "p3") }
def p3contactHandler48(evt) { handleEvent(evt, 48, "p3") }
def p3contactHandler49(evt) { handleEvent(evt, 49, "p3") }
def p3waterHandler1(evt) { handleEvent(evt, 1, "p3") }
def p3waterHandler2(evt) { handleEvent(evt, 2, "p3") }
def p3waterHandler3(evt) { handleEvent(evt, 3, "p3") }
def p3waterHandler4(evt) { handleEvent(evt, 4, "p3") }
def p3waterHandler5(evt) { handleEvent(evt, 5, "p3") }
def p3waterHandler6(evt) { handleEvent(evt, 6, "p3") }
def p3waterHandler7(evt) { handleEvent(evt, 7, "p3") }
def p3waterHandler8(evt) { handleEvent(evt, 8, "p3") }
def p3waterHandler9(evt) { handleEvent(evt, 9, "p3") }
def p3waterHandler10(evt) { handleEvent(evt, 10, "p3") }
def p3waterHandler11(evt) { handleEvent(evt, 11, "p3") }
def p3waterHandler12(evt) { handleEvent(evt, 12, "p3") }
def p3waterHandler13(evt) { handleEvent(evt, 13, "p3") }
def p3waterHandler14(evt) { handleEvent(evt, 14, "p3") }
def p3waterHandler15(evt) { handleEvent(evt, 15, "p3") }
def p3waterHandler16(evt) { handleEvent(evt, 16, "p3") }
def p3waterHandler17(evt) { handleEvent(evt, 17, "p3") }
def p3waterHandler18(evt) { handleEvent(evt, 18, "p3") }
def p3waterHandler19(evt) { handleEvent(evt, 19, "p3") }
def p3waterHandler20(evt) { handleEvent(evt, 20, "p3") }
def p3waterHandler21(evt) { handleEvent(evt, 21, "p3") }
def p3waterHandler22(evt) { handleEvent(evt, 22, "p3") }
def p3waterHandler23(evt) { handleEvent(evt, 23, "p3") }
def p3waterHandler24(evt) { handleEvent(evt, 24, "p3") }
def p3waterHandler25(evt) { handleEvent(evt, 25, "p3") }
def p3waterHandler26(evt) { handleEvent(evt, 26, "p3") }
def p3waterHandler27(evt) { handleEvent(evt, 27, "p3") }
def p3waterHandler28(evt) { handleEvent(evt, 28, "p3") }
def p3waterHandler29(evt) { handleEvent(evt, 29, "p3") }
def p3waterHandler30(evt) { handleEvent(evt, 30, "p3") }
def p3waterHandler31(evt) { handleEvent(evt, 31, "p3") }
def p3waterHandler32(evt) { handleEvent(evt, 32, "p3") }
def p3waterHandler33(evt) { handleEvent(evt, 33, "p3") }
def p3waterHandler34(evt) { handleEvent(evt, 34, "p3") }
def p3waterHandler35(evt) { handleEvent(evt, 35, "p3") }
def p3waterHandler36(evt) { handleEvent(evt, 36, "p3") }
def p3waterHandler37(evt) { handleEvent(evt, 37, "p3") }
def p3waterHandler38(evt) { handleEvent(evt, 38, "p3") }
def p3waterHandler39(evt) { handleEvent(evt, 39, "p3") }
def p3waterHandler40(evt) { handleEvent(evt, 40, "p3") }
def p3waterHandler41(evt) { handleEvent(evt, 41, "p3") }
def p3waterHandler42(evt) { handleEvent(evt, 42, "p3") }
def p3waterHandler43(evt) { handleEvent(evt, 43, "p3") }
def p3waterHandler44(evt) { handleEvent(evt, 44, "p3") }
def p3waterHandler45(evt) { handleEvent(evt, 45, "p3") }
def p3waterHandler46(evt) { handleEvent(evt, 46, "p3") }
def p3waterHandler47(evt) { handleEvent(evt, 47, "p3") }
def p3waterHandler48(evt) { handleEvent(evt, 48, "p3") }
def p3waterHandler49(evt) { handleEvent(evt, 49, "p3") }
def p3smokeHandler1(evt) { handleEvent(evt, 1, "p3") }
def p3smokeHandler2(evt) { handleEvent(evt, 2, "p3") }
def p3smokeHandler3(evt) { handleEvent(evt, 3, "p3") }
def p3smokeHandler4(evt) { handleEvent(evt, 4, "p3") }
def p3smokeHandler5(evt) { handleEvent(evt, 5, "p3") }
def p3smokeHandler6(evt) { handleEvent(evt, 6, "p3") }
def p3smokeHandler7(evt) { handleEvent(evt, 7, "p3") }
def p3smokeHandler8(evt) { handleEvent(evt, 8, "p3") }
def p3smokeHandler9(evt) { handleEvent(evt, 9, "p3") }
def p3smokeHandler10(evt) { handleEvent(evt, 10, "p3") }
def p3smokeHandler11(evt) { handleEvent(evt, 11, "p3") }
def p3smokeHandler12(evt) { handleEvent(evt, 12, "p3") }
def p3smokeHandler13(evt) { handleEvent(evt, 13, "p3") }
def p3smokeHandler14(evt) { handleEvent(evt, 14, "p3") }
def p3smokeHandler15(evt) { handleEvent(evt, 15, "p3") }
def p3smokeHandler16(evt) { handleEvent(evt, 16, "p3") }
def p3smokeHandler17(evt) { handleEvent(evt, 17, "p3") }
def p3smokeHandler18(evt) { handleEvent(evt, 18, "p3") }
def p3smokeHandler19(evt) { handleEvent(evt, 19, "p3") }
def p3smokeHandler20(evt) { handleEvent(evt, 20, "p3") }
def p3smokeHandler21(evt) { handleEvent(evt, 21, "p3") }
def p3smokeHandler22(evt) { handleEvent(evt, 22, "p3") }
def p3smokeHandler23(evt) { handleEvent(evt, 23, "p3") }
def p3smokeHandler24(evt) { handleEvent(evt, 24, "p3") }
def p3smokeHandler25(evt) { handleEvent(evt, 25, "p3") }
def p3smokeHandler26(evt) { handleEvent(evt, 26, "p3") }
def p3smokeHandler27(evt) { handleEvent(evt, 27, "p3") }
def p3smokeHandler28(evt) { handleEvent(evt, 28, "p3") }
def p3smokeHandler29(evt) { handleEvent(evt, 29, "p3") }
def p3smokeHandler30(evt) { handleEvent(evt, 30, "p3") }
def p3smokeHandler31(evt) { handleEvent(evt, 31, "p3") }
def p3smokeHandler32(evt) { handleEvent(evt, 32, "p3") }
def p3smokeHandler33(evt) { handleEvent(evt, 33, "p3") }
def p3smokeHandler34(evt) { handleEvent(evt, 34, "p3") }
def p3smokeHandler35(evt) { handleEvent(evt, 35, "p3") }
def p3smokeHandler36(evt) { handleEvent(evt, 36, "p3") }
def p3smokeHandler37(evt) { handleEvent(evt, 37, "p3") }
def p3smokeHandler38(evt) { handleEvent(evt, 38, "p3") }
def p3smokeHandler39(evt) { handleEvent(evt, 39, "p3") }
def p3smokeHandler40(evt) { handleEvent(evt, 40, "p3") }
def p3smokeHandler41(evt) { handleEvent(evt, 41, "p3") }
def p3smokeHandler42(evt) { handleEvent(evt, 42, "p3") }
def p3smokeHandler43(evt) { handleEvent(evt, 43, "p3") }
def p3smokeHandler44(evt) { handleEvent(evt, 44, "p3") }
def p3smokeHandler45(evt) { handleEvent(evt, 45, "p3") }
def p3smokeHandler46(evt) { handleEvent(evt, 46, "p3") }
def p3smokeHandler47(evt) { handleEvent(evt, 47, "p3") }
def p3smokeHandler48(evt) { handleEvent(evt, 48, "p3") }
def p3smokeHandler49(evt) { handleEvent(evt, 49, "p3") }

// ── Event handlers — Page 4 ────────────────────────────────────────────────────
def p4motionHandler1(evt) { handleEvent(evt, 1, "p4") }
def p4motionHandler2(evt) { handleEvent(evt, 2, "p4") }
def p4motionHandler3(evt) { handleEvent(evt, 3, "p4") }
def p4motionHandler4(evt) { handleEvent(evt, 4, "p4") }
def p4motionHandler5(evt) { handleEvent(evt, 5, "p4") }
def p4motionHandler6(evt) { handleEvent(evt, 6, "p4") }
def p4motionHandler7(evt) { handleEvent(evt, 7, "p4") }
def p4motionHandler8(evt) { handleEvent(evt, 8, "p4") }
def p4motionHandler9(evt) { handleEvent(evt, 9, "p4") }
def p4motionHandler10(evt) { handleEvent(evt, 10, "p4") }
def p4motionHandler11(evt) { handleEvent(evt, 11, "p4") }
def p4motionHandler12(evt) { handleEvent(evt, 12, "p4") }
def p4motionHandler13(evt) { handleEvent(evt, 13, "p4") }
def p4motionHandler14(evt) { handleEvent(evt, 14, "p4") }
def p4motionHandler15(evt) { handleEvent(evt, 15, "p4") }
def p4motionHandler16(evt) { handleEvent(evt, 16, "p4") }
def p4motionHandler17(evt) { handleEvent(evt, 17, "p4") }
def p4motionHandler18(evt) { handleEvent(evt, 18, "p4") }
def p4motionHandler19(evt) { handleEvent(evt, 19, "p4") }
def p4motionHandler20(evt) { handleEvent(evt, 20, "p4") }
def p4motionHandler21(evt) { handleEvent(evt, 21, "p4") }
def p4motionHandler22(evt) { handleEvent(evt, 22, "p4") }
def p4motionHandler23(evt) { handleEvent(evt, 23, "p4") }
def p4motionHandler24(evt) { handleEvent(evt, 24, "p4") }
def p4motionHandler25(evt) { handleEvent(evt, 25, "p4") }
def p4motionHandler26(evt) { handleEvent(evt, 26, "p4") }
def p4motionHandler27(evt) { handleEvent(evt, 27, "p4") }
def p4motionHandler28(evt) { handleEvent(evt, 28, "p4") }
def p4motionHandler29(evt) { handleEvent(evt, 29, "p4") }
def p4motionHandler30(evt) { handleEvent(evt, 30, "p4") }
def p4motionHandler31(evt) { handleEvent(evt, 31, "p4") }
def p4motionHandler32(evt) { handleEvent(evt, 32, "p4") }
def p4motionHandler33(evt) { handleEvent(evt, 33, "p4") }
def p4motionHandler34(evt) { handleEvent(evt, 34, "p4") }
def p4motionHandler35(evt) { handleEvent(evt, 35, "p4") }
def p4motionHandler36(evt) { handleEvent(evt, 36, "p4") }
def p4motionHandler37(evt) { handleEvent(evt, 37, "p4") }
def p4motionHandler38(evt) { handleEvent(evt, 38, "p4") }
def p4motionHandler39(evt) { handleEvent(evt, 39, "p4") }
def p4motionHandler40(evt) { handleEvent(evt, 40, "p4") }
def p4motionHandler41(evt) { handleEvent(evt, 41, "p4") }
def p4motionHandler42(evt) { handleEvent(evt, 42, "p4") }
def p4motionHandler43(evt) { handleEvent(evt, 43, "p4") }
def p4motionHandler44(evt) { handleEvent(evt, 44, "p4") }
def p4motionHandler45(evt) { handleEvent(evt, 45, "p4") }
def p4motionHandler46(evt) { handleEvent(evt, 46, "p4") }
def p4motionHandler47(evt) { handleEvent(evt, 47, "p4") }
def p4motionHandler48(evt) { handleEvent(evt, 48, "p4") }
def p4motionHandler49(evt) { handleEvent(evt, 49, "p4") }
def p4contactHandler1(evt) { handleEvent(evt, 1, "p4") }
def p4contactHandler2(evt) { handleEvent(evt, 2, "p4") }
def p4contactHandler3(evt) { handleEvent(evt, 3, "p4") }
def p4contactHandler4(evt) { handleEvent(evt, 4, "p4") }
def p4contactHandler5(evt) { handleEvent(evt, 5, "p4") }
def p4contactHandler6(evt) { handleEvent(evt, 6, "p4") }
def p4contactHandler7(evt) { handleEvent(evt, 7, "p4") }
def p4contactHandler8(evt) { handleEvent(evt, 8, "p4") }
def p4contactHandler9(evt) { handleEvent(evt, 9, "p4") }
def p4contactHandler10(evt) { handleEvent(evt, 10, "p4") }
def p4contactHandler11(evt) { handleEvent(evt, 11, "p4") }
def p4contactHandler12(evt) { handleEvent(evt, 12, "p4") }
def p4contactHandler13(evt) { handleEvent(evt, 13, "p4") }
def p4contactHandler14(evt) { handleEvent(evt, 14, "p4") }
def p4contactHandler15(evt) { handleEvent(evt, 15, "p4") }
def p4contactHandler16(evt) { handleEvent(evt, 16, "p4") }
def p4contactHandler17(evt) { handleEvent(evt, 17, "p4") }
def p4contactHandler18(evt) { handleEvent(evt, 18, "p4") }
def p4contactHandler19(evt) { handleEvent(evt, 19, "p4") }
def p4contactHandler20(evt) { handleEvent(evt, 20, "p4") }
def p4contactHandler21(evt) { handleEvent(evt, 21, "p4") }
def p4contactHandler22(evt) { handleEvent(evt, 22, "p4") }
def p4contactHandler23(evt) { handleEvent(evt, 23, "p4") }
def p4contactHandler24(evt) { handleEvent(evt, 24, "p4") }
def p4contactHandler25(evt) { handleEvent(evt, 25, "p4") }
def p4contactHandler26(evt) { handleEvent(evt, 26, "p4") }
def p4contactHandler27(evt) { handleEvent(evt, 27, "p4") }
def p4contactHandler28(evt) { handleEvent(evt, 28, "p4") }
def p4contactHandler29(evt) { handleEvent(evt, 29, "p4") }
def p4contactHandler30(evt) { handleEvent(evt, 30, "p4") }
def p4contactHandler31(evt) { handleEvent(evt, 31, "p4") }
def p4contactHandler32(evt) { handleEvent(evt, 32, "p4") }
def p4contactHandler33(evt) { handleEvent(evt, 33, "p4") }
def p4contactHandler34(evt) { handleEvent(evt, 34, "p4") }
def p4contactHandler35(evt) { handleEvent(evt, 35, "p4") }
def p4contactHandler36(evt) { handleEvent(evt, 36, "p4") }
def p4contactHandler37(evt) { handleEvent(evt, 37, "p4") }
def p4contactHandler38(evt) { handleEvent(evt, 38, "p4") }
def p4contactHandler39(evt) { handleEvent(evt, 39, "p4") }
def p4contactHandler40(evt) { handleEvent(evt, 40, "p4") }
def p4contactHandler41(evt) { handleEvent(evt, 41, "p4") }
def p4contactHandler42(evt) { handleEvent(evt, 42, "p4") }
def p4contactHandler43(evt) { handleEvent(evt, 43, "p4") }
def p4contactHandler44(evt) { handleEvent(evt, 44, "p4") }
def p4contactHandler45(evt) { handleEvent(evt, 45, "p4") }
def p4contactHandler46(evt) { handleEvent(evt, 46, "p4") }
def p4contactHandler47(evt) { handleEvent(evt, 47, "p4") }
def p4contactHandler48(evt) { handleEvent(evt, 48, "p4") }
def p4contactHandler49(evt) { handleEvent(evt, 49, "p4") }
def p4waterHandler1(evt) { handleEvent(evt, 1, "p4") }
def p4waterHandler2(evt) { handleEvent(evt, 2, "p4") }
def p4waterHandler3(evt) { handleEvent(evt, 3, "p4") }
def p4waterHandler4(evt) { handleEvent(evt, 4, "p4") }
def p4waterHandler5(evt) { handleEvent(evt, 5, "p4") }
def p4waterHandler6(evt) { handleEvent(evt, 6, "p4") }
def p4waterHandler7(evt) { handleEvent(evt, 7, "p4") }
def p4waterHandler8(evt) { handleEvent(evt, 8, "p4") }
def p4waterHandler9(evt) { handleEvent(evt, 9, "p4") }
def p4waterHandler10(evt) { handleEvent(evt, 10, "p4") }
def p4waterHandler11(evt) { handleEvent(evt, 11, "p4") }
def p4waterHandler12(evt) { handleEvent(evt, 12, "p4") }
def p4waterHandler13(evt) { handleEvent(evt, 13, "p4") }
def p4waterHandler14(evt) { handleEvent(evt, 14, "p4") }
def p4waterHandler15(evt) { handleEvent(evt, 15, "p4") }
def p4waterHandler16(evt) { handleEvent(evt, 16, "p4") }
def p4waterHandler17(evt) { handleEvent(evt, 17, "p4") }
def p4waterHandler18(evt) { handleEvent(evt, 18, "p4") }
def p4waterHandler19(evt) { handleEvent(evt, 19, "p4") }
def p4waterHandler20(evt) { handleEvent(evt, 20, "p4") }
def p4waterHandler21(evt) { handleEvent(evt, 21, "p4") }
def p4waterHandler22(evt) { handleEvent(evt, 22, "p4") }
def p4waterHandler23(evt) { handleEvent(evt, 23, "p4") }
def p4waterHandler24(evt) { handleEvent(evt, 24, "p4") }
def p4waterHandler25(evt) { handleEvent(evt, 25, "p4") }
def p4waterHandler26(evt) { handleEvent(evt, 26, "p4") }
def p4waterHandler27(evt) { handleEvent(evt, 27, "p4") }
def p4waterHandler28(evt) { handleEvent(evt, 28, "p4") }
def p4waterHandler29(evt) { handleEvent(evt, 29, "p4") }
def p4waterHandler30(evt) { handleEvent(evt, 30, "p4") }
def p4waterHandler31(evt) { handleEvent(evt, 31, "p4") }
def p4waterHandler32(evt) { handleEvent(evt, 32, "p4") }
def p4waterHandler33(evt) { handleEvent(evt, 33, "p4") }
def p4waterHandler34(evt) { handleEvent(evt, 34, "p4") }
def p4waterHandler35(evt) { handleEvent(evt, 35, "p4") }
def p4waterHandler36(evt) { handleEvent(evt, 36, "p4") }
def p4waterHandler37(evt) { handleEvent(evt, 37, "p4") }
def p4waterHandler38(evt) { handleEvent(evt, 38, "p4") }
def p4waterHandler39(evt) { handleEvent(evt, 39, "p4") }
def p4waterHandler40(evt) { handleEvent(evt, 40, "p4") }
def p4waterHandler41(evt) { handleEvent(evt, 41, "p4") }
def p4waterHandler42(evt) { handleEvent(evt, 42, "p4") }
def p4waterHandler43(evt) { handleEvent(evt, 43, "p4") }
def p4waterHandler44(evt) { handleEvent(evt, 44, "p4") }
def p4waterHandler45(evt) { handleEvent(evt, 45, "p4") }
def p4waterHandler46(evt) { handleEvent(evt, 46, "p4") }
def p4waterHandler47(evt) { handleEvent(evt, 47, "p4") }
def p4waterHandler48(evt) { handleEvent(evt, 48, "p4") }
def p4waterHandler49(evt) { handleEvent(evt, 49, "p4") }
def p4smokeHandler1(evt) { handleEvent(evt, 1, "p4") }
def p4smokeHandler2(evt) { handleEvent(evt, 2, "p4") }
def p4smokeHandler3(evt) { handleEvent(evt, 3, "p4") }
def p4smokeHandler4(evt) { handleEvent(evt, 4, "p4") }
def p4smokeHandler5(evt) { handleEvent(evt, 5, "p4") }
def p4smokeHandler6(evt) { handleEvent(evt, 6, "p4") }
def p4smokeHandler7(evt) { handleEvent(evt, 7, "p4") }
def p4smokeHandler8(evt) { handleEvent(evt, 8, "p4") }
def p4smokeHandler9(evt) { handleEvent(evt, 9, "p4") }
def p4smokeHandler10(evt) { handleEvent(evt, 10, "p4") }
def p4smokeHandler11(evt) { handleEvent(evt, 11, "p4") }
def p4smokeHandler12(evt) { handleEvent(evt, 12, "p4") }
def p4smokeHandler13(evt) { handleEvent(evt, 13, "p4") }
def p4smokeHandler14(evt) { handleEvent(evt, 14, "p4") }
def p4smokeHandler15(evt) { handleEvent(evt, 15, "p4") }
def p4smokeHandler16(evt) { handleEvent(evt, 16, "p4") }
def p4smokeHandler17(evt) { handleEvent(evt, 17, "p4") }
def p4smokeHandler18(evt) { handleEvent(evt, 18, "p4") }
def p4smokeHandler19(evt) { handleEvent(evt, 19, "p4") }
def p4smokeHandler20(evt) { handleEvent(evt, 20, "p4") }
def p4smokeHandler21(evt) { handleEvent(evt, 21, "p4") }
def p4smokeHandler22(evt) { handleEvent(evt, 22, "p4") }
def p4smokeHandler23(evt) { handleEvent(evt, 23, "p4") }
def p4smokeHandler24(evt) { handleEvent(evt, 24, "p4") }
def p4smokeHandler25(evt) { handleEvent(evt, 25, "p4") }
def p4smokeHandler26(evt) { handleEvent(evt, 26, "p4") }
def p4smokeHandler27(evt) { handleEvent(evt, 27, "p4") }
def p4smokeHandler28(evt) { handleEvent(evt, 28, "p4") }
def p4smokeHandler29(evt) { handleEvent(evt, 29, "p4") }
def p4smokeHandler30(evt) { handleEvent(evt, 30, "p4") }
def p4smokeHandler31(evt) { handleEvent(evt, 31, "p4") }
def p4smokeHandler32(evt) { handleEvent(evt, 32, "p4") }
def p4smokeHandler33(evt) { handleEvent(evt, 33, "p4") }
def p4smokeHandler34(evt) { handleEvent(evt, 34, "p4") }
def p4smokeHandler35(evt) { handleEvent(evt, 35, "p4") }
def p4smokeHandler36(evt) { handleEvent(evt, 36, "p4") }
def p4smokeHandler37(evt) { handleEvent(evt, 37, "p4") }
def p4smokeHandler38(evt) { handleEvent(evt, 38, "p4") }
def p4smokeHandler39(evt) { handleEvent(evt, 39, "p4") }
def p4smokeHandler40(evt) { handleEvent(evt, 40, "p4") }
def p4smokeHandler41(evt) { handleEvent(evt, 41, "p4") }
def p4smokeHandler42(evt) { handleEvent(evt, 42, "p4") }
def p4smokeHandler43(evt) { handleEvent(evt, 43, "p4") }
def p4smokeHandler44(evt) { handleEvent(evt, 44, "p4") }
def p4smokeHandler45(evt) { handleEvent(evt, 45, "p4") }
def p4smokeHandler46(evt) { handleEvent(evt, 46, "p4") }
def p4smokeHandler47(evt) { handleEvent(evt, 47, "p4") }
def p4smokeHandler48(evt) { handleEvent(evt, 48, "p4") }
def p4smokeHandler49(evt) { handleEvent(evt, 49, "p4") }

// ── Event handlers — Page 5 ────────────────────────────────────────────────────
def p5motionHandler1(evt) { handleEvent(evt, 1, "p5") }
def p5motionHandler2(evt) { handleEvent(evt, 2, "p5") }
def p5motionHandler3(evt) { handleEvent(evt, 3, "p5") }
def p5motionHandler4(evt) { handleEvent(evt, 4, "p5") }
def p5motionHandler5(evt) { handleEvent(evt, 5, "p5") }
def p5motionHandler6(evt) { handleEvent(evt, 6, "p5") }
def p5motionHandler7(evt) { handleEvent(evt, 7, "p5") }
def p5motionHandler8(evt) { handleEvent(evt, 8, "p5") }
def p5motionHandler9(evt) { handleEvent(evt, 9, "p5") }
def p5motionHandler10(evt) { handleEvent(evt, 10, "p5") }
def p5motionHandler11(evt) { handleEvent(evt, 11, "p5") }
def p5motionHandler12(evt) { handleEvent(evt, 12, "p5") }
def p5motionHandler13(evt) { handleEvent(evt, 13, "p5") }
def p5motionHandler14(evt) { handleEvent(evt, 14, "p5") }
def p5motionHandler15(evt) { handleEvent(evt, 15, "p5") }
def p5motionHandler16(evt) { handleEvent(evt, 16, "p5") }
def p5motionHandler17(evt) { handleEvent(evt, 17, "p5") }
def p5motionHandler18(evt) { handleEvent(evt, 18, "p5") }
def p5motionHandler19(evt) { handleEvent(evt, 19, "p5") }
def p5motionHandler20(evt) { handleEvent(evt, 20, "p5") }
def p5motionHandler21(evt) { handleEvent(evt, 21, "p5") }
def p5motionHandler22(evt) { handleEvent(evt, 22, "p5") }
def p5motionHandler23(evt) { handleEvent(evt, 23, "p5") }
def p5motionHandler24(evt) { handleEvent(evt, 24, "p5") }
def p5motionHandler25(evt) { handleEvent(evt, 25, "p5") }
def p5motionHandler26(evt) { handleEvent(evt, 26, "p5") }
def p5motionHandler27(evt) { handleEvent(evt, 27, "p5") }
def p5motionHandler28(evt) { handleEvent(evt, 28, "p5") }
def p5motionHandler29(evt) { handleEvent(evt, 29, "p5") }
def p5motionHandler30(evt) { handleEvent(evt, 30, "p5") }
def p5motionHandler31(evt) { handleEvent(evt, 31, "p5") }
def p5motionHandler32(evt) { handleEvent(evt, 32, "p5") }
def p5motionHandler33(evt) { handleEvent(evt, 33, "p5") }
def p5motionHandler34(evt) { handleEvent(evt, 34, "p5") }
def p5motionHandler35(evt) { handleEvent(evt, 35, "p5") }
def p5motionHandler36(evt) { handleEvent(evt, 36, "p5") }
def p5motionHandler37(evt) { handleEvent(evt, 37, "p5") }
def p5motionHandler38(evt) { handleEvent(evt, 38, "p5") }
def p5motionHandler39(evt) { handleEvent(evt, 39, "p5") }
def p5motionHandler40(evt) { handleEvent(evt, 40, "p5") }
def p5motionHandler41(evt) { handleEvent(evt, 41, "p5") }
def p5motionHandler42(evt) { handleEvent(evt, 42, "p5") }
def p5motionHandler43(evt) { handleEvent(evt, 43, "p5") }
def p5motionHandler44(evt) { handleEvent(evt, 44, "p5") }
def p5motionHandler45(evt) { handleEvent(evt, 45, "p5") }
def p5motionHandler46(evt) { handleEvent(evt, 46, "p5") }
def p5motionHandler47(evt) { handleEvent(evt, 47, "p5") }
def p5motionHandler48(evt) { handleEvent(evt, 48, "p5") }
def p5motionHandler49(evt) { handleEvent(evt, 49, "p5") }
def p5contactHandler1(evt) { handleEvent(evt, 1, "p5") }
def p5contactHandler2(evt) { handleEvent(evt, 2, "p5") }
def p5contactHandler3(evt) { handleEvent(evt, 3, "p5") }
def p5contactHandler4(evt) { handleEvent(evt, 4, "p5") }
def p5contactHandler5(evt) { handleEvent(evt, 5, "p5") }
def p5contactHandler6(evt) { handleEvent(evt, 6, "p5") }
def p5contactHandler7(evt) { handleEvent(evt, 7, "p5") }
def p5contactHandler8(evt) { handleEvent(evt, 8, "p5") }
def p5contactHandler9(evt) { handleEvent(evt, 9, "p5") }
def p5contactHandler10(evt) { handleEvent(evt, 10, "p5") }
def p5contactHandler11(evt) { handleEvent(evt, 11, "p5") }
def p5contactHandler12(evt) { handleEvent(evt, 12, "p5") }
def p5contactHandler13(evt) { handleEvent(evt, 13, "p5") }
def p5contactHandler14(evt) { handleEvent(evt, 14, "p5") }
def p5contactHandler15(evt) { handleEvent(evt, 15, "p5") }
def p5contactHandler16(evt) { handleEvent(evt, 16, "p5") }
def p5contactHandler17(evt) { handleEvent(evt, 17, "p5") }
def p5contactHandler18(evt) { handleEvent(evt, 18, "p5") }
def p5contactHandler19(evt) { handleEvent(evt, 19, "p5") }
def p5contactHandler20(evt) { handleEvent(evt, 20, "p5") }
def p5contactHandler21(evt) { handleEvent(evt, 21, "p5") }
def p5contactHandler22(evt) { handleEvent(evt, 22, "p5") }
def p5contactHandler23(evt) { handleEvent(evt, 23, "p5") }
def p5contactHandler24(evt) { handleEvent(evt, 24, "p5") }
def p5contactHandler25(evt) { handleEvent(evt, 25, "p5") }
def p5contactHandler26(evt) { handleEvent(evt, 26, "p5") }
def p5contactHandler27(evt) { handleEvent(evt, 27, "p5") }
def p5contactHandler28(evt) { handleEvent(evt, 28, "p5") }
def p5contactHandler29(evt) { handleEvent(evt, 29, "p5") }
def p5contactHandler30(evt) { handleEvent(evt, 30, "p5") }
def p5contactHandler31(evt) { handleEvent(evt, 31, "p5") }
def p5contactHandler32(evt) { handleEvent(evt, 32, "p5") }
def p5contactHandler33(evt) { handleEvent(evt, 33, "p5") }
def p5contactHandler34(evt) { handleEvent(evt, 34, "p5") }
def p5contactHandler35(evt) { handleEvent(evt, 35, "p5") }
def p5contactHandler36(evt) { handleEvent(evt, 36, "p5") }
def p5contactHandler37(evt) { handleEvent(evt, 37, "p5") }
def p5contactHandler38(evt) { handleEvent(evt, 38, "p5") }
def p5contactHandler39(evt) { handleEvent(evt, 39, "p5") }
def p5contactHandler40(evt) { handleEvent(evt, 40, "p5") }
def p5contactHandler41(evt) { handleEvent(evt, 41, "p5") }
def p5contactHandler42(evt) { handleEvent(evt, 42, "p5") }
def p5contactHandler43(evt) { handleEvent(evt, 43, "p5") }
def p5contactHandler44(evt) { handleEvent(evt, 44, "p5") }
def p5contactHandler45(evt) { handleEvent(evt, 45, "p5") }
def p5contactHandler46(evt) { handleEvent(evt, 46, "p5") }
def p5contactHandler47(evt) { handleEvent(evt, 47, "p5") }
def p5contactHandler48(evt) { handleEvent(evt, 48, "p5") }
def p5contactHandler49(evt) { handleEvent(evt, 49, "p5") }
def p5waterHandler1(evt) { handleEvent(evt, 1, "p5") }
def p5waterHandler2(evt) { handleEvent(evt, 2, "p5") }
def p5waterHandler3(evt) { handleEvent(evt, 3, "p5") }
def p5waterHandler4(evt) { handleEvent(evt, 4, "p5") }
def p5waterHandler5(evt) { handleEvent(evt, 5, "p5") }
def p5waterHandler6(evt) { handleEvent(evt, 6, "p5") }
def p5waterHandler7(evt) { handleEvent(evt, 7, "p5") }
def p5waterHandler8(evt) { handleEvent(evt, 8, "p5") }
def p5waterHandler9(evt) { handleEvent(evt, 9, "p5") }
def p5waterHandler10(evt) { handleEvent(evt, 10, "p5") }
def p5waterHandler11(evt) { handleEvent(evt, 11, "p5") }
def p5waterHandler12(evt) { handleEvent(evt, 12, "p5") }
def p5waterHandler13(evt) { handleEvent(evt, 13, "p5") }
def p5waterHandler14(evt) { handleEvent(evt, 14, "p5") }
def p5waterHandler15(evt) { handleEvent(evt, 15, "p5") }
def p5waterHandler16(evt) { handleEvent(evt, 16, "p5") }
def p5waterHandler17(evt) { handleEvent(evt, 17, "p5") }
def p5waterHandler18(evt) { handleEvent(evt, 18, "p5") }
def p5waterHandler19(evt) { handleEvent(evt, 19, "p5") }
def p5waterHandler20(evt) { handleEvent(evt, 20, "p5") }
def p5waterHandler21(evt) { handleEvent(evt, 21, "p5") }
def p5waterHandler22(evt) { handleEvent(evt, 22, "p5") }
def p5waterHandler23(evt) { handleEvent(evt, 23, "p5") }
def p5waterHandler24(evt) { handleEvent(evt, 24, "p5") }
def p5waterHandler25(evt) { handleEvent(evt, 25, "p5") }
def p5waterHandler26(evt) { handleEvent(evt, 26, "p5") }
def p5waterHandler27(evt) { handleEvent(evt, 27, "p5") }
def p5waterHandler28(evt) { handleEvent(evt, 28, "p5") }
def p5waterHandler29(evt) { handleEvent(evt, 29, "p5") }
def p5waterHandler30(evt) { handleEvent(evt, 30, "p5") }
def p5waterHandler31(evt) { handleEvent(evt, 31, "p5") }
def p5waterHandler32(evt) { handleEvent(evt, 32, "p5") }
def p5waterHandler33(evt) { handleEvent(evt, 33, "p5") }
def p5waterHandler34(evt) { handleEvent(evt, 34, "p5") }
def p5waterHandler35(evt) { handleEvent(evt, 35, "p5") }
def p5waterHandler36(evt) { handleEvent(evt, 36, "p5") }
def p5waterHandler37(evt) { handleEvent(evt, 37, "p5") }
def p5waterHandler38(evt) { handleEvent(evt, 38, "p5") }
def p5waterHandler39(evt) { handleEvent(evt, 39, "p5") }
def p5waterHandler40(evt) { handleEvent(evt, 40, "p5") }
def p5waterHandler41(evt) { handleEvent(evt, 41, "p5") }
def p5waterHandler42(evt) { handleEvent(evt, 42, "p5") }
def p5waterHandler43(evt) { handleEvent(evt, 43, "p5") }
def p5waterHandler44(evt) { handleEvent(evt, 44, "p5") }
def p5waterHandler45(evt) { handleEvent(evt, 45, "p5") }
def p5waterHandler46(evt) { handleEvent(evt, 46, "p5") }
def p5waterHandler47(evt) { handleEvent(evt, 47, "p5") }
def p5waterHandler48(evt) { handleEvent(evt, 48, "p5") }
def p5waterHandler49(evt) { handleEvent(evt, 49, "p5") }
def p5smokeHandler1(evt) { handleEvent(evt, 1, "p5") }
def p5smokeHandler2(evt) { handleEvent(evt, 2, "p5") }
def p5smokeHandler3(evt) { handleEvent(evt, 3, "p5") }
def p5smokeHandler4(evt) { handleEvent(evt, 4, "p5") }
def p5smokeHandler5(evt) { handleEvent(evt, 5, "p5") }
def p5smokeHandler6(evt) { handleEvent(evt, 6, "p5") }
def p5smokeHandler7(evt) { handleEvent(evt, 7, "p5") }
def p5smokeHandler8(evt) { handleEvent(evt, 8, "p5") }
def p5smokeHandler9(evt) { handleEvent(evt, 9, "p5") }
def p5smokeHandler10(evt) { handleEvent(evt, 10, "p5") }
def p5smokeHandler11(evt) { handleEvent(evt, 11, "p5") }
def p5smokeHandler12(evt) { handleEvent(evt, 12, "p5") }
def p5smokeHandler13(evt) { handleEvent(evt, 13, "p5") }
def p5smokeHandler14(evt) { handleEvent(evt, 14, "p5") }
def p5smokeHandler15(evt) { handleEvent(evt, 15, "p5") }
def p5smokeHandler16(evt) { handleEvent(evt, 16, "p5") }
def p5smokeHandler17(evt) { handleEvent(evt, 17, "p5") }
def p5smokeHandler18(evt) { handleEvent(evt, 18, "p5") }
def p5smokeHandler19(evt) { handleEvent(evt, 19, "p5") }
def p5smokeHandler20(evt) { handleEvent(evt, 20, "p5") }
def p5smokeHandler21(evt) { handleEvent(evt, 21, "p5") }
def p5smokeHandler22(evt) { handleEvent(evt, 22, "p5") }
def p5smokeHandler23(evt) { handleEvent(evt, 23, "p5") }
def p5smokeHandler24(evt) { handleEvent(evt, 24, "p5") }
def p5smokeHandler25(evt) { handleEvent(evt, 25, "p5") }
def p5smokeHandler26(evt) { handleEvent(evt, 26, "p5") }
def p5smokeHandler27(evt) { handleEvent(evt, 27, "p5") }
def p5smokeHandler28(evt) { handleEvent(evt, 28, "p5") }
def p5smokeHandler29(evt) { handleEvent(evt, 29, "p5") }
def p5smokeHandler30(evt) { handleEvent(evt, 30, "p5") }
def p5smokeHandler31(evt) { handleEvent(evt, 31, "p5") }
def p5smokeHandler32(evt) { handleEvent(evt, 32, "p5") }
def p5smokeHandler33(evt) { handleEvent(evt, 33, "p5") }
def p5smokeHandler34(evt) { handleEvent(evt, 34, "p5") }
def p5smokeHandler35(evt) { handleEvent(evt, 35, "p5") }
def p5smokeHandler36(evt) { handleEvent(evt, 36, "p5") }
def p5smokeHandler37(evt) { handleEvent(evt, 37, "p5") }
def p5smokeHandler38(evt) { handleEvent(evt, 38, "p5") }
def p5smokeHandler39(evt) { handleEvent(evt, 39, "p5") }
def p5smokeHandler40(evt) { handleEvent(evt, 40, "p5") }
def p5smokeHandler41(evt) { handleEvent(evt, 41, "p5") }
def p5smokeHandler42(evt) { handleEvent(evt, 42, "p5") }
def p5smokeHandler43(evt) { handleEvent(evt, 43, "p5") }
def p5smokeHandler44(evt) { handleEvent(evt, 44, "p5") }
def p5smokeHandler45(evt) { handleEvent(evt, 45, "p5") }
def p5smokeHandler46(evt) { handleEvent(evt, 46, "p5") }
def p5smokeHandler47(evt) { handleEvent(evt, 47, "p5") }
def p5smokeHandler48(evt) { handleEvent(evt, 48, "p5") }
def p5smokeHandler49(evt) { handleEvent(evt, 49, "p5") }

// ── Shared handler logic ───────────────────────────────────────────────────────
def handleEvent(evt, int idx, String prefix) {
    infoLog "[SensorMonitor] ${prefix} slot ${idx} (${evt.displayName}): ${evt.value}"
    def ind = settings.indicatorDevice
    if (!ind) { infoLog "[SensorMonitor] ERROR — indicator device not set"; return }
    int pg = (prefix.replace("p","")) as int
    String t = settings["${prefix}sensorType${idx}"] ?: "motion"
    boolean active = (evt.value == "active" || evt.value == "open" || evt.value == "wet" || evt.value == "detected")
    try {
        if (active) ind."setPage${pg}MotionActive"(idx)
        else        ind."setPage${pg}MotionInactive"(idx)
    } catch (Exception e) {
        log.error "[SensorMonitor] ERROR calling indicator for ${prefix} slot ${idx}: ${e.message}"
    }
}

// ── Page move ─────────────────────────────────────────────────────────────────
def appButtonHandler(String btn) {
    def m = btn =~ /movePage(\d+)(Up|Down)/
    if (m) {
        int pg = (m[0][1]) as int
        String dir = m[0][2]
        int other = (dir == "Up") ? pg - 1 : pg + 1
        swapPages(pg, other)
        return
    }

}

def clearMismatchedDevices() {
    int numPg = (settings.numberOfPages ?: "1") as int
    (1..numPg).each { pg ->
        String grid = settings["page${pg}GridLayout"] ?: "2x2"
        (1..maxSlots(grid)).each { idx ->
            String prefix    = "p${pg}"
            String typeKey   = "${prefix}sensorType${idx}"
            String deviceKey = "${prefix}sensor${idx}"
            String type      = settings[typeKey] ?: "none"
            def dev          = settings[deviceKey]
            if (dev != null && type != "none" && !deviceMatchesType(dev, type)) {
                infoLog "[SensorMonitor] Clearing mismatched device from ${prefix} slot ${idx} (type=${type})"
                app.updateSetting(deviceKey, [value: "", type: "capability"])
            }
        }
    }
}

def deviceMatchesType(dev, String type) {
    if (!dev || type == "none") return true
    try {
        if (type == "motion")  return dev.hasCapability("MotionSensor")
        if (type == "contact") return dev.hasCapability("ContactSensor")
        if (type == "water")   return dev.hasCapability("WaterSensor")
        if (type == "smoke")   return dev.hasCapability("SmokeDetector")
    } catch (Exception e) { }
    return false
}

def swapPages(int a, int b) {
    infoLog "[SensorMonitor] Swapping page ${a} and page ${b}"
    // All setting keys that need to be swapped
    def grid   = settings["page${a}GridLayout"]; app.updateSetting("page${a}GridLayout", [value: settings["page${b}GridLayout"] ?: "2x2", type: "enum"]); app.updateSetting("page${b}GridLayout", [value: grid ?: "2x2", type: "enum"])
    String ga = settings["page${a}GridLayout"] ?: "2x2"
    String gb = settings["page${b}GridLayout"] ?: "2x2"
    int slotsA = maxSlots(ga); int slotsB = maxSlots(gb)
    int maxS = Math.max(slotsA, slotsB)
    (1..maxS).each { idx ->
        ["p${a}sensor${idx}", "p${b}sensor${idx}"].with {
            def va = settings[it[0]]; def vb = settings[it[1]]
            if (va != null) app.updateSetting(it[1], [value: va, type: "capability"]); else app.clearSetting(it[1])
            if (vb != null) app.updateSetting(it[0], [value: vb, type: "capability"]); else app.clearSetting(it[0])
        }
        String typeA = settings["p${a}sensorType${idx}"] ?: "none"
        String typeB = settings["p${b}sensorType${idx}"] ?: "none"
        app.updateSetting("p${b}sensorType${idx}", [value: typeA, type: "enum"])
        app.updateSetting("p${a}sensorType${idx}", [value: typeB, type: "enum"])
        // Clear device if its capability doesn't match the new type after swap
        def devA = settings["p${a}sensor${idx}"]
        def devB = settings["p${b}sensor${idx}"]
        if (devA != null && !deviceMatchesType(devA, typeB)) { app.clearSetting("p${a}sensor${idx}"); devA = null }
        if (devB != null && !deviceMatchesType(devB, typeA)) { app.clearSetting("p${b}sensor${idx}"); devB = null }
        ["p${a}label${idx}", "p${b}label${idx}"].with {
            def va = settings[it[0]]; def vb = settings[it[1]]
            if (va) app.updateSetting(it[1], [value: va, type: "text"]); else app.clearSetting(it[1])
            if (vb) app.updateSetting(it[0], [value: vb, type: "text"]); else app.clearSetting(it[0])
        }
    }
    // Immediately update driver state so grid layouts are correct before next push
    def ind = settings.indicatorDevice
    if (ind) {
        try {
            ind."setPage${a}GridLayout"(settings["page${a}GridLayout"] ?: "2x2")
            ind."setPage${b}GridLayout"(settings["page${b}GridLayout"] ?: "2x2")
        } catch (Exception e) { infoLog "[SensorMonitor] WARN — swap grid update: ${e.message}" }
    }
    infoLog "[SensorMonitor] Page swap complete — hit Done to re-push display"
}

// ── Sync ───────────────────────────────────────────────────────────────────────
def syncAllSensors() {
    infoLog "[SensorMonitor] Syncing all sensor states"
    def ind = settings.indicatorDevice
    if (!ind) return
    int numPg2 = (settings.numberOfPages ?: "1") as int
    (1..numPg2).each { pg ->
        String prefix = "p${pg}"
        String grid   = settings["page${pg}GridLayout"] ?: "2x2"
        (1..maxSlots(grid)).each { idx ->
            def dev  = settings["${prefix}sensor${idx}"]
            String t = settings["${prefix}sensorType${idx}"] ?: "none"
            try {
                if (!dev || t == "none") {
                    ind."setPage${pg}SlotEmpty"(idx)
                    return
                }
                String attr = t == "contact" ? "contact" : t == "water" ? "water" : t == "smoke" ? "smoke" : "motion"
                String val  = dev.currentValue(attr) ?: (t == "contact" ? "closed" : t == "water" ? "dry" : t == "smoke" ? "clear" : "inactive")
                boolean active = (val == "active" || val == "open" || val == "wet" || val == "detected")
                if (active) ind."setPage${pg}MotionActive"(idx)
                else        ind."setPage${pg}MotionInactive"(idx)
            } catch (Exception e) {
                infoLog "[SensorMonitor] WARN sync ${prefix}:${idx}: ${e.message}"
            }
        }
    }
}

def displayRebootedHandler(evt) {
    // Skip if initialize() just ran (within 60s) AND display didn't reboot since then
    long msSince = now() - (state.lastInitMs ?: 0L)
    long msSinceReboot = now() - (state.lastRebootMs ?: 0L)
    if (msSince < 60000 && msSinceReboot > 60000) {
        infoLog "[SensorMonitor] displayRebooted skipped — initialize ran ${msSince}ms ago"
        return
    }
    state.lastRebootMs = now()
    infoLog "[SensorMonitor] SenseCAP rebooted — pushing all layouts and resyncing"
    def ind = settings.indicatorDevice; if (!ind) return
    try {
        int rPg = (settings.numberOfPages ?: "1") as int
        (1..rPg).each { pg -> ind."setPage${pg}GridLayout"(settings["page${pg}GridLayout"] ?: "2x2") }
        // Push labels/types first so driver has them before layout push
        runIn(1, pushPage1LabelsAndTypes)
        if (rPg >= 2) runIn(2, pushPage2LabelsAndTypes)
        if (rPg >= 3) runIn(3, pushPage3LabelsAndTypes)
        if (rPg >= 4) runIn(4, pushPage4LabelsAndTypes)
        if (rPg >= 5) runIn(5, pushPage5LabelsAndTypes)
        runIn(8, "pushAllLayoutsDeferred")
        // Sync actual sensor states after layouts are built (clears stale active states)
        runIn(35, syncAllSensors)
    } catch (Exception e) { infoLog "[SensorMonitor] WARN reboot handler: ${e.message}" }
}

// ── Helpers ────────────────────────────────────────────────────────────────────
def maxSlots(String grid) {
    switch (grid) {
        case "1x1": return 1;  case "3x3": return 9;  case "4x4": return 16
        case "5x5": return 25; case "6x6": return 36; case "7x7": return 49
        default:    return 4
    }
}
def gridMaxChars(String grid) {
    switch (grid) {
        case "7x7": return 4; case "1x1": return 30; case "6x6": return 5
        case "5x5": return 6; case "4x4": return 7;  case "3x3": return 11
        default:    return 16
    }
}
def subscribedCount(String prefix) {
    String grid = settings["page${prefix.replace("p","")}GridLayout"] ?: "2x2"
    (1..maxSlots(grid)).count { settings["${prefix}sensor${it}"] != null && (settings["${prefix}sensorType${it}"] ?: "none") != "none" }
}
def stripEmoji(String text) {
    if (!text) return ""
    // Remove emoji and other non-ASCII, collapse whitespace, trim
    String result = text.replaceAll(/[^\x20-\x7E]/, " ").replaceAll(/\s+/, " ").trim()
    return result
}
def wrapLabel(String text, int maxChars) {
    if (!text || text.length() <= maxChars) return text ?: ""
    List<String> words = text.split(" ") as List
    List<String> lines = []
    String cur = ""
    words.each { w ->
        if (cur.isEmpty()) { cur = w }
        else if ((cur + " " + w).length() <= maxChars) { cur = cur + " " + w }
        else { lines << cur; cur = w }
    }
    if (cur) lines << cur
    return lines.join("\n")
}
def infoLog(String msg)  { if ((settings.logLevel ?: "1") != "0") log.info msg }
def debugLog(String msg) { if ((settings.logLevel ?: "1") == "2") log.debug msg }
        }

        section("<b>SenseCAP Device</b>") {
            input name: "indicatorDevice", type: "capability.initialize",
                  title: "SenseCAP Sensor Display device", required: true, multiple: false
            paragraph ""
            input name: "numberOfPages", type: "enum", title: "Number of Pages",
                  options: ["1":"1","2":"2","3":"3","4":"4","5":"5"],
                  defaultValue: "1", required: true, submitOnChange: true, width: 3
        }

        int numPages = (settings.numberOfPages ?: "1") as int

        def pageColors = [1:"#1a73e8", 2:"#0d8a5e", 3:"#7b2d9e", 4:"#b5520a", 5:"#1a5e8a"]

        (1..numPages).each { pg ->
            String color   = pageColors[pg]
            String gridKey = "page${pg}GridLayout"
            String prefix  = "p${pg}"

            section("""<div style='background:${color};color:white;padding:10px 14px;border-radius:6px;font-size:1.2em;font-weight:bold;letter-spacing:0.5px'>&#9616; PAGE ${pg}</div>""") {
                input name: gridKey, type: "enum", title: "Page ${pg} Grid Layout",
                      options: gridOptions(), defaultValue: "2x2", required: true, submitOnChange: true, width: 4
                paragraph "<b>Sensor → Slot Mapping</b>"
                sensorSlotSection(1, maxSlots(settings[gridKey] ?: "2x2"), prefix)
            }
        }

        section("<b>Options</b>") {
            input name: "syncOnStartup", type: "bool",
                  title: "Sync all sensor states to display on startup / save", defaultValue: true
            input name: "logLevel", type: "enum", title: "Logging Level",
                  options: ["0":"None","1":"Info only","2":"Info + Debug"], defaultValue: "1", required: true
        }

        section("<b>Status</b>") {
            def ind = settings.indicatorDevice
            if (ind) {
                paragraph "Indicator device: <b>${ind.displayName}</b>"
                paragraph "MQTT status: <b>${ind.currentValue('mqttStatus') ?: 'unknown'}</b>"
            }
            int np = (settings.numberOfPages ?: "1") as int
            (1..np).each { pg ->
                String gridKey = "page${pg}GridLayout"
                paragraph "Page ${pg}: <b>${subscribedCount("p${pg}")}</b> / ${maxSlots(settings[gridKey] ?: "2x2")} slots configured"
            }
        }
    }
}

def gridOptions() {
    ["1x1":"1×1 (1 sensor)","2x2":"2×2 (4 sensors)","3x3":"3×3 (9 sensors)",
     "4x4":"4×4 (16 sensors)","5x5":"5×5 (25 sensors)","6x6":"6×6 (36 sensors)","7x7":"7×7 (49 sensors)"]
}

def sensorSlotSection(int from, int to, String prefix) {
    (from..to).each { idx ->
        String typeKey   = "${prefix}sensorType${idx}"
        String deviceKey = "${prefix}sensor${idx}"
        String labelKey  = "${prefix}label${idx}"
        String type      = settings[typeKey] ?: "none"
        paragraph "<hr style='margin:8px 0'><b style='font-size:1.05em'>Slot ${idx}</b>", width: 12
        input name: typeKey, type: "enum", title: "Type",
              options: ["none":"— None —","motion":"Motion","contact":"Contact","water":"Water","smoke":"Smoke"],
              defaultValue: "none", required: true, submitOnChange: true, width: 3
        if (type != "none") {
            String cap = type == "contact" ? "capability.contactSensor" :
                         type == "water"   ? "capability.waterSensor"   :
                         type == "smoke"   ? "capability.smokeDetector" : "capability.motionSensor"
            input name: deviceKey, type: cap, title: "Device",
                  required: false, multiple: false, width: 5
            input name: labelKey, type: "text", title: "Label (optional)",
                  required: false, width: 4
        }
    }
}

// ── Lifecycle ──────────────────────────────────────────────────────────────────
def installed() { initialize() }
def updated()   { unsubscribe(); initialize() }
def uninstalled() { unsubscribe() }

def initialize() {
    infoLog "[SensorMonitor] initialize() starting"
    state.lastInitMs = now()

    int numPg = (settings.numberOfPages ?: "1") as int
    try {
        (1..numPg).each { pg ->
            String prefix = "p${pg}"
            String grid   = settings["page${pg}GridLayout"] ?: "2x2"
            (1..maxSlots(grid)).each { idx -> subscribeSlot(idx, prefix) }
        }
    } catch (Exception e) {
        infoLog "[SensorMonitor] ERROR during subscribe: ${e.message}"
    }

    infoLog "[SensorMonitor] Subscriptions done — ${(1..numPg).collect { "p${it}:${subscribedCount("p${it}")}" }.join(" ")}"

    def ind = settings.indicatorDevice
    if (ind) {
        infoLog "[SensorMonitor] Indicator device: ${ind.displayName}"
        try {
            // Set page count and grid layouts
            ind.setNumberOfPages(numPg)
            (1..numPg).each { pg -> ind."setPage${pg}GridLayout"(settings["page${pg}GridLayout"] ?: "2x2") }
        } catch (Exception e) {
            infoLog "[SensorMonitor] WARN — indicator device call failed: ${e.message}"
        }
        // Push labels/types first, then push layouts
        runIn(1,  pushPage1LabelsAndTypes)
        if (numPg >= 2) runIn(2, pushPage2LabelsAndTypes)
        if (numPg >= 3) runIn(3, pushPage3LabelsAndTypes)
        if (numPg >= 4) runIn(4, pushPage4LabelsAndTypes)
        if (numPg >= 5) runIn(5, pushPage5LabelsAndTypes)
        runIn(6, pushAllLayoutsDeferred)
        try { subscribe(ind, "displayRebooted", displayRebootedHandler) }
        catch (Exception e) { infoLog "[SensorMonitor] WARN — displayRebooted subscribe failed: ${e.message}" }
    } else {
        infoLog "[SensorMonitor] WARNING — SenseCAP Sensor Display device not set"
    }

    // Sync sensor states after layouts have fully pushed (clears any stale active/deleted slots)
    runIn(35, syncAllSensors)
    infoLog "[SensorMonitor] initialize() complete"
}






def subscribeSlot(int idx, String prefix) {
    def dev  = settings["${prefix}sensor${idx}"]
    String t = settings["${prefix}sensorType${idx}"] ?: "none"
    if (!dev || t == "none") return
    String attr, handlerName
    if (t == "contact")     { attr = "contact"; handlerName = "${prefix}contactHandler${idx}" }
    else if (t == "water")  { attr = "water";   handlerName = "${prefix}waterHandler${idx}" }
    else if (t == "smoke")  { attr = "smoke";   handlerName = "${prefix}smokeHandler${idx}" }
    else                    { attr = "motion";  handlerName = "${prefix}motionHandler${idx}" }
    infoLog "[SensorMonitor] Subscribing slot ${prefix}:${idx} → ${dev.displayName} [${attr}] → ${handlerName}"
    subscribe(dev, attr, handlerName)
}

// ── Label / type push ──────────────────────────────────────────────────────────
def pushAllLayoutsDeferred() {
    int numPg = (settings.numberOfPages ?: "1") as int
    try { settings.indicatorDevice?.pushAllLayouts(numPg) }
    catch (Exception e) { infoLog "[SensorMonitor] WARN — pushAllLayouts: ${e.message}" }
}

def pushPage1LabelsAndTypes() { pushLabelsAndTypes("p1", settings.page1GridLayout ?: "2x2") }
def pushPage2LabelsAndTypes() { pushLabelsAndTypes("p2", settings.page2GridLayout ?: "2x2") }
def pushPage3LabelsAndTypes() { pushLabelsAndTypes("p3", settings.page3GridLayout ?: "2x2") }
def pushPage4LabelsAndTypes() { pushLabelsAndTypes("p4", settings.page4GridLayout ?: "2x2") }
def pushPage5LabelsAndTypes() { pushLabelsAndTypes("p5", settings.page5GridLayout ?: "2x2") }

def pushLabelsAndTypes(String prefix, String grid) {
    // Only push slot types - labels are handled inside pushPageLayout on the driver
    // to avoid duplicate label sends
    def ind = settings.indicatorDevice
    if (!ind) return
    int pg = (prefix.replace("p","")) as int
    def slotTypes = [:]
    (1..maxSlots(grid)).each { idx ->
        slotTypes[idx] = settings["${prefix}sensorType${idx}"] ?: "none"
    }
    try {
        ind."updatePage${pg}SlotTypes"(slotTypes)
    } catch (Exception e) {
        infoLog "[SensorMonitor] WARN — pushLabelsAndTypes p${pg}: ${e.message}"
    }
    // Also store labels in driver state so pushPageLayout can use them
    int maxChars = gridMaxChars(grid)
    def labels = [:]
    (1..maxSlots(grid)).each { idx ->
        String slotT  = settings["${prefix}sensorType${idx}"] ?: "none"
        def slotDev   = settings["${prefix}sensor${idx}"]
        boolean has   = slotDev != null && slotT != "none"
        String custom = settings["${prefix}label${idx}"]?.toString()?.trim() ?: ""
        boolean isDefault = custom ==~ /Sensor \d+/
        String raw = has ? ((!custom || isDefault) ? stripEmoji(slotDev.displayName ?: "") : stripEmoji(custom)) : ""
        if (!raw && has) {
            // All chars were emoji — use last word of original or "Sensor N"
            String[] words = slotDev.displayName?.split(" ") ?: []
            raw = words ? words[-1].replaceAll(/[^\x20-\x7E]/, "").trim() : ""
            if (!raw) raw = "Sensor ${idx}"
        }
        labels[idx] = wrapLabel(raw, maxChars)
    }
    try {
        ind."updatePage${pg}Labels"(labels)
    } catch (Exception e) {
        infoLog "[SensorMonitor] WARN — updateLabels p${pg}: ${e.message}"
    }
}

// ── Event handlers — Page 1 ────────────────────────────────────────────────────
def p1motionHandler1(evt) { handleEvent(evt, 1, "p1") }
def p1motionHandler2(evt) { handleEvent(evt, 2, "p1") }
def p1motionHandler3(evt) { handleEvent(evt, 3, "p1") }
def p1motionHandler4(evt) { handleEvent(evt, 4, "p1") }
def p1motionHandler5(evt) { handleEvent(evt, 5, "p1") }
def p1motionHandler6(evt) { handleEvent(evt, 6, "p1") }
def p1motionHandler7(evt) { handleEvent(evt, 7, "p1") }
def p1motionHandler8(evt) { handleEvent(evt, 8, "p1") }
def p1motionHandler9(evt) { handleEvent(evt, 9, "p1") }
def p1motionHandler10(evt) { handleEvent(evt, 10, "p1") }
def p1motionHandler11(evt) { handleEvent(evt, 11, "p1") }
def p1motionHandler12(evt) { handleEvent(evt, 12, "p1") }
def p1motionHandler13(evt) { handleEvent(evt, 13, "p1") }
def p1motionHandler14(evt) { handleEvent(evt, 14, "p1") }
def p1motionHandler15(evt) { handleEvent(evt, 15, "p1") }
def p1motionHandler16(evt) { handleEvent(evt, 16, "p1") }
def p1motionHandler17(evt) { handleEvent(evt, 17, "p1") }
def p1motionHandler18(evt) { handleEvent(evt, 18, "p1") }
def p1motionHandler19(evt) { handleEvent(evt, 19, "p1") }
def p1motionHandler20(evt) { handleEvent(evt, 20, "p1") }
def p1motionHandler21(evt) { handleEvent(evt, 21, "p1") }
def p1motionHandler22(evt) { handleEvent(evt, 22, "p1") }
def p1motionHandler23(evt) { handleEvent(evt, 23, "p1") }
def p1motionHandler24(evt) { handleEvent(evt, 24, "p1") }
def p1motionHandler25(evt) { handleEvent(evt, 25, "p1") }
def p1motionHandler26(evt) { handleEvent(evt, 26, "p1") }
def p1motionHandler27(evt) { handleEvent(evt, 27, "p1") }
def p1motionHandler28(evt) { handleEvent(evt, 28, "p1") }
def p1motionHandler29(evt) { handleEvent(evt, 29, "p1") }
def p1motionHandler30(evt) { handleEvent(evt, 30, "p1") }
def p1motionHandler31(evt) { handleEvent(evt, 31, "p1") }
def p1motionHandler32(evt) { handleEvent(evt, 32, "p1") }
def p1motionHandler33(evt) { handleEvent(evt, 33, "p1") }
def p1motionHandler34(evt) { handleEvent(evt, 34, "p1") }
def p1motionHandler35(evt) { handleEvent(evt, 35, "p1") }
def p1motionHandler36(evt) { handleEvent(evt, 36, "p1") }
def p1motionHandler37(evt) { handleEvent(evt, 37, "p1") }
def p1motionHandler38(evt) { handleEvent(evt, 38, "p1") }
def p1motionHandler39(evt) { handleEvent(evt, 39, "p1") }
def p1motionHandler40(evt) { handleEvent(evt, 40, "p1") }
def p1motionHandler41(evt) { handleEvent(evt, 41, "p1") }
def p1motionHandler42(evt) { handleEvent(evt, 42, "p1") }
def p1motionHandler43(evt) { handleEvent(evt, 43, "p1") }
def p1motionHandler44(evt) { handleEvent(evt, 44, "p1") }
def p1motionHandler45(evt) { handleEvent(evt, 45, "p1") }
def p1motionHandler46(evt) { handleEvent(evt, 46, "p1") }
def p1motionHandler47(evt) { handleEvent(evt, 47, "p1") }
def p1motionHandler48(evt) { handleEvent(evt, 48, "p1") }
def p1motionHandler49(evt) { handleEvent(evt, 49, "p1") }
def p1contactHandler1(evt) { handleEvent(evt, 1, "p1") }
def p1contactHandler2(evt) { handleEvent(evt, 2, "p1") }
def p1contactHandler3(evt) { handleEvent(evt, 3, "p1") }
def p1contactHandler4(evt) { handleEvent(evt, 4, "p1") }
def p1contactHandler5(evt) { handleEvent(evt, 5, "p1") }
def p1contactHandler6(evt) { handleEvent(evt, 6, "p1") }
def p1contactHandler7(evt) { handleEvent(evt, 7, "p1") }
def p1contactHandler8(evt) { handleEvent(evt, 8, "p1") }
def p1contactHandler9(evt) { handleEvent(evt, 9, "p1") }
def p1contactHandler10(evt) { handleEvent(evt, 10, "p1") }
def p1contactHandler11(evt) { handleEvent(evt, 11, "p1") }
def p1contactHandler12(evt) { handleEvent(evt, 12, "p1") }
def p1contactHandler13(evt) { handleEvent(evt, 13, "p1") }
def p1contactHandler14(evt) { handleEvent(evt, 14, "p1") }
def p1contactHandler15(evt) { handleEvent(evt, 15, "p1") }
def p1contactHandler16(evt) { handleEvent(evt, 16, "p1") }
def p1contactHandler17(evt) { handleEvent(evt, 17, "p1") }
def p1contactHandler18(evt) { handleEvent(evt, 18, "p1") }
def p1contactHandler19(evt) { handleEvent(evt, 19, "p1") }
def p1contactHandler20(evt) { handleEvent(evt, 20, "p1") }
def p1contactHandler21(evt) { handleEvent(evt, 21, "p1") }
def p1contactHandler22(evt) { handleEvent(evt, 22, "p1") }
def p1contactHandler23(evt) { handleEvent(evt, 23, "p1") }
def p1contactHandler24(evt) { handleEvent(evt, 24, "p1") }
def p1contactHandler25(evt) { handleEvent(evt, 25, "p1") }
def p1contactHandler26(evt) { handleEvent(evt, 26, "p1") }
def p1contactHandler27(evt) { handleEvent(evt, 27, "p1") }
def p1contactHandler28(evt) { handleEvent(evt, 28, "p1") }
def p1contactHandler29(evt) { handleEvent(evt, 29, "p1") }
def p1contactHandler30(evt) { handleEvent(evt, 30, "p1") }
def p1contactHandler31(evt) { handleEvent(evt, 31, "p1") }
def p1contactHandler32(evt) { handleEvent(evt, 32, "p1") }
def p1contactHandler33(evt) { handleEvent(evt, 33, "p1") }
def p1contactHandler34(evt) { handleEvent(evt, 34, "p1") }
def p1contactHandler35(evt) { handleEvent(evt, 35, "p1") }
def p1contactHandler36(evt) { handleEvent(evt, 36, "p1") }
def p1contactHandler37(evt) { handleEvent(evt, 37, "p1") }
def p1contactHandler38(evt) { handleEvent(evt, 38, "p1") }
def p1contactHandler39(evt) { handleEvent(evt, 39, "p1") }
def p1contactHandler40(evt) { handleEvent(evt, 40, "p1") }
def p1contactHandler41(evt) { handleEvent(evt, 41, "p1") }
def p1contactHandler42(evt) { handleEvent(evt, 42, "p1") }
def p1contactHandler43(evt) { handleEvent(evt, 43, "p1") }
def p1contactHandler44(evt) { handleEvent(evt, 44, "p1") }
def p1contactHandler45(evt) { handleEvent(evt, 45, "p1") }
def p1contactHandler46(evt) { handleEvent(evt, 46, "p1") }
def p1contactHandler47(evt) { handleEvent(evt, 47, "p1") }
def p1contactHandler48(evt) { handleEvent(evt, 48, "p1") }
def p1contactHandler49(evt) { handleEvent(evt, 49, "p1") }
def p1waterHandler1(evt) { handleEvent(evt, 1, "p1") }
def p1waterHandler2(evt) { handleEvent(evt, 2, "p1") }
def p1waterHandler3(evt) { handleEvent(evt, 3, "p1") }
def p1waterHandler4(evt) { handleEvent(evt, 4, "p1") }
def p1waterHandler5(evt) { handleEvent(evt, 5, "p1") }
def p1waterHandler6(evt) { handleEvent(evt, 6, "p1") }
def p1waterHandler7(evt) { handleEvent(evt, 7, "p1") }
def p1waterHandler8(evt) { handleEvent(evt, 8, "p1") }
def p1waterHandler9(evt) { handleEvent(evt, 9, "p1") }
def p1waterHandler10(evt) { handleEvent(evt, 10, "p1") }
def p1waterHandler11(evt) { handleEvent(evt, 11, "p1") }
def p1waterHandler12(evt) { handleEvent(evt, 12, "p1") }
def p1waterHandler13(evt) { handleEvent(evt, 13, "p1") }
def p1waterHandler14(evt) { handleEvent(evt, 14, "p1") }
def p1waterHandler15(evt) { handleEvent(evt, 15, "p1") }
def p1waterHandler16(evt) { handleEvent(evt, 16, "p1") }
def p1waterHandler17(evt) { handleEvent(evt, 17, "p1") }
def p1waterHandler18(evt) { handleEvent(evt, 18, "p1") }
def p1waterHandler19(evt) { handleEvent(evt, 19, "p1") }
def p1waterHandler20(evt) { handleEvent(evt, 20, "p1") }
def p1waterHandler21(evt) { handleEvent(evt, 21, "p1") }
def p1waterHandler22(evt) { handleEvent(evt, 22, "p1") }
def p1waterHandler23(evt) { handleEvent(evt, 23, "p1") }
def p1waterHandler24(evt) { handleEvent(evt, 24, "p1") }
def p1waterHandler25(evt) { handleEvent(evt, 25, "p1") }
def p1waterHandler26(evt) { handleEvent(evt, 26, "p1") }
def p1waterHandler27(evt) { handleEvent(evt, 27, "p1") }
def p1waterHandler28(evt) { handleEvent(evt, 28, "p1") }
def p1waterHandler29(evt) { handleEvent(evt, 29, "p1") }
def p1waterHandler30(evt) { handleEvent(evt, 30, "p1") }
def p1waterHandler31(evt) { handleEvent(evt, 31, "p1") }
def p1waterHandler32(evt) { handleEvent(evt, 32, "p1") }
def p1waterHandler33(evt) { handleEvent(evt, 33, "p1") }
def p1waterHandler34(evt) { handleEvent(evt, 34, "p1") }
def p1waterHandler35(evt) { handleEvent(evt, 35, "p1") }
def p1waterHandler36(evt) { handleEvent(evt, 36, "p1") }
def p1waterHandler37(evt) { handleEvent(evt, 37, "p1") }
def p1waterHandler38(evt) { handleEvent(evt, 38, "p1") }
def p1waterHandler39(evt) { handleEvent(evt, 39, "p1") }
def p1waterHandler40(evt) { handleEvent(evt, 40, "p1") }
def p1waterHandler41(evt) { handleEvent(evt, 41, "p1") }
def p1waterHandler42(evt) { handleEvent(evt, 42, "p1") }
def p1waterHandler43(evt) { handleEvent(evt, 43, "p1") }
def p1waterHandler44(evt) { handleEvent(evt, 44, "p1") }
def p1waterHandler45(evt) { handleEvent(evt, 45, "p1") }
def p1waterHandler46(evt) { handleEvent(evt, 46, "p1") }
def p1waterHandler47(evt) { handleEvent(evt, 47, "p1") }
def p1waterHandler48(evt) { handleEvent(evt, 48, "p1") }
def p1waterHandler49(evt) { handleEvent(evt, 49, "p1") }
def p1smokeHandler1(evt) { handleEvent(evt, 1, "p1") }
def p1smokeHandler2(evt) { handleEvent(evt, 2, "p1") }
def p1smokeHandler3(evt) { handleEvent(evt, 3, "p1") }
def p1smokeHandler4(evt) { handleEvent(evt, 4, "p1") }
def p1smokeHandler5(evt) { handleEvent(evt, 5, "p1") }
def p1smokeHandler6(evt) { handleEvent(evt, 6, "p1") }
def p1smokeHandler7(evt) { handleEvent(evt, 7, "p1") }
def p1smokeHandler8(evt) { handleEvent(evt, 8, "p1") }
def p1smokeHandler9(evt) { handleEvent(evt, 9, "p1") }
def p1smokeHandler10(evt) { handleEvent(evt, 10, "p1") }
def p1smokeHandler11(evt) { handleEvent(evt, 11, "p1") }
def p1smokeHandler12(evt) { handleEvent(evt, 12, "p1") }
def p1smokeHandler13(evt) { handleEvent(evt, 13, "p1") }
def p1smokeHandler14(evt) { handleEvent(evt, 14, "p1") }
def p1smokeHandler15(evt) { handleEvent(evt, 15, "p1") }
def p1smokeHandler16(evt) { handleEvent(evt, 16, "p1") }
def p1smokeHandler17(evt) { handleEvent(evt, 17, "p1") }
def p1smokeHandler18(evt) { handleEvent(evt, 18, "p1") }
def p1smokeHandler19(evt) { handleEvent(evt, 19, "p1") }
def p1smokeHandler20(evt) { handleEvent(evt, 20, "p1") }
def p1smokeHandler21(evt) { handleEvent(evt, 21, "p1") }
def p1smokeHandler22(evt) { handleEvent(evt, 22, "p1") }
def p1smokeHandler23(evt) { handleEvent(evt, 23, "p1") }
def p1smokeHandler24(evt) { handleEvent(evt, 24, "p1") }
def p1smokeHandler25(evt) { handleEvent(evt, 25, "p1") }
def p1smokeHandler26(evt) { handleEvent(evt, 26, "p1") }
def p1smokeHandler27(evt) { handleEvent(evt, 27, "p1") }
def p1smokeHandler28(evt) { handleEvent(evt, 28, "p1") }
def p1smokeHandler29(evt) { handleEvent(evt, 29, "p1") }
def p1smokeHandler30(evt) { handleEvent(evt, 30, "p1") }
def p1smokeHandler31(evt) { handleEvent(evt, 31, "p1") }
def p1smokeHandler32(evt) { handleEvent(evt, 32, "p1") }
def p1smokeHandler33(evt) { handleEvent(evt, 33, "p1") }
def p1smokeHandler34(evt) { handleEvent(evt, 34, "p1") }
def p1smokeHandler35(evt) { handleEvent(evt, 35, "p1") }
def p1smokeHandler36(evt) { handleEvent(evt, 36, "p1") }
def p1smokeHandler37(evt) { handleEvent(evt, 37, "p1") }
def p1smokeHandler38(evt) { handleEvent(evt, 38, "p1") }
def p1smokeHandler39(evt) { handleEvent(evt, 39, "p1") }
def p1smokeHandler40(evt) { handleEvent(evt, 40, "p1") }
def p1smokeHandler41(evt) { handleEvent(evt, 41, "p1") }
def p1smokeHandler42(evt) { handleEvent(evt, 42, "p1") }
def p1smokeHandler43(evt) { handleEvent(evt, 43, "p1") }
def p1smokeHandler44(evt) { handleEvent(evt, 44, "p1") }
def p1smokeHandler45(evt) { handleEvent(evt, 45, "p1") }
def p1smokeHandler46(evt) { handleEvent(evt, 46, "p1") }
def p1smokeHandler47(evt) { handleEvent(evt, 47, "p1") }
def p1smokeHandler48(evt) { handleEvent(evt, 48, "p1") }
def p1smokeHandler49(evt) { handleEvent(evt, 49, "p1") }

// ── Event handlers — Page 2 ────────────────────────────────────────────────────
def p2motionHandler1(evt) { handleEvent(evt, 1, "p2") }
def p2motionHandler2(evt) { handleEvent(evt, 2, "p2") }
def p2motionHandler3(evt) { handleEvent(evt, 3, "p2") }
def p2motionHandler4(evt) { handleEvent(evt, 4, "p2") }
def p2motionHandler5(evt) { handleEvent(evt, 5, "p2") }
def p2motionHandler6(evt) { handleEvent(evt, 6, "p2") }
def p2motionHandler7(evt) { handleEvent(evt, 7, "p2") }
def p2motionHandler8(evt) { handleEvent(evt, 8, "p2") }
def p2motionHandler9(evt) { handleEvent(evt, 9, "p2") }
def p2motionHandler10(evt) { handleEvent(evt, 10, "p2") }
def p2motionHandler11(evt) { handleEvent(evt, 11, "p2") }
def p2motionHandler12(evt) { handleEvent(evt, 12, "p2") }
def p2motionHandler13(evt) { handleEvent(evt, 13, "p2") }
def p2motionHandler14(evt) { handleEvent(evt, 14, "p2") }
def p2motionHandler15(evt) { handleEvent(evt, 15, "p2") }
def p2motionHandler16(evt) { handleEvent(evt, 16, "p2") }
def p2motionHandler17(evt) { handleEvent(evt, 17, "p2") }
def p2motionHandler18(evt) { handleEvent(evt, 18, "p2") }
def p2motionHandler19(evt) { handleEvent(evt, 19, "p2") }
def p2motionHandler20(evt) { handleEvent(evt, 20, "p2") }
def p2motionHandler21(evt) { handleEvent(evt, 21, "p2") }
def p2motionHandler22(evt) { handleEvent(evt, 22, "p2") }
def p2motionHandler23(evt) { handleEvent(evt, 23, "p2") }
def p2motionHandler24(evt) { handleEvent(evt, 24, "p2") }
def p2motionHandler25(evt) { handleEvent(evt, 25, "p2") }
def p2motionHandler26(evt) { handleEvent(evt, 26, "p2") }
def p2motionHandler27(evt) { handleEvent(evt, 27, "p2") }
def p2motionHandler28(evt) { handleEvent(evt, 28, "p2") }
def p2motionHandler29(evt) { handleEvent(evt, 29, "p2") }
def p2motionHandler30(evt) { handleEvent(evt, 30, "p2") }
def p2motionHandler31(evt) { handleEvent(evt, 31, "p2") }
def p2motionHandler32(evt) { handleEvent(evt, 32, "p2") }
def p2motionHandler33(evt) { handleEvent(evt, 33, "p2") }
def p2motionHandler34(evt) { handleEvent(evt, 34, "p2") }
def p2motionHandler35(evt) { handleEvent(evt, 35, "p2") }
def p2motionHandler36(evt) { handleEvent(evt, 36, "p2") }
def p2motionHandler37(evt) { handleEvent(evt, 37, "p2") }
def p2motionHandler38(evt) { handleEvent(evt, 38, "p2") }
def p2motionHandler39(evt) { handleEvent(evt, 39, "p2") }
def p2motionHandler40(evt) { handleEvent(evt, 40, "p2") }
def p2motionHandler41(evt) { handleEvent(evt, 41, "p2") }
def p2motionHandler42(evt) { handleEvent(evt, 42, "p2") }
def p2motionHandler43(evt) { handleEvent(evt, 43, "p2") }
def p2motionHandler44(evt) { handleEvent(evt, 44, "p2") }
def p2motionHandler45(evt) { handleEvent(evt, 45, "p2") }
def p2motionHandler46(evt) { handleEvent(evt, 46, "p2") }
def p2motionHandler47(evt) { handleEvent(evt, 47, "p2") }
def p2motionHandler48(evt) { handleEvent(evt, 48, "p2") }
def p2motionHandler49(evt) { handleEvent(evt, 49, "p2") }
def p2contactHandler1(evt) { handleEvent(evt, 1, "p2") }
def p2contactHandler2(evt) { handleEvent(evt, 2, "p2") }
def p2contactHandler3(evt) { handleEvent(evt, 3, "p2") }
def p2contactHandler4(evt) { handleEvent(evt, 4, "p2") }
def p2contactHandler5(evt) { handleEvent(evt, 5, "p2") }
def p2contactHandler6(evt) { handleEvent(evt, 6, "p2") }
def p2contactHandler7(evt) { handleEvent(evt, 7, "p2") }
def p2contactHandler8(evt) { handleEvent(evt, 8, "p2") }
def p2contactHandler9(evt) { handleEvent(evt, 9, "p2") }
def p2contactHandler10(evt) { handleEvent(evt, 10, "p2") }
def p2contactHandler11(evt) { handleEvent(evt, 11, "p2") }
def p2contactHandler12(evt) { handleEvent(evt, 12, "p2") }
def p2contactHandler13(evt) { handleEvent(evt, 13, "p2") }
def p2contactHandler14(evt) { handleEvent(evt, 14, "p2") }
def p2contactHandler15(evt) { handleEvent(evt, 15, "p2") }
def p2contactHandler16(evt) { handleEvent(evt, 16, "p2") }
def p2contactHandler17(evt) { handleEvent(evt, 17, "p2") }
def p2contactHandler18(evt) { handleEvent(evt, 18, "p2") }
def p2contactHandler19(evt) { handleEvent(evt, 19, "p2") }
def p2contactHandler20(evt) { handleEvent(evt, 20, "p2") }
def p2contactHandler21(evt) { handleEvent(evt, 21, "p2") }
def p2contactHandler22(evt) { handleEvent(evt, 22, "p2") }
def p2contactHandler23(evt) { handleEvent(evt, 23, "p2") }
def p2contactHandler24(evt) { handleEvent(evt, 24, "p2") }
def p2contactHandler25(evt) { handleEvent(evt, 25, "p2") }
def p2contactHandler26(evt) { handleEvent(evt, 26, "p2") }
def p2contactHandler27(evt) { handleEvent(evt, 27, "p2") }
def p2contactHandler28(evt) { handleEvent(evt, 28, "p2") }
def p2contactHandler29(evt) { handleEvent(evt, 29, "p2") }
def p2contactHandler30(evt) { handleEvent(evt, 30, "p2") }
def p2contactHandler31(evt) { handleEvent(evt, 31, "p2") }
def p2contactHandler32(evt) { handleEvent(evt, 32, "p2") }
def p2contactHandler33(evt) { handleEvent(evt, 33, "p2") }
def p2contactHandler34(evt) { handleEvent(evt, 34, "p2") }
def p2contactHandler35(evt) { handleEvent(evt, 35, "p2") }
def p2contactHandler36(evt) { handleEvent(evt, 36, "p2") }
def p2contactHandler37(evt) { handleEvent(evt, 37, "p2") }
def p2contactHandler38(evt) { handleEvent(evt, 38, "p2") }
def p2contactHandler39(evt) { handleEvent(evt, 39, "p2") }
def p2contactHandler40(evt) { handleEvent(evt, 40, "p2") }
def p2contactHandler41(evt) { handleEvent(evt, 41, "p2") }
def p2contactHandler42(evt) { handleEvent(evt, 42, "p2") }
def p2contactHandler43(evt) { handleEvent(evt, 43, "p2") }
def p2contactHandler44(evt) { handleEvent(evt, 44, "p2") }
def p2contactHandler45(evt) { handleEvent(evt, 45, "p2") }
def p2contactHandler46(evt) { handleEvent(evt, 46, "p2") }
def p2contactHandler47(evt) { handleEvent(evt, 47, "p2") }
def p2contactHandler48(evt) { handleEvent(evt, 48, "p2") }
def p2contactHandler49(evt) { handleEvent(evt, 49, "p2") }
def p2waterHandler1(evt) { handleEvent(evt, 1, "p2") }
def p2waterHandler2(evt) { handleEvent(evt, 2, "p2") }
def p2waterHandler3(evt) { handleEvent(evt, 3, "p2") }
def p2waterHandler4(evt) { handleEvent(evt, 4, "p2") }
def p2waterHandler5(evt) { handleEvent(evt, 5, "p2") }
def p2waterHandler6(evt) { handleEvent(evt, 6, "p2") }
def p2waterHandler7(evt) { handleEvent(evt, 7, "p2") }
def p2waterHandler8(evt) { handleEvent(evt, 8, "p2") }
def p2waterHandler9(evt) { handleEvent(evt, 9, "p2") }
def p2waterHandler10(evt) { handleEvent(evt, 10, "p2") }
def p2waterHandler11(evt) { handleEvent(evt, 11, "p2") }
def p2waterHandler12(evt) { handleEvent(evt, 12, "p2") }
def p2waterHandler13(evt) { handleEvent(evt, 13, "p2") }
def p2waterHandler14(evt) { handleEvent(evt, 14, "p2") }
def p2waterHandler15(evt) { handleEvent(evt, 15, "p2") }
def p2waterHandler16(evt) { handleEvent(evt, 16, "p2") }
def p2waterHandler17(evt) { handleEvent(evt, 17, "p2") }
def p2waterHandler18(evt) { handleEvent(evt, 18, "p2") }
def p2waterHandler19(evt) { handleEvent(evt, 19, "p2") }
def p2waterHandler20(evt) { handleEvent(evt, 20, "p2") }
def p2waterHandler21(evt) { handleEvent(evt, 21, "p2") }
def p2waterHandler22(evt) { handleEvent(evt, 22, "p2") }
def p2waterHandler23(evt) { handleEvent(evt, 23, "p2") }
def p2waterHandler24(evt) { handleEvent(evt, 24, "p2") }
def p2waterHandler25(evt) { handleEvent(evt, 25, "p2") }
def p2waterHandler26(evt) { handleEvent(evt, 26, "p2") }
def p2waterHandler27(evt) { handleEvent(evt, 27, "p2") }
def p2waterHandler28(evt) { handleEvent(evt, 28, "p2") }
def p2waterHandler29(evt) { handleEvent(evt, 29, "p2") }
def p2waterHandler30(evt) { handleEvent(evt, 30, "p2") }
def p2waterHandler31(evt) { handleEvent(evt, 31, "p2") }
def p2waterHandler32(evt) { handleEvent(evt, 32, "p2") }
def p2waterHandler33(evt) { handleEvent(evt, 33, "p2") }
def p2waterHandler34(evt) { handleEvent(evt, 34, "p2") }
def p2waterHandler35(evt) { handleEvent(evt, 35, "p2") }
def p2waterHandler36(evt) { handleEvent(evt, 36, "p2") }
def p2waterHandler37(evt) { handleEvent(evt, 37, "p2") }
def p2waterHandler38(evt) { handleEvent(evt, 38, "p2") }
def p2waterHandler39(evt) { handleEvent(evt, 39, "p2") }
def p2waterHandler40(evt) { handleEvent(evt, 40, "p2") }
def p2waterHandler41(evt) { handleEvent(evt, 41, "p2") }
def p2waterHandler42(evt) { handleEvent(evt, 42, "p2") }
def p2waterHandler43(evt) { handleEvent(evt, 43, "p2") }
def p2waterHandler44(evt) { handleEvent(evt, 44, "p2") }
def p2waterHandler45(evt) { handleEvent(evt, 45, "p2") }
def p2waterHandler46(evt) { handleEvent(evt, 46, "p2") }
def p2waterHandler47(evt) { handleEvent(evt, 47, "p2") }
def p2waterHandler48(evt) { handleEvent(evt, 48, "p2") }
def p2waterHandler49(evt) { handleEvent(evt, 49, "p2") }
def p2smokeHandler1(evt) { handleEvent(evt, 1, "p2") }
def p2smokeHandler2(evt) { handleEvent(evt, 2, "p2") }
def p2smokeHandler3(evt) { handleEvent(evt, 3, "p2") }
def p2smokeHandler4(evt) { handleEvent(evt, 4, "p2") }
def p2smokeHandler5(evt) { handleEvent(evt, 5, "p2") }
def p2smokeHandler6(evt) { handleEvent(evt, 6, "p2") }
def p2smokeHandler7(evt) { handleEvent(evt, 7, "p2") }
def p2smokeHandler8(evt) { handleEvent(evt, 8, "p2") }
def p2smokeHandler9(evt) { handleEvent(evt, 9, "p2") }
def p2smokeHandler10(evt) { handleEvent(evt, 10, "p2") }
def p2smokeHandler11(evt) { handleEvent(evt, 11, "p2") }
def p2smokeHandler12(evt) { handleEvent(evt, 12, "p2") }
def p2smokeHandler13(evt) { handleEvent(evt, 13, "p2") }
def p2smokeHandler14(evt) { handleEvent(evt, 14, "p2") }
def p2smokeHandler15(evt) { handleEvent(evt, 15, "p2") }
def p2smokeHandler16(evt) { handleEvent(evt, 16, "p2") }
def p2smokeHandler17(evt) { handleEvent(evt, 17, "p2") }
def p2smokeHandler18(evt) { handleEvent(evt, 18, "p2") }
def p2smokeHandler19(evt) { handleEvent(evt, 19, "p2") }
def p2smokeHandler20(evt) { handleEvent(evt, 20, "p2") }
def p2smokeHandler21(evt) { handleEvent(evt, 21, "p2") }
def p2smokeHandler22(evt) { handleEvent(evt, 22, "p2") }
def p2smokeHandler23(evt) { handleEvent(evt, 23, "p2") }
def p2smokeHandler24(evt) { handleEvent(evt, 24, "p2") }
def p2smokeHandler25(evt) { handleEvent(evt, 25, "p2") }
def p2smokeHandler26(evt) { handleEvent(evt, 26, "p2") }
def p2smokeHandler27(evt) { handleEvent(evt, 27, "p2") }
def p2smokeHandler28(evt) { handleEvent(evt, 28, "p2") }
def p2smokeHandler29(evt) { handleEvent(evt, 29, "p2") }
def p2smokeHandler30(evt) { handleEvent(evt, 30, "p2") }
def p2smokeHandler31(evt) { handleEvent(evt, 31, "p2") }
def p2smokeHandler32(evt) { handleEvent(evt, 32, "p2") }
def p2smokeHandler33(evt) { handleEvent(evt, 33, "p2") }
def p2smokeHandler34(evt) { handleEvent(evt, 34, "p2") }
def p2smokeHandler35(evt) { handleEvent(evt, 35, "p2") }
def p2smokeHandler36(evt) { handleEvent(evt, 36, "p2") }
def p2smokeHandler37(evt) { handleEvent(evt, 37, "p2") }
def p2smokeHandler38(evt) { handleEvent(evt, 38, "p2") }
def p2smokeHandler39(evt) { handleEvent(evt, 39, "p2") }
def p2smokeHandler40(evt) { handleEvent(evt, 40, "p2") }
def p2smokeHandler41(evt) { handleEvent(evt, 41, "p2") }
def p2smokeHandler42(evt) { handleEvent(evt, 42, "p2") }
def p2smokeHandler43(evt) { handleEvent(evt, 43, "p2") }
def p2smokeHandler44(evt) { handleEvent(evt, 44, "p2") }
def p2smokeHandler45(evt) { handleEvent(evt, 45, "p2") }
def p2smokeHandler46(evt) { handleEvent(evt, 46, "p2") }
def p2smokeHandler47(evt) { handleEvent(evt, 47, "p2") }
def p2smokeHandler48(evt) { handleEvent(evt, 48, "p2") }
def p2smokeHandler49(evt) { handleEvent(evt, 49, "p2") }

// ── Event handlers — Page 3 ────────────────────────────────────────────────────
def p3motionHandler1(evt) { handleEvent(evt, 1, "p3") }
def p3motionHandler2(evt) { handleEvent(evt, 2, "p3") }
def p3motionHandler3(evt) { handleEvent(evt, 3, "p3") }
def p3motionHandler4(evt) { handleEvent(evt, 4, "p3") }
def p3motionHandler5(evt) { handleEvent(evt, 5, "p3") }
def p3motionHandler6(evt) { handleEvent(evt, 6, "p3") }
def p3motionHandler7(evt) { handleEvent(evt, 7, "p3") }
def p3motionHandler8(evt) { handleEvent(evt, 8, "p3") }
def p3motionHandler9(evt) { handleEvent(evt, 9, "p3") }
def p3motionHandler10(evt) { handleEvent(evt, 10, "p3") }
def p3motionHandler11(evt) { handleEvent(evt, 11, "p3") }
def p3motionHandler12(evt) { handleEvent(evt, 12, "p3") }
def p3motionHandler13(evt) { handleEvent(evt, 13, "p3") }
def p3motionHandler14(evt) { handleEvent(evt, 14, "p3") }
def p3motionHandler15(evt) { handleEvent(evt, 15, "p3") }
def p3motionHandler16(evt) { handleEvent(evt, 16, "p3") }
def p3motionHandler17(evt) { handleEvent(evt, 17, "p3") }
def p3motionHandler18(evt) { handleEvent(evt, 18, "p3") }
def p3motionHandler19(evt) { handleEvent(evt, 19, "p3") }
def p3motionHandler20(evt) { handleEvent(evt, 20, "p3") }
def p3motionHandler21(evt) { handleEvent(evt, 21, "p3") }
def p3motionHandler22(evt) { handleEvent(evt, 22, "p3") }
def p3motionHandler23(evt) { handleEvent(evt, 23, "p3") }
def p3motionHandler24(evt) { handleEvent(evt, 24, "p3") }
def p3motionHandler25(evt) { handleEvent(evt, 25, "p3") }
def p3motionHandler26(evt) { handleEvent(evt, 26, "p3") }
def p3motionHandler27(evt) { handleEvent(evt, 27, "p3") }
def p3motionHandler28(evt) { handleEvent(evt, 28, "p3") }
def p3motionHandler29(evt) { handleEvent(evt, 29, "p3") }
def p3motionHandler30(evt) { handleEvent(evt, 30, "p3") }
def p3motionHandler31(evt) { handleEvent(evt, 31, "p3") }
def p3motionHandler32(evt) { handleEvent(evt, 32, "p3") }
def p3motionHandler33(evt) { handleEvent(evt, 33, "p3") }
def p3motionHandler34(evt) { handleEvent(evt, 34, "p3") }
def p3motionHandler35(evt) { handleEvent(evt, 35, "p3") }
def p3motionHandler36(evt) { handleEvent(evt, 36, "p3") }
def p3motionHandler37(evt) { handleEvent(evt, 37, "p3") }
def p3motionHandler38(evt) { handleEvent(evt, 38, "p3") }
def p3motionHandler39(evt) { handleEvent(evt, 39, "p3") }
def p3motionHandler40(evt) { handleEvent(evt, 40, "p3") }
def p3motionHandler41(evt) { handleEvent(evt, 41, "p3") }
def p3motionHandler42(evt) { handleEvent(evt, 42, "p3") }
def p3motionHandler43(evt) { handleEvent(evt, 43, "p3") }
def p3motionHandler44(evt) { handleEvent(evt, 44, "p3") }
def p3motionHandler45(evt) { handleEvent(evt, 45, "p3") }
def p3motionHandler46(evt) { handleEvent(evt, 46, "p3") }
def p3motionHandler47(evt) { handleEvent(evt, 47, "p3") }
def p3motionHandler48(evt) { handleEvent(evt, 48, "p3") }
def p3motionHandler49(evt) { handleEvent(evt, 49, "p3") }
def p3contactHandler1(evt) { handleEvent(evt, 1, "p3") }
def p3contactHandler2(evt) { handleEvent(evt, 2, "p3") }
def p3contactHandler3(evt) { handleEvent(evt, 3, "p3") }
def p3contactHandler4(evt) { handleEvent(evt, 4, "p3") }
def p3contactHandler5(evt) { handleEvent(evt, 5, "p3") }
def p3contactHandler6(evt) { handleEvent(evt, 6, "p3") }
def p3contactHandler7(evt) { handleEvent(evt, 7, "p3") }
def p3contactHandler8(evt) { handleEvent(evt, 8, "p3") }
def p3contactHandler9(evt) { handleEvent(evt, 9, "p3") }
def p3contactHandler10(evt) { handleEvent(evt, 10, "p3") }
def p3contactHandler11(evt) { handleEvent(evt, 11, "p3") }
def p3contactHandler12(evt) { handleEvent(evt, 12, "p3") }
def p3contactHandler13(evt) { handleEvent(evt, 13, "p3") }
def p3contactHandler14(evt) { handleEvent(evt, 14, "p3") }
def p3contactHandler15(evt) { handleEvent(evt, 15, "p3") }
def p3contactHandler16(evt) { handleEvent(evt, 16, "p3") }
def p3contactHandler17(evt) { handleEvent(evt, 17, "p3") }
def p3contactHandler18(evt) { handleEvent(evt, 18, "p3") }
def p3contactHandler19(evt) { handleEvent(evt, 19, "p3") }
def p3contactHandler20(evt) { handleEvent(evt, 20, "p3") }
def p3contactHandler21(evt) { handleEvent(evt, 21, "p3") }
def p3contactHandler22(evt) { handleEvent(evt, 22, "p3") }
def p3contactHandler23(evt) { handleEvent(evt, 23, "p3") }
def p3contactHandler24(evt) { handleEvent(evt, 24, "p3") }
def p3contactHandler25(evt) { handleEvent(evt, 25, "p3") }
def p3contactHandler26(evt) { handleEvent(evt, 26, "p3") }
def p3contactHandler27(evt) { handleEvent(evt, 27, "p3") }
def p3contactHandler28(evt) { handleEvent(evt, 28, "p3") }
def p3contactHandler29(evt) { handleEvent(evt, 29, "p3") }
def p3contactHandler30(evt) { handleEvent(evt, 30, "p3") }
def p3contactHandler31(evt) { handleEvent(evt, 31, "p3") }
def p3contactHandler32(evt) { handleEvent(evt, 32, "p3") }
def p3contactHandler33(evt) { handleEvent(evt, 33, "p3") }
def p3contactHandler34(evt) { handleEvent(evt, 34, "p3") }
def p3contactHandler35(evt) { handleEvent(evt, 35, "p3") }
def p3contactHandler36(evt) { handleEvent(evt, 36, "p3") }
def p3contactHandler37(evt) { handleEvent(evt, 37, "p3") }
def p3contactHandler38(evt) { handleEvent(evt, 38, "p3") }
def p3contactHandler39(evt) { handleEvent(evt, 39, "p3") }
def p3contactHandler40(evt) { handleEvent(evt, 40, "p3") }
def p3contactHandler41(evt) { handleEvent(evt, 41, "p3") }
def p3contactHandler42(evt) { handleEvent(evt, 42, "p3") }
def p3contactHandler43(evt) { handleEvent(evt, 43, "p3") }
def p3contactHandler44(evt) { handleEvent(evt, 44, "p3") }
def p3contactHandler45(evt) { handleEvent(evt, 45, "p3") }
def p3contactHandler46(evt) { handleEvent(evt, 46, "p3") }
def p3contactHandler47(evt) { handleEvent(evt, 47, "p3") }
def p3contactHandler48(evt) { handleEvent(evt, 48, "p3") }
def p3contactHandler49(evt) { handleEvent(evt, 49, "p3") }
def p3waterHandler1(evt) { handleEvent(evt, 1, "p3") }
def p3waterHandler2(evt) { handleEvent(evt, 2, "p3") }
def p3waterHandler3(evt) { handleEvent(evt, 3, "p3") }
def p3waterHandler4(evt) { handleEvent(evt, 4, "p3") }
def p3waterHandler5(evt) { handleEvent(evt, 5, "p3") }
def p3waterHandler6(evt) { handleEvent(evt, 6, "p3") }
def p3waterHandler7(evt) { handleEvent(evt, 7, "p3") }
def p3waterHandler8(evt) { handleEvent(evt, 8, "p3") }
def p3waterHandler9(evt) { handleEvent(evt, 9, "p3") }
def p3waterHandler10(evt) { handleEvent(evt, 10, "p3") }
def p3waterHandler11(evt) { handleEvent(evt, 11, "p3") }
def p3waterHandler12(evt) { handleEvent(evt, 12, "p3") }
def p3waterHandler13(evt) { handleEvent(evt, 13, "p3") }
def p3waterHandler14(evt) { handleEvent(evt, 14, "p3") }
def p3waterHandler15(evt) { handleEvent(evt, 15, "p3") }
def p3waterHandler16(evt) { handleEvent(evt, 16, "p3") }
def p3waterHandler17(evt) { handleEvent(evt, 17, "p3") }
def p3waterHandler18(evt) { handleEvent(evt, 18, "p3") }
def p3waterHandler19(evt) { handleEvent(evt, 19, "p3") }
def p3waterHandler20(evt) { handleEvent(evt, 20, "p3") }
def p3waterHandler21(evt) { handleEvent(evt, 21, "p3") }
def p3waterHandler22(evt) { handleEvent(evt, 22, "p3") }
def p3waterHandler23(evt) { handleEvent(evt, 23, "p3") }
def p3waterHandler24(evt) { handleEvent(evt, 24, "p3") }
def p3waterHandler25(evt) { handleEvent(evt, 25, "p3") }
def p3waterHandler26(evt) { handleEvent(evt, 26, "p3") }
def p3waterHandler27(evt) { handleEvent(evt, 27, "p3") }
def p3waterHandler28(evt) { handleEvent(evt, 28, "p3") }
def p3waterHandler29(evt) { handleEvent(evt, 29, "p3") }
def p3waterHandler30(evt) { handleEvent(evt, 30, "p3") }
def p3waterHandler31(evt) { handleEvent(evt, 31, "p3") }
def p3waterHandler32(evt) { handleEvent(evt, 32, "p3") }
def p3waterHandler33(evt) { handleEvent(evt, 33, "p3") }
def p3waterHandler34(evt) { handleEvent(evt, 34, "p3") }
def p3waterHandler35(evt) { handleEvent(evt, 35, "p3") }
def p3waterHandler36(evt) { handleEvent(evt, 36, "p3") }
def p3waterHandler37(evt) { handleEvent(evt, 37, "p3") }
def p3waterHandler38(evt) { handleEvent(evt, 38, "p3") }
def p3waterHandler39(evt) { handleEvent(evt, 39, "p3") }
def p3waterHandler40(evt) { handleEvent(evt, 40, "p3") }
def p3waterHandler41(evt) { handleEvent(evt, 41, "p3") }
def p3waterHandler42(evt) { handleEvent(evt, 42, "p3") }
def p3waterHandler43(evt) { handleEvent(evt, 43, "p3") }
def p3waterHandler44(evt) { handleEvent(evt, 44, "p3") }
def p3waterHandler45(evt) { handleEvent(evt, 45, "p3") }
def p3waterHandler46(evt) { handleEvent(evt, 46, "p3") }
def p3waterHandler47(evt) { handleEvent(evt, 47, "p3") }
def p3waterHandler48(evt) { handleEvent(evt, 48, "p3") }
def p3waterHandler49(evt) { handleEvent(evt, 49, "p3") }
def p3smokeHandler1(evt) { handleEvent(evt, 1, "p3") }
def p3smokeHandler2(evt) { handleEvent(evt, 2, "p3") }
def p3smokeHandler3(evt) { handleEvent(evt, 3, "p3") }
def p3smokeHandler4(evt) { handleEvent(evt, 4, "p3") }
def p3smokeHandler5(evt) { handleEvent(evt, 5, "p3") }
def p3smokeHandler6(evt) { handleEvent(evt, 6, "p3") }
def p3smokeHandler7(evt) { handleEvent(evt, 7, "p3") }
def p3smokeHandler8(evt) { handleEvent(evt, 8, "p3") }
def p3smokeHandler9(evt) { handleEvent(evt, 9, "p3") }
def p3smokeHandler10(evt) { handleEvent(evt, 10, "p3") }
def p3smokeHandler11(evt) { handleEvent(evt, 11, "p3") }
def p3smokeHandler12(evt) { handleEvent(evt, 12, "p3") }
def p3smokeHandler13(evt) { handleEvent(evt, 13, "p3") }
def p3smokeHandler14(evt) { handleEvent(evt, 14, "p3") }
def p3smokeHandler15(evt) { handleEvent(evt, 15, "p3") }
def p3smokeHandler16(evt) { handleEvent(evt, 16, "p3") }
def p3smokeHandler17(evt) { handleEvent(evt, 17, "p3") }
def p3smokeHandler18(evt) { handleEvent(evt, 18, "p3") }
def p3smokeHandler19(evt) { handleEvent(evt, 19, "p3") }
def p3smokeHandler20(evt) { handleEvent(evt, 20, "p3") }
def p3smokeHandler21(evt) { handleEvent(evt, 21, "p3") }
def p3smokeHandler22(evt) { handleEvent(evt, 22, "p3") }
def p3smokeHandler23(evt) { handleEvent(evt, 23, "p3") }
def p3smokeHandler24(evt) { handleEvent(evt, 24, "p3") }
def p3smokeHandler25(evt) { handleEvent(evt, 25, "p3") }
def p3smokeHandler26(evt) { handleEvent(evt, 26, "p3") }
def p3smokeHandler27(evt) { handleEvent(evt, 27, "p3") }
def p3smokeHandler28(evt) { handleEvent(evt, 28, "p3") }
def p3smokeHandler29(evt) { handleEvent(evt, 29, "p3") }
def p3smokeHandler30(evt) { handleEvent(evt, 30, "p3") }
def p3smokeHandler31(evt) { handleEvent(evt, 31, "p3") }
def p3smokeHandler32(evt) { handleEvent(evt, 32, "p3") }
def p3smokeHandler33(evt) { handleEvent(evt, 33, "p3") }
def p3smokeHandler34(evt) { handleEvent(evt, 34, "p3") }
def p3smokeHandler35(evt) { handleEvent(evt, 35, "p3") }
def p3smokeHandler36(evt) { handleEvent(evt, 36, "p3") }
def p3smokeHandler37(evt) { handleEvent(evt, 37, "p3") }
def p3smokeHandler38(evt) { handleEvent(evt, 38, "p3") }
def p3smokeHandler39(evt) { handleEvent(evt, 39, "p3") }
def p3smokeHandler40(evt) { handleEvent(evt, 40, "p3") }
def p3smokeHandler41(evt) { handleEvent(evt, 41, "p3") }
def p3smokeHandler42(evt) { handleEvent(evt, 42, "p3") }
def p3smokeHandler43(evt) { handleEvent(evt, 43, "p3") }
def p3smokeHandler44(evt) { handleEvent(evt, 44, "p3") }
def p3smokeHandler45(evt) { handleEvent(evt, 45, "p3") }
def p3smokeHandler46(evt) { handleEvent(evt, 46, "p3") }
def p3smokeHandler47(evt) { handleEvent(evt, 47, "p3") }
def p3smokeHandler48(evt) { handleEvent(evt, 48, "p3") }
def p3smokeHandler49(evt) { handleEvent(evt, 49, "p3") }

// ── Event handlers — Page 4 ────────────────────────────────────────────────────
def p4motionHandler1(evt) { handleEvent(evt, 1, "p4") }
def p4motionHandler2(evt) { handleEvent(evt, 2, "p4") }
def p4motionHandler3(evt) { handleEvent(evt, 3, "p4") }
def p4motionHandler4(evt) { handleEvent(evt, 4, "p4") }
def p4motionHandler5(evt) { handleEvent(evt, 5, "p4") }
def p4motionHandler6(evt) { handleEvent(evt, 6, "p4") }
def p4motionHandler7(evt) { handleEvent(evt, 7, "p4") }
def p4motionHandler8(evt) { handleEvent(evt, 8, "p4") }
def p4motionHandler9(evt) { handleEvent(evt, 9, "p4") }
def p4motionHandler10(evt) { handleEvent(evt, 10, "p4") }
def p4motionHandler11(evt) { handleEvent(evt, 11, "p4") }
def p4motionHandler12(evt) { handleEvent(evt, 12, "p4") }
def p4motionHandler13(evt) { handleEvent(evt, 13, "p4") }
def p4motionHandler14(evt) { handleEvent(evt, 14, "p4") }
def p4motionHandler15(evt) { handleEvent(evt, 15, "p4") }
def p4motionHandler16(evt) { handleEvent(evt, 16, "p4") }
def p4motionHandler17(evt) { handleEvent(evt, 17, "p4") }
def p4motionHandler18(evt) { handleEvent(evt, 18, "p4") }
def p4motionHandler19(evt) { handleEvent(evt, 19, "p4") }
def p4motionHandler20(evt) { handleEvent(evt, 20, "p4") }
def p4motionHandler21(evt) { handleEvent(evt, 21, "p4") }
def p4motionHandler22(evt) { handleEvent(evt, 22, "p4") }
def p4motionHandler23(evt) { handleEvent(evt, 23, "p4") }
def p4motionHandler24(evt) { handleEvent(evt, 24, "p4") }
def p4motionHandler25(evt) { handleEvent(evt, 25, "p4") }
def p4motionHandler26(evt) { handleEvent(evt, 26, "p4") }
def p4motionHandler27(evt) { handleEvent(evt, 27, "p4") }
def p4motionHandler28(evt) { handleEvent(evt, 28, "p4") }
def p4motionHandler29(evt) { handleEvent(evt, 29, "p4") }
def p4motionHandler30(evt) { handleEvent(evt, 30, "p4") }
def p4motionHandler31(evt) { handleEvent(evt, 31, "p4") }
def p4motionHandler32(evt) { handleEvent(evt, 32, "p4") }
def p4motionHandler33(evt) { handleEvent(evt, 33, "p4") }
def p4motionHandler34(evt) { handleEvent(evt, 34, "p4") }
def p4motionHandler35(evt) { handleEvent(evt, 35, "p4") }
def p4motionHandler36(evt) { handleEvent(evt, 36, "p4") }
def p4motionHandler37(evt) { handleEvent(evt, 37, "p4") }
def p4motionHandler38(evt) { handleEvent(evt, 38, "p4") }
def p4motionHandler39(evt) { handleEvent(evt, 39, "p4") }
def p4motionHandler40(evt) { handleEvent(evt, 40, "p4") }
def p4motionHandler41(evt) { handleEvent(evt, 41, "p4") }
def p4motionHandler42(evt) { handleEvent(evt, 42, "p4") }
def p4motionHandler43(evt) { handleEvent(evt, 43, "p4") }
def p4motionHandler44(evt) { handleEvent(evt, 44, "p4") }
def p4motionHandler45(evt) { handleEvent(evt, 45, "p4") }
def p4motionHandler46(evt) { handleEvent(evt, 46, "p4") }
def p4motionHandler47(evt) { handleEvent(evt, 47, "p4") }
def p4motionHandler48(evt) { handleEvent(evt, 48, "p4") }
def p4motionHandler49(evt) { handleEvent(evt, 49, "p4") }
def p4contactHandler1(evt) { handleEvent(evt, 1, "p4") }
def p4contactHandler2(evt) { handleEvent(evt, 2, "p4") }
def p4contactHandler3(evt) { handleEvent(evt, 3, "p4") }
def p4contactHandler4(evt) { handleEvent(evt, 4, "p4") }
def p4contactHandler5(evt) { handleEvent(evt, 5, "p4") }
def p4contactHandler6(evt) { handleEvent(evt, 6, "p4") }
def p4contactHandler7(evt) { handleEvent(evt, 7, "p4") }
def p4contactHandler8(evt) { handleEvent(evt, 8, "p4") }
def p4contactHandler9(evt) { handleEvent(evt, 9, "p4") }
def p4contactHandler10(evt) { handleEvent(evt, 10, "p4") }
def p4contactHandler11(evt) { handleEvent(evt, 11, "p4") }
def p4contactHandler12(evt) { handleEvent(evt, 12, "p4") }
def p4contactHandler13(evt) { handleEvent(evt, 13, "p4") }
def p4contactHandler14(evt) { handleEvent(evt, 14, "p4") }
def p4contactHandler15(evt) { handleEvent(evt, 15, "p4") }
def p4contactHandler16(evt) { handleEvent(evt, 16, "p4") }
def p4contactHandler17(evt) { handleEvent(evt, 17, "p4") }
def p4contactHandler18(evt) { handleEvent(evt, 18, "p4") }
def p4contactHandler19(evt) { handleEvent(evt, 19, "p4") }
def p4contactHandler20(evt) { handleEvent(evt, 20, "p4") }
def p4contactHandler21(evt) { handleEvent(evt, 21, "p4") }
def p4contactHandler22(evt) { handleEvent(evt, 22, "p4") }
def p4contactHandler23(evt) { handleEvent(evt, 23, "p4") }
def p4contactHandler24(evt) { handleEvent(evt, 24, "p4") }
def p4contactHandler25(evt) { handleEvent(evt, 25, "p4") }
def p4contactHandler26(evt) { handleEvent(evt, 26, "p4") }
def p4contactHandler27(evt) { handleEvent(evt, 27, "p4") }
def p4contactHandler28(evt) { handleEvent(evt, 28, "p4") }
def p4contactHandler29(evt) { handleEvent(evt, 29, "p4") }
def p4contactHandler30(evt) { handleEvent(evt, 30, "p4") }
def p4contactHandler31(evt) { handleEvent(evt, 31, "p4") }
def p4contactHandler32(evt) { handleEvent(evt, 32, "p4") }
def p4contactHandler33(evt) { handleEvent(evt, 33, "p4") }
def p4contactHandler34(evt) { handleEvent(evt, 34, "p4") }
def p4contactHandler35(evt) { handleEvent(evt, 35, "p4") }
def p4contactHandler36(evt) { handleEvent(evt, 36, "p4") }
def p4contactHandler37(evt) { handleEvent(evt, 37, "p4") }
def p4contactHandler38(evt) { handleEvent(evt, 38, "p4") }
def p4contactHandler39(evt) { handleEvent(evt, 39, "p4") }
def p4contactHandler40(evt) { handleEvent(evt, 40, "p4") }
def p4contactHandler41(evt) { handleEvent(evt, 41, "p4") }
def p4contactHandler42(evt) { handleEvent(evt, 42, "p4") }
def p4contactHandler43(evt) { handleEvent(evt, 43, "p4") }
def p4contactHandler44(evt) { handleEvent(evt, 44, "p4") }
def p4contactHandler45(evt) { handleEvent(evt, 45, "p4") }
def p4contactHandler46(evt) { handleEvent(evt, 46, "p4") }
def p4contactHandler47(evt) { handleEvent(evt, 47, "p4") }
def p4contactHandler48(evt) { handleEvent(evt, 48, "p4") }
def p4contactHandler49(evt) { handleEvent(evt, 49, "p4") }
def p4waterHandler1(evt) { handleEvent(evt, 1, "p4") }
def p4waterHandler2(evt) { handleEvent(evt, 2, "p4") }
def p4waterHandler3(evt) { handleEvent(evt, 3, "p4") }
def p4waterHandler4(evt) { handleEvent(evt, 4, "p4") }
def p4waterHandler5(evt) { handleEvent(evt, 5, "p4") }
def p4waterHandler6(evt) { handleEvent(evt, 6, "p4") }
def p4waterHandler7(evt) { handleEvent(evt, 7, "p4") }
def p4waterHandler8(evt) { handleEvent(evt, 8, "p4") }
def p4waterHandler9(evt) { handleEvent(evt, 9, "p4") }
def p4waterHandler10(evt) { handleEvent(evt, 10, "p4") }
def p4waterHandler11(evt) { handleEvent(evt, 11, "p4") }
def p4waterHandler12(evt) { handleEvent(evt, 12, "p4") }
def p4waterHandler13(evt) { handleEvent(evt, 13, "p4") }
def p4waterHandler14(evt) { handleEvent(evt, 14, "p4") }
def p4waterHandler15(evt) { handleEvent(evt, 15, "p4") }
def p4waterHandler16(evt) { handleEvent(evt, 16, "p4") }
def p4waterHandler17(evt) { handleEvent(evt, 17, "p4") }
def p4waterHandler18(evt) { handleEvent(evt, 18, "p4") }
def p4waterHandler19(evt) { handleEvent(evt, 19, "p4") }
def p4waterHandler20(evt) { handleEvent(evt, 20, "p4") }
def p4waterHandler21(evt) { handleEvent(evt, 21, "p4") }
def p4waterHandler22(evt) { handleEvent(evt, 22, "p4") }
def p4waterHandler23(evt) { handleEvent(evt, 23, "p4") }
def p4waterHandler24(evt) { handleEvent(evt, 24, "p4") }
def p4waterHandler25(evt) { handleEvent(evt, 25, "p4") }
def p4waterHandler26(evt) { handleEvent(evt, 26, "p4") }
def p4waterHandler27(evt) { handleEvent(evt, 27, "p4") }
def p4waterHandler28(evt) { handleEvent(evt, 28, "p4") }
def p4waterHandler29(evt) { handleEvent(evt, 29, "p4") }
def p4waterHandler30(evt) { handleEvent(evt, 30, "p4") }
def p4waterHandler31(evt) { handleEvent(evt, 31, "p4") }
def p4waterHandler32(evt) { handleEvent(evt, 32, "p4") }
def p4waterHandler33(evt) { handleEvent(evt, 33, "p4") }
def p4waterHandler34(evt) { handleEvent(evt, 34, "p4") }
def p4waterHandler35(evt) { handleEvent(evt, 35, "p4") }
def p4waterHandler36(evt) { handleEvent(evt, 36, "p4") }
def p4waterHandler37(evt) { handleEvent(evt, 37, "p4") }
def p4waterHandler38(evt) { handleEvent(evt, 38, "p4") }
def p4waterHandler39(evt) { handleEvent(evt, 39, "p4") }
def p4waterHandler40(evt) { handleEvent(evt, 40, "p4") }
def p4waterHandler41(evt) { handleEvent(evt, 41, "p4") }
def p4waterHandler42(evt) { handleEvent(evt, 42, "p4") }
def p4waterHandler43(evt) { handleEvent(evt, 43, "p4") }
def p4waterHandler44(evt) { handleEvent(evt, 44, "p4") }
def p4waterHandler45(evt) { handleEvent(evt, 45, "p4") }
def p4waterHandler46(evt) { handleEvent(evt, 46, "p4") }
def p4waterHandler47(evt) { handleEvent(evt, 47, "p4") }
def p4waterHandler48(evt) { handleEvent(evt, 48, "p4") }
def p4waterHandler49(evt) { handleEvent(evt, 49, "p4") }
def p4smokeHandler1(evt) { handleEvent(evt, 1, "p4") }
def p4smokeHandler2(evt) { handleEvent(evt, 2, "p4") }
def p4smokeHandler3(evt) { handleEvent(evt, 3, "p4") }
def p4smokeHandler4(evt) { handleEvent(evt, 4, "p4") }
def p4smokeHandler5(evt) { handleEvent(evt, 5, "p4") }
def p4smokeHandler6(evt) { handleEvent(evt, 6, "p4") }
def p4smokeHandler7(evt) { handleEvent(evt, 7, "p4") }
def p4smokeHandler8(evt) { handleEvent(evt, 8, "p4") }
def p4smokeHandler9(evt) { handleEvent(evt, 9, "p4") }
def p4smokeHandler10(evt) { handleEvent(evt, 10, "p4") }
def p4smokeHandler11(evt) { handleEvent(evt, 11, "p4") }
def p4smokeHandler12(evt) { handleEvent(evt, 12, "p4") }
def p4smokeHandler13(evt) { handleEvent(evt, 13, "p4") }
def p4smokeHandler14(evt) { handleEvent(evt, 14, "p4") }
def p4smokeHandler15(evt) { handleEvent(evt, 15, "p4") }
def p4smokeHandler16(evt) { handleEvent(evt, 16, "p4") }
def p4smokeHandler17(evt) { handleEvent(evt, 17, "p4") }
def p4smokeHandler18(evt) { handleEvent(evt, 18, "p4") }
def p4smokeHandler19(evt) { handleEvent(evt, 19, "p4") }
def p4smokeHandler20(evt) { handleEvent(evt, 20, "p4") }
def p4smokeHandler21(evt) { handleEvent(evt, 21, "p4") }
def p4smokeHandler22(evt) { handleEvent(evt, 22, "p4") }
def p4smokeHandler23(evt) { handleEvent(evt, 23, "p4") }
def p4smokeHandler24(evt) { handleEvent(evt, 24, "p4") }
def p4smokeHandler25(evt) { handleEvent(evt, 25, "p4") }
def p4smokeHandler26(evt) { handleEvent(evt, 26, "p4") }
def p4smokeHandler27(evt) { handleEvent(evt, 27, "p4") }
def p4smokeHandler28(evt) { handleEvent(evt, 28, "p4") }
def p4smokeHandler29(evt) { handleEvent(evt, 29, "p4") }
def p4smokeHandler30(evt) { handleEvent(evt, 30, "p4") }
def p4smokeHandler31(evt) { handleEvent(evt, 31, "p4") }
def p4smokeHandler32(evt) { handleEvent(evt, 32, "p4") }
def p4smokeHandler33(evt) { handleEvent(evt, 33, "p4") }
def p4smokeHandler34(evt) { handleEvent(evt, 34, "p4") }
def p4smokeHandler35(evt) { handleEvent(evt, 35, "p4") }
def p4smokeHandler36(evt) { handleEvent(evt, 36, "p4") }
def p4smokeHandler37(evt) { handleEvent(evt, 37, "p4") }
def p4smokeHandler38(evt) { handleEvent(evt, 38, "p4") }
def p4smokeHandler39(evt) { handleEvent(evt, 39, "p4") }
def p4smokeHandler40(evt) { handleEvent(evt, 40, "p4") }
def p4smokeHandler41(evt) { handleEvent(evt, 41, "p4") }
def p4smokeHandler42(evt) { handleEvent(evt, 42, "p4") }
def p4smokeHandler43(evt) { handleEvent(evt, 43, "p4") }
def p4smokeHandler44(evt) { handleEvent(evt, 44, "p4") }
def p4smokeHandler45(evt) { handleEvent(evt, 45, "p4") }
def p4smokeHandler46(evt) { handleEvent(evt, 46, "p4") }
def p4smokeHandler47(evt) { handleEvent(evt, 47, "p4") }
def p4smokeHandler48(evt) { handleEvent(evt, 48, "p4") }
def p4smokeHandler49(evt) { handleEvent(evt, 49, "p4") }

// ── Event handlers — Page 5 ────────────────────────────────────────────────────
def p5motionHandler1(evt) { handleEvent(evt, 1, "p5") }
def p5motionHandler2(evt) { handleEvent(evt, 2, "p5") }
def p5motionHandler3(evt) { handleEvent(evt, 3, "p5") }
def p5motionHandler4(evt) { handleEvent(evt, 4, "p5") }
def p5motionHandler5(evt) { handleEvent(evt, 5, "p5") }
def p5motionHandler6(evt) { handleEvent(evt, 6, "p5") }
def p5motionHandler7(evt) { handleEvent(evt, 7, "p5") }
def p5motionHandler8(evt) { handleEvent(evt, 8, "p5") }
def p5motionHandler9(evt) { handleEvent(evt, 9, "p5") }
def p5motionHandler10(evt) { handleEvent(evt, 10, "p5") }
def p5motionHandler11(evt) { handleEvent(evt, 11, "p5") }
def p5motionHandler12(evt) { handleEvent(evt, 12, "p5") }
def p5motionHandler13(evt) { handleEvent(evt, 13, "p5") }
def p5motionHandler14(evt) { handleEvent(evt, 14, "p5") }
def p5motionHandler15(evt) { handleEvent(evt, 15, "p5") }
def p5motionHandler16(evt) { handleEvent(evt, 16, "p5") }
def p5motionHandler17(evt) { handleEvent(evt, 17, "p5") }
def p5motionHandler18(evt) { handleEvent(evt, 18, "p5") }
def p5motionHandler19(evt) { handleEvent(evt, 19, "p5") }
def p5motionHandler20(evt) { handleEvent(evt, 20, "p5") }
def p5motionHandler21(evt) { handleEvent(evt, 21, "p5") }
def p5motionHandler22(evt) { handleEvent(evt, 22, "p5") }
def p5motionHandler23(evt) { handleEvent(evt, 23, "p5") }
def p5motionHandler24(evt) { handleEvent(evt, 24, "p5") }
def p5motionHandler25(evt) { handleEvent(evt, 25, "p5") }
def p5motionHandler26(evt) { handleEvent(evt, 26, "p5") }
def p5motionHandler27(evt) { handleEvent(evt, 27, "p5") }
def p5motionHandler28(evt) { handleEvent(evt, 28, "p5") }
def p5motionHandler29(evt) { handleEvent(evt, 29, "p5") }
def p5motionHandler30(evt) { handleEvent(evt, 30, "p5") }
def p5motionHandler31(evt) { handleEvent(evt, 31, "p5") }
def p5motionHandler32(evt) { handleEvent(evt, 32, "p5") }
def p5motionHandler33(evt) { handleEvent(evt, 33, "p5") }
def p5motionHandler34(evt) { handleEvent(evt, 34, "p5") }
def p5motionHandler35(evt) { handleEvent(evt, 35, "p5") }
def p5motionHandler36(evt) { handleEvent(evt, 36, "p5") }
def p5motionHandler37(evt) { handleEvent(evt, 37, "p5") }
def p5motionHandler38(evt) { handleEvent(evt, 38, "p5") }
def p5motionHandler39(evt) { handleEvent(evt, 39, "p5") }
def p5motionHandler40(evt) { handleEvent(evt, 40, "p5") }
def p5motionHandler41(evt) { handleEvent(evt, 41, "p5") }
def p5motionHandler42(evt) { handleEvent(evt, 42, "p5") }
def p5motionHandler43(evt) { handleEvent(evt, 43, "p5") }
def p5motionHandler44(evt) { handleEvent(evt, 44, "p5") }
def p5motionHandler45(evt) { handleEvent(evt, 45, "p5") }
def p5motionHandler46(evt) { handleEvent(evt, 46, "p5") }
def p5motionHandler47(evt) { handleEvent(evt, 47, "p5") }
def p5motionHandler48(evt) { handleEvent(evt, 48, "p5") }
def p5motionHandler49(evt) { handleEvent(evt, 49, "p5") }
def p5contactHandler1(evt) { handleEvent(evt, 1, "p5") }
def p5contactHandler2(evt) { handleEvent(evt, 2, "p5") }
def p5contactHandler3(evt) { handleEvent(evt, 3, "p5") }
def p5contactHandler4(evt) { handleEvent(evt, 4, "p5") }
def p5contactHandler5(evt) { handleEvent(evt, 5, "p5") }
def p5contactHandler6(evt) { handleEvent(evt, 6, "p5") }
def p5contactHandler7(evt) { handleEvent(evt, 7, "p5") }
def p5contactHandler8(evt) { handleEvent(evt, 8, "p5") }
def p5contactHandler9(evt) { handleEvent(evt, 9, "p5") }
def p5contactHandler10(evt) { handleEvent(evt, 10, "p5") }
def p5contactHandler11(evt) { handleEvent(evt, 11, "p5") }
def p5contactHandler12(evt) { handleEvent(evt, 12, "p5") }
def p5contactHandler13(evt) { handleEvent(evt, 13, "p5") }
def p5contactHandler14(evt) { handleEvent(evt, 14, "p5") }
def p5contactHandler15(evt) { handleEvent(evt, 15, "p5") }
def p5contactHandler16(evt) { handleEvent(evt, 16, "p5") }
def p5contactHandler17(evt) { handleEvent(evt, 17, "p5") }
def p5contactHandler18(evt) { handleEvent(evt, 18, "p5") }
def p5contactHandler19(evt) { handleEvent(evt, 19, "p5") }
def p5contactHandler20(evt) { handleEvent(evt, 20, "p5") }
def p5contactHandler21(evt) { handleEvent(evt, 21, "p5") }
def p5contactHandler22(evt) { handleEvent(evt, 22, "p5") }
def p5contactHandler23(evt) { handleEvent(evt, 23, "p5") }
def p5contactHandler24(evt) { handleEvent(evt, 24, "p5") }
def p5contactHandler25(evt) { handleEvent(evt, 25, "p5") }
def p5contactHandler26(evt) { handleEvent(evt, 26, "p5") }
def p5contactHandler27(evt) { handleEvent(evt, 27, "p5") }
def p5contactHandler28(evt) { handleEvent(evt, 28, "p5") }
def p5contactHandler29(evt) { handleEvent(evt, 29, "p5") }
def p5contactHandler30(evt) { handleEvent(evt, 30, "p5") }
def p5contactHandler31(evt) { handleEvent(evt, 31, "p5") }
def p5contactHandler32(evt) { handleEvent(evt, 32, "p5") }
def p5contactHandler33(evt) { handleEvent(evt, 33, "p5") }
def p5contactHandler34(evt) { handleEvent(evt, 34, "p5") }
def p5contactHandler35(evt) { handleEvent(evt, 35, "p5") }
def p5contactHandler36(evt) { handleEvent(evt, 36, "p5") }
def p5contactHandler37(evt) { handleEvent(evt, 37, "p5") }
def p5contactHandler38(evt) { handleEvent(evt, 38, "p5") }
def p5contactHandler39(evt) { handleEvent(evt, 39, "p5") }
def p5contactHandler40(evt) { handleEvent(evt, 40, "p5") }
def p5contactHandler41(evt) { handleEvent(evt, 41, "p5") }
def p5contactHandler42(evt) { handleEvent(evt, 42, "p5") }
def p5contactHandler43(evt) { handleEvent(evt, 43, "p5") }
def p5contactHandler44(evt) { handleEvent(evt, 44, "p5") }
def p5contactHandler45(evt) { handleEvent(evt, 45, "p5") }
def p5contactHandler46(evt) { handleEvent(evt, 46, "p5") }
def p5contactHandler47(evt) { handleEvent(evt, 47, "p5") }
def p5contactHandler48(evt) { handleEvent(evt, 48, "p5") }
def p5contactHandler49(evt) { handleEvent(evt, 49, "p5") }
def p5waterHandler1(evt) { handleEvent(evt, 1, "p5") }
def p5waterHandler2(evt) { handleEvent(evt, 2, "p5") }
def p5waterHandler3(evt) { handleEvent(evt, 3, "p5") }
def p5waterHandler4(evt) { handleEvent(evt, 4, "p5") }
def p5waterHandler5(evt) { handleEvent(evt, 5, "p5") }
def p5waterHandler6(evt) { handleEvent(evt, 6, "p5") }
def p5waterHandler7(evt) { handleEvent(evt, 7, "p5") }
def p5waterHandler8(evt) { handleEvent(evt, 8, "p5") }
def p5waterHandler9(evt) { handleEvent(evt, 9, "p5") }
def p5waterHandler10(evt) { handleEvent(evt, 10, "p5") }
def p5waterHandler11(evt) { handleEvent(evt, 11, "p5") }
def p5waterHandler12(evt) { handleEvent(evt, 12, "p5") }
def p5waterHandler13(evt) { handleEvent(evt, 13, "p5") }
def p5waterHandler14(evt) { handleEvent(evt, 14, "p5") }
def p5waterHandler15(evt) { handleEvent(evt, 15, "p5") }
def p5waterHandler16(evt) { handleEvent(evt, 16, "p5") }
def p5waterHandler17(evt) { handleEvent(evt, 17, "p5") }
def p5waterHandler18(evt) { handleEvent(evt, 18, "p5") }
def p5waterHandler19(evt) { handleEvent(evt, 19, "p5") }
def p5waterHandler20(evt) { handleEvent(evt, 20, "p5") }
def p5waterHandler21(evt) { handleEvent(evt, 21, "p5") }
def p5waterHandler22(evt) { handleEvent(evt, 22, "p5") }
def p5waterHandler23(evt) { handleEvent(evt, 23, "p5") }
def p5waterHandler24(evt) { handleEvent(evt, 24, "p5") }
def p5waterHandler25(evt) { handleEvent(evt, 25, "p5") }
def p5waterHandler26(evt) { handleEvent(evt, 26, "p5") }
def p5waterHandler27(evt) { handleEvent(evt, 27, "p5") }
def p5waterHandler28(evt) { handleEvent(evt, 28, "p5") }
def p5waterHandler29(evt) { handleEvent(evt, 29, "p5") }
def p5waterHandler30(evt) { handleEvent(evt, 30, "p5") }
def p5waterHandler31(evt) { handleEvent(evt, 31, "p5") }
def p5waterHandler32(evt) { handleEvent(evt, 32, "p5") }
def p5waterHandler33(evt) { handleEvent(evt, 33, "p5") }
def p5waterHandler34(evt) { handleEvent(evt, 34, "p5") }
def p5waterHandler35(evt) { handleEvent(evt, 35, "p5") }
def p5waterHandler36(evt) { handleEvent(evt, 36, "p5") }
def p5waterHandler37(evt) { handleEvent(evt, 37, "p5") }
def p5waterHandler38(evt) { handleEvent(evt, 38, "p5") }
def p5waterHandler39(evt) { handleEvent(evt, 39, "p5") }
def p5waterHandler40(evt) { handleEvent(evt, 40, "p5") }
def p5waterHandler41(evt) { handleEvent(evt, 41, "p5") }
def p5waterHandler42(evt) { handleEvent(evt, 42, "p5") }
def p5waterHandler43(evt) { handleEvent(evt, 43, "p5") }
def p5waterHandler44(evt) { handleEvent(evt, 44, "p5") }
def p5waterHandler45(evt) { handleEvent(evt, 45, "p5") }
def p5waterHandler46(evt) { handleEvent(evt, 46, "p5") }
def p5waterHandler47(evt) { handleEvent(evt, 47, "p5") }
def p5waterHandler48(evt) { handleEvent(evt, 48, "p5") }
def p5waterHandler49(evt) { handleEvent(evt, 49, "p5") }
def p5smokeHandler1(evt) { handleEvent(evt, 1, "p5") }
def p5smokeHandler2(evt) { handleEvent(evt, 2, "p5") }
def p5smokeHandler3(evt) { handleEvent(evt, 3, "p5") }
def p5smokeHandler4(evt) { handleEvent(evt, 4, "p5") }
def p5smokeHandler5(evt) { handleEvent(evt, 5, "p5") }
def p5smokeHandler6(evt) { handleEvent(evt, 6, "p5") }
def p5smokeHandler7(evt) { handleEvent(evt, 7, "p5") }
def p5smokeHandler8(evt) { handleEvent(evt, 8, "p5") }
def p5smokeHandler9(evt) { handleEvent(evt, 9, "p5") }
def p5smokeHandler10(evt) { handleEvent(evt, 10, "p5") }
def p5smokeHandler11(evt) { handleEvent(evt, 11, "p5") }
def p5smokeHandler12(evt) { handleEvent(evt, 12, "p5") }
def p5smokeHandler13(evt) { handleEvent(evt, 13, "p5") }
def p5smokeHandler14(evt) { handleEvent(evt, 14, "p5") }
def p5smokeHandler15(evt) { handleEvent(evt, 15, "p5") }
def p5smokeHandler16(evt) { handleEvent(evt, 16, "p5") }
def p5smokeHandler17(evt) { handleEvent(evt, 17, "p5") }
def p5smokeHandler18(evt) { handleEvent(evt, 18, "p5") }
def p5smokeHandler19(evt) { handleEvent(evt, 19, "p5") }
def p5smokeHandler20(evt) { handleEvent(evt, 20, "p5") }
def p5smokeHandler21(evt) { handleEvent(evt, 21, "p5") }
def p5smokeHandler22(evt) { handleEvent(evt, 22, "p5") }
def p5smokeHandler23(evt) { handleEvent(evt, 23, "p5") }
def p5smokeHandler24(evt) { handleEvent(evt, 24, "p5") }
def p5smokeHandler25(evt) { handleEvent(evt, 25, "p5") }
def p5smokeHandler26(evt) { handleEvent(evt, 26, "p5") }
def p5smokeHandler27(evt) { handleEvent(evt, 27, "p5") }
def p5smokeHandler28(evt) { handleEvent(evt, 28, "p5") }
def p5smokeHandler29(evt) { handleEvent(evt, 29, "p5") }
def p5smokeHandler30(evt) { handleEvent(evt, 30, "p5") }
def p5smokeHandler31(evt) { handleEvent(evt, 31, "p5") }
def p5smokeHandler32(evt) { handleEvent(evt, 32, "p5") }
def p5smokeHandler33(evt) { handleEvent(evt, 33, "p5") }
def p5smokeHandler34(evt) { handleEvent(evt, 34, "p5") }
def p5smokeHandler35(evt) { handleEvent(evt, 35, "p5") }
def p5smokeHandler36(evt) { handleEvent(evt, 36, "p5") }
def p5smokeHandler37(evt) { handleEvent(evt, 37, "p5") }
def p5smokeHandler38(evt) { handleEvent(evt, 38, "p5") }
def p5smokeHandler39(evt) { handleEvent(evt, 39, "p5") }
def p5smokeHandler40(evt) { handleEvent(evt, 40, "p5") }
def p5smokeHandler41(evt) { handleEvent(evt, 41, "p5") }
def p5smokeHandler42(evt) { handleEvent(evt, 42, "p5") }
def p5smokeHandler43(evt) { handleEvent(evt, 43, "p5") }
def p5smokeHandler44(evt) { handleEvent(evt, 44, "p5") }
def p5smokeHandler45(evt) { handleEvent(evt, 45, "p5") }
def p5smokeHandler46(evt) { handleEvent(evt, 46, "p5") }
def p5smokeHandler47(evt) { handleEvent(evt, 47, "p5") }
def p5smokeHandler48(evt) { handleEvent(evt, 48, "p5") }
def p5smokeHandler49(evt) { handleEvent(evt, 49, "p5") }

// ── Shared handler logic ───────────────────────────────────────────────────────
def handleEvent(evt, int idx, String prefix) {
    infoLog "[SensorMonitor] ${prefix} slot ${idx} (${evt.displayName}): ${evt.value}"
    def ind = settings.indicatorDevice
    if (!ind) { infoLog "[SensorMonitor] ERROR — indicator device not set"; return }
    int pg = (prefix.replace("p","")) as int
    String t = settings["${prefix}sensorType${idx}"] ?: "motion"
    boolean active = (evt.value == "active" || evt.value == "open" || evt.value == "wet" || evt.value == "detected")
    try {
        if (active) ind."setPage${pg}MotionActive"(idx)
        else        ind."setPage${pg}MotionInactive"(idx)
    } catch (Exception e) {
        log.error "[SensorMonitor] ERROR calling indicator for ${prefix} slot ${idx}: ${e.message}"
    }
}

// ── Sync ───────────────────────────────────────────────────────────────────────
def syncAllSensors() {
    infoLog "[SensorMonitor] Syncing all sensor states"
    def ind = settings.indicatorDevice
    if (!ind) return
    int numPg2 = (settings.numberOfPages ?: "1") as int
    (1..numPg2).each { pg ->
        String prefix = "p${pg}"
        String grid   = settings["page${pg}GridLayout"] ?: "2x2"
        (1..maxSlots(grid)).each { idx ->
            def dev  = settings["${prefix}sensor${idx}"]
            String t = settings["${prefix}sensorType${idx}"] ?: "none"
            try {
                if (!dev || t == "none") {
                    ind."setPage${pg}SlotEmpty"(idx)
                    return
                }
                String attr = t == "contact" ? "contact" : t == "water" ? "water" : t == "smoke" ? "smoke" : "motion"
                String val  = dev.currentValue(attr) ?: (t == "contact" ? "closed" : t == "water" ? "dry" : t == "smoke" ? "clear" : "inactive")
                boolean active = (val == "active" || val == "open" || val == "wet" || val == "detected")
                if (active) ind."setPage${pg}MotionActive"(idx)
                else        ind."setPage${pg}MotionInactive"(idx)
            } catch (Exception e) {
                infoLog "[SensorMonitor] WARN sync ${prefix}:${idx}: ${e.message}"
            }
        }
    }
}

def displayRebootedHandler(evt) {
    // Skip if initialize() just ran (within 60s) — it already pushed layouts
    long msSince = now() - (state.lastInitMs ?: 0L)
    if (msSince < 60000) {
        infoLog "[SensorMonitor] displayRebooted skipped — initialize ran ${msSince}ms ago"
        return
    }
    infoLog "[SensorMonitor] SenseCAP rebooted — pushing all layouts and resyncing"
    def ind = settings.indicatorDevice; if (!ind) return
    try {
        int rPg = (settings.numberOfPages ?: "1") as int
        (1..rPg).each { pg -> ind."setPage${pg}GridLayout"(settings["page${pg}GridLayout"] ?: "2x2") }
        ind.pushAllLayouts(rPg)
    } catch (Exception e) { infoLog "[SensorMonitor] WARN reboot handler: ${e.message}" }
    runIn(28, syncAllSensors)
}

// ── Helpers ────────────────────────────────────────────────────────────────────
def maxSlots(String grid) {
    switch (grid) {
        case "1x1": return 1;  case "3x3": return 9;  case "4x4": return 16
        case "5x5": return 25; case "6x6": return 36; case "7x7": return 49
        default:    return 4
    }
}
def gridMaxChars(String grid) {
    switch (grid) {
        case "7x7": return 4; case "1x1": return 30; case "6x6": return 5
        case "5x5": return 6; case "4x4": return 7;  case "3x3": return 11
        default:    return 16
    }
}
def subscribedCount(String prefix) {
    String grid = settings["page${prefix.replace("p","")}GridLayout"] ?: "2x2"
    (1..maxSlots(grid)).count { settings["${prefix}sensor${it}"] != null && (settings["${prefix}sensorType${it}"] ?: "none") != "none" }
}
def stripEmoji(String text) {
    if (!text) return ""
    // Remove emoji and other non-ASCII, collapse whitespace, trim
    String result = text.replaceAll(/[^\x20-\x7E]/, " ").replaceAll(/\s+/, " ").trim()
    return result
}
def wrapLabel(String text, int maxChars) {
    if (!text || text.length() <= maxChars) return text ?: ""
    List<String> words = text.split(" ") as List
    List<String> lines = []
    String cur = ""
    words.each { w ->
        if (cur.isEmpty()) { cur = w }
        else if ((cur + " " + w).length() <= maxChars) { cur = cur + " " + w }
        else { lines << cur; cur = w }
    }
    if (cur) lines << cur
    return lines.join("\n")
}
def infoLog(String msg)  { if ((settings.logLevel ?: "1") != "0") log.info msg }
def debugLog(String msg) { if ((settings.logLevel ?: "1") == "2") log.debug msg }
