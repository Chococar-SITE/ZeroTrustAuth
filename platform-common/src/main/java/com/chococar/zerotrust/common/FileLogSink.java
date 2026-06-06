package com.chococar.zerotrust.common;

import com.chococar.zerotrust.audit.LogSink;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Objects;
import java.util.logging.Logger;

/**
 * {@link LogSink} 的檔案實作（計劃 6.2）。與遊戲載入器無關，供所有平台共用。
 *
 * <ul>
 *   <li>將結構化 JSON 行附加至 {@code <dataFolder>/logs/zerotrust.log}。</li>
 *   <li><b>每日輪替</b>：日期變更時，將現有 {@code zerotrust.log} 改名為
 *       {@code zerotrust-YYYY-MM-DD.log}（前一日日期）。</li>
 *   <li><b>保留</b>：刪除超過保留天數的已輪替檔。</li>
 *   <li>執行緒安全（所有公開方法 {@code synchronized}）。</li>
 * </ul>
 *
 * <p>核心保證寫入的 JSON 不含任何秘密；本類別不檢視內容，僅負責落盤與輪替。
 */
public final class FileLogSink implements LogSink {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final String BASE_NAME = "zerotrust";
    private static final String EXT = ".log";

    private final Path logDir;
    private final Path activeFile;
    private final int retentionDays;
    private final Logger log;

    private BufferedWriter writer;
    private LocalDate currentDay;
    private boolean closed;

    /**
     * @param dataFolder    資料夾（其下建立 {@code logs/}）
     * @param retentionDays 已輪替檔保留天數（計劃預設 90；&lt;= 0 表示不清除）
     * @param log           平台 logger（記錄 IO 警告，不含秘密）
     */
    public FileLogSink(Path dataFolder, int retentionDays, Logger log) {
        Objects.requireNonNull(dataFolder, "dataFolder");
        this.logDir = dataFolder.resolve("logs");
        this.activeFile = logDir.resolve(BASE_NAME + EXT);
        this.retentionDays = retentionDays;
        this.log = Objects.requireNonNull(log, "log");
        this.currentDay = today();
        try {
            Files.createDirectories(logDir);
            openWriter();
            purgeOld();
        } catch (IOException e) {
            throw new UncheckedIOException("無法初始化日誌檔：" + activeFile, e);
        }
    }

    @Override
    public synchronized void write(String jsonLine) {
        if (closed || jsonLine == null) {
            return;
        }
        try {
            rotateIfNeeded();
            writer.write(jsonLine);
            writer.write(System.lineSeparator());
            writer.flush();
        } catch (IOException e) {
            log.warning("ZeroTrust 審計日誌寫入失敗：" + e.getMessage());
        }
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        closeWriter();
    }

    // ── 內部 ────────────────────────────────────────────────

    private void openWriter() throws IOException {
        this.writer = Files.newBufferedWriter(
                activeFile,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.APPEND);
    }

    private void closeWriter() {
        if (writer != null) {
            try {
                writer.flush();
                writer.close();
            } catch (IOException e) {
                log.warning("ZeroTrust 關閉日誌檔時發生錯誤：" + e.getMessage());
            } finally {
                writer = null;
            }
        }
    }

    private void rotateIfNeeded() throws IOException {
        LocalDate now = today();
        if (now.equals(currentDay)) {
            return;
        }
        closeWriter();
        if (Files.exists(activeFile)) {
            Path archived = logDir.resolve(BASE_NAME + "-" + currentDay.format(DATE) + EXT);
            try {
                if (Files.exists(archived)) {
                    archived = logDir.resolve(
                            BASE_NAME + "-" + currentDay.format(DATE) + "-" + System.currentTimeMillis() + EXT);
                }
                Files.move(activeFile, archived);
            } catch (IOException e) {
                log.warning("ZeroTrust 日誌輪替改名失敗：" + e.getMessage());
            }
        }
        currentDay = now;
        openWriter();
        purgeOld();
    }

    private void purgeOld() {
        if (retentionDays <= 0) {
            return;
        }
        LocalDate cutoff = today().minusDays(retentionDays);
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(logDir, BASE_NAME + "-*" + EXT)) {
            for (Path p : stream) {
                LocalDate fileDate = parseArchiveDate(p);
                if (fileDate != null && fileDate.isBefore(cutoff)) {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException e) {
                        log.warning("ZeroTrust 無法刪除過期日誌 " + p.getFileName() + "：" + e.getMessage());
                    }
                }
            }
        } catch (IOException e) {
            log.warning("ZeroTrust 清理過期日誌時發生錯誤：" + e.getMessage());
        }
    }

    private static LocalDate parseArchiveDate(Path p) {
        String name = p.getFileName().toString();
        String prefix = BASE_NAME + "-";
        if (!name.startsWith(prefix) || !name.endsWith(EXT)) {
            return null;
        }
        String middle = name.substring(prefix.length(), name.length() - EXT.length());
        if (middle.length() < 10) {
            return null;
        }
        try {
            return LocalDate.parse(middle.substring(0, 10), DATE);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static LocalDate today() {
        return LocalDate.now(ZoneOffset.UTC);
    }
}
