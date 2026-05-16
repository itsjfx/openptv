package ac.jfx.openptv.core.network

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pin parity with `backend/internal/ptv/signer_test.go`. The same input must produce the same
 * signed URL on both sides — direct mode in the mobile app and proxy mode through the Go server
 * hit PTV with the identical signature when given the same (path, devId, key) tuple.
 */
class PtvSignerTest {
    @Test
    fun `signs the PTV docs sample to the documented HMAC-SHA1`() {
        val signer = PtvSigner(devId = "3000176", key = "9c132d31-6a30-4cac-8d8b-8a1970834799")

        val signed = signer.sign("/v3/route_types")

        assertThat(signed)
            .isEqualTo(
                "/v3/route_types?devid=3000176&signature=EBD12B055DFEBB7CC0F9FB2B6E3AA0FE3CFD87B6",
            )
    }

    @Test
    fun `appends devid with ampersand when query already present`() {
        val signer = PtvSigner(devId = "DEV", key = "KEY")

        val signed = signer.sign("/v3/stops/1071/route_types/0?max_results=3")

        assertThat(signed).contains("max_results=3&devid=DEV&signature=")
    }

    @Test
    fun `appends devid with question mark when no query present`() {
        val signer = PtvSigner(devId = "DEV", key = "KEY")

        val signed = signer.sign("/v3/route_types")

        assertThat(signed).startsWith("/v3/route_types?devid=DEV&signature=")
    }

    @Test
    fun `signature is uppercase hex of HMAC-SHA1`() {
        val signer = PtvSigner(devId = "DEV", key = "KEY")

        val signed = signer.sign("/v3/route_types")

        // Strip the prefix and assert the signature half is 40 uppercase-hex chars.
        val sig = signed.substringAfter("&signature=")
        assertThat(sig).hasLength(40)
        assertThat(sig).matches("[0-9A-F]{40}")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects path without leading slash`() {
        PtvSigner(devId = "DEV", key = "KEY").sign("v3/route_types")
    }
}
