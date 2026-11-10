package online.pigeonshouse.shelliconkit;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Reads the Windows Shell icon of a filesystem item, not its thumbnail. */
public final class WindowsIcons {
    public static final int DEFAULT_EDGE = 256;
    private static final int FIRST_RETRY_DELAY = 20;
    private static final int LAST_RETRY_DELAY = 160;

    private WindowsIcons() {}

    /** Requests an extra-large Shell icon. Call off the UI thread. */
    public static BufferedImage read(Path item) throws IOException {
        return read(item, DEFAULT_EDGE);
    }

    /**
     * Requests an icon that fits within {@code edge} physical pixels (1–256).
     * Windows chooses the image. A large bitmap can still come from a small source asset.
     * This may do disk I/O or call a Shell handler; run it off the UI thread.
     *
     * @throws IOException if the item cannot be read, the Shell fails, or the bundled DLL cannot load
     * @throws UnsupportedOperationException outside Windows x86/x64 or for a non-default Path provider
     */
    public static BufferedImage read(Path item, int edge) throws IOException {
        Objects.requireNonNull(item, "item");
        checkEdge(edge);
        String path = item.toFile().getAbsolutePath();
        NativeIcons.ensureLoaded();
        return NativeIcons.toImage(readWithRetry(path, edge));
    }

    /** Requests default-size icons for a batch. Call off the UI thread. */
    public static List<BatchResult<BufferedImage>> readBatch(List<? extends Path> items) throws IOException {
        return readBatch(items, DEFAULT_EDGE);
    }

    /**
     * Returns one result per path, in input order. File errors do not stop other items.
     * The native work runs in chunks on the calling thread; the list retains all returned images.
     *
     * @throws IOException if the native reader cannot initialize or waiting for the Shell is interrupted
     */
    public static List<BatchResult<BufferedImage>> readBatch(List<? extends Path> items, int edge)
            throws IOException {
        checkEdge(edge);
        List<Path> input = NativeIcons.batchInput(items);
        List<BatchResult<BufferedImage>> results = new ArrayList<>(Collections.nCopies(input.size(), null));
        for (int start = 0; start < input.size();) {
            int count = Math.min(NativeIcons.BATCH_SIZE, input.size() - start);
            String[] paths = new String[count];
            boolean active = false;
            for (int i = 0; i < count; i++) {
                Path item = input.get(start + i);
                try {
                    paths[i] = item.toFile().getAbsolutePath();
                    active = true;
                } catch (UnsupportedOperationException error) {
                    results.set(start + i, BatchResult.failure(new IOException("Not a Windows file path: " + item, error)));
                }
            }
            if (active) {
                NativeIcons.ensureLoaded();
                readChunk(paths, edge, results, start);
            }
            start += count;
        }
        return Collections.unmodifiableList(results);
    }

    /** Encodes the default-size Shell icon as PNG. Call off the UI thread. */
    public static byte[] png(Path item) throws IOException {
        return png(item, DEFAULT_EDGE);
    }

    /** Encodes the Shell icon as PNG with the same size semantics as {@link #read(Path, int)}. */
    public static byte[] png(Path item, int edge) throws IOException {
        return NativeIcons.toPng(read(item, edge));
    }

    private static void readChunk(String[] paths, int edge, List<BatchResult<BufferedImage>> results, int start)
            throws IOException {
        for (int delay = FIRST_RETRY_DELAY; ; delay *= 2) {
            IOException[] errors = new IOException[paths.length];
            int[][] rasters = NativeIcons.readShellBatch(paths, edge, errors);
            boolean pending = false;
            for (int i = 0; i < paths.length; i++) {
                if (paths[i] == null) {
                    continue;
                }
                BatchResult<BufferedImage> result;
                if (errors[i] != null) {
                    result = BatchResult.failure(errors[i]);
                } else if (rasters[i] != null) {
                    result = BatchResult.success(NativeIcons.toImage(rasters[i]));
                    rasters[i] = null;
                } else if (delay > LAST_RETRY_DELAY) {
                    result = BatchResult.failure(pendingError(paths[i]));
                } else {
                    pending = true;
                    continue;
                }
                results.set(start + i, result);
                paths[i] = null; // Do not submit completed items again.
            }
            if (!pending) {
                return;
            }
            waitToRetry(delay, "icon batch");
        }
    }

    private static int[] readWithRetry(String path, int edge) throws IOException {
        // The Shell can return E_PENDING while it builds a cold icon cache entry.
        for (int delay = FIRST_RETRY_DELAY; ; delay *= 2) {
            int[] raster = NativeIcons.readShell(path, edge);
            if (raster != null) {
                return raster;
            }
            if (delay > LAST_RETRY_DELAY) {
                throw pendingError(path);
            }
            waitToRetry(delay, path);
        }
    }

    private static void checkEdge(int edge) {
        if (edge < 1 || edge > DEFAULT_EDGE) {
            throw new IllegalArgumentException("Icon edge must be between 1 and " + DEFAULT_EDGE + " pixels");
        }
    }

    private static IOException pendingError(String path) {
        return new IOException("Windows Shell icon still pending (HRESULT 0x8000000A): " + path);
    }

    private static void waitToRetry(int delay, String item) throws InterruptedIOException {
        try {
            Thread.sleep(delay);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            InterruptedIOException failure = new InterruptedIOException("Interrupted waiting for icon: " + item);
            failure.initCause(interrupted);
            throw failure;
        }
    }
}
