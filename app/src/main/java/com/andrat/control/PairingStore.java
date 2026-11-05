package com.andrat.control;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** Stores the local device capability key. It never leaves the device except in a command path. */
public final class PairingStore {
    private static final String PREFS = "andrat_control";
    private static final String KEY_DEVICE_ID = "device_id";
    private static final String KEY_CONTROL_TOKEN = "control_token";
    private static final String KEY_ENDPOINT = "database_endpoint";
    private static final String KEY_PROCESSED_COMMANDS = "processed_commands";
    private static final int MAX_PROCESSED_COMMANDS = 100;
    private static final String TOKEN_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";

    private final SharedPreferences preferences;

    public PairingStore(Context context) {
        preferences = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public String getDeviceId() {
        String value = preferences.getString(KEY_DEVICE_ID, null);
        if (value == null || value.trim().isEmpty()) {
            value = UUID.randomUUID().toString();
            preferences.edit().putString(KEY_DEVICE_ID, value).apply();
        }
        return value;
    }

    public String getControlToken() {
        String value = preferences.getString(KEY_CONTROL_TOKEN, null);
        if (value == null || value.trim().isEmpty()) {
            SecureRandom random = new SecureRandom();
            StringBuilder token = new StringBuilder(32);
            for (int i = 0; i < 32; i++) {
                token.append(TOKEN_ALPHABET.charAt(random.nextInt(TOKEN_ALPHABET.length())));
            }
            value = token.toString();
            preferences.edit().putString(KEY_CONTROL_TOKEN, value).apply();
        }
        return value;
    }

    public String getEndpoint() {
        return preferences.getString(KEY_ENDPOINT, "");
    }

    public void saveEndpoint(String endpoint) {
        preferences.edit().putString(KEY_ENDPOINT, ControlRepository.normalizeEndpoint(endpoint)).apply();
    }

    public synchronized boolean wasCommandProcessed(String commandId) {
        Set<String> processed = preferences.getStringSet(KEY_PROCESSED_COMMANDS, null);
        return processed != null && processed.contains(commandId);
    }

    public synchronized void markCommandProcessed(String commandId) {
        Set<String> stored = preferences.getStringSet(KEY_PROCESSED_COMMANDS, null);
        Set<String> processed = stored == null ? new HashSet<String>() : new HashSet<>(stored);
        processed.add(commandId);
        while (processed.size() > MAX_PROCESSED_COMMANDS) {
            Iterator<String> iterator = processed.iterator();
            if (!iterator.hasNext()) {
                break;
            }
            iterator.next();
            iterator.remove();
        }
        preferences.edit().putStringSet(KEY_PROCESSED_COMMANDS, processed).apply();
    }

    public String getDeviceName() {
        String manufacturer = Build.MANUFACTURER == null ? "Android" : Build.MANUFACTURER;
        String model = Build.MODEL == null ? "device" : Build.MODEL;
        String readable = (manufacturer + " " + model).trim();
        if (readable.isEmpty()) {
            return "Android device";
        }
        return readable.substring(0, 1).toUpperCase(Locale.US) + readable.substring(1);
    }
}
