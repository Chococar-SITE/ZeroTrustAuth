package com.chococar.zerotrust.audit;

/**
 * 審計日誌輸出端。核心產生結構化 JSON 行，由平台層提供具體實作
 * （檔案輸出、每日輪替、90 天保留）。測試可用記憶體實作。
 */
public interface LogSink {

    /** 寫入一行 JSON（不含換行；實作負責換行與輪替）。 */
    void write(String jsonLine);

    /** 釋放資源。 */
    default void close() {}
}
