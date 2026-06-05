package com.chococar.zerotrust.paper;

import com.chococar.zerotrust.auth.AuthEngine;
import com.chococar.zerotrust.auth.CommandResult;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * {@code /authkey} 指令處理（計劃 3.7）。將子指令路由至 {@link AuthEngine} 並回饋
 * {@link CommandResult#message()}。
 *
 * <ul>
 *   <li><b>主控台限定</b>：{@code enroll}、{@code revoke}（防盜帳號者自助建立 / 撤銷信任，計劃 3.5 / 6.5）。</li>
 *   <li><b>遊戲內</b>：{@code upload}、{@code rotate}、{@code verify}、{@code list}。</li>
 * </ul>
 *
 * <p>玩家指令的 {@code self} 取自 {@link Player#getUniqueId()}，{@code connectionId} 取自本次登入
 * 的共享紀錄（{@link FreezeListener} 於 join 時寫入）。
 */
final class AuthKeyCommand implements CommandExecutor, TabCompleter {

    private final AuthEngine engine;
    private final Map<UUID, String> connectionIds;

    AuthKeyCommand(AuthEngine engine, Map<UUID, String> connectionIds) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.connectionIds = Objects.requireNonNull(connectionIds, "connectionIds");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendUsage(sender);
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "enroll" -> handleEnroll(sender, args);
            case "upload" -> handleUpload(sender, args);
            case "rotate" -> handleRotate(sender, args);
            case "verify" -> handleVerify(sender);
            case "list" -> handleList(sender);
            case "revoke" -> handleRevoke(sender, args);
            default -> sendUsage(sender);
        }
        return true;
    }

    // ── 主控台限定 ───────────────────────────────────────

    private void handleEnroll(CommandSender sender, String[] args) {
        boolean fromConsole = sender instanceof ConsoleCommandSender;
        if (args.length < 2) {
            sender.sendMessage("用法：/authkey enroll <uuid>（僅主控台）");
            return;
        }
        UUID target = parseUuid(args[1]);
        if (target == null) {
            sender.sendMessage("§c無效的 UUID：" + args[1]);
            return;
        }
        // 引擎依 fromConsole 決定是否放行（主控台限定）；訊息已含「Enrollment code for ...」。
        CommandResult result = engine.enroll(target, fromConsole);
        sender.sendMessage(result.message());
    }

    private void handleRevoke(CommandSender sender, String[] args) {
        boolean fromConsole = sender instanceof ConsoleCommandSender;
        if (args.length < 2) {
            sender.sendMessage("用法：/authkey revoke <uuid> [label]（僅主控台）");
            return;
        }
        UUID target = parseUuid(args[1]);
        if (target == null) {
            sender.sendMessage("§c無效的 UUID：" + args[1]);
            return;
        }
        String labelArg = args.length >= 3 ? args[2] : null;
        CommandResult result = engine.revoke(target, labelArg, fromConsole);
        sender.sendMessage(result.message());
    }

    // ── 遊戲內（玩家）────────────────────────────────────

    private void handleUpload(CommandSender sender, String[] args) {
        Player player = requirePlayer(sender);
        if (player == null) {
            return;
        }
        if (args.length < 3) {
            sender.sendMessage("用法：/authkey upload <pubkey> <code> [label]");
            return;
        }
        String pubKey = args[1];
        String code = args[2];
        String labelArg = args.length >= 4 ? args[3] : null;
        CommandResult result = engine.upload(player.getUniqueId(), pubKey, code, labelArg);
        sender.sendMessage(result.message());
    }

    private void handleRotate(CommandSender sender, String[] args) {
        Player player = requirePlayer(sender);
        if (player == null) {
            return;
        }
        if (args.length < 2) {
            sender.sendMessage("用法：/authkey rotate <newpubkey> [label]");
            return;
        }
        String newPubKey = args[1];
        String labelArg = args.length >= 3 ? args[2] : null;
        CommandResult result = engine.rotate(player.getUniqueId(), newPubKey, labelArg);
        sender.sendMessage(result.message());
    }

    private void handleVerify(CommandSender sender) {
        Player player = requirePlayer(sender);
        if (player == null) {
            return;
        }
        UUID uuid = player.getUniqueId();
        // 沿用本次登入的連線 ID；若不存在（極少見）則建立並記錄，維持 Nonce 綁定。
        String connectionId = connectionIds.computeIfAbsent(uuid, k -> UUID.randomUUID().toString());
        CommandResult result = engine.verify(uuid, connectionId);
        sender.sendMessage(result.message());
    }

    private void handleList(CommandSender sender) {
        Player player = requirePlayer(sender);
        if (player == null) {
            return;
        }
        CommandResult result = engine.list(player.getUniqueId());
        sender.sendMessage(result.message());
    }

    // ── 小工具 ────────────────────────────────────────────

    private Player requirePlayer(CommandSender sender) {
        if (sender instanceof Player p) {
            return p;
        }
        sender.sendMessage("§c此指令僅能在遊戲內執行。");
        return null;
    }

    private static UUID parseUuid(String s) {
        try {
            return UUID.fromString(s.trim());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage("§e/authkey 子指令：");
        if (sender instanceof ConsoleCommandSender) {
            sender.sendMessage("§7  enroll <uuid>            產生一次性註冊碼");
            sender.sendMessage("§7  revoke <uuid> [label]    撤銷金鑰並終止 Session");
        } else {
            sender.sendMessage("§7  upload <pubkey> <code> [label]   上傳新公鑰");
            sender.sendMessage("§7  rotate <newpubkey> [label]       換鑰（免註冊碼）");
            sender.sendMessage("§7  verify                            在線重新驗證");
            sender.sendMessage("§7  list                              列出自己的金鑰");
        }
    }

    // ── Tab 補全 ──────────────────────────────────────────

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> subs = new ArrayList<>();
            if (sender instanceof ConsoleCommandSender) {
                subs.add("enroll");
                subs.add("revoke");
            } else {
                subs.add("upload");
                subs.add("rotate");
                subs.add("verify");
                subs.add("list");
            }
            return filterPrefix(subs, args[0]);
        }
        return List.of();
    }

    private static List<String> filterPrefix(List<String> options, String prefix) {
        String p = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String o : options) {
            if (o.toLowerCase(Locale.ROOT).startsWith(p)) {
                out.add(o);
            }
        }
        return out;
    }
}
