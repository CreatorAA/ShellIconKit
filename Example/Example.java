package online.pigeonshouse.shelliconkit.demo;

import online.pigeonshouse.shelliconkit.BatchResult;
import online.pigeonshouse.shelliconkit.IconResources;
import online.pigeonshouse.shelliconkit.WindowsIcons;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

public final class Example {
    public static void main(String[] args) throws IOException {
        Path system32 = Paths.get(System.getenv("WINDIR"), "System32");
        List<Path> files = args.length == 0
                ? Arrays.asList(system32.resolve("cmd.exe"), system32.resolve("notepad.exe"))
                : Arrays.stream(args).map(Paths::get).collect(Collectors.toList());

        List<BatchResult<BufferedImage>> icons = WindowsIcons.readBatch(files, 48);
        List<BatchResult<List<IconResources.Group>>> catalogs = IconResources.listBatch(files);
        List<IconResources.Variant> frames = new ArrayList<>();
        for (int i = 0; i < files.size(); i++) {
            printIcon("Shell " + files.get(i), icons.get(i));
            try {
                catalogs.get(i).get().stream()
                        .flatMap(group -> group.getVariants().stream())
                        .max(Comparator.comparingInt(frame -> frame.getWidth() * frame.getHeight()))
                        .ifPresent(frames::add);
            } catch (IOException error) {
                System.err.println("Catalog " + files.get(i) + ": " + error.getMessage());
            }
        }

        List<BatchResult<BufferedImage>> originals = IconResources.readBatch(frames);
        for (int i = 0; i < frames.size(); i++) {
            printIcon("Embedded " + frames.get(i).getSource(), originals.get(i));
        }
    }

    private static void printIcon(String label, BatchResult<BufferedImage> result) {
        try {
            BufferedImage icon = result.get();
            System.out.printf("%s: %dx%d%n", label, icon.getWidth(), icon.getHeight());
        } catch (IOException error) {
            System.err.println(label + ": " + error.getMessage());
        }
    }
}
