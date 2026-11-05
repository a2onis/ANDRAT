package com.andrat.control;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.os.Build;
import android.os.IBinder;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Visible, user-started connection service. It only handles the small set of explicit demo commands
 * documented in the panel; it has no file, contact, SMS, accessibility, or screen-capture access.
 */
public final class ControlService extends Service {
    public static final String ACTION_START = "com.andrat.control.START";
    public static final String ACTION_STOP = "com.andrat.control.STOP";

    private static final String CHANNEL_ID = "andrat_control_connection";
    private static final int NOTIFICATION_ID = 1101;
    private static final long POLL_INTERVAL_SECONDS = 3L;
    private static final long STATUS_INTERVAL_MS = 12_000L;

    private PairingStore store;
    private ControlRepository repository;
    private ScheduledExecutorService scheduler;
    private volatile long lastStatusAt;

    @Override
    public void onCreate() {
        super.onCreate();
        store = new PairingStore(this);
        repository = new ControlRepository();
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildForegroundNotification());
        scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                pollOnce();
            }
        }, 0L, POLL_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    @Override
    public int onStartCommand(android.content.Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_NOT_STICKY;
    }

    private void pollOnce() {
        String endpoint = store.getEndpoint();
        if (endpoint.isEmpty()) {
            return;
        }
        String deviceId = store.getDeviceId();
        String token = store.getControlToken();
        try {
            long now = System.currentTimeMillis();
            if (now - lastStatusAt >= STATUS_INTERVAL_MS) {
                repository.writeStatus(endpoint, deviceId, buildStatus(now, true, null));
                lastStatusAt = now;
            }

            List<ControlRepository.RemoteCommand> commands =
                    repository.readCommands(endpoint, deviceId, token);
            for (ControlRepository.RemoteCommand command : commands) {
                handleCommand(endpoint, deviceId, token, command);
            }
        } catch (Exception error) {
            // Keep the foreground connection alive; the panel will show the stale/error state.
            try {
                long now = System.currentTimeMillis();
                if (now - lastStatusAt >= STATUS_INTERVAL_MS) {
                    repository.writeStatus(endpoint, deviceId, buildStatus(now, false,
                            error.getMessage()));
                    lastStatusAt = now;
                }
            } catch (Exception ignored) {
                // A network failure should not take down the service.
            }
        }
    }

    private void handleCommand(String endpoint, String deviceId, String token,
                               ControlRepository.RemoteCommand command) {
        if (store.wasCommandProcessed(command.id)) {
            acknowledge(endpoint, deviceId, token, command, true, "Command already processed");
            return;
        }

        boolean ok = false;
        String message;
        try {
            if ("ping".equals(command.type)) {
                ok = true;
                message = "Device is reachable";
            } else if ("vibrate".equals(command.type)) {
                int duration = clamp(command.payload.optInt("durationMs", 350), 50, 2000);
                Vibrator vibrator = getVibrator();
                if (vibrator == null || !vibrator.hasVibrator()) {
                    message = "This device has no vibrator";
                } else {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        vibrator.vibrate(VibrationEffect.createOneShot(duration,
                                VibrationEffect.DEFAULT_AMPLITUDE));
                    } else {
                        vibrator.vibrate(duration);
                    }
                    ok = true;
                    message = "Vibrated for " + duration + " ms";
                }
            } else if ("flashlight".equals(command.type)) {
                boolean enabled = command.payload.optBoolean("enabled", false);
                message = setFlashlight(enabled);
                ok = message.startsWith("Flashlight ");
            } else if ("notify".equals(command.type)) {
                String text = command.payload.optString("message", "Remote panel notice").trim();
                if (text.isEmpty()) {
                    text = "Remote panel notice";
                }
                if (text.length() > 200) {
                    text = text.substring(0, 200);
                }
                message = showRemoteNotice(text);
                ok = message.startsWith("Notice shown");
            } else if ("status".equals(command.type)) {
                repository.writeStatus(endpoint, deviceId,
                        buildStatus(System.currentTimeMillis(), true, null));
                ok = true;
                message = "Status refreshed";
            } else {
                message = "Unknown command";
            }
        } catch (Exception error) {
            message = error.getMessage() == null ? "Command failed" : error.getMessage();
        }

        store.markCommandProcessed(command.id);
        acknowledge(endpoint, deviceId, token, command, ok, message);
    }

    private void acknowledge(String endpoint, String deviceId, String token,
                             ControlRepository.RemoteCommand command, boolean ok, String message) {
        try {
            JSONObject ack = new JSONObject();
            ack.put("commandId", command.id);
            ack.put("type", command.type);
            ack.put("ok", ok);
            ack.put("message", message);
            ack.put("completedAt", System.currentTimeMillis());
            repository.writeAck(endpoint, deviceId, token, command.id, ack);
            repository.deleteCommand(endpoint, deviceId, token, command.id);
        } catch (Exception ignored) {
            // The next poll can retry the acknowledgement without repeating the action.
        }
    }

    private Vibrator getVibrator() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            VibratorManager manager = (VibratorManager) getSystemService(VIBRATOR_MANAGER_SERVICE);
            return manager == null ? null : manager.getDefaultVibrator();
        }
        return (Vibrator) getSystemService(VIBRATOR_SERVICE);
    }

    private String setFlashlight(boolean enabled) throws Exception {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return "Flashlight is not supported on this Android version";
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            return "Camera permission is required for the flashlight";
        }
        CameraManager manager = (CameraManager) getSystemService(CAMERA_SERVICE);
        if (manager == null) {
            return "Camera service is unavailable";
        }
        for (String cameraId : manager.getCameraIdList()) {
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
            Boolean hasFlash = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
            Integer lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING);
            if (Boolean.TRUE.equals(hasFlash)
                    && (lensFacing == null || lensFacing == CameraCharacteristics.LENS_FACING_BACK)) {
                manager.setTorchMode(cameraId, enabled);
                return "Flashlight " + (enabled ? "on" : "off");
            }
        }
        return "No flashlight is available";
    }

    private String showRemoteNotice(String text) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            return "Notification permission is required";
        }
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager == null) {
            return "Notification service is unavailable";
        }
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        builder.setSmallIcon(com.andrat.control.R.drawable.ic_stat_control)
                .setContentTitle("Andrat Control notice")
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_MESSAGE)
                .setPriority(Notification.PRIORITY_DEFAULT);
        manager.notify((int) (System.currentTimeMillis() & 0x7fffffff), builder.build());
        return "Notice shown on this device";
    }

    private JSONObject buildStatus(long now, boolean online, String error) throws JSONException {
        JSONObject status = new JSONObject();
        status.put("deviceId", store.getDeviceId());
        status.put("deviceName", store.getDeviceName());
        status.put("appVersion", "1.0.0");
        status.put("online", online);
        status.put("lastSeen", now);
        status.put("supports", "ping,vibrate,flashlight,notify,status");
        status.put("cameraPermission", checkSelfPermission(Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED);
        status.put("notificationPermission", Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED);
        if (error != null && !error.trim().isEmpty()) {
            status.put("lastError", error.length() > 160 ? error.substring(0, 160) : error);
        }
        return status;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.service_channel_name),
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription(getString(R.string.service_channel_description));
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildForegroundNotification() {
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return builder.setSmallIcon(R.drawable.ic_stat_control)
                .setContentTitle(getString(R.string.service_notification_title))
                .setContentText(getString(R.string.service_notification_text))
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setPriority(Notification.PRIORITY_LOW)
                .build();
    }

    @Override
    public void onDestroy() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        String endpoint = store == null ? "" : store.getEndpoint();
        if (!endpoint.isEmpty() && store != null && repository != null) {
            final String deviceId = store.getDeviceId();
            Executors.newSingleThreadExecutor().execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        repository.writeStatus(endpoint, deviceId,
                                buildStatus(System.currentTimeMillis(), false, "Stopped by user"));
                    } catch (Exception ignored) {
                        // Best effort only during shutdown.
                    }
                }
            });
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(android.content.Intent intent) {
        return null;
    }
}
