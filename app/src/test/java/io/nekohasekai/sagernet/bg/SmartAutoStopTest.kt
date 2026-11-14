package io.nekohasekai.sagernet.bg

import org.junit.Assert.*
import org.junit.Test

class SmartAutoStopTest {
    @Test fun acceptsNumericRulesAndSeparators() {
        assertEquals(listOf("192.168.1.1", "gateway:10.0.0.1/24", "2001:db8::1", "::/0", "::ffff:192.168.1.0/120"),
            SmartAutoStopRuleParser.parse("192.168.1.1，gAtEwAy:10.0.0.1/24;2001:db8::1\n::/0;::ffff:192.168.1.0/120").getOrThrow())
    }
    @Test fun rejectsNamesMalformedCidrsAndSixthEntry() {
        listOf("home-wifi", "example.com", "localhost", "127.1", "1.2.3.4/33", "::1/129", "::1/-1", "::1/1/2", "gateway:",
            "1.1.1.1;2.2.2.2;3.3.3.3;4.4.4.4;5.5.5.5;6.6.6.6").forEach {
            assertTrue(it, SmartAutoStopRuleParser.parse(it).isFailure)
        }
        assertEquals(emptyList<String>(), SmartAutoStopRuleParser.parse(" ,;\n").getOrThrow())
    }
}

class SmartAutoStopAndroidUserTest {
    @org.junit.Test fun derivesUserFromAndroidMultiuserUid() {
        org.junit.Assert.assertEquals(0, SmartAutoStopAndroidUser.fromUid(10123))
        org.junit.Assert.assertEquals(10, SmartAutoStopAndroidUser.fromUid(1010123))
        org.junit.Assert.assertEquals(21474, SmartAutoStopAndroidUser.fromUid(Int.MAX_VALUE))
    }
}
