# ShellIconKit

Read Windows icons for Java launchers and file managers. Request the size you need instead of enlarging the small icon returned by older JDKs, or list and read the original icon frames embedded in an EXE, DLL or ICO.

Supports Windows x86 and x64 with Java 8 or later.

[中文](README_CN.md) · [JDK comparison](docs/comparison.md)

```powershell
java -jar .\target\shelliconkit-2.0.0.jar "$env:WINDIR\System32\cmd.exe" .\cmd.png
java -jar .\target\shelliconkit-2.0.0.jar --list "$env:WINDIR\System32\cmd.exe"
```

## Example

See [Example.java](Example/Example.java).

- `WindowsIcons.read(path)` / `png(path)`: get the Shell icon for a file, folder or shortcut. The default request is 256 px; pass a smaller size if needed. Windows may resize or pad the image.
- `IconResources.list(path)`: list embedded groups and frames in PE files such as EXE and DLL, or in an ICO. The list contains metadata only. `read(frame)` and `png(frame)` decode the selected frame at its original size.
- Batch methods: `WindowsIcons.readBatch(paths)`, `IconResources.listBatch(paths)`, and `IconResources.readBatch(frames)`. Results follow input order, with errors reported per item.
- Read images off the UI thread. For JavaFX, use `new Image(new ByteArrayInputStream(png))`.

## Build

Run `.\build.ps1` with JDK 8 (or a newer JDK that supports `--release 8`), CMake 3.21+, Visual Studio 2022 C++ tools and a Windows SDK. The output is `target\shelliconkit-2.0.0.jar`.

Add the JAR to the classpath. No `init()` call or manual DLL placement is needed. For library use on JDK 24+, enable native access with `--enable-native-access=ALL-UNNAMED` on the classpath or `--enable-native-access=online.pigeonshouse.shelliconkit` on the module path.

[MIT](LICENSE)

