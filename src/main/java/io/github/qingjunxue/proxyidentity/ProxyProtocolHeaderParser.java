package io.github.qingjunxue.proxyidentity;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;

public final class ProxyProtocolHeaderParser {
    private static final byte[] V2_SIGNATURE = new byte[] {
            0x0D, 0x0A, 0x0D, 0x0A, 0x00, 0x0D, 0x0A, 0x51, 0x55, 0x49, 0x54, 0x0A
    };
    private static final int V1_MAX_HEADER_LENGTH = 108;
    private static final int V2_HEADER_LENGTH = 16;

    private ProxyProtocolHeaderParser() {
        throw new AssertionError();
    }

    public static Result detect(byte[] bytes, int length) {
        if (length == 0) {
            return Result.needsMoreData();
        }

        Result v2 = detectV2(bytes, length);
        if (v2.state != State.INVALID) {
            return v2;
        }

        return detectV1(bytes, length);
    }

    private static Result detectV1(byte[] bytes, int length) {
        byte[] prefix = "PROXY ".getBytes(StandardCharsets.US_ASCII);
        for (int i = 0; i < Math.min(length, prefix.length); i++) {
            if (bytes[i] != prefix[i]) {
                return Result.invalid();
            }
        }
        if (length < prefix.length) {
            return Result.needsMoreData();
        }

        int lineEnd = -1;
        int max = Math.min(length - 1, V1_MAX_HEADER_LENGTH);
        for (int i = prefix.length; i < max; i++) {
            if (bytes[i] == '\r' && bytes[i + 1] == '\n') {
                lineEnd = i;
                break;
            }
        }
        if (lineEnd < 0) {
            return length >= V1_MAX_HEADER_LENGTH ? Result.invalid() : Result.needsMoreData();
        }

        String header = new String(bytes, 0, lineEnd, StandardCharsets.US_ASCII);
        String[] parts = header.split(" ");
        if (parts.length < 2) {
            return Result.invalid();
        }
        if ("UNKNOWN".equals(parts[1])) {
            return Result.detected(null, lineEnd + 2, 1);
        }
        if (parts.length != 6 || (!"TCP4".equals(parts[1]) && !"TCP6".equals(parts[1]))) {
            return Result.invalid();
        }

        try {
            int sourcePort = parsePort(parts[4]);
            return Result.detected(new InetSocketAddress(InetAddress.getByName(parts[2]), sourcePort), lineEnd + 2, 1);
        } catch (IllegalArgumentException e) {
            return Result.invalid();
        } catch (UnknownHostException e) {
            return Result.invalid();
        }
    }

    private static Result detectV2(byte[] bytes, int length) {
        for (int i = 0; i < Math.min(length, V2_SIGNATURE.length); i++) {
            if (bytes[i] != V2_SIGNATURE[i]) {
                return Result.invalid();
            }
        }
        if (length < V2_SIGNATURE.length) {
            return Result.needsMoreData();
        }
        if (length < V2_HEADER_LENGTH) {
            return Result.needsMoreData();
        }

        int versionCommand = bytes[12] & 0xFF;
        if ((versionCommand & 0xF0) != 0x20) {
            return Result.invalid();
        }

        int command = versionCommand & 0x0F;
        int family = bytes[13] & 0xF0;
        int transport = bytes[13] & 0x0F;
        int addressLength = ((bytes[14] & 0xFF) << 8) | (bytes[15] & 0xFF);
        int totalLength = V2_HEADER_LENGTH + addressLength;
        if (length < totalLength) {
            return Result.needsMoreData();
        }
        if (command == 0x00) {
            return Result.detected(null, totalLength, 2);
        }
        if (command != 0x01 || transport != 0x01) {
            return Result.detected(null, totalLength, 2);
        }

        if (family == 0x10) {
            if (addressLength < 12) {
                return Result.invalid();
            }
            int sourcePort = unsignedShort(bytes, V2_HEADER_LENGTH + 8);
            return Result.detected(socketAddress(bytes, V2_HEADER_LENGTH, 4, sourcePort), totalLength, 2);
        }
        if (family == 0x20) {
            if (addressLength < 36) {
                return Result.invalid();
            }
            int sourcePort = unsignedShort(bytes, V2_HEADER_LENGTH + 32);
            return Result.detected(socketAddress(bytes, V2_HEADER_LENGTH, 16, sourcePort), totalLength, 2);
        }

        return Result.detected(null, totalLength, 2);
    }

    private static int parsePort(String value) {
        int port = Integer.parseInt(value);
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("Port out of range");
        }
        return port;
    }

    private static int unsignedShort(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xFF) << 8) | (bytes[offset + 1] & 0xFF);
    }

    private static InetSocketAddress socketAddress(byte[] bytes, int offset, int addressLength, int port) {
        byte[] address = new byte[addressLength];
        System.arraycopy(bytes, offset, address, 0, addressLength);
        try {
            return new InetSocketAddress(InetAddress.getByAddress(address), port);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("Invalid address length", e);
        }
    }

    public enum State {
        NEEDS_MORE_DATA,
        INVALID,
        DETECTED
    }

    public static final class Result {
        private final State state;
        private final InetSocketAddress sourceAddress;
        private final int headerLength;
        private final int version;

        private Result(State state, InetSocketAddress sourceAddress, int headerLength, int version) {
            this.state = state;
            this.sourceAddress = sourceAddress;
            this.headerLength = headerLength;
            this.version = version;
        }

        public static Result needsMoreData() {
            return new Result(State.NEEDS_MORE_DATA, null, 0, 0);
        }

        public static Result invalid() {
            return new Result(State.INVALID, null, 0, 0);
        }

        public static Result detected(InetSocketAddress sourceAddress, int headerLength, int version) {
            return new Result(State.DETECTED, sourceAddress, headerLength, version);
        }

        public State state() {
            return state;
        }

        public InetSocketAddress sourceAddress() {
            return sourceAddress;
        }

        public int headerLength() {
            return headerLength;
        }

        public int version() {
            return version;
        }
    }
}
