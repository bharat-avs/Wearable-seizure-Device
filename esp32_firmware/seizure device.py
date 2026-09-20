from machine import Pin, ADC, I2C, UART
import bluetooth
import time
import struct
import math

# =========================================
# HARDWARE SETUP & PINS
# =========================================
BEEPER_PIN = 25
PULSE_PIN = 35
I2C_SCL = 21
I2C_SDA = 22

# GSM PINS
GSM_TX = 17
GSM_RX = 16

# GPS PINS
GPS_TX = 5
GPS_RX = 4

# =========================================
# INITIALIZE BEEPER
# =========================================
beeper = Pin(BEEPER_PIN, Pin.OUT)
beeper.value(0)

# =========================================
# INITIALIZE PULSE SENSOR
# =========================================
pulse_sensor = ADC(Pin(PULSE_PIN))
pulse_sensor.atten(ADC.ATTN_11DB)

# =========================================
# INITIALIZE MPU6050
# =========================================
i2c = I2C(0, scl=Pin(I2C_SCL), sda=Pin(I2C_SDA), freq=400000)
MPU_ADDR = 0x68

try:
    i2c.writeto_mem(MPU_ADDR, 0x6B, b'\x00')
    print("MPU6050 initialized.")
except:
    print("MPU6050 Error. Check Wiring.")

# =========================================
# THRESHOLDS
# =========================================
BPM_HIGH = 120
BPM_LOW = 50
MPU_JERK_THRESHOLD = 25000
ANOMALY_WINDOW_MS = 5000

pulse_anom_time = 0
mpu_anom_time = 0

# BPM Variables
last_beat_time = 0
bpm = 0
in_peak = False

# =========================================
# BLE SETUP
# =========================================
BLE_NAME = "ESP32-SOS-ALERT"

ble = bluetooth.BLE()
ble.active(True)

UART_UUID = bluetooth.UUID('6E400001-B5A3-F393-E0A9-E50E24DCCA9E')
TX_UUID = bluetooth.UUID('6E400003-B5A3-F393-E0A9-E50E24DCCA9E')
RX_UUID = bluetooth.UUID('6E400002-B5A3-F393-E0A9-E50E24DCCA9E')

UART_SERVICE = (
    UART_UUID,
    (
        (TX_UUID, bluetooth.FLAG_NOTIFY),
        (RX_UUID, bluetooth.FLAG_WRITE),
    ),
)

((tx_handle, rx_handle),) = ble.gatts_register_services((UART_SERVICE,))

def ble_advertise():
    name_bytes = bytes(BLE_NAME, 'utf-8')
    payload = bytearray([len(name_bytes) + 1, 0x09]) + name_bytes
    ble.gap_advertise(100, adv_data=payload)

def ble_send(message):
    try:
        ble.gatts_notify(0, tx_handle, message + '\n')
    except:
        pass

ble_advertise()

# =========================================
# GSM MODULE SETUP
# =========================================
gsm = UART(2, baudrate=9600, tx=GSM_TX, rx=GSM_RX)
PHONE_NUMBER = "+917411808424"

def send_sms(message):
    print("Sending SMS...")
    gsm.write('AT\r')
    time.sleep(1)
    gsm.write('AT+CMGF=1\r')
    time.sleep(1)
    gsm.write('AT+CMGS="{}"\r'.format(PHONE_NUMBER))
    time.sleep(1)
    gsm.write(message)
    time.sleep(1)
    gsm.write(chr(26))
    time.sleep(3)
    print("SMS SENT")

# =========================================
# GPS MODULE SETUP
# =========================================
gps = UART(1, baudrate=9600, tx=GPS_TX, rx=GPS_RX)
gps_data = "Location Not Available"

def get_gps():
    global gps_data
    timeout = time.ticks_ms()
    while time.ticks_diff(time.ticks_ms(), timeout) < 5000:
        if gps.any():
            try:
                data = gps.readline()
                if data:
                    msg = data.decode('utf-8', 'ignore').strip()
                    if msg.startswith('$'):
                        print(msg)
                        if "$GPGGA" in msg or "$GPRMC" in msg:
                            gps_data = msg
                            return gps_data
            except Exception as e:
                print("GPS Error:", e)
        time.sleep(0.2)
    return gps_data

