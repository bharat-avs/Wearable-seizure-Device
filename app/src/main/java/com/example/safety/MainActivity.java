package com.example.safety;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothProfile;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.telephony.SmsManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;
import androidx.core.app.ActivityCompat;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.material.navigation.NavigationView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;

public class MainActivity extends AppCompatActivity {

    // =========================================
    // ESP32 BLE MAC ADDRESS (Verify this matches!)
    // =========================================
    private static final String ESP32_MAC_ADDRESS = "1C:C3:AB:A0:73:72";

    BluetoothGatt bluetoothGatt;
    FusedLocationProviderClient fusedLocationClient;

    DrawerLayout drawerLayout;
    NavigationView navigationView;
    TextView tvStatus, tvSubStatus, tvMotion, tvRisk, tvConnection, tvBpm;
    ProgressBar progressRisk;
    CardView statusCard;
    Button btnConnect, btnStart, btnEmergency, btnSaveNumber;
    EditText etEmergencyNumber;

    // Prevent double SMS sending
    private boolean isEmergencyActive = false;

    // =========================================
    // SAVE HEALTH RECORD (FIXED FOR HACKATHON)
    // =========================================
    private void saveHealthRecord(String status, String bpm, String risk) {
        SharedPreferences sharedPreferences = getSharedPreferences("HealthRecords", MODE_PRIVATE);

        // 1. Get the existing history log (or blank if it's the first time)
        String existingHistory = sharedPreferences.getString("history_log", "");

        // 2. Create a precise timestamp (includes Hours, Mins, Seconds)
        String timestamp = new java.text.SimpleDateFormat("MMM dd, yyyy - hh:mm:ss a", java.util.Locale.getDefault()).format(new java.util.Date());

        // 3. Format the new entry perfectly
        String newEntry = "🕒 " + timestamp + "\n" +
                "⚠️ Status: " + status + "\n" +
                "❤️ BPM: " + bpm + " | 🚨 Risk: " + risk + "\n" +
                "-----------------------------------\n";

        // 4. Prepend the new entry to the top of the old history and save it
        sharedPreferences.edit().putString("history_log", newEntry + existingHistory).apply();
    }

    @SuppressLint("MissingPermission")
    private void sendEmergencySMS() {
        fusedLocationClient.getLastLocation().addOnSuccessListener(location -> {
            SharedPreferences sharedPreferences = getSharedPreferences("HealthRecords", MODE_PRIVATE);
            String savedNumber = sharedPreferences.getString("emergency_number", "");

            if (savedNumber.isEmpty()) {
                Toast.makeText(MainActivity.this, "No Emergency Contact Saved", Toast.LENGTH_SHORT).show();
                return;
            }

            String message = "EMERGENCY ALERT\n\nPossible seizure detected.\nPatient requires immediate assistance.\n\nBPM: "
                    + tvBpm.getText().toString() + "\nRisk: Critical";

            if (location != null) {
                String mapsLink = "https://maps.google.com/?q=" + location.getLatitude() + "," + location.getLongitude();
                message += "\n\nLive Location:\n" + mapsLink;
            } else {
                message += "\n\n(GPS Location unavailable)";
            }

            try {
                SmsManager smsManager = SmsManager.getDefault();

                // ---------------------------------------------------------
                // THE HACKATHON FIX: Divide the massive message into parts
                // ---------------------------------------------------------
                java.util.ArrayList<String> parts = smsManager.divideMessage(message);
                smsManager.sendMultipartTextMessage(savedNumber, null, parts, null, null);

                Toast.makeText(MainActivity.this, "Emergency SMS Sent to " + savedNumber, Toast.LENGTH_LONG).show();
            } catch (Exception e) {
                e.printStackTrace();
                Toast.makeText(MainActivity.this, "Failed to send SMS. Check Permissions.", Toast.LENGTH_SHORT).show();
            }
        });
    }

    // =========================================
    // TRIGGER EMERGENCY (Called by BLE or Button)
    // =========================================
    private void triggerEmergency() {
        if (isEmergencyActive) return; // Prevent spamming
        isEmergencyActive = true;

        runOnUiThread(() -> {
            tvStatus.setText("SEIZURE DETECTED");
            tvStatus.setTextColor(0xFFFF4444); // Red
            tvSubStatus.setText("Emergency medical response triggered");
            tvMotion.setText("ABNORMAL");
            tvMotion.setTextColor(0xFFFF4444);
            tvRisk.setText("CRITICAL RISK");
            tvRisk.setTextColor(0xFFFF4444);
            progressRisk.setProgress(95);

            saveHealthRecord("Seizure Detected", tvBpm.getText().toString(), "Critical");
            sendEmergencySMS();
            Toast.makeText(MainActivity.this, "Emergency Alert Activated", Toast.LENGTH_LONG).show();
        });
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);

        ActivityCompat.requestPermissions(this, new String[]{
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.SEND_SMS
        }, 1);

