package online.pigeonshouse.shelliconkit.cli;

import online.pigeonshouse.shelliconkit.IconResources;
import online.pigeonshouse.shelliconkit.WindowsIcons;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static java.nio.file.StandardOpenOption.CREATE_NEW;
import static java.nio.file.StandardOpenOption.WRITE;

/** Command-line entry point for exporting a Shell icon. */
public final class IconTool {
    private IconTool() {}

    public static void main(String[] args) {
        try {
            if (args.length == 2 && args[0].equals("--list")) {
                list(Paths.get(args[1]));
                return;
            }
            if (args.length < 2 || args.length > 3 || args[0].equals("--list")) {
                System.err.println("Usage: java -jar shelliconkit-2.0.0.jar <item> <new-png-file> [edge]");
                System.err.println("       java -jar shelliconkit-2.0.0.jar --list <exe|dll|ico>");
                System.exit(2);
                return;
            }
            Path input = Paths.get(args[0]);
            int edge = args.length == 3 ? Integer.parseInt(args[2]) : WindowsIcons.DEFAULT_EDGE;
            byte[] png = WindowsIcons.png(input, edge);
            Path output = Paths.get(args[1]);
            Files.write(output, png, CREATE_NEW, WRITE);
            System.out.println(output.toAbsolutePath());
        } catch (FileAlreadyExistsException e) {
            System.err.println("ShellIconKit: output already exists: " + e.getFile());
            System.exit(1);
        } catch (IOException | IllegalArgumentException | UnsupportedOperationException e) {
            System.err.println("ShellIconKit: " + e.getMessage());
            System.exit(1);
        }
    }

    private static void list(Path input) throws IOException {
        List<IconResources.Group> groups = IconResources.list(input);
        System.out.printf("%d icon group(s) in %s%n", groups.size(), input.toAbsolutePath());
        for (IconResources.Group group : groups) {
            String name = group.getResourceName().orElseGet(() ->
                    group.getResourceId().isPresent() ? "#" + group.getResourceId().getAsInt() : "ICO");
            if (group.getLanguageId().isPresent()) {
                name += String.format("  LANGID 0x%04X", group.getLanguageId().getAsInt());
            }
            System.out.println(name);
            for (IconResources.Variant frame : group.getVariants()) {
                String depth = frame.getBitDepth() == 0 ? "unspecified bpp" : frame.getBitDepth() + " bpp";
                System.out.printf("  [%d] %dx%d  %s  %d bytes%n", frame.getIndex(), frame.getWidth(),
                        frame.getHeight(), depth, frame.getEncodedSize());
            }
        }
    }
}
