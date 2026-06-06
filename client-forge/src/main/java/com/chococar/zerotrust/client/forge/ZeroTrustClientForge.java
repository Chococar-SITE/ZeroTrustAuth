package com.chococar.zerotrust.client.forge;

import com.mojang.brigadier.CommandDispatcher;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

import java.util.logging.Logger;

/**
 * 選項 A 的 MinecraftForge <b>客戶端</b> Mod 進入點，**LEGACY 版本線**（旗艦頂版，Minecraft 1.20.1 / Forge 47.x）。
 *
 * <p>跑在「管理員的遊戲客戶端」：
 * <ol>
 *   <li>於 {@link FMLClientSetupEvent} 註冊選項 A 的 {@link NonceMsg} SimpleChannel（通道
 *       {@code zerotrustauth:auth}，與伺服器 {@code platform-forge} 完全對齊）。收到伺服器 Nonce 挑戰時，
 *       {@link NonceMsg#handle} 自動加領域前綴簽名並回傳。玩家無需任何操作。</li>
 *   <li>於 {@link RegisterClientCommandsEvent} 註冊 {@code /ztclient pubkey}，把可上傳公鑰印到聊天，
 *       供玩家於伺服器執行 {@code /authkey upload <pubkey> <code>}。</li>
 * </ol>
 *
 * <h2>事件匯流排（Forge 1.20.1 / 47.x）</h2>
 * 此 Forge 版本不支援建構子注入 {@link IEventBus}；以<b>無參數建構子</b>啟動，
 * 自 {@link FMLJavaModLoadingContext#getModEventBus()} 取得 mod 匯流排：
 * <ul>
 *   <li><b>Mod 匯流排</b>：{@link FMLClientSetupEvent}（註冊 {@link NonceMsg} SimpleChannel）。</li>
 *   <li><b>遊戲匯流排</b>（{@link MinecraftForge#EVENT_BUS}）：{@link RegisterClientCommandsEvent}
 *       （客戶端指令）。</li>
 * </ul>
 *
 * <p>本 mod 為純客戶端（mods.toml 標 {@code side = "CLIENT"}）；私鑰永不離開本機，網路只傳簽名。
 */
@Mod("zerotrustauthclient")
public final class ZeroTrustClientForge {

    private static final Logger LOG = Logger.getLogger("ZeroTrustAuthClient");

    /**
     * Forge 1.20.1 以<b>無參數建構子</b>啟動 mod。自 {@link FMLJavaModLoadingContext} 取得 mod 匯流排
     * 註冊 {@link FMLClientSetupEvent}（網路設定），並於遊戲匯流排註冊本實例的 {@code @SubscribeEvent}。
     */
    public ZeroTrustClientForge() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        // Mod 匯流排：客戶端設定階段註冊選項 A 封包（SimpleChannel 訊息）。
        modBus.addListener(this::onClientSetup);
        // 遊戲匯流排：客戶端指令註冊（@SubscribeEvent 實例方法）。
        MinecraftForge.EVENT_BUS.register(this);
        LOG.info("ZeroTrustAuth 客戶端（Forge 1.20.1 / 選項 A）已載入。");
    }

    // ── Mod 匯流排：客戶端設定（網路註冊）─────────────────────

    /**
     * {@link FMLClientSetupEvent}：在 {@link NonceMsg#CHANNEL} 上註冊選項 A 訊息（{@code zerotrustauth:auth}）。
     * {@code enqueueWork(...)}：網路註冊須於同步工作佇列執行，避免並行設定期競態。
     */
    private void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(NonceMsg::register);
    }

    // ── 遊戲匯流排：客戶端指令 ───────────────────────────────

    /** 註冊 {@code /ztclient pubkey}（客戶端指令，不送伺服器）。 */
    @SubscribeEvent
    public void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(
                Commands.literal("ztclient")
                        .then(Commands.literal("pubkey")
                                .executes(ctx -> {
                                    printPubKey(ctx.getSource());
                                    return 1;
                                })));
    }

    /** 把可上傳公鑰印到聊天。失敗則印錯誤訊息，不丟例外。 */
    private static void printPubKey(CommandSourceStack source) {
        try {
            String pub = NonceMsg.publicKeyBase64();
            // 1.20.1：sendSuccess 取 Supplier<Component>（1.19.4+ 起的形式；裸 Component 多載已移除）。
            source.sendSuccess(() -> Component.literal(
                    "§a[ZeroTrustAuth] 你的公鑰（在伺服器執行 /authkey upload <pubkey> <code>）："), false);
            source.sendSuccess(() -> Component.literal("§f" + pub), false);
        } catch (Throwable t) {
            LOG.warning("ZeroTrustAuth 取得公鑰失敗：" + t);
            source.sendFailure(Component.literal("§c[ZeroTrustAuth] 無法讀取 / 產生金鑰：" + t.getMessage()));
        }
    }
}
