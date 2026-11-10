package online.pigeonshouse.shelliconkit;

import java.awt.image.BufferedImage;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/** Enumerates and decodes embedded ICO or Windows PE icon resources, independent of the Shell. */
public final class IconResources {
    private static final int HEADER_BYTES = 6;
    private static final int ICO_ENTRY_BYTES = 16;
    private static final int PE_ENTRY_BYTES = 14;
    private static final long MAX_FRAME_BYTES = 16 * 1024 * 1024; // Bound the selected-frame allocation on corrupt files.

    private IconResources() {}

    /**
     * Lists embedded icon groups and their frames without decoding or retaining any frame bytes.
     * A standalone ICO has one group. A PE with no icon groups returns an empty list.
     * Groups in a PE are identified by name or numeric ID and by language.
     *
     * @throws IOException if the file is not an ICO or PE, is malformed, or cannot be opened
     */
    public static List<Group> list(Path file) throws IOException {
        Path source = Objects.requireNonNull(file, "file").toAbsolutePath();
        List<Group> ico = listIco(source);
        if (ico != null) {
            return ico;
        }
        IOException[] errors = new IOException[1];
        NativeIcons.PeCatalog catalog = NativeIcons.listPe(new String[]{source.toFile().getPath()}, errors)[0];
        if (errors[0] != null) {
            throw errors[0];
        }
        return peGroups(source, catalog);
    }

    /**
     * Lists icon metadata for each file in input order. No pixel data is read.
     * A PE without icons has a successful empty list; file errors are returned per item.
     *
     * @throws IOException if the native reader is needed but cannot load
     */
    public static List<BatchResult<List<Group>>> listBatch(List<? extends Path> files) throws IOException {
        List<Path> input = NativeIcons.batchInput(files);
        List<BatchResult<List<Group>>> results = new ArrayList<>(Collections.nCopies(input.size(), null));
        for (int start = 0; start < input.size();) {
            int count = Math.min(NativeIcons.BATCH_SIZE, input.size() - start);
            Path[] sources = new Path[count];
            String[] pePaths = new String[count];
            boolean hasPe = false;
            for (int i = 0; i < count; i++) {
                Path file = input.get(start + i);
                try {
                    Path source = file.toAbsolutePath();
                    List<Group> ico = listIco(source);
                    if (ico != null) {
                        results.set(start + i, BatchResult.success(ico));
                    } else {
                        sources[i] = source;
                        pePaths[i] = source.toFile().getPath();
                        hasPe = true;
                    }
                } catch (IOException error) {
                    results.set(start + i, BatchResult.failure(error));
                } catch (UnsupportedOperationException error) {
                    results.set(start + i, BatchResult.failure(new IOException("Unsupported file path: " + file, error)));
                }
            }
            if (hasPe) {
                IOException[] errors = new IOException[count];
                NativeIcons.PeCatalog[] catalogs = NativeIcons.listPe(pePaths, errors);
                for (int i = 0; i < count; i++) {
                    if (pePaths[i] == null) {
                        continue;
                    }
                    if (errors[i] != null) {
                        results.set(start + i, BatchResult.failure(errors[i]));
                    } else {
                        try {
                            results.set(start + i, BatchResult.success(peGroups(sources[i], catalogs[i])));
                        } catch (IOException error) {
                            results.set(start + i, BatchResult.failure(error));
                        }
                    }
                }
            }
            start += count;
        }
        return Collections.unmodifiableList(results);
    }

