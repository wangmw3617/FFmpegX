# =============================================================================
#  FFmpegX 原生层构建脚本
#
#  产物：
#    libffmpegcmd.so   —— FFmpeg CLI（ffmpeg.c 及其全部 fftools）编译而成，导出 ffmpegx_run_ffmpeg
#    libffprobecmd.so  —— FFprobe CLI 编译而成，导出 ffmpegx_run_ffprobe
#    libffmpegx.so     —— JNI 桥接层，运行时 dlopen 上面两个库
#
#  为什么不直接链接成一个 .so：
#    ffmpeg.c 与 ffprobe.c 都会定义 program_name / program_birth_year 等全局符号，
#    且各自的 fftools 依赖集不同（ffprobe 不需要 graph/ 但 ffmpeg 需要）。
#    拆成两个独立 .so + 运行时按名 dlopen(RTLD_LOCAL)，可彻底避免符号冲突。
#
#  依赖的 FFmpeg 预编译产物由 scripts/build-ffmpeg-android.sh 生成：
#    cpp/ffmpeg/include/**     头文件 + config.h
#    cpp/fftools/**            FFmpeg 的 fftools 源码（CLI 主程序）
#    cpp/compat/**             cmdutils.c 需要的兼容头
#    ../jniLibs/<abi>/*.so     FFmpeg 共享库
# =============================================================================

cmake_minimum_required(VERSION 3.22.1)
project(ffmpegx C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 20)

set(CPP_DIR        ${CMAKE_CURRENT_SOURCE_DIR})
set(FFMPEG_ROOT    ${CPP_DIR}/ffmpeg)
set(FFMPEG_INC     ${FFMPEG_ROOT}/include)
set(FFTOOLS_DIR    ${CPP_DIR}/fftools)
set(JNI_LIBS_DIR   ${CPP_DIR}/../jniLibs/${ANDROID_ABI})

# ---------------------------------------------------------------- 前置校验 ----
if(NOT EXISTS ${FFMPEG_INC}/libavcodec/avcodec.h)
    message(FATAL_ERROR
        "找不到 FFmpeg 头文件：${FFMPEG_INC}\n"
        "请先执行 scripts/build-ffmpeg-android.sh 生成预编译产物。")
endif()
if(NOT EXISTS ${FFTOOLS_DIR}/ffmpeg.c)
    message(FATAL_ERROR
        "找不到 fftools 源码：${FFTOOLS_DIR}/ffmpeg.c\n"
        "请先执行 scripts/build-ffmpeg-android.sh。")
endif()

message(STATUS "FFmpegX native build | ABI=${ANDROID_ABI} | jniLibs=${JNI_LIBS_DIR}")

# ------------------------------------------------------- FFmpeg 共享库导入 ----
set(FFMPEG_SHARED_LIBS avformat avcodec avfilter swresample swscale avutil)
set(FFMPEG_LINK_TARGETS "")
foreach(lib ${FFMPEG_SHARED_LIBS})
    if(NOT EXISTS ${JNI_LIBS_DIR}/lib${lib}.so)
        message(FATAL_ERROR "缺少预编译库：${JNI_LIBS_DIR}/lib${lib}.so")
    endif()
    add_library(${lib} SHARED IMPORTED)
    set_target_properties(${lib} PROPERTIES
        IMPORTED_LOCATION ${JNI_LIBS_DIR}/lib${lib}.so)
    list(APPEND FFMPEG_LINK_TARGETS ${lib})
endforeach()

# 日志依赖：FFmpeg 链接 android log
find_library(log-lib log)
find_library(android-lib android)
find_library(z-lib z)

# ------------------------------------------------------------ 公共编译参数 ----
set(FFTOOLS_COMMON_DEFS
    -D_FILE_OFFSET_BITS=64
    -D_GNU_SOURCE
    -DHAVE_AV_CONFIG_H
    -DFFMPEGX_ANDROID=1
)

set(FFTOOLS_COMMON_OPTS
    -Wno-deprecated-declarations
    -Wno-pointer-sign
    -Wno-implicit-function-declaration
    -Wno-unused-result
    -Wno-unused-variable
    -Wno-unused-but-set-variable
    -Wno-missing-field-initializers
    -Wno-format
    -fPIC
    -ffunction-sections
    -fdata-sections
)

set(FFTOOLS_COMMON_INCLUDES
    ${FFTOOLS_DIR}
    ${CPP_DIR}            # 供 cmdutils.c 的 #include "compat/va_copy.h"
    ${FFMPEG_INC}
)

