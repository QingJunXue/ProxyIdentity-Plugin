package io.github.qingjunxue.proxyidentity.protocol;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ProxyProtocolHeaderParserTest {
    @Test
    void parsesVersionOneTcp4Header() {
        byte[] bytes = "PROXY TCP4 198.51.100.23 203.0.113.7 45678 25565\r\n".getBytes(StandardCharsets.US_ASCII);

        ProxyProtocolHeaderParser.Result result = ProxyProtocolHeaderParser.detect(bytes, bytes.length);

        assertEquals(ProxyProtocolHeaderParser.State.DETECTED, result.state());
        assertEquals("198.51.100.23", result.sourceAddress().getHostString());
        assertEquals("198.51.100.23", result.sourceAddress().getAddress().getHostAddress());
        assertEquals(45678, result.sourceAddress().getPort());
        assertEquals(bytes.length, result.headerLength());
    }

    @Test
    void reportsNeedMoreDataForPartialVersionOneHeader() {
        byte[] bytes = "PROXY TCP4 198.51.100.23".getBytes(StandardCharsets.US_ASCII);

        ProxyProtocolHeaderParser.Result result = ProxyProtocolHeaderParser.detect(bytes, bytes.length);

        assertEquals(ProxyProtocolHeaderParser.State.NEEDS_MORE_DATA, result.state());
    }

    @Test
    void rejectsNonProxyTraffic() {
        byte[] bytes = new byte[] { 0x00, 0x2F, 0x01 };

        ProxyProtocolHeaderParser.Result result = ProxyProtocolHeaderParser.detect(bytes, bytes.length);

        assertEquals(ProxyProtocolHeaderParser.State.INVALID, result.state());
    }

    @Test
    void parsesVersionTwoTcp4Header() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(new byte[] { 0x0D, 0x0A, 0x0D, 0x0A, 0x00, 0x0D, 0x0A, 0x51, 0x55, 0x49, 0x54, 0x0A }, 0, 12);
        out.write(new byte[] { 0x21, 0x11, 0x00, 0x0C }, 0, 4);
        out.write(new byte[] { (byte) 198, 51, 100, 23, (byte) 203, 0, 113, 7, (byte) 0xB2, 0x6E, 0x63, (byte) 0xDD }, 0, 12);
        byte[] bytes = out.toByteArray();

        ProxyProtocolHeaderParser.Result result = ProxyProtocolHeaderParser.detect(bytes, bytes.length);

        assertEquals(ProxyProtocolHeaderParser.State.DETECTED, result.state());
        assertEquals("198.51.100.23", result.sourceAddress().getHostString());
        assertEquals("198.51.100.23", result.sourceAddress().getAddress().getHostAddress());
        assertEquals(45678, result.sourceAddress().getPort());
        assertEquals(bytes.length, result.headerLength());
    }

    @Test
    void acceptsUnknownVersionOneWithoutSourceAddress() {
        byte[] bytes = "PROXY UNKNOWN\r\n".getBytes(StandardCharsets.US_ASCII);

        ProxyProtocolHeaderParser.Result result = ProxyProtocolHeaderParser.detect(bytes, bytes.length);

        assertEquals(ProxyProtocolHeaderParser.State.DETECTED, result.state());
        assertNull(result.sourceAddress());
        assertEquals(bytes.length, result.headerLength());
    }
}