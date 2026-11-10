#include "online_pigeonshouse_shelliconkit_NativeIcons.h"

#include <windows.h>
#include <shobjidl.h>
#include <wincodec.h>
#include <wrl/client.h>

#include <cstdio>
#include <limits>
#include <memory>
#include <new>
#include <string>
#include <type_traits>
#include <utility>
#include <vector>

namespace {
using Microsoft::WRL::ComPtr;

class ShellApartment {
public:
    const HRESULT result = CoInitializeEx(nullptr, COINIT_APARTMENTTHREADED | COINIT_DISABLE_OLE1DDE);

    ShellApartment() = default;
    ShellApartment(const ShellApartment&) = delete;
    ShellApartment& operator=(const ShellApartment&) = delete;
    ~ShellApartment() {
        if (SUCCEEDED(result)) {
            CoUninitialize();  // Also balances S_FALSE when the thread is already an STA.
        }
    }
};

struct DeleteBitmap {
    void operator()(HBITMAP bitmap) const noexcept { DeleteObject(bitmap); }
};
using Bitmap = std::unique_ptr<std::remove_pointer_t<HBITMAP>, DeleteBitmap>;

struct DestroyOwnedIcon {
    void operator()(HICON icon) const noexcept { DestroyIcon(icon); }
};
using Icon = std::unique_ptr<std::remove_pointer_t<HICON>, DestroyOwnedIcon>;

struct FreeModule {
    void operator()(HMODULE module) const noexcept { FreeLibrary(module); }
};
using ResourceModule = std::unique_ptr<std::remove_pointer_t<HMODULE>, FreeModule>;

void throwJava(JNIEnv* env, const char* type, const char* message) {
    if (jclass cls = env->FindClass(type)) {
        env->ThrowNew(cls, message);
        env->DeleteLocalRef(cls);
    }
}

template<class T>
auto localRef(JNIEnv* env, T ref) {
    auto release = [env](T value) { env->DeleteLocalRef(value); };
    return std::unique_ptr<std::remove_pointer_t<T>, decltype(release)>(ref, release);
}

// Only an item's I/O failure goes in the result. JVM errors must still leave the native call.
bool captureIoFailure(JNIEnv* env, jclass ioException, jobjectArray errors, jsize index) {
    if (!env->ExceptionCheck()) {
        return true;
    }
    auto failure = localRef(env, env->ExceptionOccurred());
    env->ExceptionClear();
    if (!env->IsInstanceOf(failure.get(), ioException)) {
        env->Throw(failure.get());
        return false;
    }
    env->SetObjectArrayElement(errors, index, failure.get());
    return !env->ExceptionCheck();
}

bool storeRaster(JNIEnv* env, jclass ioException, jobjectArray images, jobjectArray errors,
                 jsize index, jintArray raster) {
    auto pixels = localRef(env, raster);
    if (!captureIoFailure(env, ioException, errors, index)) {
        return false;
    }
    if (pixels) {
        env->SetObjectArrayElement(images, index, pixels.get());
    }
    return !env->ExceptionCheck();
}

jintArray reportError(JNIEnv* env, const char* operation, HRESULT result) {
    char message[144];
    std::snprintf(message, sizeof(message), "%s failed (HRESULT 0x%08lX)",
                  operation, static_cast<unsigned long>(result));
    throwJava(env, "java/io/IOException", message);
    return nullptr;
}

jintArray winError(JNIEnv* env, const char* operation, DWORD error) {
    return reportError(env, operation, error ? HRESULT_FROM_WIN32(error) : E_FAIL);
}

std::wstring widePath(JNIEnv* env, jstring path) {
    const jsize length = env->GetStringLength(path);
    std::vector<jchar> utf16(length);
    env->GetStringRegion(path, 0, length, utf16.data());
    if (env->ExceptionCheck()) {
        return {};
    }
    return {utf16.begin(), utf16.end()};
}

jintArray argb(JNIEnv* env, IWICBitmapSource* source, UINT maxEdge) {
    ComPtr<IWICBitmapSource> bgra;
    HRESULT hr = WICConvertBitmapSource(GUID_WICPixelFormat32bppBGRA, source, &bgra);
    if (FAILED(hr)) {
        return reportError(env, "WICConvertBitmapSource", hr);
    }

    UINT width = 0, height = 0;
    hr = bgra->GetSize(&width, &height);
    if (FAILED(hr)) {
        return reportError(env, "IWICBitmapSource::GetSize", hr);
    }
    if (width == 0 || height == 0 || width > maxEdge || height > maxEdge) {
        throwJava(env, "java/io/IOException", "Icon dimensions are outside the requested bounds");
        return nullptr;
    }
    std::vector<jint> raster(2 + width * height);
    raster[0] = static_cast<jint>(width);
    raster[1] = static_cast<jint>(height);
    // On Windows x86/x64, straight BGRA bytes in memory are Java 0xAARRGGBB ints.
    hr = bgra->CopyPixels(nullptr, width * 4, width * height * 4,
                          reinterpret_cast<BYTE*>(raster.data() + 2));
    if (FAILED(hr)) {
        return reportError(env, "IWICBitmapSource::CopyPixels", hr);
    }
    const auto size = static_cast<jsize>(raster.size());
    jintArray result = env->NewIntArray(size);
    if (result) {
        env->SetIntArrayRegion(result, 0, size, raster.data());
    }
    return result;
}

HRESULT ensureImagingFactory(ComPtr<IWICImagingFactory>& imaging) {
    return imaging ? S_OK :
           CoCreateInstance(CLSID_WICImagingFactory, nullptr, CLSCTX_INPROC_SERVER, IID_PPV_ARGS(&imaging));
}

jintArray decodeFrame(JNIEnv* env, BYTE* bytes, DWORD length, ComPtr<IWICImagingFactory>& imaging) {
    Icon icon(CreateIconFromResourceEx(bytes, length, TRUE, 0x00030000, 0, 0, LR_DEFAULTCOLOR));
    if (!icon) {
        return winError(env, "CreateIconFromResourceEx", GetLastError());
    }
    HRESULT hr = ensureImagingFactory(imaging);
    if (FAILED(hr)) {
        return reportError(env, "Create WIC imaging factory", hr);
    }
    ComPtr<IWICBitmap> source;
    hr = imaging->CreateBitmapFromHICON(icon.get(), &source);
    if (FAILED(hr)) {
        return reportError(env, "IWICImagingFactory::CreateBitmapFromHICON", hr);
    }
    return argb(env, source.Get(), 256);
}

jintArray decodeIco(JNIEnv* env, jbyteArray frame, ComPtr<IWICImagingFactory>& imaging) {
    const jsize length = env->GetArrayLength(frame);
    std::vector<BYTE> data(length);
    env->GetByteArrayRegion(frame, 0, length, reinterpret_cast<jbyte*>(data.data()));
    if (env->ExceptionCheck()) {
        return nullptr;
    }
    return decodeFrame(env, data.data(), static_cast<DWORD>(length), imaging);
}

jintArray shellIcon(JNIEnv* env, const std::wstring& name, jint edge, ComPtr<IWICImagingFactory>& imaging) {
    ComPtr<IShellItemImageFactory> item;
    HRESULT hr = SHCreateItemFromParsingName(name.c_str(), nullptr, IID_PPV_ARGS(&item));
    if (FAILED(hr)) {
        return reportError(env, "SHCreateItemFromParsingName", hr);
    }
    HBITMAP handle = nullptr;
    hr = item->GetImage(SIZE{edge, edge}, SIIGBF_ICONONLY, &handle);
    Bitmap bitmap(handle);
    if (hr == E_PENDING) {
        return nullptr;  // Java retries after the Shell/COM resources have been released.
    }
    if (FAILED(hr) || !bitmap) {
        return reportError(env, "IShellItemImageFactory::GetImage", FAILED(hr) ? hr : E_UNEXPECTED);
    }
    hr = ensureImagingFactory(imaging);
    if (FAILED(hr)) {
        return reportError(env, "Create WIC imaging factory", hr);
    }
    ComPtr<IWICBitmap> source;
    hr = imaging->CreateBitmapFromHBITMAP(bitmap.get(), nullptr, WICBitmapUseAlpha, &source);
    if (FAILED(hr)) {
        return reportError(env, "IWICImagingFactory::CreateBitmapFromHBITMAP", hr);
    }
    return argb(env, source.Get(), static_cast<UINT>(edge));
}

ResourceModule openResources(const std::wstring& file) {
    // A resource mapping, not an executable load. No DllMain or imports are executed.
    return ResourceModule(LoadLibraryExW(file.c_str(), nullptr,
                          LOAD_LIBRARY_AS_DATAFILE_EXCLUSIVE | LOAD_LIBRARY_AS_IMAGE_RESOURCE));
}

BYTE* resourceBytes(HMODULE module, HRSRC resource) {
    HGLOBAL view = LoadResource(module, resource);
    return view ? static_cast<BYTE*>(LockResource(view)) : nullptr;
}

struct PeFrame {
    BYTE* bytes;
    DWORD size;
};

PeFrame findPeFrame(JNIEnv* env, HMODULE module, jint id, jint language, jlong expectedSize) {
    HRSRC resource = FindResourceExW(module, RT_ICON, MAKEINTRESOURCEW(id), static_cast<WORD>(language));
    if (!resource) {
        winError(env, "FindResourceExW (RT_ICON)", GetLastError());
        return {};
    }
    const DWORD size = SizeofResource(module, resource);
    if (size != expectedSize) {
        throwJava(env, "java/io/IOException", "PE icon length no longer matches its group directory");
        return {};
    }
    BYTE* bytes = resourceBytes(module, resource);
    if (!bytes) {
        winError(env, "LoadResource (RT_ICON)", GetLastError());
    }
    return {bytes, size};
}

struct GroupDirectory {
    jint id;
    WORD language;
    std::wstring name;
    const BYTE* bytes;
    DWORD size;
};

struct GroupScan {
    std::vector<GroupDirectory> groups;
    const char* failedOperation = nullptr;
    DWORD error = ERROR_SUCCESS;
    bool allocationFailed = false;

