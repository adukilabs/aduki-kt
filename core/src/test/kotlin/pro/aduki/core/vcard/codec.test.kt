package pro.aduki.core.vcard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import pro.aduki.core.models.Card
import pro.aduki.core.models.Link
import pro.aduki.core.models.Postal

/** vCard 3.0 and 4.0 reading and writing (#38). */
class VcardTest {

    private val v3 = listOf(
        "BEGIN:VCARD",
        "VERSION:3.0",
        "UID:u-1",
        "FN:Ann Lee",
        "N:Lee;Ann;;;",
        "ORG:Acme\\, Inc.;Research",
        "item1.EMAIL;TYPE=INTERNET,WORK:ann@acme.example",
        "EMAIL;TYPE=HOME:ann@home.example",
        "TEL;TYPE=CELL:+1 555 0100",
        "TEL;TYPE=\"voice,work\":+1 555 0101",
        "NOTE:line one\\nline two",
        "PHOTO;ENCODING=b;TYPE=JPEG:/9j/4AAQ",
        " SkZJRg==",
        "CATEGORIES:friends,work",
        "BDAY:1990-01-02",
        "X-CUSTOM;X-P=1:keep me",
        "END:VCARD"
    ).joinToString("\r\n") + "\r\n"

    @Test
    fun readsAThreeZeroCard() {
        val card = Vcard.parse(v3)
        assertEquals("Ann Lee", card.name)
        assertEquals(listOf("ann@acme.example", "ann@home.example"), card.emails)
        assertEquals(listOf("+1 555 0100", "+1 555 0101"), card.phones)
        assertEquals("Acme, Inc.", card.company)
        assertEquals("line one\nline two", card.notes)
        assertEquals("data:image/jpeg;base64,/9j/4AAQSkZJRg==", card.photo)
        assertEquals(listOf("friends", "work"), card.groups)
        assertEquals("u-1", card.uid)
    }

    @Test
    fun readsAFourZeroCardWithUris() {
        val card = Vcard.parse(
            "BEGIN:VCARD\nVERSION:4.0\nFN:Bo\nEMAIL;PREF=1:bo@x.example\nTEL;VALUE=uri;TYPE=cell:tel:+15550100\n" +
                "PHOTO:https://x.example/bo.png\nEND:VCARD\n"
        )
        assertEquals(listOf("bo@x.example"), card.emails)
        assertEquals(listOf("+15550100"), card.phones)
        assertEquals("https://x.example/bo.png", card.photo)
    }

    @Test
    fun nameFallsBackToNThenOrganisation() {
        assertEquals("Ann Lee", Vcard.parse("BEGIN:VCARD\nVERSION:3.0\nN:Lee;Ann;;;\nEND:VCARD\n").name)
        assertEquals("Acme", Vcard.parse("BEGIN:VCARD\nVERSION:3.0\nORG:Acme\nEND:VCARD\n").name)
    }

    @Test
    fun refusesOtherVersionsAndNamelessCards() {
        assertThrows(IllegalArgumentException::class.java) { Vcard.parse("BEGIN:VCARD\nVERSION:1.0\nFN:A\nEND:VCARD\n") }
        assertThrows(IllegalArgumentException::class.java) { Vcard.parse("BEGIN:VCARD\nVERSION:5.0\nFN:A\nEND:VCARD\n") }
        assertThrows(IllegalArgumentException::class.java) { Vcard.parse("BEGIN:VCARD\nVERSION:4.0\nEND:VCARD\n") }
        assertThrows(IllegalArgumentException::class.java) { Vcard.parse("BEGIN:VCARD\nVERSION:4.0\nFN:A\n") }
    }

    @Test
    fun splitsAFileIntoCards() {
        val file = "junk\r\n$v3\r\nBEGIN:VCARD\nVERSION:4.0\nFN:B\nEND:VCARD\nBEGIN:VCARD\nVERSION:4.0\nFN:C\n"
        val cards = Vcard.split(file)
        assertEquals(3, cards.size)
        assertEquals("Ann Lee", Vcard.parse(cards[0]).name)
        assertEquals("B", Vcard.parse(cards[1]).name)
    }

