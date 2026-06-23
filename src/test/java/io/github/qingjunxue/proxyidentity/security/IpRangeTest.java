package io.github.qingjunxue.proxyidentity.security;

import org.junit.jupiter.api.Test;

import java.net.UnknownHostException;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class IpRangeTest {
    @Test
    void parse() throws UnknownHostException  {
        Function<IpRange, List<IpRange>> f = Collections::singletonList;
        assertEquals(f.apply(new IpRange("127.0.0.0", 8)), IpRange.parse("127.0.0.0/8"));
        assertEquals(f.apply(new IpRange("224.0.0.0", 4)), IpRange.parse("224.0.0.0/4"));
        assertEquals(f.apply(new IpRange("192.168.0.0", 24)), IpRange.parse("192.168.0.0/24"));
        assertEquals(f.apply(new IpRange("2001:db8::", 112)), IpRange.parse("2001:db8::/112"));
        assertEquals(f.apply(new IpRange("2001:db8::ffff", 128)), IpRange.parse("2001:db8::ffff"));
    }

    @Test
    void parseDomain() throws UnknownHostException {
        List<IpRange> list = IpRange.parse("localhost");
        assertTrue(list.size() >= 1, "Should resolve at least one address");
        assertTrue(list.stream().anyMatch(cidr -> cidr.contains("127.0.0.1") || cidr.contains("::1")),
                "Should resolve to a local loopback address");
    }

    @Test
    void parseInvalid() {
        assertThrows(IllegalArgumentException.class, () -> IpRange.parse(""));
        assertThrows(IllegalArgumentException.class, () -> IpRange.parse("/"));
        assertThrows(IllegalArgumentException.class, () -> IpRange.parse("q/"));
        assertThrows(IllegalArgumentException.class, () -> IpRange.parse("/3"));
        assertThrows(IllegalArgumentException.class, () -> IpRange.parse("127.0.0.1/"));
        assertThrows(IllegalArgumentException.class, () -> IpRange.parse("127.0.0.1/3a"));
        assertThrows(IllegalArgumentException.class, () -> IpRange.parse("127.0.0.1/42"));
        assertThrows(IllegalArgumentException.class, () -> IpRange.parse("2001:db8::/666"));
        assertThrows(IllegalArgumentException.class, () -> IpRange.parse("example.com/8"));
        assertThrows(IllegalArgumentException.class, () -> IpRange.parse("12:34:56:78/8"));
        assertThrows(UnknownHostException.class, () -> IpRange.parse("%"));
    }

    @Test
    void contains() throws UnknownHostException {
        assertTrue(IpRange.parse("127.0.0.0/8").get(0).contains("127.0.0.0"));
        assertTrue(IpRange.parse("127.0.0.0/8").get(0).contains("127.1.2.3"));
        assertFalse(IpRange.parse("127.0.0.0/8").get(0).contains("192.168.0.1"));
        assertTrue(IpRange.parse("127.0.0.2").get(0).contains("127.0.0.2"));
        assertFalse(IpRange.parse("127.0.0.2").get(0).contains("127.0.0.1"));
        assertTrue(IpRange.parse("2001:db8::/112").get(0).contains("2001:db8::42"));
        assertFalse(IpRange.parse("2001:db8::/112").get(0).contains("8.8.8.8"));
    }
}