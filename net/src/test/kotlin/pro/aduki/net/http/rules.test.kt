package pro.aduki.net.http

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SieveTest {

    private val sender = Condition(Condition.Field.SENDER, "boss@example.com")

    @Test
    fun quotingEscapesBackslashAndQuote() {
        assertEquals("\"a\\\\b\\\"c\"", Sieve.quote("a\\b\"c"))
        assertEquals("\"plain\"", Sieve.quote("plain"))
    }

    @Test
    fun aRuleBecomesAnIfBlock() {
        val script = Sieve.build(listOf(Rule(id = "r1", conditions = listOf(sender), move = "Work", read = true)))
        assertTrue(script.contains("require [\"fileinto\", \"imap4flags\", \"body\"];"))
        assertTrue(script.contains("if header :contains \"from\" \"boss@example.com\" {\n" +
            "    addflag [\"\\\\Seen\"];\n" +
            "    fileinto \"Work\";\n" +
            "    stop;\n" +
            "}"))
    }

    @Test
    fun conditionsCombineWithAllofOrAnyof() {
        val subject = Condition(Condition.Field.SUBJECT, "invoice")
        val all = Sieve.build(listOf(Rule(conditions = listOf(sender, subject), flag = true)))
        assertTrue(all.contains("if allof (header :contains \"from\" \"boss@example.com\", header :contains \"subject\" \"invoice\") {"))
        assertTrue(all.contains("addflag [\"\\\\Flagged\"];"))
        val any = Sieve.build(listOf(Rule(conditions = listOf(sender, subject), all = false, delete = true, stop = false)))
        assertTrue(any.contains("if anyof ("))
        assertTrue(any.contains("    discard;\n}"))
        assertFalse(any.contains("stop;"))
    }

    @Test
    fun recipientAndAttachmentTests() {
        val script = Sieve.build(listOf(Rule(
            conditions = listOf(Condition(Condition.Field.RECIPIENT, "team@"), Condition(Condition.Field.ATTACHMENT)),
            move = "Team"
        )))
        assertTrue(script.contains("header :contains [\"to\", \"cc\"] \"team@\""))
        assertTrue(script.contains("body :raw :contains [\"Content-Disposition: attachment\""))
    }

    @Test
    fun hostileValuesStayInsideTheirString() {
        val evil = "x\" { discard; } if true { keep"
        val rule = Rule(id = "r", name = "a\nb", conditions = listOf(Condition(Condition.Field.SUBJECT, evil)), move = "In\\box\"")
        val script = Sieve.build(listOf(rule))
        assertTrue(script.contains("header :contains \"subject\" \"x\\\" { discard; } if true { keep\""))
        assertTrue(script.contains("fileinto \"In\\\\box\\\"\";"))
        // The name's newline stays escaped inside the one-line JSON header.
        assertEquals(1, script.lines().count { it.startsWith("# aduki-rules") })
        assertEquals(listOf(rule), Sieve.parse(script))
    }

    @Test
    fun rulesReadBackExactlyAndInactiveOnesAreNotRun() {
        val rules = listOf(
            Rule(id = "a", name = "Boss", conditions = listOf(sender), flag = true, read = true, stop = false),
            Rule(id = "b", name = "Off", conditions = listOf(Condition(Condition.Field.ATTACHMENT)), move = "Files", active = false)
        )
        val script = Sieve.build(rules)
        assertEquals(rules, Sieve.parse(script))
        assertFalse(script.contains("fileinto \"Files\""))
    }

    @Test
    fun aScriptWithoutTheHeaderIsNotOurs() {
        assertNull(Sieve.parse("require \"fileinto\";\nfileinto \"x\";\n"))
    }

    @Test
    fun invalidRulesAreRefused() {
        assertThrows(IllegalArgumentException::class.java) { Sieve.build(listOf(Rule(conditions = listOf(sender)))) }
        assertThrows(IllegalArgumentException::class.java) { Sieve.build(listOf(Rule(move = "x"))) }
        assertThrows(IllegalArgumentException::class.java) {
            Sieve.build(listOf(Rule(conditions = listOf(Condition(Condition.Field.SUBJECT, "a\nb")), move = "x")))
        }
        assertThrows(IllegalArgumentException::class.java) { Sieve.build(listOf(Rule(conditions = listOf(sender), move = "x", delete = true))) }
        val one = Rule(id = "same", conditions = listOf(sender), read = true)
        assertThrows(IllegalArgumentException::class.java) { Sieve.build(listOf(one, one)) }
    }
}
