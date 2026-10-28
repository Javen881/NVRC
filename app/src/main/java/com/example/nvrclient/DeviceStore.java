package com.example.nvrclient;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONArray;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Encrypt the complete configuration, including embedded device addresses. */
final class DeviceStore {
    private final Context context;
    DeviceStore(Context context) { this.context = context; }
    private SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        String alias = "nvr-device-config";
        if (!store.containsAlias(alias)) {
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
            generator.generateKey();
        }
        return (SecretKey) store.getKey(alias, null);
    }
    List<Device> load() throws Exception {
        String data = context.getSharedPreferences("devices", 0).getString("encrypted", "");
        List<Device> result = new ArrayList<>();
        if (data.isEmpty()) return result;
        String[] parts = data.split(":", 2);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
        JSONArray array = new JSONArray(new String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), StandardCharsets.UTF_8));
        for (int i = 0; i < array.length(); i++) result.add(Device.from(array.getJSONObject(i)));
        return result;
    }
    void save(List<Device> devices) throws Exception {
        JSONArray array = new JSONArray(); for (Device d : devices) array.put(d.json());
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key());
        String value = Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + ":"
            + Base64.encodeToString(cipher.doFinal(array.toString().getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
        if (!context.getSharedPreferences("devices", 0).edit().putString("encrypted", value).commit())
            throw new IllegalStateException("保存设备失败");
    }
}
