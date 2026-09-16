/*
 * ffmpegx_runner.c
 * ---------------------------------------------------------------------------
 * 把 FFmpeg CLI 的 main() 包装成一个可重复调用、可取消、可回传日志的 C 入口。
 *
 * 编译时会带上：
 *   FFMPEGX_TARGET_FFMPEG   -> 链接 ffmpeg.c，导出 ffmpegx_run_ffmpeg
 *   FFMPEGX_TARGET_FFPROBE  -> 链接 ffprobe.c，导出 ffmpegx_run_ffprobe
 *
 * 两个库共用本文件，通过 -Wl,-Bsymbolic + RTLD_LOCAL 保证互不干扰。
 *
 * 关于取消：
 *   FFmpeg 的 main() 内部会调用 term_init()，为 SIGINT 注册处理器，
 *   处理器把 received_sigterm 置 1，transcode() 主循环随即退出并走正常 cleanup。
 *   因此「取消」= 向当前线程 raise(SIGINT)，无需访问任何 static 全局变量。
 *
 * 关于进度：
 *   FFmpeg 通过 av_log(NULL, AV_LOG_INFO, "frame=... fps=... time=...") 输出统计行，
 *   这里不做解析，原样交给上层（Kotlin 侧 ProgressParser）解析，保持 C 层最小化。
 */

#include <pthread.h>
#include <signal.h>
#include <stdarg.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

#include <libavutil/log.h>

/* ---- 来自 ffmpegx_exit_compat.c ---- */
extern int  ffmpegx_exit_guard(void);
extern int  ffmpegx_exit_arm(void);
extern void ffmpegx_exit_disarm(void);
extern int  ffmpegx_exit_fired(void);
extern int  ffmpegx_exit_code(void);

/* ---- 由 CMake 的 -Dmain=... 重命名而来 ---- */
#ifdef FFMPEGX_TARGET_FFMPEG
extern int ffmpegx_ffmpeg_main(int argc, char **argv);
#  define FFMPEGX_CLI_MAIN  ffmpegx_ffmpeg_main
#  define FFMPEGX_ENTRY     ffmpegx_run_ffmpeg
#  define FFMPEGX_CANCEL    ffmpegx_cancel_ffmpeg
#else
extern int ffmpegx_ffprobe_main(int argc, char **argv);
#  define FFMPEGX_CLI_MAIN  ffmpegx_ffprobe_main
#  define FFMPEGX_ENTRY     ffmpegx_run_ffprobe
#  define FFMPEGX_CANCEL    ffmpegx_cancel_ffprobe
#endif

#define FFMPEGX_LOG_MAX        4096
/* fftools 里 AV_LOG_STDERR 的取值，用于放行 -progress / 统计行 */
#define FFMPEGX_STDERR_MARKER  64

typedef void (*ffmpegx_log_cb_t)(int level, const char *msg);

static ffmpegx_log_cb_t  g_log_cb   = NULL;
static pthread_mutex_t   g_run_lock = PTHREAD_MUTEX_INITIALIZER;
static atomic_int        g_running  = 0;

/* ------------------------------------------------------------------ 日志 ---- */

static void ffmpegx_avlog(void *avcl, int level, const char *fmt, va_list vl)
{
    char buf[FFMPEGX_LOG_MAX];
    (void)avcl;

    if (level > av_log_get_level() && level != FFMPEGX_STDERR_MARKER) {
        return;
    }
    vsnprintf(buf, sizeof(buf), fmt, vl);
    if (g_log_cb) {
        g_log_cb(level, buf);
    }
}

void ffmpegx_set_log_callback(void *cb)
{
    g_log_cb = (ffmpegx_log_cb_t)cb;
}

int ffmpegx_is_running(void)
{
    return atomic_load(&g_running);
}

/* ------------------------------------------------------------------ 取消 ---- */

void FFMPEGX_CANCEL(void)
{
    if (!atomic_load(&g_running)) {
        return;
    }
    /* 交给 FFmpeg 自己注册的 SIGINT 处理器，走正常的中断与清理流程 */
    raise(SIGINT);
}

/* ------------------------------------------------------------------ 执行 ---- */

int FFMPEGX_ENTRY(int argc, char **argv, void *log_cb)
{
    int rc = -1;

    pthread_mutex_lock(&g_run_lock);

    atomic_store(&g_running, 1);
    g_log_cb = (ffmpegx_log_cb_t)log_cb;

    /* 接管 av_log：所有 FFmpeg 输出（含统计行）都通过回调抛给上层 */
    av_log_set_callback(ffmpegx_avlog);

    ffmpegx_exit_arm();
    if (ffmpegx_exit_guard() == 0) {
        /* 正常路径：ffmpeg_main 内部会 parse -> transcode -> exit_program(cleanup) -> exit()
         * 所以最终仍会 longjmp 回来，这里拿到的返回值仅作为兜底 */
        rc = FFMPEGX_CLI_MAIN(argc, argv);
    } else {
        rc = ffmpegx_exit_fired() ? ffmpegx_exit_code() : -1;
    }
    ffmpegx_exit_disarm();

    av_log_set_callback(av_log_default_callback);

    g_log_cb = NULL;
    atomic_store(&g_running, 0);

    pthread_mutex_unlock(&g_run_lock);
    return rc;
}
