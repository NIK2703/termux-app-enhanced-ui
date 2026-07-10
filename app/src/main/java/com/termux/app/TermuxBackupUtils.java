package com.termux.app;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.R;
import com.termux.shared.errors.Error;
import com.termux.shared.file.filesystem.FileTypes;
import com.termux.shared.file.FileUtils;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.atomic.AtomicReference;

public final class TermuxBackupUtils {

    private static final String LOG_TAG = "TermuxBackupUtils";
    private static final String TAR_BINARY = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/tar";

    public interface ResultListener {
        void onResult(@Nullable Error error);
    }

    @FunctionalInterface
    public interface ProgressCallback {
        /**
         * @param bytesCopied bytes transferred so far (compressed stream)
         * @param totalBytes  estimated total (0 = unknown / indeterminate)
         */
        void onProgress(long bytesCopied, long totalBytes);
    }

    private TermuxBackupUtils() {}

    // -----------------------------------------------------------------------
    // Size estimation
    // -----------------------------------------------------------------------

    /**
     * Estimates the total uncompressed size of the Termux data directory
     * ({@code $FILES}) by running {@code du -sb}.  Returns 0 if the estimate
     * cannot be obtained.
     */
    public static long getEstimatedBackupSize(@NonNull Context context) {
        Error health = checkTarHealth(context);
        if (health != null) return 0;
        final String filesDir = TermuxConstants.TERMUX_FILES_DIR_PATH;
        try {
            ProcessBuilder pb = new ProcessBuilder("/system/bin/du", "-sb", filesDir);
            pb.environment().clear();
            pb.environment().put("PATH", TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH);
            Process proc = pb.start();
            byte[] buf = new byte[128];
            int n = proc.getInputStream().read(buf);
            proc.waitFor();
            if (n > 0) {
                String line = new String(buf, 0, n, java.nio.charset.StandardCharsets.US_ASCII).trim();
                int space = line.indexOf('\t');
                if (space < 0) space = line.indexOf(' ');
                if (space > 0) {
                    return Long.parseLong(line.substring(0, space));
                }
            }
        } catch (Exception ignored) {
        }
        return 0;
    }

    // -----------------------------------------------------------------------
    // Health check
    // -----------------------------------------------------------------------

    @Nullable
    private static Error checkTarHealth(@NonNull Context context) {
        File tarFile = new File(TAR_BINARY);
        if (!tarFile.isFile()) {
            return new Error(context.getString(R.string.backup_restore_need_termux));
        }
        try {
            ProcessBuilder pb = new ProcessBuilder(TAR_BINARY, "--version");
            pb.environment().clear();
            pb.environment().put("PATH", TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH);
            pb.environment().put("PREFIX", TermuxConstants.TERMUX_PREFIX_DIR_PATH);
            pb.environment().put("HOME", TermuxConstants.TERMUX_HOME_DIR_PATH);
            pb.environment().put("TMPDIR", TermuxConstants.TERMUX_TMP_PREFIX_DIR_PATH);
            pb.directory(new File(TermuxConstants.TERMUX_PREFIX_DIR_PATH));
            int code = pb.start().waitFor();
            if (code != 0) {
                return new Error("tar binary health check failed (exit " + code + ")");
            }
        } catch (IOException | InterruptedException e) {
            return new Error("tar binary health check failed: " + e.getMessage());
        }
        return null;
    }

    // -----------------------------------------------------------------------
    // Backup
    // -----------------------------------------------------------------------

