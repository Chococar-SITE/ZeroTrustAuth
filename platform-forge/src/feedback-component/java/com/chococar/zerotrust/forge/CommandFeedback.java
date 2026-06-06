package com.chococar.zerotrust.forge;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;

/**
 * {@code sendSuccess} 跨版本相容墊片 —— <b>component 紀元</b>（Minecraft ≤1.19）。
 *
 * <p>{@link CommandSourceStack#sendSuccess} 的簽名隨 Minecraft 版本改變，故無法以單一原始碼同時編譯：
 * <ul>
 *   <li><b>1.20+</b>：{@code sendSuccess(Supplier<Component>, boolean)}（見 {@code src/feedback-supplier}）。</li>
 *   <li><b>≤1.19（本檔）</b>：{@code sendSuccess(Component, boolean)}（裸 {@code Component}，無 lambda）。</li>
 * </ul>
 *
 * <p>兩個紀元版本擁有完全相同的 public 簽名與套件，故呼叫端（{@code AuthKeyCommand} 等）
 * 與紀元無關；建置時由 {@code build.gradle.kts} 依 {@code -PforgeMc} 選入對應目錄。
 * 第二參數固定 {@code false}（不廣播給其他管理員 / 操作者）。
 */
final class CommandFeedback {

    private CommandFeedback() {
    }

    /** ≤1.19：以裸 {@link Component} 形式回報成功訊息（不廣播）。 */
    static void success(CommandSourceStack src, Component msg) {
        src.sendSuccess(msg, false);
    }
}
