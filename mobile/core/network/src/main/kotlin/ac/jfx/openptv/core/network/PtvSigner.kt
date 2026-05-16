package ac.jfx.openptv.core.network

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Kotlin port of `backend/internal/ptv/signer.go`. Signs PTV API paths with HMAC-SHA1 per the
 * published scheme:
 *
 * ```
 * url   = /v3/<path>?<query>&devid=<DEV_ID>
 * sig   = uppercase(hex(hmac_sha1(KEY, url)))
 * final = /v3/<path>?<query>&devid=<DEV_ID>&signature=<sig>
 * ```
 *
 * Used only when "direct PTV mode" is enabled in settings — the user has supplied their own
 * dev_id and key and wants to bypass the Go proxy. When the proxy is in use, the proxy holds the
 * signing key and the mobile app never instantiates this type.
 *
 * Public so `:core:data`'s `SettingsPtvUrlResolver` can construct one per call from the user's
 * stored credentials. The key is held only as `ByteArray` for HMAC input — there's no accessor
 * that hands it back, so promoting the class to `public` doesn't widen the surface in any
 * meaningful way (the user's key already lives in `SettingsRepository` which is also public).
 */
class PtvSigner(
    private val devId: String,
    key: String,
) {
    private val keyBytes: ByteArray = key.toByteArray(Charsets.UTF_8)

    /**
     * Sign [rawPath] (must start with `/`) — appends `devid=<id>` with the right separator,
     * computes the HMAC-SHA1, and returns the path with `&signature=<UPPER_HEX>` appended.
     *
     * The returned value is intended to be concatenated onto the PTV host base URL.
     */
    fun sign(rawPath: String): String {
        require(rawPath.startsWith("/")) { "ptv: path must start with /, got \"$rawPath\"" }
        val sep = if (rawPath.contains('?')) '&' else '?'
        val withDevId = "$rawPath${sep}devid=$devId"

        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(keyBytes, HMAC_ALGORITHM))
        val signature = mac.doFinal(withDevId.toByteArray(Charsets.UTF_8)).toUpperHex()
        return "$withDevId&signature=$signature"
    }

    private fun ByteArray.toUpperHex(): String {
        val out = StringBuilder(size * 2)
        for (b in this) {
            val v = b.toInt() and BYTE_MASK
            out.append(HEX[v ushr NIBBLE_BITS])
            out.append(HEX[v and NIBBLE_MASK])
        }
        return out.toString()
    }

    private companion object {
        const val HMAC_ALGORITHM = "HmacSHA1"
        val HEX = "0123456789ABCDEF".toCharArray()
        const val BYTE_MASK = 0xFF
        const val NIBBLE_MASK = 0x0F
        const val NIBBLE_BITS = 4
    }
}
