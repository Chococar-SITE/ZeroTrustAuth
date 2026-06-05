package com.chococar.zerotrust.platform;

import java.util.Optional;
import java.util.UUID;

/**
 * 平台適配介面（計劃 2.3）。核心邏輯透過此介面操作玩家與權限，
 * 不依賴任何特定平台 API（Paper / Fabric / Forge / NeoForge 各自實作）。
 *
 * <p><b>執行緒：</b>核心可能從任意執行緒呼叫；實作須自行將需在主執行緒進行的操作
 * （權限、踢出、傳訊）排程至主執行緒。
 */
public interface PlatformAdapter {

    /** 凍結玩家：禁止移動、背包、容器、方塊互動、聊天，僅放行 {@code /authkey}（計劃 4.5）。 */
    void freezePlayer(UUID uuid);

    void unfreezePlayer(UUID uuid);

    /** 動態授予管理員權限。<b>必須使用 transient/非持久化方式</b>（計劃 5.2）。 */
    void grantAdminPerm(UUID uuid);

    void revokeAdminPerm(UUID uuid);

    void kickPlayer(UUID uuid, String reason);

    void sendMessage(UUID uuid, String message);

    /** 從設定判斷此帳號是否為受本系統保護的管理員帳號。 */
    boolean isAdminAccount(UUID uuid);

    /**
     * 剝奪原版 OP（{@code ops.json}），防止繞過本系統（計劃 4.3 / 6.1）。
     * 登入與撤銷時呼叫。
     */
    void stripVanillaOp(UUID uuid);

    /** 驗證通過後以受控方式恢復原版 OP（可選；建議管理員完全不使用原版 OP）。 */
    default void restoreVanillaOp(UUID uuid) {}

    /**
     * 送出選項 A 挑戰至客戶端（自訂封包 / Plugin Message）。
     * 客戶端 Mod 收到後加領域前綴簽名回傳。
     */
    void sendChallenge(UUID uuid, byte[] nonce);

    /** 玩家目前是否在線。 */
    boolean isOnline(UUID uuid);

    Optional<String> getPlayerName(UUID uuid);

    /**
     * 玩家當前連線 IP（若可得）。供審計日誌以 HMAC-SHA256 雜湊記錄（計劃 6.2）。
     * 預設回傳空（核心將省略 {@code ip_hmac} 欄位，資料最小化）。
     */
    default Optional<String> getPlayerIp(UUID uuid) {
        return Optional.empty();
    }
}
