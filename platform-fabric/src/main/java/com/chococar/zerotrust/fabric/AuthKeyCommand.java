package com.chococar.zerotrust.fabric;

import com.chococar.zerotrust.auth.AuthEngine;
import com.chococar.zerotrust.auth.CommandResult;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permission;
import net.minecraft.server.permissions.PermissionLevel;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * {@code /authkey} 指令（Brigadier；計劃 3.7）。將子指令路由至 {@link AuthEngine} 並回饋
 * {@link CommandResult#message()}。名稱採用 <b>Mojang 官方對應</b>。
 *
 * <ul>
 *   <li><b>主控台 / 高權限限定</b>：{@code enroll}、{@code revoke}（防盜帳號者自助建立 / 撤銷信任，
 *       計劃 3.5 / 6.5）。判定：{@code source.getEntity() == null}（主控台）或
 *       {@code source.hasPermission(4)}。引擎再以 {@code fromConsole} 做第二道把關。</li>
 *   <li><b>遊戲內（玩家）</b>：{@code upload}、{@code rotate}、{@code verify}、{@code list}。</li>
 * </ul>
 *
 * <p>玩家指令的 {@code self} 取自 {@link ServerPlayer#getUUID()}，{@code connectionId} 取自本次登入
 * 的共享紀錄（{@link ConnectionListener} 於 join 時寫入）。
 *
 * <p><b>生命週期：</b>{@code CommandRegistrationCallback} 在 {@code MinecraftServer} 建構期觸發，
 * <b>早於</b>引擎於 {@code SERVER_STARTING} 建立的時點。故指令樹以 {@link Supplier} 在<b>執行時</b>
 * 才解析引擎；若此時引擎尚未就緒（理論上罕見），回覆失敗訊息而非 NPE。
 */
final class AuthKeyCommand {

    private final Supplier<AuthEngine> engineSupplier;
    private final Map<UUID, String> connectionIds;

    AuthKeyCommand(Supplier<AuthEngine> engineSupplier, Map<UUID, String> connectionIds) {
        this.engineSupplier = Objects.requireNonNull(engineSupplier, "engineSupplier");
        this.connectionIds = Objects.requireNonNull(connectionIds, "connectionIds");
    }

    /** 由 {@code CommandRegistrationCallback} 呼叫，建立整棵 {@code /authkey} 指令樹。 */
    void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("authkey")
                // enroll <uuid>（主控台 / 高權限）
                .then(Commands.literal("enroll")
                        .then(Commands.argument("uuid", StringArgumentType.word())
                                .executes(this::doEnroll)))
                // upload <pubkey> <code> [label]
                .then(Commands.literal("upload")
                        .then(Commands.argument("pubkey", StringArgumentType.string())
                                .then(Commands.argument("code", StringArgumentType.string())
                                        .executes(ctx -> doUpload(ctx, null))
                                        .then(Commands.argument("label", StringArgumentType.word())
                                                .executes(ctx -> doUpload(ctx,
                                                        StringArgumentType.getString(ctx, "label")))))))
                // rotate <newpubkey> [label]
                .then(Commands.literal("rotate")
                        .then(Commands.argument("newpubkey", StringArgumentType.string())
                                .executes(ctx -> doRotate(ctx, null))
                                .then(Commands.argument("label", StringArgumentType.word())
                                        .executes(ctx -> doRotate(ctx,
                                                StringArgumentType.getString(ctx, "label"))))))
                // verify
                .then(Commands.literal("verify")
                        .executes(this::doVerify))
                // list
                .then(Commands.literal("list")
                        .executes(this::doList))
                // revoke <uuid> [label]（主控台 / 高權限）
                .then(Commands.literal("revoke")
                        .then(Commands.argument("uuid", StringArgumentType.word())
                                .executes(ctx -> doRevoke(ctx, null))
                                .then(Commands.argument("label", StringArgumentType.word())
                                        .executes(ctx -> doRevoke(ctx,
                                                StringArgumentType.getString(ctx, "label")))))));
    }

    // ── 主控台 / 高權限限定 ──────────────────────────────────

    private int doEnroll(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        boolean fromConsole = isConsole(src);
        UUID target = parseUuid(StringArgumentType.getString(ctx, "uuid"));
        if (target == null) {
            src.sendFailure(Component.literal("無效的 UUID"));
            return 0;
        }
        // 引擎依 fromConsole 決定是否放行；訊息含「Enrollment code for <uuid>: <code>」整合標記。
        AuthEngine engine = engine(src);
        if (engine == null) {
            return 0;
        }
        return feedback(src, engine.enroll(target, fromConsole));
    }

    private int doRevoke(CommandContext<CommandSourceStack> ctx, String label) {
        CommandSourceStack src = ctx.getSource();
        boolean fromConsole = isConsole(src);
        UUID target = parseUuid(StringArgumentType.getString(ctx, "uuid"));
        if (target == null) {
            src.sendFailure(Component.literal("無效的 UUID"));
            return 0;
        }
        AuthEngine engine = engine(src);
        if (engine == null) {
            return 0;
        }
        return feedback(src, engine.revoke(target, label, fromConsole));
    }

    // ── 遊戲內（玩家）────────────────────────────────────────

    private int doUpload(CommandContext<CommandSourceStack> ctx, String label) {
        ServerPlayer player = requirePlayer(ctx.getSource());
        if (player == null) {
            return 0;
        }
        AuthEngine engine = engine(ctx.getSource());
        if (engine == null) {
            return 0;
        }
        String pubKey = StringArgumentType.getString(ctx, "pubkey");
        String code = StringArgumentType.getString(ctx, "code");
        return feedback(ctx.getSource(), engine.upload(player.getUUID(), pubKey, code, label));
    }

    private int doRotate(CommandContext<CommandSourceStack> ctx, String label) {
        ServerPlayer player = requirePlayer(ctx.getSource());
        if (player == null) {
            return 0;
        }
        AuthEngine engine = engine(ctx.getSource());
        if (engine == null) {
            return 0;
        }
        String newPubKey = StringArgumentType.getString(ctx, "newpubkey");
        return feedback(ctx.getSource(), engine.rotate(player.getUUID(), newPubKey, label));
    }

    private int doVerify(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = requirePlayer(ctx.getSource());
        if (player == null) {
            return 0;
        }
        AuthEngine engine = engine(ctx.getSource());
        if (engine == null) {
            return 0;
        }
        UUID uuid = player.getUUID();
        // 沿用本次登入的連線 ID；若不存在（極少見）則建立並記錄，維持 Nonce 綁定。
        String connectionId = connectionIds.computeIfAbsent(uuid, k -> UUID.randomUUID().toString());
        return feedback(ctx.getSource(), engine.verify(uuid, connectionId));
    }

    private int doList(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = requirePlayer(ctx.getSource());
        if (player == null) {
            return 0;
        }
        AuthEngine engine = engine(ctx.getSource());
        if (engine == null) {
            return 0;
        }
        return feedback(ctx.getSource(), engine.list(player.getUUID()));
    }

    // ── 小工具 ───────────────────────────────────────────────

    /**
     * 主控台或高權限（OP level 4 = OWNERS）。
     *
     * <p>26.1：整數權限等級已換為 {@code PermissionSet} 系統；原 {@code hasPermission(4)} 等價於
     * 檢查 {@code Permission.HasCommandLevel(PermissionLevel.OWNERS)}（等級 4 = OWNERS）。
     */
    private static boolean isConsole(CommandSourceStack src) {
        return src.getEntity() == null
                || src.permissions().hasPermission(new Permission.HasCommandLevel(PermissionLevel.OWNERS));
    }

    /** 執行時解析引擎；未就緒則回覆失敗並回傳 null（fail-closed）。 */
    private AuthEngine engine(CommandSourceStack src) {
        AuthEngine e = engineSupplier.get();
        if (e == null) {
            src.sendFailure(Component.literal("ZeroTrustAuth 尚未就緒，請稍後再試。"));
        }
        return e;
    }

    private ServerPlayer requirePlayer(CommandSourceStack src) {
        ServerPlayer p = src.getPlayer();
        if (p == null) {
            src.sendFailure(Component.literal("此指令僅能在遊戲內執行。"));
        }
        return p;
    }

    /** 將 {@link CommandResult} 回饋給來源：成功用 sendSuccess、失敗用 sendFailure。 */
    private static int feedback(CommandSourceStack src, CommandResult result) {
        String msg = FabricPlatformAdapter.stripLegacy(result.message());
        if (result.success()) {
            src.sendSuccess(() -> Component.literal(msg), false);
            return 1;
        }
        src.sendFailure(Component.literal(msg));
        return 0;
    }

    private static UUID parseUuid(String s) {
        if (s == null) {
            return null;
        }
        try {
            return UUID.fromString(s.trim());
        } catch (RuntimeException e) {
            return null;
        }
    }
}