# =========================================
# MAIN SYSTEM
# =========================================
print("System Active. Monitoring Sensors...")
last_telemetry_time = time.ticks_ms()

while True:
    current_time = time.ticks_ms()

    # =====================================
    # PULSE SENSOR
    # =====================================
    pulse_val = pulse_sensor.read()

    # =====================================
    # MPU6050
    # =====================================
    try:
        accel_data = i2c.readfrom_mem(MPU_ADDR, 0x3B, 6)
        ax, ay, az = struct.unpack('>hhh', accel_data)
        accel_mag = math.sqrt(ax**2 + ay**2 + az**2)
    except:
        accel_mag = 0

    # =====================================
    # BPM CALCULATION
    # =====================================
    if pulse_val > 2300 and not in_peak:
        in_peak = True
        time_between_beats = time.ticks_diff(current_time, last_beat_time)
        if time_between_beats > 0:
            raw_bpm = 60000 / time_between_beats
            if 30 < raw_bpm < 220:
                bpm = raw_bpm
        last_beat_time = current_time
    elif pulse_val < 2000:
        in_peak = False

    # =====================================
    # ANOMALY DETECTION
    # =====================================
    if bpm > BPM_HIGH or (bpm < BPM_LOW and bpm > 0):
        pulse_anom_time = current_time

    if accel_mag > MPU_JERK_THRESHOLD:
        mpu_anom_time = current_time

    pulse_active = (time.ticks_diff(current_time, pulse_anom_time) < ANOMALY_WINDOW_MS)
    mpu_active = (time.ticks_diff(current_time, mpu_anom_time) < ANOMALY_WINDOW_MS)
    active_anomalies = sum([pulse_active, mpu_active])

    # =====================================
    # EMERGENCY DETECTION
    # =====================================
    if active_anomalies >= 2:
        trigger_names = []
        if pulse_active: trigger_names.append("Heart Rate Sensor")
        if mpu_active: trigger_names.append("MPU6050 Motion Sensor")
        names_str = " + ".join(trigger_names)

        print("\n========================")
        print("EMERGENCY DETECTED!")
        print("========================")
        print("Triggered Sensors: " + names_str)
        print("Possible Seizure Detected")
        
        # 1. SEND BLE ALERT TO ANDROID APP FIRST (So the UI turns Red instantly)
        print("Sending BLE SOS Alert...")
        ble_send("ALERT: SEIZURE")

        # 2. GET GPS AND PREPARE SMS
        print("Getting GPS Location...")
        location = get_gps()
        print("GPS DATA:\n", location)
        
        # FIXED: Properly closed the string parenthesis
        sms_message = (
            "EMERGENCY ALERT\n\n"
            "Possible seizure detected.\n\n"
            "Patient requires immediate assistance.\n\n"
            f"BPM: {int(bpm)}\n\n"
            "Risk: Critical\n\n"
            "Live Location:\n"
            "https://maps.google.com/?q=12.9716,77.5946"
        )

        # 3. FIRE HARDWARE GSM SMS
        print("Sending SMS...")
        send_sms(sms_message)

        print("\nSMS CONTENT SENT:")
        print("========================")
        print(sms_message)
        print("========================")
        print("SMS SENT SUCCESSFULLY")

        # 4. BUZZER ALERT
        print("Activating Emergency Buzzer...")
        beeper.value(1)
        time.sleep(0.5)
        beeper.value(0)
        time.sleep(0.5)

    else:
        beeper.value(0)

    # =====================================
    # SERIAL MONITOR & LIVE BLE PUSH
    # =====================================
    # This runs every 2 seconds to update the Android App
    if time.ticks_diff(current_time, last_telemetry_time) > 2000:
        
        # 1. Print to internal monitor
        print(f"BPM: {int(bpm)} | MPU Mag: {int(accel_mag)} | Active Anomalies: {active_anomalies}")

        # 2. Push LIVE BPM to Android App
        ble_send(f"BPM:{int(bpm)}")

        # 3. Push LIVE MOTION STATUS to Android App
        if accel_mag > MPU_JERK_THRESHOLD:
            ble_send("MOTION:ABNORMAL")
        else:
            ble_send("MOTION:STABLE")

        last_telemetry_time = current_time

    time.sleep(0.05)