package com.chococar.zerotrust.support;

import com.chococar.zerotrust.platform.KeyRepository;
import com.chococar.zerotrust.platform.StoredKey;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** 測試用 {@link KeyRepository}：以 Map 保存，記錄 save 呼叫次數。 */
public final class InMemoryKeyRepository implements KeyRepository {

    private final Map<UUID, List<StoredKey>> store = new ConcurrentHashMap<>();
    public final AtomicInteger saveCount = new AtomicInteger();
    public final AtomicInteger flushCount = new AtomicInteger();

    @Override
    public Map<UUID, List<StoredKey>> loadAll() {
        Map<UUID, List<StoredKey>> copy = new HashMap<>();
        for (Map.Entry<UUID, List<StoredKey>> e : store.entrySet()) {
            copy.put(e.getKey(), new ArrayList<>(e.getValue()));
        }
        return copy;
    }

    @Override
    public void save(UUID uuid, List<StoredKey> keys) {
        saveCount.incrementAndGet();
        if (keys == null || keys.isEmpty()) {
            store.remove(uuid);
        } else {
            store.put(uuid, new ArrayList<>(keys));
        }
    }

    @Override
    public void flush() {
        flushCount.incrementAndGet();
    }

    /** 測試輔助：直接植入既有金鑰（模擬重啟後載入）。 */
    public void seed(UUID uuid, StoredKey... keys) {
        store.put(uuid, new ArrayList<>(List.of(keys)));
    }

    public List<StoredKey> current(UUID uuid) {
        List<StoredKey> l = store.get(uuid);
        return l == null ? List.of() : new ArrayList<>(l);
    }
}
