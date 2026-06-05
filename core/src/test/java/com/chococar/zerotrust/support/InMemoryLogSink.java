package com.chococar.zerotrust.support;

import com.chococar.zerotrust.audit.LogSink;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/** 測試用 {@link LogSink}：收集所有寫入的 JSON 行。 */
public final class InMemoryLogSink implements LogSink {

    public final List<String> lines = new CopyOnWriteArrayList<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);

    @Override
    public void write(String jsonLine) {
        lines.add(jsonLine);
    }

    @Override
    public void close() {
        closed.set(true);
    }

    public boolean isClosed() { return closed.get(); }

    public String last() {
        return lines.isEmpty() ? null : lines.get(lines.size() - 1);
    }
}
