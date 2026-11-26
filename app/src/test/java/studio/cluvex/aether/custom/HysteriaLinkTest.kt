package studio.cluvex.aether.custom

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class HysteriaLinkTest {

    private val tables = { CustomConfigTestSupport.tables }

    private fun parse(text: String) = ConfigParser.parse(text, ConfigSource.LINK, tables, null, NOW)
    private fun out(c: CustomConfig): JsonObject = Json.parseToJsonElement(c.outbound).jsonObject
    private fun JsonObject.obj(k: String) = this[k]!!.jsonObject
    private fun JsonObject.s(k: String) = this[k]!!.jsonPrimitive.content

    @Test
    fun `hysteria2 link with sni and name`() {
        val c = parse("hysteria2://secret@hy.example.com:8443/?sni=front.example.com#my-hy").single()
        assertEquals(ConfigProtocol.HYSTERIA, c.protocol)
        assertEquals("my-hy", c.name)
        assertEquals("hy.example.com:8443", c.server)
        val o = out(c)
        assertEquals("hysteria", o.s("protocol"))
        assertEquals("hy.example.com", o.obj("settings").s("address"))
        val stream = o.obj("streamSettings")
        assertEquals("hysteria", stream.s("network"))
        assertEquals("secret", stream.obj("hysteriaSettings").s("auth"))
        assertEquals("front.example.com", stream.obj("tlsSettings").s("serverName"))
        assertFalse(stream.containsKey("finalmask"))
    }

    @Test
    fun `hy2 scheme defaults to port 443 and keeps user password auth`() {
        val c = parse("hy2://user:pass@hy.example.com/").single()
        assertEquals("hy.example.com:443", c.server)
        assertEquals("user:pass", out(c).obj("streamSettings").obj("hysteriaSettings").s("auth"))
    }

    @Test
    fun `port hopping range keeps the first port`() {
        val c = parse("hysteria2://secret@hy.example.com:20000-30000/").single()
        assertEquals("hy.example.com:20000", c.server)
    }

    @Test
    fun `salamander obfuscation becomes a finalmask udp entry`() {
        val c = parse("hysteria2://secret@hy.example.com:443/?obfs=salamander&obfs-password=xyz").single()
        val mask = out(c).obj("streamSettings").obj("finalmask")["udp"]!!.jsonArray[0].jsonObject
        assertEquals("salamander", mask.s("type"))
        assertEquals("xyz", mask.obj("settings").s("password"))
    }

    @Test
    fun `insecure flag is carried to the tls settings`() {
        val c = parse("hysteria2://secret@192.0.2.5:443/?insecure=1").single()
        assertTrue(out(c).obj("streamSettings").obj("tlsSettings")["allowInsecure"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `hysteria 1 links are refused with an explanation`() {
        try {
            parse("hysteria://hy.example.com:443?auth=abc")
            fail("expected ConfigImportException")
        } catch (e: ConfigImportException) {
            assertTrue(e.message!!.contains("Hysteria 1"))
        }
    }

    @Test
    fun `json outbound named hysteria2 is normalised for Xray`() {
        val c = parse(
            """{"outbounds":[{"protocol":"hysteria2","settings":{"version":2,"address":"hy.example.com","port":443},""" +
                """"streamSettings":{"network":"hysteria","security":"tls","hysteriaSettings":{"version":2,"auth":"a"}}}]}""",
        ).single()
        assertEquals(ConfigProtocol.HYSTERIA, c.protocol)
        assertEquals("hysteria", out(c).s("protocol"))
    }

    private companion object {
        const val NOW = 1_700_000_000_000L
    }
}
