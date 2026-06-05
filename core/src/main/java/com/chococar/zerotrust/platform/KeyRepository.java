package com.chococar.zerotrust.platform;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 公鑰持久化來源。平台層以設定檔（YAML）實作；核心透過此介面讀寫，
 * 不依賴任何特定儲存格式。測試可用記憶體實作。
 *
 * <p>核心只持久化<b>公鑰</b>（外洩無法偽造簽名，計劃 6.2）。
 */
public interface KeyRepository {

    /** 啟動時載入所有管理員的金鑰。回傳的 Map 可為唯讀。 */
    Map<UUID, List<StoredKey>> loadAll();

    /** 某管理員金鑰異動（新增 / 撤銷 / 更新 last-used）後，寫回該管理員的完整金鑰清單。 */
    void save(UUID uuid, List<StoredKey> keys);

    /** 立即落盤（可選）。 */
    default void flush() {}
}