    /** Returns null for a PE candidate, otherwise parses the ICO directory. */
    private static List<Group> listIco(Path source) throws IOException {
        try (FileChannel in = FileChannel.open(source)) {
            ByteBuffer header = readAt(in, 0, HEADER_BYTES);
            if (word(header, 0) == 0x5a4d) { // MZ
                return null;
            }
            int count = directoryCount(header);
            int tableSize = count * ICO_ENTRY_BYTES;
            ByteBuffer table = readAt(in, HEADER_BYTES, tableSize);
            long fileSize = in.size();
            List<Variant> variants = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                int start = i * ICO_ENTRY_BYTES;
                long size = uint(table, start + 8);
                long offset = uint(table, start + 12);
                if (size == 0 || offset < HEADER_BYTES + tableSize || size > fileSize - offset) {
                    throw new IOException("ICO frame " + i + " is outside the file: " + source);
                }
                variants.add(variant(source, table, start, i, offset, -1, -1));
            }
            return Collections.singletonList(new Group(-1, null, -1, variants));
        }
    }

    private static List<Group> peGroups(Path source, NativeIcons.PeCatalog catalog) throws IOException {
        List<Group> groups = new ArrayList<>(catalog.directories.length);
        for (int group = 0; group < catalog.directories.length; group++) {
            int id = catalog.idsAndLanguages[2 * group];
            int language = catalog.idsAndLanguages[2 * group + 1];
            ByteBuffer entries = ByteBuffer.wrap(catalog.directories[group]).order(ByteOrder.LITTLE_ENDIAN);
            int count = directoryCount(entries);
            if (entries.capacity() < HEADER_BYTES + count * PE_ENTRY_BYTES) {
                throw new IOException("Truncated PE icon group in " + source);
            }
            List<Variant> variants = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                int start = HEADER_BYTES + i * PE_ENTRY_BYTES;
                variants.add(variant(source, entries, start, i, -1, word(entries, start + 12), language));
            }
            groups.add(new Group(id, catalog.names[group], language, variants));
        }
        groups.sort(Comparator.<Group>comparingInt(g -> g.id < 0 ? 65536 : g.id)
                .thenComparing(g -> g.name, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparingInt(g -> g.language));
        return Collections.unmodifiableList(groups);
    }

    /** Reads one listed frame at its embedded size. Re-list after changing the source file. */
    public static BufferedImage read(Variant frame) throws IOException {
        Objects.requireNonNull(frame, "frame");
        checkFrameSize(frame);
        NativeIcons.ensureLoaded();
        int[] raster;
        if (frame.imageId >= 0) {
            raster = NativeIcons.readPeIcon(frame.source.toFile().getPath(), frame.imageId, frame.language, frame.size);
        } else {
            raster = NativeIcons.decodeIcon(readIcoFrame(frame));
        }
        return frameImage(frame, raster);
    }

    /**
     * Reads selected frames at their embedded sizes. Results follow input order.
     * Frames from the same PE share its resource mapping within each native chunk.
     * Call off the UI thread; the returned list retains all decoded images.
     *
     * @throws IOException if the native reader cannot initialize
     */
    public static List<BatchResult<BufferedImage>> readBatch(List<? extends Variant> frames) throws IOException {
        List<Variant> input = NativeIcons.batchInput(frames);
        List<BatchResult<BufferedImage>> results = new ArrayList<>(Collections.nCopies(input.size(), null));
        for (int start = 0; start < input.size();) {
            int end = start;
            long encoded = 0;
            while (end < input.size() && end - start < NativeIcons.BATCH_SIZE) {
                Variant frame = input.get(end);
                // PE data stays mapped in native code. Only ICO frame bytes enter this budget.
                if (frame.imageId < 0 && frame.size > 0 && frame.size <= MAX_FRAME_BYTES) {
                    if (frame.size > MAX_FRAME_BYTES - encoded) {
                        break;
                    }
                    encoded += frame.size;
                }
                end++;
            }
            readFrameChunk(input, start, end, results);
            start = end;
        }
        return Collections.unmodifiableList(results);
    }

    private static void readFrameChunk(List<Variant> input, int start, int end,
                                       List<BatchResult<BufferedImage>> results) throws IOException {
        List<FrameRequest> requests = new ArrayList<>(end - start);
        for (int i = start; i < end; i++) {
            Variant frame = input.get(i);
            try {
                checkFrameSize(frame);
                if (frame.imageId >= 0) {
                    requests.add(new FrameRequest(i, frame.source.toFile().getPath(), null));
                } else {
                    requests.add(new FrameRequest(i, null, readIcoFrame(frame)));
                }
            } catch (IOException error) {
                results.set(i, BatchResult.failure(error));
            } catch (UnsupportedOperationException error) {
                results.set(i, BatchResult.failure(new IOException("Unsupported file path: " + frame.source, error)));
            }
        }
        if (requests.isEmpty()) {
            return;
        }

        // Keep one PE mapped at a time, even when the requested frames are interleaved.
        requests.sort(Comparator.comparing(request -> request.pePath, Comparator.nullsLast(Comparator.naturalOrder())));
        int count = requests.size();
        String[] paths = new String[count];
        long[] keys = new long[3 * count];
        byte[][] icoFrames = new byte[count][];
        for (int i = 0; i < count; i++) {
            FrameRequest request = requests.get(i);
            Variant frame = input.get(request.index);
            paths[i] = request.pePath;
            keys[3 * i] = frame.imageId;
            keys[3 * i + 1] = frame.language;
            keys[3 * i + 2] = frame.size;
            icoFrames[i] = request.ico;
        }
        NativeIcons.ensureLoaded();
        IOException[] errors = new IOException[count];
        int[][] rasters = NativeIcons.readFramesBatch(paths, keys, icoFrames, errors);
        for (int i = 0; i < count; i++) {
            int index = requests.get(i).index;
            if (errors[i] != null) {
                results.set(index, BatchResult.failure(errors[i]));
            } else {
                try {
                    results.set(index, BatchResult.success(frameImage(input.get(index), rasters[i])));
                } catch (IOException error) {
                    results.set(index, BatchResult.failure(error));
                }
                rasters[i] = null;
            }
        }
    }

    private static void checkFrameSize(Variant frame) throws IOException {
        if (frame.size < 1 || frame.size > MAX_FRAME_BYTES) {
            throw new IOException("Unsupported encoded icon size: " + frame.size + " bytes");
        }
    }

    private static byte[] readIcoFrame(Variant frame) throws IOException {
        try (FileChannel in = FileChannel.open(frame.source)) {
            return readAt(in, frame.offset, (int) frame.size).array();
        }
    }

    private static BufferedImage frameImage(Variant frame, int[] raster) throws IOException {
        if (raster[0] != frame.width || raster[1] != frame.height) {
            throw new IOException("Icon dimensions no longer match the directory: " + frame.source);
        }
        return NativeIcons.toImage(raster);
    }

    /** Encodes one embedded frame as a PNG, without scaling it. */
    public static byte[] png(Variant frame) throws IOException {
        return NativeIcons.toPng(read(frame));
    }

    private static Variant variant(Path source, ByteBuffer data, int start, int index,
                                   long offset, int imageId, int language) {
        int width = Byte.toUnsignedInt(data.get(start));
        int height = Byte.toUnsignedInt(data.get(start + 1));
        return new Variant(source, index, width == 0 ? 256 : width, height == 0 ? 256 : height,
                word(data, start + 6), uint(data, start + 8), offset, imageId, language);
    }

    private static int directoryCount(ByteBuffer header) throws IOException {
        int count = word(header, 4);
        if (word(header, 0) != 0 || word(header, 2) != 1 || count == 0) {
            throw new IOException("Expected a non-empty ICO icon directory or a Windows PE file");
        }
        return count;
    }

    private static int word(ByteBuffer bytes, int offset) {
        return Short.toUnsignedInt(bytes.getShort(offset));
    }

    private static long uint(ByteBuffer bytes, int offset) {
        return Integer.toUnsignedLong(bytes.getInt(offset));
    }

    private static ByteBuffer readAt(FileChannel in, long offset, int size) throws IOException {
        ByteBuffer bytes = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
        in.position(offset);
        while (bytes.hasRemaining()) {
            if (in.read(bytes) < 0) {
                throw new EOFException("Truncated icon directory or frame");
            }
        }
        bytes.flip();
        return bytes;
    }

    private static final class FrameRequest {
        final int index;
        final String pePath;
        final byte[] ico;

        FrameRequest(int index, String pePath, byte[] ico) {
            this.index = index;
            this.pePath = pePath;
            this.ico = ico;
        }
    }

    /** Metadata for one icon group in one language (or the sole group of an ICO). */
    public static final class Group {
        private final int id;
        private final String name;
        private final int language;
        private final List<Variant> variants;

        private Group(int id, String name, int language, List<Variant> variants) {
            this.id = id;
            this.name = name;
            this.language = language;
            this.variants = Collections.unmodifiableList(variants);
        }

        public OptionalInt getResourceId() { return id >= 0 ? OptionalInt.of(id) : OptionalInt.empty(); }
        public Optional<String> getResourceName() { return Optional.ofNullable(name); }
        public OptionalInt getLanguageId() { return language >= 0 ? OptionalInt.of(language) : OptionalInt.empty(); }
        public List<Variant> getVariants() { return variants; }
    }

    /** A frame descriptor, with no pixel array or encoded image payload. */
    public static final class Variant {
        private final Path source;
        private final int index, width, height, bitDepth, imageId, language;
        private final long size, offset;

        private Variant(Path source, int index, int width, int height, int bitDepth, long size,
                        long offset, int imageId, int language) {
            this.source = source;
            this.index = index;
            this.width = width;
            this.height = height;
            this.bitDepth = bitDepth;
            this.size = size;
            this.offset = offset;
            this.imageId = imageId;
            this.language = language;
        }

        public Path getSource() { return source; }
        public int getIndex() { return index; }
        public int getWidth() { return width; }
        public int getHeight() { return height; }
        /** Bit depth recorded in the directory, or zero if unspecified. */
        public int getBitDepth() { return bitDepth; }
        /** Encoded frame size recorded in the directory; no frame bytes are stored here. */
        public long getEncodedSize() { return size; }
        public OptionalInt getImageResourceId() { return imageId >= 0 ? OptionalInt.of(imageId) : OptionalInt.empty(); }
    }
}
