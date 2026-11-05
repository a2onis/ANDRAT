package com.andrat.control;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

public final class MainActivity extends Activity {
    private static final int REQUEST_CAMERA = 4101;
    private static final int REQUEST_NOTIFICATIONS = 4102;

    private PairingStore store;
    private TextView connectionState;
    private TextView lastSync;
    private TextView permissionStatus;
    private EditText endpointInput;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        store = new PairingStore(this);
        bindViews();
        renderIdentity();
        endpointInput.setText(store.getEndpoint());
        bindActions();
        renderPermissions();
    }

    @Override
    protected void onResume() {
        super.onResume();
        renderServiceState();
        renderPermissions();
    }

    private void bindViews() {
        connectionState = findViewById(R.id.txtConnectionState);
        lastSync = findViewById(R.id.txtLastSync);
        permissionStatus = findViewById(R.id.txtPermissionStatus);
        endpointInput = findViewById(R.id.editDatabaseUrl);
    }

    private void bindActions() {
        Button save = findViewById(R.id.btnSaveConnection);
        save.setOnClickListener(view -> saveEndpoint());

        findViewById(R.id.btnStartService).setOnClickListener(view -> startControlService());
        findViewById(R.id.btnStopService).setOnClickListener(view -> stopControlService());
        findViewById(R.id.btnCopyId).setOnClickListener(view -> copy("Device ID", store.getDeviceId()));
        findViewById(R.id.btnCopyToken).setOnClickListener(view -> copy("Control token", store.getControlToken()));
        findViewById(R.id.btnCameraPermission).setOnClickListener(view -> requestCameraPermission());
        findViewById(R.id.btnNotificationPermission).setOnClickListener(view -> requestNotificationPermission());
    }

    private void renderIdentity() {
        TextView deviceId = findViewById(R.id.txtDeviceId);
        TextView token = findViewById(R.id.txtControlToken);
        deviceId.setText("Device ID\n" + store.getDeviceId());
        token.setText("Control token\n" + store.getControlToken());
    }

    private void saveEndpoint() {
        String endpoint = ControlRepository.normalizeEndpoint(endpointInput.getText().toString());
        if (endpoint.isEmpty() || !endpoint.startsWith("https://")) {
            endpointInput.setError("Enter your Realtime Database URL");
            return;
        }
        store.saveEndpoint(endpoint);
        Toast.makeText(this, "Endpoint saved", Toast.LENGTH_SHORT).show();
        renderServiceState();
    }

    private void startControlService() {
        if (store.getEndpoint().isEmpty()) {
            endpointInput.setError("Save an endpoint first");
            endpointInput.requestFocus();
            return;
        }
        Intent intent = new Intent(this, ControlService.class).setAction(ControlService.ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        Toast.makeText(this, "Control connection started", Toast.LENGTH_SHORT).show();
        renderServiceState();
    }

    private void stopControlService() {
        stopService(new Intent(this, ControlService.class).setAction(ControlService.ACTION_STOP));
        Toast.makeText(this, "Control connection stopped", Toast.LENGTH_SHORT).show();
        renderServiceState();
    }

    private void renderServiceState() {
        boolean configured = !store.getEndpoint().isEmpty();
        connectionState.setText(configured ? "READY" : "SETUP");
        lastSync.setText(configured
                ? "Save the endpoint, then start the visible connection service."
                : "Add the Firebase endpoint to connect this device.");
    }

    private void renderPermissions() {
        boolean camera = Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                || checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
        boolean notices = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
        permissionStatus.setText("Flashlight: " + (camera ? "allowed" : "not allowed")
                + "  •  Notices: " + (notices ? "allowed" : "not allowed"));
    }

    private void requestCameraPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA);
        } else {
            Toast.makeText(this, "Flashlight permission is already allowed", Toast.LENGTH_SHORT).show();
        }
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS);
        } else {
            Toast.makeText(this, "Notice permission is already allowed", Toast.LENGTH_SHORT).show();
        }
    }

    private void copy(String label, String value) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText(label, value));
            Toast.makeText(this, label + " copied", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        renderPermissions();
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Permission allowed", Toast.LENGTH_SHORT).show();
        }
    }
}
