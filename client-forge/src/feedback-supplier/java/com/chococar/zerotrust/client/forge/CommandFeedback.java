package com.chococar.zerotrust.client.forge;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;

import java.util.function.Supplier;

/**
 * {@code sendSuccess} 跨版本相容墊片 —— <b>supplier 紀元</b>（Minecraft 1.20+）。
 *
 * <p>{@link CommandSourceStack#sendSuccess} 的簽名隨 Minecraft 版本改變，且裸 {@code Component}
 * 多載在 1.20 已移除，故無法以單一原始碼同時編譯：
 * <ul>
 *   <li><b>1.20+（本檔）</b>：{@code sendSuccess(Supplier<Component>, boolean)}。</li>
 *   <li><b>≤1.19</b>：{@code sendSuccess(Component, boolean)}（見 {@code src/feedback-component}）。</li>
 * </ul>
 *
 * <p>兩個紀元版本擁有完全相同的 public 簽名與套件，故呼叫端（{@code ZeroTrustClientForge}）
 * 與紀元無關；建置時由 {@code build.gradle.kts} 依 {@code -PforgeMc} 選入對應目錄。
 * 第二參數固定 {@code false}（不廣播）。
 */
final class CommandFeedback {

    private CommandFeedback() {
    }

    /** 1.20+：以 {@link Supplier} 形式回報成功訊息（不廣播）。 */
    static void success(CommandSourceStack src, Component msg) {
        src.sendSuccess(() -> msg, false);
    }
}
