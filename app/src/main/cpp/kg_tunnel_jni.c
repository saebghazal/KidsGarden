/*
 * JNI bridge for Kids Garden (T041). Wraps hev-socks5-tunnel's library API so the
 * configuration is passed in memory, never through a file.
 *
 * Privacy: stdout and stderr are pointed at /dev/null when the library loads, so no
 * native error path (including upstream fprintf calls) can print anything. The tunnel's
 * logger stays disabled because the configuration never sets log-file.
 */

#include <fcntl.h>
#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include "hev-main.h"

static void
silence_stdio (void)
{
    int fd = open ("/dev/null", O_WRONLY | O_CLOEXEC);
    if (fd < 0)
        return;
    dup2 (fd, STDOUT_FILENO);
    dup2 (fd, STDERR_FILENO);
    if (fd > STDERR_FILENO)
        close (fd);
}

JNIEXPORT jint JNICALL
JNI_OnLoad (JavaVM *vm, void *reserved)
{
    (void)vm;
    (void)reserved;
    silence_stdio ();
    return JNI_VERSION_1_6;
}

JNIEXPORT jint JNICALL
Java_com_kidsgarden_app_vpn_NativeTunnel_run (JNIEnv *env, jclass klass,
                                              jbyteArray config, jint tun_fd)
{
    (void)klass;
    jsize len = (*env)->GetArrayLength (env, config);
    if (len <= 0)
        return -1;

    unsigned char *buf = malloc ((size_t)len);
    if (!buf)
        return -1;
    (*env)->GetByteArrayRegion (env, config, 0, len, (jbyte *)buf);

    int res = hev_socks5_tunnel_main_from_str (buf, (unsigned int)len, tun_fd);

    /* The config holds per-start relay credentials; clear them. */
    memset (buf, 0, (size_t)len);
    free (buf);
    return res;
}

JNIEXPORT void JNICALL
Java_com_kidsgarden_app_vpn_NativeTunnel_quit (JNIEnv *env, jclass klass)
{
    (void)env;
    (void)klass;
    hev_socks5_tunnel_quit ();
}
