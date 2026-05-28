# SenseCAP Sensor Monitor for Hubitat

### This project allows you to display either a 2x2, 3x3, or 4x4 grid of sensors from a Hubitat hub onto a SenseCAP Indicator D1 configured with OpenHASP firmware.

## Requirements

- Hubitat Elevation hub
- SenseCAP Indicator D1 (480×480, running openHASP 0.7+)
- MQTT broker (Hubitat's built-in broker works)

### Here are the brief instructions:
  - Hubitat - install and configure MQTT Export Integration
  - Hubitat - copy driver code from github
  - Hubitat - copy app code from github
  - SenseCAP - update with OpenHASP and connect to WiFi
  - SenseCAP - connect via serial port
  - SenseCAP - connect via WiFi and configure it
  - Hubitat - create a virtual device using the driver
  - Hubitat - Install and configure app

## Install and configure MQTT Export Integration
  - On Hubitat hub, Select Integrations / Add Built-in integration
  - Select MQTT Export Integration (beta)
  - Open Integration and select Use built-in MQTT service at _hubs ip address here_:1882
  - Save the login information displayed. It will be hubitat / _16 character password_
  - Select Done

## Copy driver code from github
  - On github, choose the _sensecap-sensor-driver.groovy_ file, click on **Raw** and copy the entire text
  - On the Hubitat hub, expand the **FOR DEVELOPERS** menu and select **Drivers Code**
  - Select **+ Add driver** and in the New driver, paste the contents of the copied text
  - Select **Save**

## Copy app code from github
  - On github, choose the _sensecap-sensor-app.groovy_ file, click on **Raw** and copy the entire text
  - On the Hubitat hub, select **Apps code**
  - Select **+ Add app** and in the New app, paste the contents of the copied text
  - Select **Save**
    
## Update SenseCAP with OpenHASP and connect to WiFi
  - Using Chrome browser, go to [openhasp](https://nightly.openhasp.com/), choose SenseCap Indicator D1, and select **INSTALL**
  - Choose the USB Serial option and select **Connect**. Choose **INSTALL OPENHASP**. Select _Erase device_ checkbox\. Select **NEXT**, then **INSTALL**
  - After a couple of minutes,the SenseCAP will display a screen that shows some instructions to connect to it either via WiFi, or as an Access Point. Tap the screen to connect it to your WiFi
  - Enter the Ssid: and Password: and the the check mark on the bottom right
  - If correct, the SenseCAP will reboot and show the current name **_plate_** and IP address of the device. You will need the IP address later, so document it. It is also a good idea to give it a DHCP reservation so the address doesn't change

## Connecting to SenseCAP via serial port:
  - Use a terminal program to connect to the SenseCAP. I use a MAC so the terminal program I used is Serial Port Utility (SPU) available on the Apple Store
  - Set the baud rate to 115200. You should see two ports (among others), one is usbmodem, the other usbserial. Select the usbserial port

## Connect to SenseCAP via WiFi and configure it:
  - In a browser, go to the IP address displayed on the SenseCAP
  - Choose **HASP Design**, and select _Material Dark_ for the **UI Theme**
  - Select **Save Settings**
  - Choose **Time Settings** and select your **Region** and **Timezone** and **Save Settings**
  - Choose **MQTT Settings**. You can rename the device by changing the _Hostname*_ field.
  - Broker: Enter the IP address of the Hubitat hub.
  - Username: hubitat
  - Password: _16 character password_
  - Leave everything else the same
  - Select **Save**
  - Select **Main Menu**
  - Select **File Editor**
  - Delete the all the files _**EXCEPT**_ **{}config json** 
  - Select **Save**, then **Home**
  - Select **Restart** to load the new image
  - You should see some activity on the serial port connection
  - 
## Install and Configure the device
  - On the hubitat hub, select **Devices**, then **+ Add device**
  - Select **Virtual**, and choose _SenseCAP Indicator Motion_
  - Select **Next**, give it a name, select **Next**, select a room if so inclined, or **Skip >>** if not.
  - Select **View Device Details**, then **Preferences**.
  - Enter _hubitat_ for the **MQTT Username**
  - Enter the password from the MQTT app for **MQTT Password**
  - Change the **openHASP Node Name** if you changed it on the SenseCAP
  - Choose the **Active color** from the list
  - For each sensor type choose the **Inactive color**
  - Modify any of the other fields as required and select **Save**
  - Select **Save** and then select **Commands**
  - Choose the **Set Grid Layout** that corresponds to the layout you chose
  - Select **Reconnect Mqtt**
  - You should see _Connected_ on the **Mqtt Status** and on the serial port connection, messages like _MQTT RCV: p1b1.bg_color = #008000_

## Install and Configure the app
  - On the Hubitat hub, select **Apps** and then **+ Add user app**
  - Choose _SenseCAP Indicator Motion_
  - Choose the Device you created on the **Select your SenseCAP Indicator device** and select **Update**
  - Choose the **Grid Layout** you want
  - For each slot, choose the **Sensor Type**, the device you wnat to use, and the **Label**
  - Select **Done**

# You should now see motion on the SenseCAP






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