# ------------------------------------------------------- 收集 fftools 源文件 ----
# 顶层 .c（ffmpeg.c / ffprobe.c / cmdutils.c / opt_common.c / ffmpeg_*.c / sync_queue.c ...）
file(GLOB FFTOOLS_TOP ${FFTOOLS_DIR}/*.c)

# 子目录：不同 FFmpeg 版本会把 graph/、textformat/、compat/ 放在不同位置，做存在性判断
set(FFTOOLS_SUBDIRS "")
foreach(sub graph textformat compat)
    if(IS_DIRECTORY ${FFTOOLS_DIR}/${sub})
        file(GLOB _sub_src ${FFTOOLS_DIR}/${sub}/*.c)
        list(APPEND FFTOOLS_SUBDIRS ${_sub_src})
        list(APPEND FFTOOLS_COMMON_INCLUDES ${FFTOOLS_DIR}/${sub})
    endif()
endforeach()

# ---- ffmpeg CLI：全部 fftools，剔除 ffprobe.c ----
set(FFMPEG_CMDLINE_SOURCES ${FFTOOLS_TOP} ${FFTOOLS_SUBDIRS})
list(FILTER FFMPEG_CMDLINE_SOURCES EXCLUDE REGEX "ffprobe\\.c$")

# ---- ffprobe CLI：只要 ffprobe.c + cmdutils.c + opt_common.c (+ textformat/) ----
set(FFPROBE_CMDLINE_SOURCES
    ${FFTOOLS_DIR}/ffprobe.c
    ${FFTOOLS_DIR}/cmdutils.c
    ${FFTOOLS_DIR}/opt_common.c
)
if(IS_DIRECTORY ${FFTOOLS_DIR}/textformat)
    file(GLOB _tf ${FFTOOLS_DIR}/textformat/*.c)
    list(APPEND FFPROBE_CMDLINE_SOURCES ${_tf})
endif()

list(LENGTH FFMPEG_CMDLINE_SOURCES _n1)
list(LENGTH FFPROBE_CMDLINE_SOURCES _n2)
message(STATUS "ffmpeg CLI sources: ${_n1} | ffprobe CLI sources: ${_n2}")

# ============================================================================
#  libffmpegcmd.so —— FFmpeg CLI
# ============================================================================
add_library(ffmpegcmd SHARED
    ${FFMPEG_CMDLINE_SOURCES}
    ${CPP_DIR}/ffmpegx_runner.c
    ${CPP_DIR}/ffmpegx_exit_compat.c
)

# 关键：把 main() 重命名，避免与 ffprobe 的 main 冲突，也让 JNI 层能直接调用
set_source_files_properties(${FFTOOLS_DIR}/ffmpeg.c PROPERTIES
    COMPILE_DEFINITIONS "main=ffmpegx_ffmpeg_main")

target_compile_definitions(ffmpegcmd PRIVATE
    ${FFTOOLS_COMMON_DEFS}
    FFMPEGX_TARGET_FFMPEG=1
)
target_compile_options(ffmpegcmd PRIVATE ${FFTOOLS_COMMON_OPTS})
target_include_directories(ffmpegcmd PRIVATE ${FFTOOLS_COMMON_INCLUDES})
target_link_libraries(ffmpegcmd PRIVATE ${FFMPEG_LINK_TARGETS} ${log-lib} ${android-lib} ${z-lib} m)

# -Wl,-Bsymbolic：让本库内部对 exit() 的引用绑定到我们自己的实现，
# 从而把 FFmpeg 的 exit_program() 变成可 longjmp 返回，而不是杀进程。
target_link_options(ffmpegcmd PRIVATE
    "-Wl,-Bsymbolic"
    "-Wl,--gc-sections"
    "-Wl,-z,max-page-size=16384"
)

# ============================================================================
#  libffprobecmd.so —— FFprobe CLI
# ============================================================================
add_library(ffprobecmd SHARED
    ${FFPROBE_CMDLINE_SOURCES}
    ${CPP_DIR}/ffmpegx_runner.c
    ${CPP_DIR}/ffmpegx_exit_compat.c
)

set_source_files_properties(${FFTOOLS_DIR}/ffprobe.c PROPERTIES
    COMPILE_DEFINITIONS "main=ffmpegx_ffprobe_main")

target_compile_definitions(ffprobecmd PRIVATE
    ${FFTOOLS_COMMON_DEFS}
    FFMPEGX_TARGET_FFPROBE=1
)
target_compile_options(ffprobecmd PRIVATE ${FFTOOLS_COMMON_OPTS})
target_include_directories(ffprobecmd PRIVATE ${FFTOOLS_COMMON_INCLUDES})
target_link_libraries(ffprobecmd PRIVATE ${FFMPEG_LINK_TARGETS} ${log-lib} ${android-lib} ${z-lib} m)

target_link_options(ffprobecmd PRIVATE
    "-Wl,-Bsymbolic"
    "-Wl,--gc-sections"
    "-Wl,-z,max-page-size=16384"
)

# ============================================================================
#  libffmpegx.so —— JNI 桥接
# ============================================================================
add_library(ffmpegx SHARED ${CPP_DIR}/ffmpegx_jni.cpp)

target_compile_definitions(ffmpegx PRIVATE FFMPEGX_ANDROID=1)
target_compile_options(ffmpegx PRIVATE -fPIC -O2 -ffunction-sections -fdata-sections)
target_link_libraries(ffmpegx PRIVATE ${log-lib} ${android-lib} dl)

target_link_options(ffmpegx PRIVATE
    "-Wl,--gc-sections"
    "-Wl,-z,max-page-size=16384"
)