        drawerLayout = findViewById(R.id.drawerLayout);
        navigationView = findViewById(R.id.navigationView);
        tvStatus = findViewById(R.id.tvStatus);
        tvSubStatus = findViewById(R.id.tvSubStatus);
        tvMotion = findViewById(R.id.tvMotion);
        tvRisk = findViewById(R.id.tvRisk);
        tvConnection = findViewById(R.id.tvConnection);
        tvBpm = findViewById(R.id.tvBpm);
        progressRisk = findViewById(R.id.progressRisk);
        statusCard = findViewById(R.id.statusCard);
        btnConnect = findViewById(R.id.btnConnect);
        btnStart = findViewById(R.id.btnStart);
        btnEmergency = findViewById(R.id.btnEmergency);
        etEmergencyNumber = findViewById(R.id.etEmergencyNumber);
        btnSaveNumber = findViewById(R.id.btnSaveNumber);

        // Load saved number on startup
        SharedPreferences prefs = getSharedPreferences("HealthRecords", MODE_PRIVATE);
        etEmergencyNumber.setText(prefs.getString("emergency_number", ""));

        btnSaveNumber.setOnClickListener(v -> {
            String number = etEmergencyNumber.getText().toString().trim();
            if (number.isEmpty()) {
                Toast.makeText(MainActivity.this, "Enter valid number", Toast.LENGTH_SHORT).show();
                return;
            }
            prefs.edit().putString("emergency_number", number).apply();
            Toast.makeText(MainActivity.this, "Emergency Contact Saved", Toast.LENGTH_SHORT).show();
        });

        findViewById(R.id.menuButton).setOnClickListener(v -> drawerLayout.openDrawer(GravityCompat.START));

        navigationView.setNavigationItemSelectedListener(item -> {
            int id = item.getItemId();
            if (id == R.id.nav_dashboard) {
                drawerLayout.closeDrawer(GravityCompat.START);
                return true;
            } else if (id == R.id.nav_history) {
                startActivity(new Intent(MainActivity.this, HistoryActivity.class));
                drawerLayout.closeDrawer(GravityCompat.START);
                return true;
            }
            return false;
        });

        btnConnect.setOnClickListener(v -> {
            BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
            if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
                tvConnection.setText("Bluetooth Disabled");
                Toast.makeText(MainActivity.this, "Enable Bluetooth First", Toast.LENGTH_SHORT).show();
                return;
            }
            try {
                BluetoothDevice device = bluetoothAdapter.getRemoteDevice(ESP32_MAC_ADDRESS);
                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return;
                bluetoothGatt = device.connectGatt(this, false, gattCallback);
                tvConnection.setText("Connecting to ESP32...");
            } catch (Exception e) {
                e.printStackTrace();
                Toast.makeText(MainActivity.this, "Connection Failed", Toast.LENGTH_SHORT).show();
            }
        });

        btnStart.setOnClickListener(v -> {
            isEmergencyActive = false; // Reset emergency block
            tvStatus.setText("PATIENT STABLE");
            tvStatus.setTextColor(0xFFFFFFFF); // White or your default color
            tvSubStatus.setText("Monitoring live health vitals...");
            tvMotion.setText("STABLE");
            tvMotion.setTextColor(0xFFFFFFFF);
            tvRisk.setText("LOW RISK");
            tvRisk.setTextColor(0xFFFFFFFF);
            progressRisk.setProgress(20);
            tvBpm.setText("78");
            Toast.makeText(MainActivity.this, "Live Monitoring Started", Toast.LENGTH_SHORT).show();
        });

        btnEmergency.setOnClickListener(v -> triggerEmergency());
    }

    // =========================================
    // BLE CALLBACK (FIXED)
    // =========================================
    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                runOnUiThread(() -> {
                    tvConnection.setText("BLE + GSM Connected");
                    Toast.makeText(MainActivity.this, "ESP32 Connected", Toast.LENGTH_SHORT).show();
                });
                gatt.discoverServices(); // This was correct
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                runOnUiThread(() -> tvConnection.setText("Disconnected"));
            }
        }

        // ADDED: Actually subscribe to the data
        @SuppressLint("MissingPermission")
        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                UUID UART_SERVICE = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E");
                UUID TX_CHAR = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E");

                if (gatt.getService(UART_SERVICE) != null) {
                    BluetoothGattCharacteristic tx = gatt.getService(UART_SERVICE).getCharacteristic(TX_CHAR);
                    gatt.setCharacteristicNotification(tx, true);
                }
            }
        }

        // ADDED: Actually Read the Live Data
        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            String message = new String(characteristic.getValue()).trim();

            runOnUiThread(() -> {
                // 1. Check for Seizure SOS
                if (message.contains("ALERT") || message.contains("SEIZURE")) {
                    triggerEmergency();
                }
                // 2. Parse Live BPM (e.g., if ESP32 sends "BPM:85")
                else if (message.startsWith("BPM:")) {
                    String bpmValue = message.substring(4);
                    tvBpm.setText(bpmValue);
                }
                // 3. Parse Live Motion (e.g., if ESP32 sends "MOTION:TREMOR")
                else if (message.startsWith("MOTION:")) {
                    String motionValue = message.substring(7);
                    tvMotion.setText(motionValue);
                }
            });
        }
    };
}