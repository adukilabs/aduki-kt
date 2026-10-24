package pro.aduki.core.vcard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import pro.aduki.core.models.Card

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
        assertThrows(IllegalArgumentException::class.java) { Vcard.parse("BEGIN:VCARD\nVERSION:2.1\nFN:A\nEND:VCARD\n") }
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
}