    @Test
    fun writingKeepsUnmodelledPropertiesAndParameters() {
        val out = Vcard.write(Vcard.parse(v3), Vcard.V4)
        val lines = out.split("\r\n")
        assertEquals("BEGIN:VCARD", lines.first())
        assertEquals("VERSION:4.0", lines[1])
        assertTrue("item1.EMAIL;TYPE=INTERNET,WORK:ann@acme.example" in lines)
        assertTrue("TEL;TYPE=\"voice,work\":+1 555 0101" in lines)
        assertTrue("ORG:Acme\\, Inc.;Research" in lines)
        assertTrue("BDAY:1990-01-02" in lines)
        assertTrue("X-CUSTOM;X-P=1:keep me" in lines)
        assertTrue("PHOTO:data:image/jpeg;base64,/9j/4AAQSkZJRg==" in lines)
        assertTrue("CATEGORIES:friends,work" in lines)
        assertTrue(out.endsWith("END:VCARD\r\n"))
    }

    @Test
    fun roundTripsBothVersions() {
        val card = Vcard.parse(v3)
        for (version in listOf(Vcard.V3, Vcard.V4)) {
            val again = Vcard.parse(Vcard.write(card, version))
            assertTrue("$version: $again", again.same(card))
            assertEquals("u-1", again.uid)
        }
        assertTrue("PHOTO;ENCODING=b;TYPE=JPEG:/9j/4AAQSkZJRg==" in Vcard.write(card, Vcard.V3).split("\r\n"))
    }

    @Test
    fun editedFieldsReplaceTheSourceLines() {
        val card = Vcard.parse(v3).copy(
            name = "Ann Lee-Park",
            emails = listOf("ann@home.example", "new@x.example"),
            phones = emptyList(),
            notes = "",
            groups = listOf("a,b")
        )
        val lines = Vcard.write(card).split("\r\n")
        assertTrue("FN:Ann Lee-Park" in lines)
        assertTrue("N:Lee-Park;Ann;;;" in lines)
        assertTrue("EMAIL;TYPE=HOME:ann@home.example" in lines)
        assertTrue("EMAIL:new@x.example" in lines)
        assertFalse(lines.any { it.startsWith("item1.EMAIL") || it.startsWith("TEL") || it.startsWith("NOTE") })
        assertTrue("CATEGORIES:a\\,b" in lines)
        assertEquals(listOf("a,b"), Vcard.parse(Vcard.write(card)).groups)
    }

    @Test
    fun foldsLongLinesAtSeventyFiveOctets() {
        val card = Card(name = "Ünïcödé ".repeat(20).trim(), notes = "x".repeat(300))
        val out = Vcard.write(card)
        for (line in out.split("\r\n")) assertTrue(line.toByteArray(Charsets.UTF_8).size <= 75)
        assertEquals(card.name, Vcard.parse(out).name)
        assertEquals(card.notes, Vcard.parse(out).notes)
    }

    @Test
    fun readsTwoOneCardWithBareParametersAndQuotedPrintableAndPhoto() {
        val v21 = listOf(
            "BEGIN:VCARD",
            "VERSION:2.1",
            "N;CHARSET=UTF-8;ENCODING=QUOTED-PRINTABLE:=C3=89=C3=A9=C3=A8;=C3=A0;;;",
            "FN;CHARSET=UTF-8;ENCODING=QUOTED-PRINTABLE:=C3=A0 =C3=89=C3=A9=C3=A8",
            "ORG;CHARSET=UTF-8;ENCODING=QUOTED-PRINTABLE:Aduki =C2=AE Labs",
            "TEL;CELL;PREF:+254712345678",
            "TEL;HOME:+254722000000",
            "EMAIL;INTERNET;HOME:ao@example.com",
            "NOTE;ENCODING=QUOTED-PRINTABLE;CHARSET=UTF-8:First line of note ==",
            "=0D=0ASecond line continues",
            "PHOTO;ENCODING=BASE64;JPEG:",
            "    /9j/4AAQSkZJRg==",
            "END:VCARD"
        ).joinToString("\r\n")

        val card = Vcard.parse(v21)
        assertEquals("à Ééè", card.name)
        assertEquals(listOf("+254712345678", "+254722000000"), card.phones)
        assertEquals(listOf("ao@example.com"), card.emails)
        assertEquals("Aduki ® Labs", card.company)
        assertTrue(card.notes.contains("First line of note ="))
        assertTrue(card.notes.contains("Second line continues"))
        assertEquals("data:image/jpeg;base64,/9j/4AAQSkZJRg==", card.photo)

        // Writing produces valid 4.0 or 3.0 vCard
        val v4out = Vcard.write(card, Vcard.V4)
        val v4card = Vcard.parse(v4out)
        assertTrue(v4card.same(card))
        assertTrue("VERSION:4.0" in v4out)
        assertTrue("TEL;TYPE=CELL,PREF:+254712345678" in v4out)
        assertFalse("QUOTED-PRINTABLE" in v4out)
    }

