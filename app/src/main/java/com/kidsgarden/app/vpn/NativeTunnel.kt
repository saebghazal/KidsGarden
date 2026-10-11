package com.kidsgarden.app.vpn

/**
 * JNI bridge to the audited hev-socks5-tunnel build (`libkg-tunnel.so`,
 * app/src/main/cpp). The native side discards stdout/stderr and runs with its logger
 * disabled; config arrives as bytes, so nothing is written to a file.
 */
internal object NativeTunnel {

    /** False when the native library is not packaged (for example, submodule missing). */
    val available: Boolean = try {
        System.loadLibrary("kg-tunnel")
        true
    } catch (_: UnsatisfiedLinkError) {
        false
    }

    /** Blocks until [quit] is called or the tunnel fails. Returns 0 on clean exit. */
    @JvmStatic
    external fun run(config: ByteArray, tunFd: Int): Int

    @JvmStatic
    external fun quit()
}
