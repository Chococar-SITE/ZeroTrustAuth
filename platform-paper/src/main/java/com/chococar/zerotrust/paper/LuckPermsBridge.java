package com.chococar.zerotrust.paper;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;
import net.luckperms.api.model.user.UserManager;
import net.luckperms.api.node.Node;

import java.util.UUID;

/**
 * 對 LuckPerms API 的隔離橋接層。
 *
 * <p><b>為何獨立成類別：</b>LuckPerms（{@code net.luckperms:api}）為 {@code compileOnly} +
 * {@code softdepend}，執行時可能不存在。若 {@link PaperPlatformAdapter} 直接引用 LuckPerms 類別，
 * 則該 adapter 的類別載入即可能因缺少 LuckPerms 類別而拋 {@link NoClassDefFoundError}。
 * 把所有 LuckPerms 型別集中於此，並僅在確認 LuckPerms 存在後才觸碰本類別，即可將連結錯誤
 * 侷限在這裡並被呼叫端的 {@code try/catch(Throwable)} 攔截。
 *
 * <h2>權限不持久化（計劃 5.2）</h2>
 * 僅使用 <b>transient</b> node（{@code user.transientData()}）。transient node 只存於記憶體、
 * 不寫入 LuckPerms 資料庫、重啟即失效，符合零信任「權限不持久化」原則。<br>
 * <b>絕不</b>呼叫 {@code user.data()}（持久化）。
 */
final class LuckPermsBridge {

    private LuckPermsBridge() {}

    /** LuckPerms API 是否就緒（provider 可取得）。觸發類別載入；失敗時由呼叫端攔截。 */
    static boolean isReady() {
        return LuckPermsProvider.get() != null;
    }

    /**
     * 以 transient node 授予指定權限 node。
     * 對在線玩家（{@code getUser} 命中）即時生效。
     */
    static void grant(UUID uuid, String permissionNode) {
        LuckPerms lp = LuckPermsProvider.get();
        UserManager um = lp.getUserManager();
        User user = um.getUser(uuid);
        if (user == null) {
            // 玩家應在線（grant 僅於驗證通過後呼叫）；保險起見同步載入。
            user = um.loadUser(uuid).join();
            if (user == null) {
                return;
            }
        }
        Node node = Node.builder(permissionNode).build();
        user.transientData().add(node);
        // transient 變更即時套用；saveUser 不會持久化 transient node，僅推動重算 / 廣播。
        um.saveUser(user);
    }

    /** 移除先前以 transient 授予的權限 node。 */
    static void revoke(UUID uuid, String permissionNode) {
        LuckPerms lp = LuckPermsProvider.get();
        UserManager um = lp.getUserManager();
        User user = um.getUser(uuid);
        if (user == null) {
            user = um.loadUser(uuid).join();
            if (user == null) {
                return;
            }
        }
        Node node = Node.builder(permissionNode).build();
        user.transientData().remove(node);
        um.saveUser(user);
    }
}
