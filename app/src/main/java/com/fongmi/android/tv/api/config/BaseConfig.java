package com.fongmi.android.tv.api.config;

import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.Decoder;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.event.ConfigEvent;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.server.Server;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.Task;
import com.fongmi.android.tv.utils.UrlUtil;
import com.github.catvod.bean.Header;
import com.github.catvod.bean.Proxy;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Json;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.InterruptedIOException;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

abstract class BaseConfig {

    public static final int VOD = 0;
    public static final int LIVE = 1;
    public static final int WALL = 2;

    private static final ThreadLocal<Boolean> FROM_CACHE = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> FORCE_REMOTE = new ThreadLocal<>();
    private static final ThreadLocal<String> FALLBACK_JSON = new ThreadLocal<>();

    private final AtomicInteger taskId = new AtomicInteger(0);
    private final AtomicBoolean refreshing = new AtomicBoolean(false);

    protected boolean sync;
    protected volatile Config config;
    private volatile Future<?> future;

    protected abstract String getTag();

    protected abstract int getType();

    protected abstract Config defaultConfig();

    protected abstract void load(Config config) throws Throwable;

    protected abstract boolean isLoaded();

    // Built-in / default config source URL. May be a bundled asset (assets://vod.json) or a
    // remote URL (the app's pre-configured default). Empty means no built-in source.
    protected String builtInUrl() {
        return "";
    }

    protected boolean hasBuiltIn() {
        return !TextUtils.isEmpty(builtInUrl());
    }

    // Bundled (assets) copy of the built-in config, used when the remote built-in source is
    // unreachable (offline first launch). Empty means no bundled fallback.
    protected String fallbackUrl() {
        return "";
    }

    protected boolean hasFallback() {
        return !TextUtils.isEmpty(fallbackUrl());
    }

    protected Config builtInConfig() {
        return Config.find(builtInUrl(), getType());
    }

    // Asset-aware, cache-aware JSON fetch.
    // - asset(s)://  -> read the bundled JSON directly (offline)
    // - otherwise    -> prefer the local cache; fall back to a network fetch and cache the result
    protected String getJson(Config config, String tag) throws Exception {
        // Bundled fallback content already prepared by loadFallback(): use it as-is.
        String fallback = FALLBACK_JSON.get();
        if (fallback != null) {
            FALLBACK_JSON.remove();
            return fallback;
        }
        String url = config.getUrl();
        if (TextUtils.isEmpty(url)) throw new Exception("Config url is empty");
        if (Decoder.isAsset(url)) return Decoder.getJsonFromAsset(url, tag);
        if (!Boolean.TRUE.equals(FORCE_REMOTE.get())) {
            String cached = ConfigCache.read(url);
            if (!TextUtils.isEmpty(cached)) {
                FROM_CACHE.set(Boolean.TRUE);
                return cached;
            }
        }
        String json = Decoder.getJson(UrlUtil.convert(url), tag);
        ConfigCache.write(url, json);
        return json;
    }

    // Parse the bundled fallback JSON, but keep using the caller's Config so that the config
    // row (home / parse selections) stays consistent with what the user has selected.
    protected void loadFallback(Config config) throws Throwable {
        String json = Decoder.getJsonFromAsset(fallbackUrl(), getTag());
        if (TextUtils.isEmpty(json)) throw new Exception("Fallback config is empty");
        ConfigCache.write(config.getUrl(), json);
        FALLBACK_JSON.set(json);
        try {
            load(config);
        } finally {
            FALLBACK_JSON.remove();
        }
    }

    // Load a config, preferring the local cache. If the cached content fails to load,
    // force a fresh remote fetch (which also refreshes the cache). On a cache hit, a
    // background task compares the cache with the remote and overwrites it when they differ.
    protected void loadWithCache(Config config) throws Throwable {
        FROM_CACHE.remove();
        FORCE_REMOTE.remove();
        try {
            load(config);
            if (Boolean.TRUE.equals(FROM_CACHE.get())) scheduleRefresh(config);
        } catch (Throwable e) {
            boolean usedCache = Boolean.TRUE.equals(FROM_CACHE.get());
            if (!usedCache || Decoder.isAsset(config.getUrl())) throw e;
            // Cached JSON could not be loaded: re-fetch from remote.
            FROM_CACHE.remove();
            FORCE_REMOTE.set(Boolean.TRUE);
            try {
                load(config);
            } finally {
                FORCE_REMOTE.remove();
            }
        } finally {
            FROM_CACHE.remove();
        }
    }

    // Explicit user-triggered reload: fetch fresh from remote first; if that fails and a
    // cache exists, fall back to the cached copy so the app still works offline.
    protected void loadRemoteFirst(Config config) throws Throwable {
        String url = config.getUrl();
        if (Decoder.isAsset(url)) {
            load(config);
            return;
        }
        FROM_CACHE.remove();
        FORCE_REMOTE.set(Boolean.TRUE);
        try {
            load(config);
            return;
        } catch (Throwable e) {
            if (!ConfigCache.exists(url)) throw e;
        } finally {
            FORCE_REMOTE.remove();
        }
        loadWithCache(config);
    }

