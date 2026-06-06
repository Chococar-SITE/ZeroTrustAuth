package com.chococar.zerotrust.forge;

import com.chococar.zerotrust.auth.AuthEngine;
import com.chococar.zerotrust.auth.CommandResult;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * {@code /authkey} 指令（計劃 3.7），以 Brigadier 註冊於 {@code RegisterCommandsEvent}。
 * 將子指令路由至 {@link AuthEngine} 並回饋 {@link CommandResult#message()}。
 *
 * <ul>
 *   <li><b>主控台 / 權限等級 4 限定</b>：{@code enroll}、{@code revoke}
 *       （防盜帳號者自助建立 / 撤銷信任，計劃 3.5 / 6.5）。引擎再以 {@code fromConsole} 二次把關。</li>
 *   <li><b>遊戲內（玩家）</b>：{@code upload}、{@code rotate}、{@code verify}、{@code list}。</li>
 * </ul>
 *
 * <p>玩家指令的 {@code self} 取自 {@link ServerPlayer#getUUID()}，{@code connectionId} 取自本次登入
 * 的共享紀錄（{@link ZeroTrustForge} 於 join 時寫入）。
 *
 * <p><b>引擎延遲解析：</b>{@code RegisterCommandsEvent} 可能早於引擎建立（{@code ServerAboutToStartEvent}）
 * 觸發；故引擎以 {@link Supplier} 於<b>執行時</b>解析。玩家要到伺服器啟動完成、可接受連線後才可能執行
 * 指令（遠晚於引擎建立），但仍以 null 檢查作為縱深防禦：未就緒時回安全錯誤訊息（fail-closed）。
 */
final class AuthKeyCommand {

    private final Supplier<AuthEngine> engineSupplier;
    private final Map<UUID, String> connectionIds;

    AuthKeyCommand(Supplier<AuthEngine> engineSupplier, Map<UUID, String> connectionIds) {
        this.engineSupplier = Objects.requireNonNull(engineSupplier, "engineSupplier");
        this.connectionIds = Objects.requireNonNull(connectionIds, "connectionIds");
    }

    /** 於 {@code RegisterCommandsEvent} 呼叫，建立指令樹。 */
    void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("authkey")
                // enroll <uuid>（主控台 / level 4）。
                .then(Commands.literal("enroll")
                    .requires(src -> isConsoleOrLevel4(src))
                    .then(Commands.argument("uuid", UuidArgument.uuid())
                        .executes(this::doEnroll)))
                // revoke <uuid> [label]（主控台 / level 4）。
                .then(Commands.literal("revoke")
                    .requires(src -> isConsoleOrLevel4(src))
                    .then(Commands.argument("uuid", UuidArgument.uuid())
                        .executes(ctx -> doRevoke(ctx, null))
                        .then(Commands.argument("label", StringArgumentType.word())
                            .executes(ctx -> doRevoke(ctx, StringArgumentType.getString(ctx, "label"))))))
                // upload <pubkey> <code> [label]（遊戲內）。
                .then(Commands.literal("upload")
                    .then(Commands.argument("pubkey", StringArgumentType.word())
                        .then(Commands.argument("code", StringArgumentType.word())
                            .executes(ctx -> doUpload(ctx, null))
                            .then(Commands.argument("label", StringArgumentType.word())
                                .executes(ctx -> doUpload(ctx, StringArgumentType.getString(ctx, "label")))))))
                // rotate <newpubkey> [label]（遊戲內）。
                .then(Commands.literal("rotate")
                    .then(Commands.argument("newpubkey", StringArgumentType.word())
                        .executes(ctx -> doRotate(ctx, null))
                        .then(Commands.argument("label", StringArgumentType.word())
                            .executes(ctx -> doRotate(ctx, StringArgumentType.getString(ctx, "label"))))))
                // verify（遊戲內）。
                .then(Commands.literal("verify")
                    .executes(this::doVerify))
                // list（遊戲內）。
                .then(Commands.literal("list")
                    .executes(this::doList))
        );
    }

    // ── 主控台 / level 4 ─────────────────────────────────────

    private int doEnroll(CommandContext<CommandSourceStack> ctx) {
        AuthEngine engine = engine(ctx.getSource());
        if (engine == null) {
            return 0;
        }
        UUID target = UuidArgument.getUuid(ctx, "uuid");
        boolean fromConsole = isFromConsole(ctx.getSource());
        // 引擎依 fromConsole 二次把關；訊息已含「Enrollment code for <uuid>: <code>」（逐字保留）。
        CommandResult result = engine.enroll(target, fromConsole);
        return reply(ctx.getSource(), result);
    }

    private int doRevoke(CommandContext<CommandSourceStack> ctx, String label) {
        AuthEngine engine = engine(ctx.getSource());
        if (engine == null) {
            return 0;
        }
        UUID target = UuidArgument.getUuid(ctx, "uuid");
        boolean fromConsole = isFromConsole(ctx.getSource());
        CommandResult result = engine.revoke(target, label, fromConsole);
        return reply(ctx.getSource(), result);
    }

    // ── 遊戲內（玩家）─────────────────────────────────────

    private int doUpload(CommandContext<CommandSourceStack> ctx, String label) throws CommandSyntaxException {
        AuthEngine engine = engine(ctx.getSource());
        if (engine == null) {
            return 0;
        }
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        String pubKey = StringArgumentType.getString(ctx, "pubkey");
        String code = StringArgumentType.getString(ctx, "code");
        CommandResult result = engine.upload(player.getUUID(), pubKey, code, label);
        return reply(ctx.getSource(), result);
    }

    private int doRotate(CommandContext<CommandSourceStack> ctx, String label) throws CommandSyntaxException {
        AuthEngine engine = engine(ctx.getSource());
        if (engine == null) {
            return 0;
        }
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        String newPubKey = StringArgumentType.getString(ctx, "newpubkey");
        CommandResult result = engine.rotate(player.getUUID(), newPubKey, label);
        return reply(ctx.getSource(), result);
    }

    private int doVerify(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        AuthEngine engine = engine(ctx.getSource());
        if (engine == null) {
            return 0;
        }
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        UUID uuid = player.getUUID();
        // 沿用本次登入的連線 ID；若不存在（極少見）則建立並記錄，維持 Nonce 綁定（計劃 3.2）。
        String connectionId = connectionIds.computeIfAbsent(uuid, k -> UUID.randomUUID().toString());
        CommandResult result = engine.verify(uuid, connectionId);
        return reply(ctx.getSource(), result);
    }

    private int doList(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        AuthEngine engine = engine(ctx.getSource());
        if (engine == null) {
            return 0;
        }
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        CommandResult result = engine.list(player.getUUID());
        return reply(ctx.getSource(), result);
    }

    /** 解析引擎；未就緒時對來源回 fail-closed 訊息並回 {@code null}。 */
    private AuthEngine engine(CommandSourceStack src) {
        AuthEngine engine = engineSupplier.get();
        if (engine == null) {
            src.sendFailure(Component.literal("§c驗證系統尚未就緒，請稍候再試"));
        }
        return engine;
    }

    // ── 小工具 ────────────────────────────────────────────

    /**
     * 主控台或權限等級 4。{@code requires(...)} 在指令樹建構 / 補全時評估，作為第一道閘；
     * 引擎仍以 {@code fromConsole} 對 enroll/revoke 做最終把關（縱深防禦）。
     */
    private static boolean isConsoleOrLevel4(CommandSourceStack src) {
        return src.hasPermission(4);
    }

    /** 是否真正來自主控台（非玩家實體即視為主控台 / 命令方塊以外的系統來源）。 */
    private static boolean isFromConsole(CommandSourceStack src) {
        return src.getEntity() == null;
    }

    /** 將 {@link CommandResult} 回饋至來源，並回傳 Brigadier 結果碼（成功 1 / 失敗 0）。 */
    private static int reply(CommandSourceStack src, CommandResult result) {
        if (result.success()) {
            // 不廣播給其他管理員（第二參數 false）。
            // 1.20.1：sendSuccess 取 Supplier<Component>（1.19.4+ 起的形式；裸 Component 多載已移除）。
            src.sendSuccess(() -> Component.literal(result.message()), false);
            return 1;
        } else {
            src.sendFailure(Component.literal(result.message()));
            return 0;
        }
    }
}
