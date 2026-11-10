cmake_minimum_required(VERSION 3.21)
project(ShellIconKit VERSION 2.0.0 LANGUAGES CXX)

if(NOT WIN32 OR NOT MSVC)
    message(FATAL_ERROR "ShellIconKit requires MSVC and the Windows SDK.")
endif()
if(NOT EXISTS "${JAVA_HOME}/include/win32/jni_md.h" OR
   NOT EXISTS "${JNI_HEADERS}/online_pigeonshouse_shelliconkit_NativeIcons.h")
    message(FATAL_ERROR "Run build.ps1 to select a JDK and generate the JNI header.")
endif()

add_library(shelliconkit SHARED native/windows_icons.cpp)
target_compile_features(shelliconkit PRIVATE cxx_std_17)
target_compile_options(shelliconkit PRIVATE /W4)
target_compile_definitions(shelliconkit PRIVATE WIN32_LEAN_AND_MEAN NOMINMAX UNICODE _UNICODE)
target_include_directories(shelliconkit PRIVATE
    "${JAVA_HOME}/include" "${JAVA_HOME}/include/win32" "${JNI_HEADERS}")
target_link_libraries(shelliconkit PRIVATE shell32 ole32 gdi32 user32 windowscodecs)
set_property(TARGET shelliconkit PROPERTY MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")