    public static void backup(@NonNull Context context, @NonNull OutputStream out,
                              @NonNull ResultListener listener,
                              @Nullable ProgressCallback progress) {
        Error health = checkTarHealth(context);
        if (health != null) {
            listener.onResult(health);
            return;
        }
        final String filesDir = TermuxConstants.TERMUX_FILES_DIR_PATH;
        final String parentDir = TermuxConstants.TERMUX_INTERNAL_PRIVATE_APP_DATA_DIR_PATH;
        long estimatedSize = getEstimatedBackupSize(context);
        // Use -C <absolute files path> so tar's view of "." is always the on-disk
        // files/ directory.  pb.directory(parent) only sets the process start dir;
        // -C is what tar actually uses for extraction/creation and survives any
        // directory removal/recreation done between fork-exec and tar's chdir.
        runTar(context,
            new String[]{TAR_BINARY, "-czpf", "-", "--numeric-owner",
                "-C", filesDir, "."},
            null, out, listener, progress,
            new File(parentDir), estimatedSize);
    }

    // -----------------------------------------------------------------------
    // Restore
    // -----------------------------------------------------------------------

    /** Log the on-disk state of a path: existence, type, perms and (for dirs) entry count + size. */
    private static void logDirState(String stage, String path) {
        File f = new File(path);
        if (!f.exists()) {
            Logger.logInfo(LOG_TAG, "[restore-state] " + stage + ": " + path
                + " => DOES NOT EXIST");
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(stage).append(": ").append(path)
            .append(" exists=").append(true)
            .append(" isDir=").append(f.isDirectory())
            .append(" isFile=").append(f.isFile())
            .append(" canRead=").append(f.canRead())
            .append(" canWrite=").append(f.canWrite());
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            int count = (children != null) ? children.length : -1;
            long size = 0;
            if (children != null) for (File c : children) size += sizeOf(c);
            sb.append(" entries=").append(count).append(" sizeBytes=").append(size);
        } else {
            sb.append(" length=").append(f.length());
        }
        Logger.logInfo(LOG_TAG, "[restore-state] " + sb);
    }

    /** Recursive size in bytes. */
    private static long sizeOf(File f) {
        if (f.isFile()) return f.length();
        long total = 0;
        File[] children = f.listFiles();
        if (children != null) for (File c : children) total += sizeOf(c);
        return total;
    }

