package online.pigeonshouse.shelliconkit;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

final class NativeIcons {
    static final int BATCH_SIZE = 32;
    private static boolean loaded;

    private NativeIcons() {}

    static synchronized void ensureLoaded() throws IOException {
        if (loaded) {
            return;
        }
        if (!System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("windows")) {
            throw new UnsupportedOperationException("ShellIconKit native operations require Windows");
        }
        String cpu = System.getProperty("os.arch");
        String arch;
        switch (cpu.toLowerCase(Locale.ROOT)) {
            case "amd64":
            case "x86_64":
                arch = "x64";
                break;
            case "x86":
            case "i386":
            case "i486":
            case "i586":
            case "i686":
                arch = "x86";
                break;
            default:
                throw new UnsupportedOperationException("No ShellIconKit DLL for JVM architecture " + cpu);
        }
        String resource = "/online/pigeonshouse/shelliconkit/native/" + arch + "/shelliconkit.dll";
        try (InputStream stream = NativeIcons.class.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IOException("The ShellIconKit JAR is missing " + resource);
            }
            Path dir = Files.createTempDirectory("shelliconkit-");
            Path dll = dir.resolve("shelliconkit.dll");
            try {
                Files.copy(stream, dll);
                dir.toFile().deleteOnExit();
                dll.toFile().deleteOnExit();
                System.load(dll.toAbsolutePath().toString());
                loaded = true;
            } catch (IOException | UnsatisfiedLinkError e) {
                try {
                    Files.deleteIfExists(dll);
                    Files.deleteIfExists(dir);
                } catch (IOException cleanup) {
                    e.addSuppressed(cleanup);
                }
                throw new IOException("Could not load the ShellIconKit " + arch + " library", e);
            }
        }
    }

    static BufferedImage toImage(int[] raster) {
        int width = raster[0];
        int height = raster[1];
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, width, height, raster, 2, width);
        return image;
    }

    static byte[] toPng(BufferedImage image) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "png", output)) {
            throw new IOException("No PNG encoder available");
        }
        return output.toByteArray();
    }

    static <T> List<T> batchInput(List<? extends T> items) {
        List<T> copy = new ArrayList<>(Objects.requireNonNull(items, "items"));
        for (T item : copy) {
            Objects.requireNonNull(item, "batch item");
        }
        return copy;
    }

    /** Native rasters contain width, height, then straight-alpha ARGB pixels. A bare null means E_PENDING. */
    static native int[] readShell(String path, int edge) throws IOException;

    /** Null input paths are skipped. A null raster with no error means E_PENDING for an active path. */
    static native int[][] readShellBatch(String[] paths, int edge, IOException[] errors) throws IOException;

    static PeCatalog[] listPe(String[] paths, IOException[] errors) throws IOException {
        ensureLoaded();
        PeCatalog[] catalogs = new PeCatalog[paths.length];
        for (int i = 0; i < paths.length; i++) {
            if (paths[i] != null) {
                catalogs[i] = new PeCatalog();
            }
        }
        listPeBatch(paths, catalogs, errors);
        return catalogs;
    }

    private static native void listPeBatch(String[] paths, PeCatalog[] catalogs, IOException[] errors);

    static native int[] readPeIcon(String path, int resourceId, int languageId, long encodedSize) throws IOException;

    static native int[] decodeIcon(byte[] frame) throws IOException;

    /** PE requests with the same path are contiguous. Each PE key is ID, language, encoded size. */
    static native int[][] readFramesBatch(String[] paths, long[] peKeys, byte[][] icoFrames,
                                          IOException[] errors) throws IOException;

    static final class PeCatalog {
        int[] idsAndLanguages;
        String[] names;
        byte[][] directories;
    }
}
