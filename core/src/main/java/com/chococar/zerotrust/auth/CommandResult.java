package com.chococar.zerotrust.auth;

import java.util.Objects;

/** {@code /authkey} 子指令的結果，供指令層回饋使用者。 */
public final class CommandResult {

    private final boolean success;
    private final String message;

    private CommandResult(boolean success, String message) {
        this.success = success;
        this.message = Objects.requireNonNull(message, "message");
    }

    public static CommandResult ok(String message) { return new CommandResult(true, message); }
    public static CommandResult fail(String message) { return new CommandResult(false, message); }

    public boolean success() { return success; }
    public String message() { return message; }
}