    @Test
    fun readsTwoOneCardWithEmptyFamilyName() {
        val v21 = listOf(
            "BEGIN:VCARD",
            "VERSION:2.1",
            "N:;Fred;;;",
            "TEL;CELL:+254700000001",
            "END:VCARD"
        ).joinToString("\r\n")

        val card = Vcard.parse(v21)
        assertEquals("Fred", card.name)
        assertEquals(listOf("+254700000001"), card.phones)

        val v4out = Vcard.write(card, Vcard.V4)
        val v4card = Vcard.parse(v4out)
        assertEquals("Fred", v4card.name)
    }

    @Test
    fun splitsAndParsesMixedTwoOneAndThreeZeroFile() {
        val v21 = "BEGIN:VCARD\r\nVERSION:2.1\r\nFN:Card One\r\nTEL;CELL:+100\r\nEND:VCARD\r\n"
        val v30 = "BEGIN:VCARD\r\nVERSION:3.0\r\nFN:Card Two\r\nTEL;TYPE=WORK:+200\r\nEND:VCARD\r\n"
        val file = "$v21\r\n$v30"

        val cards = Vcard.split(file)
        assertEquals(2, cards.size)
        val c1 = Vcard.parse(cards[0])
        val c2 = Vcard.parse(cards[1])
        assertEquals("Card One", c1.name)
        assertEquals(listOf("+100"), c1.phones)
        assertEquals("Card Two", c2.name)
        assertEquals(listOf("+200"), c2.phones)
    }

    @Test
    fun parsesAndWritesPostalAddressesLinksTitleBirthdayNicknameAndCollected() {
        val text = listOf(
            "BEGIN:VCARD",
            "VERSION:4.0",
            "FN:Alice Developer",
            "TITLE:Staff Engineer",
            "BDAY:1995-05-15",
            "NICKNAME:Ali",
            "ADR;TYPE=HOME:;;123 Main St;Nairobi;Nairobi County;00100;Kenya",
            "ADR;TYPE=WORK:PO Box 456;;Tech Park;Westlands;Nairobi;00800;Kenya",
            "URL;TYPE=WORK:https://example.com/alice",
            "URL:https://github.com/alice",
            "X-ADUKI-COLLECTED:TRUE",
            "END:VCARD"
        ).joinToString("\r\n")

        val card = Vcard.parse(text)
        assertEquals("Alice Developer", card.name)
        assertEquals("Staff Engineer", card.title)
        assertEquals("1995-05-15", card.birthday)
        assertEquals("Ali", card.nickname)
        assertTrue(card.collected)
        assertEquals(2, card.addresses.size)
        assertEquals(
            Postal(street = "123 Main St", locality = "Nairobi", region = "Nairobi County", code = "00100", country = "Kenya", type = "home"),
            card.addresses[0]
        )
        assertEquals(
            Postal(pobox = "PO Box 456", street = "Tech Park", locality = "Westlands", region = "Nairobi", code = "00800", country = "Kenya", type = "work"),
            card.addresses[1]
        )
        assertEquals(2, card.urls.size)
        assertEquals(Link(url = "https://example.com/alice", type = "work"), card.urls[0])
        assertEquals(Link(url = "https://github.com/alice", type = "home"), card.urls[1])

        // Roundtrip to V4
        val outV4 = Vcard.write(card, Vcard.V4)
        val roundtripV4 = Vcard.parse(outV4)
        assertTrue(roundtripV4.same(card))
        assertTrue("X-ADUKI-COLLECTED:TRUE" in outV4.split("\r\n"))
        assertTrue("TITLE:Staff Engineer" in outV4.split("\r\n"))
        assertTrue("BDAY:1995-05-15" in outV4.split("\r\n"))
        assertTrue("NICKNAME:Ali" in outV4.split("\r\n"))

        // Unmarking collected removes X-ADUKI-COLLECTED
        val uncollected = card.copy(collected = false)
        val outUncollected = Vcard.write(uncollected, Vcard.V4)
        val roundtripUncollected = Vcard.parse(outUncollected)
        assertFalse(roundtripUncollected.collected)
        assertFalse(outUncollected.contains("COLLECTED"))
    }
}
