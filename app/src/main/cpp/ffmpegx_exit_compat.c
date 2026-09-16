/*
 * ffmpegx_exit_compat.c
 * ---------------------------------------------------------------------------
 * FFmpeg 的 fftools 是为「命令行进程」写的：出错时调用 exit_program()，
 * 而 exit_program() 最终会调用 libc 的 exit()，直接把整个进程干掉。
 * 在 Android App 里这是不可接受的——一次转码失败不能带走宿主进程。
 *
 * 解法：
 *   1) 本 .so 以 -Wl,-Bsymbolic 链接，库内部对 exit() 的引用会绑定到本文件
 *      提供的同名实现，而不是 libc 的 exit()。
 *   2) exit() 被调用时，若已 armed，则 longjmp 回到 ffmpegx_run_* 的调用点。
 *   3) FFmpeg 在调用 exit() 之前，exit_program() 会先执行 program_exit 回调
 *      （即 ffmpeg_cleanup / ffprobe_cleanup），因此资源已经被正确释放。
 *
 * 这套机制不改动 FFmpeg 源码，也不依赖任何版本相关的内部符号。
 */

#include <setjmp.h>
#include <stddef.h>
#include <stdlib.h>
#include <unistd.h>

/* 线程局部：每个执行 FFmpeg 的线程有独立的跳转目标 */
static __thread jmp_buf       t_exit_jmp;
static __thread volatile int  t_exit_armed = 0;
static __thread volatile int  t_exit_code  = 0;
static __thread volatile int  t_exit_fired = 0;

int ffmpegx_exit_guard(void)
{
    return setjmp(t_exit_jmp);
}

int ffmpegx_exit_arm(void)
{
    t_exit_fired = 0;
    t_exit_code  = 0;
    t_exit_armed = 1;
    return 0;
}

void ffmpegx_exit_disarm(void)
{
    t_exit_armed = 0;
}

int ffmpegx_exit_fired(void)
{
    return t_exit_fired;
}

int ffmpegx_exit_code(void)
{
    return t_exit_code;
}

/*
 * 覆盖 libc 的 exit()。
 * 未 armed 时退回 _exit()，行为与 libc 一致（真退出），保证不会误吞其它模块的退出请求。
 */
__attribute__((noreturn)) void exit(int code)
{
    if (t_exit_armed) {
        t_exit_armed = 0;
        t_exit_code  = code;
        t_exit_fired = 1;
        longjmp(t_exit_jmp, 1);
    }
    _exit(code);
}
