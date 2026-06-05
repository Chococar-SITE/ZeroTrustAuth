package com.chococar.zerotrust.support;

import com.chococar.zerotrust.platform.PlatformAdapter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 測試用 {@link PlatformAdapter}：記錄所有動作呼叫，並維護線上集合與名稱對照。
 */
public final class FakePlatformAdapter implements PlatformAdapter {

    public final List<UUID> frozen = new CopyOnWriteArrayList<>();
    public final List<UUID> unfrozen = new CopyOnWriteArrayList<>();
    public final List<UUID> granted = new CopyOnWriteArrayList<>();
    public final List<UUID> revoked = new CopyOnWriteArrayList<>();
    public final List<UUID> strippedOp = new CopyOnWriteArrayList<>();
    public final List<UUID> restoredOp = new CopyOnWriteArrayList<>();
    public final List<Kick> kicks = new CopyOnWriteArrayList<>();
    public final List<Message> messages = new CopyOnWriteArrayList<>();
    public final List<Challenge> challenges = new CopyOnWriteArrayList<>();

    private final Set<UUID> admins = ConcurrentHashMap.newKeySet();
    private final Set<UUID> online = ConcurrentHashMap.newKeySet();
    private final Map<UUID, String> names = new ConcurrentHashMap<>();

    public record Kick(UUID uuid, String reason) {}
    public record Message(UUID uuid, String message) {}
    public record Challenge(UUID uuid, byte[] nonce) {}

    public FakePlatformAdapter online(UUID uuid, String name) {
        online.add(uuid);
        if (name != null) names.put(uuid, name);
        return this;
    }

    public FakePlatformAdapter admin(UUID uuid) {
        admins.add(uuid);
        return this;
    }

    public void setOffline(UUID uuid) {
        online.remove(uuid);
    }

    @Override public void freezePlayer(UUID uuid) { frozen.add(uuid); }
    @Override public void unfreezePlayer(UUID uuid) { unfrozen.add(uuid); }
    @Override public void grantAdminPerm(UUID uuid) { granted.add(uuid); }
    @Override public void revokeAdminPerm(UUID uuid) { revoked.add(uuid); }
    @Override public void kickPlayer(UUID uuid, String reason) {
        kicks.add(new Kick(uuid, reason));
        online.remove(uuid);
    }
    @Override public void sendMessage(UUID uuid, String message) { messages.add(new Message(uuid, message)); }
    @Override public boolean isAdminAccount(UUID uuid) { return admins.contains(uuid); }
    @Override public void stripVanillaOp(UUID uuid) { strippedOp.add(uuid); }
    @Override public void restoreVanillaOp(UUID uuid) { restoredOp.add(uuid); }
    @Override public void sendChallenge(UUID uuid, byte[] nonce) {
        challenges.add(new Challenge(uuid, nonce == null ? null : nonce.clone()));
    }
    @Override public boolean isOnline(UUID uuid) { return online.contains(uuid); }
    @Override public Optional<String> getPlayerName(UUID uuid) { return Optional.ofNullable(names.get(uuid)); }

    // ── 測試輔助查詢 ─────────────────────────────────────

    public int kickCount() { return kicks.size(); }

    public boolean wasKicked(UUID uuid) {
        return kicks.stream().anyMatch(k -> k.uuid().equals(uuid));
    }

    public boolean wasGranted(UUID uuid) { return granted.contains(uuid); }
    public boolean wasRevoked(UUID uuid) { return revoked.contains(uuid); }
    public boolean wasFrozen(UUID uuid) { return frozen.contains(uuid); }
    public boolean wasUnfrozen(UUID uuid) { return unfrozen.contains(uuid); }
    public boolean wasChallenged(UUID uuid) {
        return challenges.stream().anyMatch(c -> c.uuid().equals(uuid));
    }

    public byte[] lastChallengeNonce(UUID uuid) {
        byte[] out = null;
        for (Challenge c : challenges) {
            if (c.uuid().equals(uuid)) out = c.nonce();
        }
        return out;
    }

    public List<String> messagesTo(UUID uuid) {
        List<String> out = new ArrayList<>();
        for (Message m : messages) {
            if (m.uuid().equals(uuid)) out.add(m.message());
        }
        return out;
    }
}
