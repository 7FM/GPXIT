package dev.gpxit.app.data.komoot

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class KomootUrlParserTest {

    @Test fun `tour url with share token`() {
        val ref = KomootUrlParser.parse(
            "https://www.komoot.com/tour/2906227605?ref=aso&share_token=a0IiRcc5HTux8PD4MpjvFYDdWB99qRUZAU1EB7Jd15inFd26pp"
        )
        assertNotNull(ref)
        assertEquals(2906227605L, ref.tourId)
        assertEquals("a0IiRcc5HTux8PD4MpjvFYDdWB99qRUZAU1EB7Jd15inFd26pp", ref.shareToken)
    }

    @Test fun `tour url with locale segment`() {
        val ref = KomootUrlParser.parse(
            "https://www.komoot.com/de-de/tour/2891635011?share_token=a4v6NhVWARA33ZI1jlJ6PWHDur8OZxg1bdBVJm9sKjsRcQUSBW&ref=wtd"
        )
        assertNotNull(ref)
        assertEquals(2891635011L, ref.tourId)
        assertEquals("a4v6NhVWARA33ZI1jlJ6PWHDur8OZxg1bdBVJm9sKjsRcQUSBW", ref.shareToken)
    }

    @Test fun `invite-tour url with locale segment`() {
        val ref = KomootUrlParser.parse(
            "https://www.komoot.com/de-de/invite-tour/2878414149?code=5jr8fb-Mb-42PbfDdTPlWIUmAFSHCA1zd7-o6dQ82mS4eR0J7A&ref=wtd&share_token=a8qyR6nMQgE6kaqbQbtSjRJ4uW8ZB500PQdtZsvzKn4hj9Y373"
        )
        assertNotNull(ref)
        assertEquals(2878414149L, ref.tourId)
        assertEquals("a8qyR6nMQgE6kaqbQbtSjRJ4uW8ZB500PQdtZsvzKn4hj9Y373", ref.shareToken)
    }

    @Test fun `german domain works`() {
        val ref = KomootUrlParser.parse("https://komoot.de/tour/12345")
        assertNotNull(ref)
        assertEquals(12345L, ref.tourId)
        assertNull(ref.shareToken)
    }

    @Test fun `bare host without scheme is accepted`() {
        val ref = KomootUrlParser.parse("www.komoot.com/tour/9876")
        assertNotNull(ref)
        assertEquals(9876L, ref.tourId)
    }

    @Test fun `smarttour path is recognised`() {
        val ref = KomootUrlParser.parse("https://komoot.com/smarttour/55")
        assertNotNull(ref)
        assertEquals(55L, ref.tourId)
    }

    @Test fun `whitespace tolerated`() {
        val ref = KomootUrlParser.parse("  https://komoot.com/tour/42  ")
        assertNotNull(ref)
        assertEquals(42L, ref.tourId)
    }

    @Test fun `non-komoot host rejected`() {
        assertNull(KomootUrlParser.parse("https://example.com/tour/42"))
    }

    @Test fun `komoot user profile is not a tour`() {
        assertNull(KomootUrlParser.parse("https://www.komoot.com/user/12345"))
    }

    @Test fun `non-numeric tour id rejected`() {
        assertNull(KomootUrlParser.parse("https://www.komoot.com/tour/abcdef"))
    }

    @Test fun `empty input rejected`() {
        assertNull(KomootUrlParser.parse(""))
        assertNull(KomootUrlParser.parse(null))
        assertNull(KomootUrlParser.parse("   "))
    }

    @Test fun `garbage input does not crash`() {
        assertNull(KomootUrlParser.parse("not a url"))
        assertNull(KomootUrlParser.parse("ftp://komoot.com/tour/42"))
    }

    @Test fun `host-check helper works`() {
        assertEquals(true, KomootUrlParser.isKomootHost("komoot.com"))
        assertEquals(true, KomootUrlParser.isKomootHost("WWW.komoot.de"))
        assertEquals(false, KomootUrlParser.isKomootHost("strava.com"))
    }
}