    private void scheduleRefresh(Config config) {
        String url = config.getUrl();
        if (TextUtils.isEmpty(url) || Decoder.isAsset(url)) return;
        if (!refreshing.compareAndSet(false, true)) return;
        Task.execute(() -> {
            try {
                String local = ConfigCache.read(url);
                String remote = Decoder.getJson(UrlUtil.convert(url), getTag());
                if (!TextUtils.isEmpty(remote) && !remote.equals(local)) ConfigCache.write(url, remote);
            } catch (Throwable ignored) {
            } finally {
                refreshing.set(false);
            }
        });
    }

    public synchronized void ensureLoaded() {
        try {
            if (isLoaded()) return;
            Server.get().start();
            if (config == null) config = defaultConfig();
            // No config configured yet (fresh install): use the built-in source.
            if (TextUtils.isEmpty(config.getUrl()) && hasBuiltIn()) config = builtInConfig();
            boolean ok = false;
            try {
                loadWithCache(config);
                ok = isLoaded();
            } catch (Throwable e) {
                e.printStackTrace();
            }
            // Configured source failed: retry with the built-in source when it is a different one.
            if (!ok && hasBuiltIn() && !builtInUrl().equals(config.getUrl())) {
                config = builtInConfig();
                try {
                    loadWithCache(config);
                    ok = isLoaded();
                } catch (Throwable e) {
                    e.printStackTrace();
                }
            }
            // Still nothing: parse the bundled offline copy so the app is never empty.
            if (!ok && hasFallback() && !Decoder.isAsset(config.getUrl())) {
                try {
                    loadFallback(config);
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    protected void postEvent() {
        ConfigEvent.common();
    }

    public boolean needSync(String url) {
        return sync || config == null || TextUtils.isEmpty(config.getUrl()) || url.equals(config.getUrl());
    }

    public Config getConfig() {
        return config == null ? defaultConfig() : config;
    }

    protected void setHeaders(List<Header> headers) {
        OkHttp.responseInterceptor().addAll(headers);
    }

    protected void setProxy(List<Proxy> proxy) {
        OkHttp.selector().addAll(proxy);
    }

    protected void setHosts(List<String> hosts) {
        OkHttp.dns().addAll(hosts);
    }

    public void load(Callback callback) {
        int id = taskId.incrementAndGet();
        if (future != null && !future.isDone()) future.cancel(true);
        future = Task.submit(() -> loadConfig(id, config, callback));
        callback.start();
    }

    protected void loadConfig(int id, Config config, Callback callback) {
        try {
            Server.get().start();
            OkHttp.cancel(getTag());
            // Fresh install or a config without url: switch to the built-in source instead of
            // failing with an empty url (this is the path used by HomeActivity at startup).
            if (config == null || TextUtils.isEmpty(config.getUrl())) {
                config = hasBuiltIn() ? builtInConfig() : defaultConfig();
                this.config = config;
            }
            final Config target = config;
            try {
                loadRemoteFirst(target);
            } catch (Throwable e) {
                if (!hasFallback() || Decoder.isAsset(target.getUrl())) throw e;
                e.printStackTrace();
                loadFallback(target);
            }
            if (taskId.get() != id) return;
            if (target.equals(this.config)) target.update();
            App.post(() -> Notify.show(target.getNotice()));
            App.post(callback::success);
        } catch (Throwable e) {
            e.printStackTrace();
            if (isCanceled(e)) return;
            if (taskId.get() != id) return;
            if (TextUtils.isEmpty(config.getUrl())) App.post(() -> callback.error(""));
            else App.post(() -> callback.error(Notify.getError(R.string.error_config_get, e)));
        } finally {
            if (taskId.get() == id) postEvent();
        }
    }

    protected boolean isCanceled(Throwable e) {
        if ("Canceled".equals(e.getMessage())) return true;
        if (e instanceof InterruptedException) return true;
        if (e instanceof InterruptedIOException) return true;
        return e.getCause() instanceof InterruptedIOException;
    }

    protected JsonArray fetchArray(JsonObject object, String key) {
        if (!object.has(key)) return new JsonArray();
        JsonElement element = object.get(key);
        if (element.isJsonObject()) return new JsonArray();
        if (element.isJsonPrimitive()) element = fetch(element.getAsString());
        JsonArray result = new JsonArray();
        for (JsonElement item : element.getAsJsonArray()) {
            if (item.isJsonPrimitive()) result.addAll(fetch(item.getAsString()));
            else if (item.isJsonObject()) result.add(item);
        }
        return result;
    }

    private JsonArray fetch(String url) {
        try {
            JsonElement parsed = Json.parse(OkHttp.string(UrlUtil.convert(url)));
            return parsed.isJsonArray() ? parsed.getAsJsonArray() : new JsonArray();
        } catch (Exception e) {
            return new JsonArray();
        }
    }
}
