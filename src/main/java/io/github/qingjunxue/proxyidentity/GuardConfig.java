package io.github.qingjunxue.proxyidentity;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class GuardConfig {
	public static volatile boolean debug = false;
	public static volatile boolean proxyProtocolEnabled = true;
	public static volatile boolean acceptV1 = true;
	public static volatile boolean acceptV2 = true;
	public static volatile TrustedProxyList trustedProxies = new TrustedProxyList(defaultTrustedProxyRanges());

	private GuardConfig() {}

	public static void loadOrDefault(Path dataDirectory) throws IOException {
		Files.createDirectories(dataDirectory);
		Path config = dataDirectory.resolve("config.yml");
		if (!Files.exists(config) || Files.isDirectory(config)) {
			List<String> lines = Arrays.asList(
				"# ProxyIdentity 配置文件",
				"#",
				"proxy-protocol:",
				"  # 是否启用 PROXY protocol 解析。FRP/HAProxy 必须发送该协议头，否则插件无法还原真实 IP。",
				"  enabled: true",
				"  # 是否接受 PROXY protocol v1 文本头。",
				"  accept-v1: true",
				"  # 是否接受 PROXY protocol v2 二进制头。",
				"  accept-v2: true",
				"",
				"security:",
				"  # 只有这些代理地址可以提交替换后的真实 IP。",
				"  # 支持单个 IP、域名和 CIDR 网段；域名仅在启动时解析一次。",
				"  trusted-proxies:",
				"    - 127.0.0.1",
				"    - ::1",
				"    - 10.0.0.0/8",
				"    - 172.16.0.0/12",
				"    - 192.168.0.0/16",
				"",
				"# 是否输出调试日志。排查连接问题时可临时开启。",
				"debug: false"
			);
			Files.write(config, lines, StandardCharsets.UTF_8,
				StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
		}
		parse(config);
	}

	private static void parse(Path config) throws IOException {
		debug = false;
		proxyProtocolEnabled = true;
		acceptV1 = true;
		acceptV2 = true;
		ArrayList<String> trustedProxyLines = new ArrayList<>(defaultTrustedProxyLines());

		String section = "";
		String listKey = "";
		boolean trustedProxyListSeen = false;
		for (String rawLine : Files.readAllLines(config, StandardCharsets.UTF_8)) {
			String withoutComment = stripComment(rawLine);
			String s = withoutComment.trim();
			if (s.isEmpty() || s.startsWith("#")) continue;

			int indent = countLeadingSpaces(withoutComment);
			if (s.startsWith("-")) {
				if ("security".equals(section) && "trusted-proxies".equals(listKey)) {
					String item = unquote(s.substring(1).trim());
					if (!item.isEmpty()) {
						trustedProxyLines.add(item);
					}
				}
				continue;
			}

			int idx = s.indexOf(':');
			if (idx <= 0) continue;
			String key = s.substring(0, idx).trim().toLowerCase();
			String value = unquote(s.substring(idx + 1).trim()).toLowerCase();

			if (indent == 0) {
				listKey = "";
				if (value.isEmpty()) {
					section = key;
				} else {
					section = "";
					if ("debug".equals(key)) {
						debug = isTruthy(value);
					}
				}
				continue;
			}

			if ("proxy-protocol".equals(section)) {
				if ("enabled".equals(key)) {
					proxyProtocolEnabled = isTruthy(value);
				} else if ("accept-v1".equals(key)) {
					acceptV1 = isTruthy(value);
				} else if ("accept-v2".equals(key)) {
					acceptV2 = isTruthy(value);
				}
			} else if ("security".equals(section) && "trusted-proxies".equals(key)) {
				listKey = key;
				if (!trustedProxyListSeen) {
					trustedProxyLines.clear();
					trustedProxyListSeen = true;
				}
			}
		}

		trustedProxies = TrustedProxyList.parseEntries(trustedProxyLines);
	}

	private static boolean isTruthy(String value) {
		return "true".equals(value) || "yes".equals(value) || "on".equals(value) || "1".equals(value);
	}

	private static int countLeadingSpaces(String line) {
		int count = 0;
		while (count < line.length() && line.charAt(count) == ' ') {
			count++;
		}
		return count;
	}

	private static String stripComment(String line) {
		boolean quoted = false;
		char quote = 0;
		for (int i = 0; i < line.length(); i++) {
			char c = line.charAt(i);
			if ((c == '"' || c == '\'') && (i == 0 || line.charAt(i - 1) != '\\')) {
				if (!quoted) {
					quoted = true;
					quote = c;
				} else if (quote == c) {
					quoted = false;
				}
			} else if (c == '#' && !quoted) {
				return line.substring(0, i);
			}
		}
		return line;
	}

	private static String unquote(String value) {
		if (value.length() >= 2) {
			char first = value.charAt(0);
			char last = value.charAt(value.length() - 1);
			if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
				return value.substring(1, value.length() - 1).trim();
			}
		}
		return value;
	}

	private static List<String> defaultTrustedProxyLines() {
		return Arrays.asList(
				"127.0.0.1",
				"::1",
				"10.0.0.0/8",
				"172.16.0.0/12",
				"192.168.0.0/16"
		);
	}

	private static List<IpRange> defaultTrustedProxyRanges() {
		try {
			ArrayList<IpRange> ranges = new ArrayList<>();
			for (String line : defaultTrustedProxyLines()) {
				ranges.addAll(IpRange.parse(line));
			}
			return ranges;
		} catch (IOException e) {
			throw new ExceptionInInitializerError(e);
		}
	}
}


