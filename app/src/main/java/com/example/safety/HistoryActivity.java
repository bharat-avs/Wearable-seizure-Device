package com.example.safety;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;

public class HistoryActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_history);

        TextView tvHistoryLog = findViewById(R.id.tvHistoryLog);
        Button btnClear = findViewById(R.id.btnClear);
        Button btnBack = findViewById(R.id.btnBack);

        // 1. MUST EXACTLY MATCH "HealthRecords" AND "history_log"
        SharedPreferences prefs = getSharedPreferences("HealthRecords", MODE_PRIVATE);
        String history = prefs.getString("history_log", "");

        // 2. Display the log or the standby message
        if (history == null || history.trim().isEmpty()) {
            tvHistoryLog.setText("No emergency events recorded yet. System is standing by.");
        } else {
            tvHistoryLog.setText(history);
        }

        // 3. Clear History Button
        btnClear.setOnClickListener(v -> {
            prefs.edit().remove("history_log").apply();
            tvHistoryLog.setText("No emergency events recorded yet. System is standing by.");
        });

        // 4. Back Button
        btnBack.setOnClickListener(v -> finish());
    }
}