    BOOL fail(const char* operation, DWORD code) {
        failedOperation = operation;
        error = code;
        return FALSE;
    }
};

BOOL CALLBACK emitGroup(HMODULE module, LPCWSTR type, LPCWSTR name, WORD language, LONG_PTR data) {
    auto& scan = *reinterpret_cast<GroupScan*>(data);
    HRSRC resource = FindResourceExW(module, type, name, language);
    if (!resource) {
        return scan.fail("FindResourceExW (RT_GROUP_ICON)", GetLastError());
    }
    const DWORD size = SizeofResource(module, resource);
    if (size < 6 || size > 6 + 14 * 65535) {
        return scan.fail("Invalid PE icon group directory length", ERROR_INVALID_DATA);
    }
    const BYTE* bytes = resourceBytes(module, resource);
    if (!bytes) {
        return scan.fail("LoadResource (RT_GROUP_ICON)", GetLastError());
    }
    try {
        if (IS_INTRESOURCE(name)) {
            scan.groups.push_back({static_cast<jint>(reinterpret_cast<ULONG_PTR>(name)), language, {}, bytes, size});
        } else {
            scan.groups.push_back({-1, language, name, bytes, size});
        }
    } catch (const std::bad_alloc&) {
        scan.allocationFailed = true;
        return FALSE;
    }
    return TRUE;
}

BOOL CALLBACK groupLanguages(HMODULE module, LPCWSTR type, LPWSTR name, LONG_PTR data) {
    auto& scan = *reinterpret_cast<GroupScan*>(data);
    if (!EnumResourceLanguagesExW(module, type, name, emitGroup, data, RESOURCE_ENUM_LN, 0)) {
        const DWORD error = GetLastError();
        if (!scan.failedOperation && !scan.allocationFailed) {
            return scan.fail("EnumResourceLanguagesExW (RT_GROUP_ICON)", error);
        }
        return FALSE;
    }
    return TRUE;
}

void outOfMemory(JNIEnv* env) {
    throwJava(env, "java/lang/OutOfMemoryError", "Allocating Windows icon data");
}

struct CatalogLayout {
    jfieldID idsAndLanguages;
    jfieldID names;
    jfieldID directories;
    jclass stringClass;
    jclass bytesClass;
};

void listPe(JNIEnv* env, const std::wstring& path, jobject catalog, const CatalogLayout& layout) {
    ResourceModule module = openResources(path);
    if (!module) {
        winError(env, "LoadLibraryExW (icon resources)", GetLastError());
        return;
    }
    GroupScan scan;
    if (!EnumResourceNamesExW(module.get(), RT_GROUP_ICON, groupLanguages,
                              reinterpret_cast<LONG_PTR>(&scan), RESOURCE_ENUM_LN, 0)) {
        const DWORD error = GetLastError();
        if (scan.allocationFailed) {
            outOfMemory(env);
            return;
        }
        if (scan.failedOperation) {
            winError(env, scan.failedOperation, scan.error);
            return;
        }
        if (error != ERROR_RESOURCE_TYPE_NOT_FOUND && error != ERROR_RESOURCE_DATA_NOT_FOUND) {
            winError(env, "EnumResourceNamesExW (RT_GROUP_ICON)", error);
            return;
        }
    }
    if (scan.groups.size() > static_cast<size_t>(std::numeric_limits<jsize>::max() / 2)) {
        outOfMemory(env);
        return;
    }

    const jsize count = static_cast<jsize>(scan.groups.size());
    auto ids = localRef(env, env->NewIntArray(2 * count));
    if (!ids) {
        return;
    }
    auto names = localRef(env, env->NewObjectArray(count, layout.stringClass, nullptr));
    if (!names) {
        return;
    }
    auto directories = localRef(env, env->NewObjectArray(count, layout.bytesClass, nullptr));
    if (!directories) {
        return;
    }
    std::vector<jint> pairs(2 * count);
    for (jsize i = 0; i < count; i++) {
        const auto& group = scan.groups[i];
        pairs[2 * i] = group.id;
        pairs[2 * i + 1] = group.language;
        if (group.id < 0) {
            static_assert(sizeof(wchar_t) == sizeof(jchar), "Windows resource names are UTF-16");
            auto label = localRef(env, env->NewString(reinterpret_cast<const jchar*>(group.name.data()),
                                                       static_cast<jsize>(group.name.size())));
            if (!label) {
                return;
            }
            env->SetObjectArrayElement(names.get(), i, label.get());
            if (env->ExceptionCheck()) {
                return;
            }
        }
        const auto size = static_cast<jsize>(group.size);
        auto directory = localRef(env, env->NewByteArray(size));
        if (!directory) {
            return;
        }
        env->SetByteArrayRegion(directory.get(), 0, size, reinterpret_cast<const jbyte*>(group.bytes));
        if (env->ExceptionCheck()) {
            return;
        }
        env->SetObjectArrayElement(directories.get(), i, directory.get());
        if (env->ExceptionCheck()) {
            return;
        }
    }
    if (count != 0) {
        env->SetIntArrayRegion(ids.get(), 0, 2 * count, pairs.data());
        if (env->ExceptionCheck()) {
            return;
        }
    }
    env->SetObjectField(catalog, layout.idsAndLanguages, ids.get());
    env->SetObjectField(catalog, layout.names, names.get());
    env->SetObjectField(catalog, layout.directories, directories.get());
}

jobjectArray imageArray(JNIEnv* env, jsize count) {
    auto intArrayClass = localRef(env, env->FindClass("[I"));
    return intArrayClass ? env->NewObjectArray(count, intArrayClass.get(), nullptr) : nullptr;
}
}  // namespace

