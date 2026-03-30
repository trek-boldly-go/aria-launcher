package com.aria.launcher.aria.llm

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AuthConfigTest {

    @Test
    fun `None encodes and decodes`() {
        val config = AuthConfig.None
        val encoded = config.encode()
        val decoded = AuthConfig.decode(encoded)
        assertThat(decoded).isEqualTo(AuthConfig.None)
    }

    @Test
    fun `Basic encodes and decodes`() {
        val config = AuthConfig.Basic("admin", "secret123")
        val encoded = config.encode()
        val decoded = AuthConfig.decode(encoded)
        assertThat(decoded).isEqualTo(config)
    }

    @Test
    fun `Basic handles password with colons`() {
        val config = AuthConfig.Basic("user", "p@ss:w:ord")
        val encoded = config.encode()
        val decoded = AuthConfig.decode(encoded)
        assertThat(decoded).isInstanceOf(AuthConfig.Basic::class.java)
        assertThat((decoded as AuthConfig.Basic).username).isEqualTo("user")
        assertThat(decoded.password).isEqualTo("p@ss:w:ord")
    }

    @Test
    fun `Basic handles special characters`() {
        val config = AuthConfig.Basic("user@domain.com", "p\$s{w}ord\"test")
        val encoded = config.encode()
        val decoded = AuthConfig.decode(encoded)
        assertThat(decoded).isEqualTo(config)
    }

    @Test
    fun `BearerToken encodes and decodes`() {
        val config = AuthConfig.BearerToken("eyJhbGciOi.test.token")
        val encoded = config.encode()
        val decoded = AuthConfig.decode(encoded)
        assertThat(decoded).isEqualTo(config)
    }

    @Test
    fun `CustomHeaders encodes and decodes`() {
        val config = AuthConfig.CustomHeaders(
            mapOf(
                "CF-Access-Client-Id" to "abc123",
                "CF-Access-Client-Secret" to "secret456",
            ),
        )
        val encoded = config.encode()
        val decoded = AuthConfig.decode(encoded)
        assertThat(decoded).isEqualTo(config)
    }

    @Test
    fun `CustomHeaders single header`() {
        val config = AuthConfig.CustomHeaders(mapOf("X-Api-Key" to "mykey"))
        val encoded = config.encode()
        val decoded = AuthConfig.decode(encoded)
        assertThat(decoded).isEqualTo(config)
    }

    @Test
    fun `decode null returns None`() {
        assertThat(AuthConfig.decode(null)).isEqualTo(AuthConfig.None)
    }

    @Test
    fun `decode blank returns None`() {
        assertThat(AuthConfig.decode("")).isEqualTo(AuthConfig.None)
        assertThat(AuthConfig.decode("   ")).isEqualTo(AuthConfig.None)
    }

    @Test
    fun `decode malformed JSON returns None`() {
        assertThat(AuthConfig.decode("not json at all")).isEqualTo(AuthConfig.None)
        assertThat(AuthConfig.decode("{invalid}")).isEqualTo(AuthConfig.None)
    }

    @Test
    fun `decode unknown type returns None`() {
        assertThat(AuthConfig.decode("""{"type":"unknown"}""")).isEqualTo(AuthConfig.None)
    }
}