    /** Remove the *contents* of {@code dir} (not the directory itself) so its inode stays stable.
     * This avoids the "unlinked inode" bug where tar (already chdir'd into dir) keeps writing
     * into a dangling inode after the directory is deleted and recreated. */
    private static void clearDirectoryContents(String label, String dirPath) {
        File dir = new File(dirPath);
        if (!dir.isDirectory()) return;
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File child : children) {
            Error err = FileUtils.deleteFile(label, child.getAbsolutePath(), true, false,
                FileTypes.FILE_TYPE_ANY_FLAGS);
            if (err != null) {
                Logger.logError(LOG_TAG, "Failed to delete " + child.getAbsolutePath()
                    + ": " + err.getMinimalErrorString());
            }
        }
    }

    public static void restore(@NonNull Context context, @NonNull InputStream in,
                               @NonNull ResultListener listener,
                               @Nullable ProgressCallback progress) {
        final String filesDir = TermuxConstants.TERMUX_FILES_DIR_PATH;
        final String parentDir = TermuxConstants.TERMUX_INTERNAL_PRIVATE_APP_DATA_DIR_PATH;

        Error health = checkTarHealth(context);
        if (health != null) {
            listener.onResult(health);
            return;
        }

        // Diagnostic: state BEFORE any change.
        logDirState("BEFORE tar-start", filesDir);
        logDirState("BEFORE tar-start (tar binary)", TAR_BINARY);

        try {
            // Start tar FIRST so the binary is loaded into memory before we wipe $FILES.
            // -C <absolute files path> makes tar chdir into the on-disk files/ directory at
            // extraction time, AFTER our wipe+mkdirs below — so it always targets the live
            // directory, not a dangling inode left behind by deleteDirectoryFile().
            // pb.directory(parent) is only the process start dir (a path tar never removes).
            ProcessBuilder pb = new ProcessBuilder(
                TAR_BINARY, "-xzpf", "-", "--numeric-owner", "--no-same-owner",
                "-C", filesDir);
            pb.environment().clear();
            pb.environment().put("PATH", TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH);
            pb.environment().put("PREFIX", TermuxConstants.TERMUX_PREFIX_DIR_PATH);
            pb.environment().put("HOME", TermuxConstants.TERMUX_HOME_DIR_PATH);
            pb.environment().put("TMPDIR", TermuxConstants.TERMUX_TMP_PREFIX_DIR_PATH);
            pb.directory(new File(parentDir));
            final Process process = pb.start();

            // Diagnostics: tar has started; its CWD inode.
            logDirState("AFTER tar-start (tar CWD)", filesDir);

            // Wipe the *contents* of files/ (NOT the directory itself, so the inode stays
            // stable and tar keeps writing into the live directory).
            clearDirectoryContents("restore_wipe_files", filesDir);
            logDirState("AFTER wipe-contents", filesDir);

            // The directory itself still exists (same inode) — no mkdirs needed. If it was
            // somehow removed, recreate it, but that is the degenerate path.
            File fd = new File(filesDir);
            if (!fd.exists() && !fd.mkdirs()) {
                process.destroy();
                listener.onResult(new Error("Failed to recreate " + filesDir));
                return;
            }

            final StringBuilder stderr = new StringBuilder();
            final Thread errPump = new Thread(() -> {
                try (InputStream e = process.getErrorStream()) {
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = e.read(buf)) > 0)
                        stderr.append(new String(buf, 0, n, java.nio.charset.StandardCharsets.UTF_8));
                } catch (IOException ignored) {
                }
            });
            errPump.start();

            final AtomicReference<IOException> pumpError = new AtomicReference<>();
            // totalBytes for restore is unknown here — the caller (fragment) gets
            // the archive size from SAF and passes it via the ProgressCallback if it wants.
            final Thread dataPump = new Thread(() -> {
                long bytesCopied = 0;
                long lastReport = 0;
                try (InputStream i = in; OutputStream p = process.getOutputStream()) {
                    byte[] buf = new byte[32768];
                    int n;
                    while ((n = i.read(buf)) > 0) {
                        p.write(buf, 0, n);
                        bytesCopied += n;
                        if (progress != null && bytesCopied - lastReport >= 1_000_000) {
                            lastReport = bytesCopied;
                            // total = 0 means unknown; the caller (fragment) will provide
                            // total from SAF stat via a wrapping callback if known.
                            progress.onProgress(bytesCopied, 0);
                        }
                    }
                } catch (IOException e) {
                    pumpError.set(e);
                    Logger.logStackTraceWithMessage(LOG_TAG, "Error feeding tar input stream", e);
                    process.destroy();
                }
            });
            dataPump.start();

            final int exitCode = process.waitFor();
            errPump.join();
            dataPump.join();

            // Diagnostic: state AFTER extraction.
            logDirState("AFTER extract (exit=" + exitCode + ")", filesDir);
            logDirState("AFTER extract (tar binary)", TAR_BINARY);

            IOException pErr = pumpError.get();
            if (pErr != null) {
                rollbackRestore(filesDir);
                listener.onResult(new Error("Restore I/O error: " + pErr.getMessage(), pErr));
            } else if (exitCode == 0) {
                listener.onResult(null);
            } else {
                rollbackRestore(filesDir);
                String msg = stderr.toString().trim();
                if (msg.isEmpty()) msg = "tar exited with code " + exitCode;
                listener.onResult(new Error(msg));
            }
        } catch (Exception e) {
            listener.onResult(new Error(e.getMessage(), e));
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static void rollbackRestore(final String filesDir) {
        logDirState("ROLLBACK before", filesDir);
        // Clear contents (keep the directory inode stable) instead of deleting the dir itself.
        clearDirectoryContents("restore_rollback_partial", filesDir);
        if (!new File(filesDir).exists()) {
            if (!new File(filesDir).mkdirs()) {
                Logger.logError(LOG_TAG, "Rollback: failed to recreate " + filesDir);
            }
        }
        logDirState("ROLLBACK after", filesDir);
    }

    // -----------------------------------------------------------------------
    // Shared runner (used by backup)
    // -----------------------------------------------------------------------

    private static void runTar(@NonNull Context context, String[] command,
                               @Nullable InputStream in, @Nullable OutputStream out,
                               @NonNull ResultListener listener,
                               @Nullable ProgressCallback progress,
                               @Nullable File workingDir,
                               long totalBytes) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.environment().clear();
            pb.environment().put("PATH", TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH);
            pb.environment().put("PREFIX", TermuxConstants.TERMUX_PREFIX_DIR_PATH);
            pb.environment().put("HOME", TermuxConstants.TERMUX_HOME_DIR_PATH);
            pb.environment().put("TMPDIR", TermuxConstants.TERMUX_TMP_PREFIX_DIR_PATH);
            pb.directory(workingDir != null
                ? workingDir
                : new File(TermuxConstants.TERMUX_PREFIX_DIR_PATH));
            final Process process = pb.start();

            final StringBuilder stderr = new StringBuilder();
            final Thread errPump = new Thread(() -> {
                try (InputStream e = process.getErrorStream()) {
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = e.read(buf)) > 0)
                        stderr.append(new String(buf, 0, n, java.nio.charset.StandardCharsets.UTF_8));
                } catch (IOException ignored) {
                }
            });
            errPump.start();

            final AtomicReference<IOException> pumpError = new AtomicReference<>();
            final Thread dataPump;
            if (out != null) {
                final OutputStream finalOut = out;
                dataPump = new Thread(() -> {
                    long bytesCopied = 0;
                    long lastReport = 0;
                    try (OutputStream o = finalOut; InputStream p = process.getInputStream()) {
                        byte[] buf = new byte[32768];
                        int n;
                        while ((n = p.read(buf)) > 0) {
                            o.write(buf, 0, n);
                            bytesCopied += n;
                            if (progress != null && bytesCopied - lastReport >= 1_000_000) {
                                lastReport = bytesCopied;
                                progress.onProgress(bytesCopied, totalBytes);
                            }
                        }
                    } catch (IOException e) {
                        pumpError.set(e);
                        Logger.logStackTraceWithMessage(LOG_TAG, "Error reading tar output stream", e);
                        process.destroy();
                    }
                });
            } else {
                final InputStream finalIn = in;
                dataPump = new Thread(() -> {
                    long bytesCopied = 0;
                    long lastReport = 0;
                    try (InputStream i = finalIn; OutputStream p = process.getOutputStream()) {
                        byte[] buf = new byte[32768];
                        int n;
                        while ((n = i.read(buf)) > 0) {
                            p.write(buf, 0, n);
                            bytesCopied += n;
                            if (progress != null && bytesCopied - lastReport >= 1_000_000) {
                                lastReport = bytesCopied;
                                progress.onProgress(bytesCopied, totalBytes);
                            }
                        }
                    } catch (IOException e) {
                        pumpError.set(e);
                        Logger.logStackTraceWithMessage(LOG_TAG, "Error feeding tar input stream", e);
                        process.destroy();
                    }
                });
            }
            dataPump.start();

            final int exitCode = process.waitFor();
            errPump.join();
            dataPump.join();

            IOException pErr = pumpError.get();
            if (pErr != null) {
                listener.onResult(new Error("I/O error: " + pErr.getMessage(), pErr));
            } else if (exitCode == 0) {
                listener.onResult(null);
            } else {
                String msg = stderr.toString().trim();
                if (msg.isEmpty()) msg = "tar exited with code " + exitCode;
                listener.onResult(new Error(msg));
            }
        } catch (Exception e) {
            listener.onResult(new Error(e.getMessage(), e));
        }
    }
}