JNIEXPORT jintArray JNICALL Java_online_pigeonshouse_shelliconkit_NativeIcons_readShell(
        JNIEnv* env, jclass, jstring path, jint edge) {
    try {
        const std::wstring name = widePath(env, path);
        if (env->ExceptionCheck()) {
            return nullptr;
        }
        ShellApartment apartment;
        if (FAILED(apartment.result)) {
            return reportError(env, "CoInitializeEx (use an STA or a fresh thread)", apartment.result);
        }
        ComPtr<IWICImagingFactory> imaging;
        return shellIcon(env, name, edge, imaging);
    } catch (const std::bad_alloc&) {
        outOfMemory(env);
        return nullptr;
    }
}

JNIEXPORT jobjectArray JNICALL Java_online_pigeonshouse_shelliconkit_NativeIcons_readShellBatch(
        JNIEnv* env, jclass, jobjectArray paths, jint edge, jobjectArray errors) {
    try {
        ShellApartment apartment;
        if (FAILED(apartment.result)) {
            reportError(env, "CoInitializeEx (use an STA or a fresh thread)", apartment.result);
            return nullptr;
        }
        ComPtr<IWICImagingFactory> imaging;
        const jsize count = env->GetArrayLength(paths);
        jobjectArray images = imageArray(env, count);
        if (!images) {
            return nullptr;
        }
        auto ioException = localRef(env, env->FindClass("java/io/IOException"));
        if (!ioException) {
            return nullptr;
        }
        for (jsize i = 0; i < count; i++) {
            auto path = localRef(env, static_cast<jstring>(env->GetObjectArrayElement(paths, i)));
            if (env->ExceptionCheck()) {
                return nullptr;
            }
            if (!path) {
                continue;
            }
            const std::wstring name = widePath(env, path.get());
            if (env->ExceptionCheck() ||
                !storeRaster(env, ioException.get(), images, errors, i, shellIcon(env, name, edge, imaging))) {
                return nullptr;
            }
        }
        return images;
    } catch (const std::bad_alloc&) {
        outOfMemory(env);
        return nullptr;
    }
}

