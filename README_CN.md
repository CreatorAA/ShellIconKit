# ShellIconKit

给 Windows 上的 Java 启动器、文件管理器等应用读取图标。旧版 JDK 的接口常只给小图；这个库可以按尺寸向 Windows 请求文件、目录或快捷方式的图标，也可以列出 EXE、DLL、ICO 的内嵌图标并读取指定帧。

支持 Windows x86 / x64，运行在 Java 8 及以上版本。

[English](README.md) · [取图对比](docs/comparison.md)

```powershell
# 导出 Windows 显示的图标
java -jar .\target\shelliconkit-2.0.0.jar "$env:WINDIR\System32\cmd.exe" .\cmd.png
# 列出文件内嵌的图标
java -jar .\target\shelliconkit-2.0.0.jar --list "$env:WINDIR\System32\cmd.exe"
```

## Example

示例请参考[Example.java](Example/Example.java)

- `WindowsIcons.read(path)` / `png(path)`：取文件、目录或快捷方式的系统图标，不取缩略图。默认请求 256 px，可传第二个参数指定更小尺寸；Shell 可能缩放或留白。
- `IconResources.list(path)`：列出 EXE、DLL、ICO 等文件中的组和帧。包含 ID 或名称、语言、尺寸、位深、编码长度，不含图像数据。选中后用 `read(frame)` 或 `png(frame)` 按原尺寸取图。
- 批量接口：`WindowsIcons.readBatch(paths)`、`IconResources.listBatch(paths)`、`IconResources.readBatch(frames)`。每项结果和输入按顺序对应，某项失败不影响其他项。
- JavaFX 可把 PNG 传给 `new Image(new ByteArrayInputStream(png))`。取图放在后台线程。

## 构建

用 JDK 8（或支持 `--release 8` 的新版 JDK）、CMake 3.21+、Visual Studio 2022 C++ 工具和 Windows SDK，运行 `.\build.ps1`。输出 `target\shelliconkit-2.0.0.jar`。

把 JAR 加入 classpath 即可；不需要 `init()` 或手动放置 DLL。JDK 24+ 用作库时，classpath 模式加 `--enable-native-access=ALL-UNNAMED`，模块路径模式加 `--enable-native-access=online.pigeonshouse.shelliconkit`。

[MIT](LICENSE)

