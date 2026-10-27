package com.fongmi.android.tv.api.config;

import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.github.catvod.utils.Util;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

// Local cache of downloaded remote config JSON, keyed by the config URL.
// Lives in the app's persistent files dir (not cache dir) so it survives restarts
// and lets the app load configs fully offline on the next launch.
final class ConfigCache {

    private static final String DIR = "config";
    private static final String PREFIX = "config_";
    private static final String SUFFIX = ".json";

    private ConfigCache() {
    }

    private static File dir() {
        File dir = new File(App.get().getFilesDir(), DIR);
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    static File file(String url) {
        return new File(dir(), PREFIX + Util.md5(url) + SUFFIX);
    }

    static boolean exists(String url) {
        if (TextUtils.isEmpty(url)) return false;
        File file = file(url);
        return file.exists() && file.length() > 0;
    }

    static String read(String url) {
        if (TextUtils.isEmpty(url)) return "";
        File file = file(url);
        if (!file.exists() || file.length() == 0) return "";
        try (FileInputStream is = new FileInputStream(file)) {
            ByteArrayOutputStream os = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = is.read(buffer)) != -1) os.write(buffer, 0, n);
            return os.toString("UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    static void write(String url, String data) {
        if (TextUtils.isEmpty(url) || TextUtils.isEmpty(data)) return;
        File target = file(url);
        File temp = new File(target.getParentFile(), target.getName() + ".tmp");
        try {
            try (FileOutputStream os = new FileOutputStream(temp)) {
                os.write(data.getBytes(StandardCharsets.UTF_8));
                os.flush();
            }
            if (target.exists()) target.delete();
            if (!temp.renameTo(target)) copy(temp, target);
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (temp.exists()) temp.delete();
        }
    }

    static void delete(String url) {
        if (TextUtils.isEmpty(url)) return;
        File file = file(url);
        if (file.exists()) file.delete();
    }

    private static void copy(File from, File to) {
        try (FileInputStream is = new FileInputStream(from); FileOutputStream os = new FileOutputStream(to)) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = is.read(buffer)) != -1) os.write(buffer, 0, n);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