JNIEXPORT void JNICALL Java_online_pigeonshouse_shelliconkit_NativeIcons_listPeBatch(
        JNIEnv* env, jclass, jobjectArray paths, jobjectArray catalogs, jobjectArray errors) {
    try {
        auto catalogClass = localRef(env, env->FindClass("online/pigeonshouse/shelliconkit/NativeIcons$PeCatalog"));
        if (!catalogClass) {
            return;
        }
        jfieldID ids = env->GetFieldID(catalogClass.get(), "idsAndLanguages", "[I");
        if (!ids) {
            return;
        }
        jfieldID names = env->GetFieldID(catalogClass.get(), "names", "[Ljava/lang/String;");
        if (!names) {
            return;
        }
        jfieldID directories = env->GetFieldID(catalogClass.get(), "directories", "[[B");
        if (!directories) {
            return;
        }
        auto stringClass = localRef(env, env->FindClass("java/lang/String"));
        if (!stringClass) {
            return;
        }
        auto bytesClass = localRef(env, env->FindClass("[B"));
        if (!bytesClass) {
            return;
        }
        auto ioException = localRef(env, env->FindClass("java/io/IOException"));
        if (!ioException) {
            return;
        }
        const CatalogLayout layout{ids, names, directories, stringClass.get(), bytesClass.get()};
        const jsize count = env->GetArrayLength(paths);
        for (jsize i = 0; i < count; i++) {
            auto path = localRef(env, static_cast<jstring>(env->GetObjectArrayElement(paths, i)));
            if (env->ExceptionCheck()) {
                return;
            }
            if (!path) {
                continue;
            }
            auto catalog = localRef(env, env->GetObjectArrayElement(catalogs, i));
            if (env->ExceptionCheck()) {
                return;
            }
            const std::wstring name = widePath(env, path.get());
            if (env->ExceptionCheck()) {
                return;
            }
            listPe(env, name, catalog.get(), layout);
            if (!captureIoFailure(env, ioException.get(), errors, i)) {
                return;
            }
        }
    } catch (const std::bad_alloc&) {
        outOfMemory(env);
    }
}

JNIEXPORT jintArray JNICALL Java_online_pigeonshouse_shelliconkit_NativeIcons_readPeIcon(
        JNIEnv* env, jclass, jstring path, jint id, jint language, jlong expectedSize) {
    try {
        const std::wstring name = widePath(env, path);
        if (env->ExceptionCheck()) {
            return nullptr;
        }
        ResourceModule module = openResources(name);
        if (!module) {
            return winError(env, "LoadLibraryExW (icon resources)", GetLastError());
        }
        const PeFrame frame = findPeFrame(env, module.get(), id, language, expectedSize);
        if (!frame.bytes) {
            return nullptr;
        }
        ShellApartment apartment;
        if (FAILED(apartment.result)) {
            return reportError(env, "CoInitializeEx", apartment.result);
        }
        ComPtr<IWICImagingFactory> imaging;
        return decodeFrame(env, frame.bytes, frame.size, imaging);
    } catch (const std::bad_alloc&) {
        outOfMemory(env);
        return nullptr;
    }
}

JNIEXPORT jintArray JNICALL Java_online_pigeonshouse_shelliconkit_NativeIcons_decodeIcon(
        JNIEnv* env, jclass, jbyteArray frame) {
    try {
        ShellApartment apartment;
        if (FAILED(apartment.result)) {
            return reportError(env, "CoInitializeEx", apartment.result);
        }
        ComPtr<IWICImagingFactory> imaging;
        return decodeIco(env, frame, imaging);
    } catch (const std::bad_alloc&) {
        outOfMemory(env);
        return nullptr;
    }
}

JNIEXPORT jobjectArray JNICALL Java_online_pigeonshouse_shelliconkit_NativeIcons_readFramesBatch(
        JNIEnv* env, jclass, jobjectArray paths, jlongArray peKeys, jobjectArray icoFrames, jobjectArray errors) {
    try {
        ShellApartment apartment;
        if (FAILED(apartment.result)) {
            reportError(env, "CoInitializeEx", apartment.result);
            return nullptr;
        }
        ComPtr<IWICImagingFactory> imaging;
        const jsize count = env->GetArrayLength(paths);
        std::vector<jlong> keys(3 * count);
        env->GetLongArrayRegion(peKeys, 0, 3 * count, keys.data());
        if (env->ExceptionCheck()) {
            return nullptr;
        }
        jobjectArray images = imageArray(env, count);
        if (!images) {
            return nullptr;
        }
        auto ioException = localRef(env, env->FindClass("java/io/IOException"));
        if (!ioException) {
            return nullptr;
        }
        ResourceModule module;
        std::wstring mappedPath;
        DWORD openError = ERROR_SUCCESS;
        bool mapped = false;
        for (jsize i = 0; i < count; i++) {
            auto path = localRef(env, static_cast<jstring>(env->GetObjectArrayElement(paths, i)));
            if (env->ExceptionCheck()) {
                return nullptr;
            }
            jintArray raster = nullptr;
            if (path) {
                std::wstring name = widePath(env, path.get());
                if (env->ExceptionCheck()) {
                    return nullptr;
                }
                if (!mapped || name != mappedPath) {
                    module.reset();
                    mappedPath = std::move(name);
                    module = openResources(mappedPath);
                    openError = module ? ERROR_SUCCESS : GetLastError();
                    mapped = true;
                }
                if (!module) {
                    winError(env, "LoadLibraryExW (icon resources)", openError);
                } else {
                    const PeFrame frame = findPeFrame(env, module.get(), static_cast<jint>(keys[3 * i]),
                                                      static_cast<jint>(keys[3 * i + 1]), keys[3 * i + 2]);
                    if (frame.bytes) {
                        raster = decodeFrame(env, frame.bytes, frame.size, imaging);
                    }
                }
            } else {
                module.reset();
                mapped = false;
                auto frame = localRef(env, static_cast<jbyteArray>(env->GetObjectArrayElement(icoFrames, i)));
                if (env->ExceptionCheck()) {
                    return nullptr;
                }
                if (!frame) {
                    throwJava(env, "java/lang/IllegalArgumentException", "Missing ICO frame in batch");
                    return nullptr;
                }
                raster = decodeIco(env, frame.get(), imaging);
            }
            if (!storeRaster(env, ioException.get(), images, errors, i, raster)) {
                return nullptr;
            }
        }
        return images;
    } catch (const std::bad_alloc&) {
        outOfMemory(env);
        return nullptr;
    }
}
