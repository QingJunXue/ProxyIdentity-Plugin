package io.github.qingjunxue.proxyidentity;

import io.github.qingjunxue.proxyidentity.security.IpRange;
import io.github.qingjunxue.proxyidentity.security.BlockedCountryList;
import io.github.qingjunxue.proxyidentity.security.IpBlockList;
import io.github.qingjunxue.proxyidentity.security.TrustedProxyList;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class ProxyIdentityConfig {
	private static final int CURRENT_CONFIG_VERSION = 3;

	public static volatile int configVersion = CURRENT_CONFIG_VERSION;
	public static volatile boolean debug = false;
	public static volatile boolean proxyProtocolEnabled = true;
	public static volatile boolean requireProxyHeader = true;
	public static volatile boolean acceptV1 = true;
	public static volatile boolean acceptV2 = true;
	public static volatile int handshakeTimeoutMs = 3000;
	public static volatile TrustedProxyList trustedProxies = new TrustedProxyList(defaultTrustedProxyRanges());
	public static volatile IpBlockList blockedIps = new IpBlockList(new ArrayList<IpRange>());
	public static volatile BlockedCountryList blockedCountries = new BlockedCountryList(new ArrayList<String>());
	public static volatile boolean geoIpEnabled = false;
	public static volatile String geoIpDatabasePath = "GeoLite2-Country.mmdb";
	public static volatile boolean geoIpAutoUpdate = false;
	public static volatile int geoIpUpdateDays = 7;
	public static volatile int geoIpConnectTimeoutMs = 5000;
	public static volatile int geoIpReadTimeoutMs = 15000;

	private ProxyIdentityConfig() {}

	public static void loadOrDefault(Path dataDirectory) throws IOException {
		Files.createDirectories(dataDirectory);
		Path config = dataDirectory.resolve("config.yml");
		if (!Files.exists(config) || Files.isDirectory(config)) {
			writeDefaultConfig(config);
		} else if (isOutdatedConfig(config)) {
			upgradeConfig(config);
		}
		parse(config);
	}

	private static void writeDefaultConfig(Path config) throws IOException {
		List<String> lines = new ArrayList<>();
		lines.add("# ProxyIdentity 配置文件");
		lines.add("#");
		lines.add("# 配置版本。请勿手动降低版本号，旧版本会在启动时自动补齐新配置项。");
		lines.add("config-version: " + CURRENT_CONFIG_VERSION);
		lines.add("");
		lines.add("# 是否输出调试日志。排查连接问题时可临时开启。");
		lines.add("debug: false");
		lines.add("");
		lines.add("proxy-protocol:");
		lines.add("  # 是否启用 PROXY protocol 解析。FRP/HAProxy 必须发送该协议头，否则插件无法还原真实 IP。");
		lines.add("  enabled: true");
		lines.add("  # 是否强制要求所有连接都携带 PROXY protocol 头。开启后，直连和端口探测会被提前关闭。");
		lines.add("  require-header: true");
		lines.add("  # 是否接受 PROXY protocol v1 文本头。");
		lines.add("  accept-v1: true");
		lines.add("  # 是否接受 PROXY protocol v2 二进制头。");
		lines.add("  accept-v2: true");
		lines.add("  # 初始握手超时时间(毫秒)。未在此时间内识别为 PROXY protocol 的连接将被主动关闭。");
		lines.add("  # 用于减少 MCSManager 等状态探测工具造成的长连接超时日志。");
		lines.add("  handshake-timeout-ms: 3000");
		lines.add("");
		lines.add("security:");
		lines.add("  # 只有这些代理地址可以提交替换后的真实 IP。");
		lines.add("  # 支持单个 IP、域名和 CIDR 网段；域名仅在启动时解析一次。");
		lines.add("  trusted-proxies:");
		for (String proxy : defaultTrustedProxyLines()) {
			lines.add("    - " + proxy);
		}
		lines.add("  # 封禁的源站 IP / 网段。命中后会直接拒绝连接。");
		lines.add("  blocked-ips:");
		lines.add("    # - 176.65.148.0/24");
		lines.add("  # 封禁的国家/地区代码。仅在启用 geoip.enabled 且本地数据库可用时生效。");
		lines.add("  blocked-countries:");
		lines.add("    # - CN");
		lines.add("");
		lines.add("geoip:");
		lines.add("  # 是否启用国家/地区封禁。");
		lines.add("  enabled: false");
		lines.add("  # GeoIP2 Country 数据库文件路径。相对路径以插件数据目录为基准。");
		lines.add("  database-path: GeoLite2-Country.mmdb");
		lines.add("  # 是否在服务器启动后异步同步 GeoIP 数据库。下载源由插件内置，不会写入配置文件。");
		lines.add("  auto-update: false");
		lines.add("  # 本地数据库超过多少天后尝试同步。");
		lines.add("  update-days: 7");
		lines.add("  # GeoIP 数据库同步连接超时时间(毫秒)。");
		lines.add("  connect-timeout-ms: 5000");
		lines.add("  # GeoIP 数据库同步读取超时时间(毫秒)。");
		lines.add("  read-timeout-ms: 15000");
		Files.write(config, lines, StandardCharsets.UTF_8,
				StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
	}

	private static boolean isOutdatedConfig(Path config) throws IOException {
		return readConfigVersion(config) < CURRENT_CONFIG_VERSION;
	}

	private static int readConfigVersion(Path config) throws IOException {
		for (String rawLine : Files.readAllLines(config, StandardCharsets.UTF_8)) {
			String s = stripComment(rawLine).trim();
			int idx = s.indexOf(':');
			if (idx > 0 && "config-version".equals(s.substring(0, idx).trim().toLowerCase())) {
				return parseIntOrDefault(unquote(s.substring(idx + 1).trim()), 1);
			}
		}
		return 1;
	}

	private static void upgradeConfig(Path config) throws IOException {
		List<String> lines = Files.readAllLines(config, StandardCharsets.UTF_8);
		ArrayList<String> upgraded = new ArrayList<>();
		boolean versionWritten = false;
		boolean requireHeaderWritten = false;
		boolean blockedIpsWritten = false;
		boolean blockedCountriesWritten = false;
		boolean geoIpWritten = false;

		for (String line : lines) {
			String s = stripComment(line).trim();
			String key = keyOf(s);
			if ("config-version".equals(key)) {
				upgraded.add("config-version: " + CURRENT_CONFIG_VERSION);
				versionWritten = true;
				continue;
			}
			upgraded.add(line);
			if ("enabled".equals(key) && countLeadingSpaces(line) > 0) {
				upgraded.add("  # 是否强制要求所有连接都携带 PROXY protocol 头。开启后，直连和端口探测会被提前关闭。");
				upgraded.add("  require-header: true");
				requireHeaderWritten = true;
			} else if ("require-header".equals(key)) {
				requireHeaderWritten = true;
			} else if ("blocked-ips".equals(key)) {
				blockedIpsWritten = true;
			} else if ("blocked-countries".equals(key)) {
				blockedCountriesWritten = true;
			} else if ("geoip".equals(key)) {
				geoIpWritten = true;
			}
		}

		if (!versionWritten) {
			upgraded.add(0, "");
			upgraded.add(0, "config-version: " + CURRENT_CONFIG_VERSION);
			upgraded.add(0, "# 配置版本。请勿手动降低版本号，旧版本会在启动时自动补齐新配置项。");
		}
		if (!requireHeaderWritten) {
			upgraded.add("");
			upgraded.add("proxy-protocol:");
			upgraded.add("  # 是否强制要求所有连接都携带 PROXY protocol 头。开启后，直连和端口探测会被提前关闭。");
			upgraded.add("  require-header: true");
		}
		if (!blockedIpsWritten || !blockedCountriesWritten) {
			upgraded.add("");
			upgraded.add("security:");
			if (!blockedIpsWritten) {
				upgraded.add("  # 封禁的源站 IP / 网段。命中后会直接拒绝连接。");
				upgraded.add("  blocked-ips:");
				upgraded.add("    # - 176.65.148.0/24");
			}
			if (!blockedCountriesWritten) {
				upgraded.add("  # 封禁的国家/地区代码。仅在启用 geoip.enabled 且本地数据库可用时生效。");
				upgraded.add("  blocked-countries:");
				upgraded.add("    # - CN");
			}
		}
		if (!geoIpWritten) {
			upgraded.add("");
			upgraded.add("geoip:");
			upgraded.add("  # 是否启用国家/地区封禁。");
			upgraded.add("  enabled: false");
			upgraded.add("  # GeoIP2 Country 数据库文件路径。相对路径以插件数据目录为基准。");
			upgraded.add("  database-path: GeoLite2-Country.mmdb");
			upgraded.add("  # 是否在服务器启动后异步同步 GeoIP 数据库。下载源由插件内置，不会写入配置文件。");
			upgraded.add("  auto-update: false");
			upgraded.add("  # 本地数据库超过多少天后尝试同步。");
			upgraded.add("  update-days: 7");
			upgraded.add("  # GeoIP 数据库同步连接超时时间(毫秒)。");
			upgraded.add("  connect-timeout-ms: 5000");
			upgraded.add("  # GeoIP 数据库同步读取超时时间(毫秒)。");
			upgraded.add("  read-timeout-ms: 15000");
		}
		Files.write(config, upgraded, StandardCharsets.UTF_8,
				StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
	}

	private static String keyOf(String line) {
		int idx = line.indexOf(':');
		return idx > 0 ? line.substring(0, idx).trim().toLowerCase() : "";
	}

	private static void parse(Path config) throws IOException {
		configVersion = CURRENT_CONFIG_VERSION;
		debug = false;
		proxyProtocolEnabled = true;
		requireProxyHeader = true;
		acceptV1 = true;
		acceptV2 = true;
		handshakeTimeoutMs = 3000;
		ArrayList<String> trustedProxyLines = new ArrayList<>(defaultTrustedProxyLines());
		ArrayList<String> blockedIpLines = new ArrayList<>();
		ArrayList<String> blockedCountryLines = new ArrayList<>();
		boolean geoIpEnabledLocal = false;
		String geoIpDatabasePathLocal = "GeoLite2-Country.mmdb";
		boolean geoIpAutoUpdateLocal = false;
		int geoIpUpdateDaysLocal = 7;
		int geoIpConnectTimeoutMsLocal = 5000;
		int geoIpReadTimeoutMsLocal = 15000;

		String section = "";
		String listKey = "";
		boolean trustedProxyListSeen = false;
		boolean blockedIpListSeen = false;
		boolean blockedCountryListSeen = false;
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
				} else if ("security".equals(section) && "blocked-ips".equals(listKey)) {
					String item = unquote(s.substring(1).trim());
					if (!item.isEmpty()) {
						blockedIpLines.add(item);
					}
				} else if ("security".equals(section) && "blocked-countries".equals(listKey)) {
					String item = unquote(s.substring(1).trim());
					if (!item.isEmpty()) {
						blockedCountryLines.add(item);
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
					} else if ("config-version".equals(key)) {
						configVersion = parseIntOrDefault(value, CURRENT_CONFIG_VERSION);
					}
				}
				continue;
			}

			if ("proxy-protocol".equals(section)) {
				if ("enabled".equals(key)) {
					proxyProtocolEnabled = isTruthy(value);
				} else if ("require-header".equals(key)) {
					requireProxyHeader = isTruthy(value);
				} else if ("accept-v1".equals(key)) {
					acceptV1 = isTruthy(value);
				} else if ("accept-v2".equals(key)) {
					acceptV2 = isTruthy(value);
				} else if ("handshake-timeout-ms".equals(key)) {
					handshakeTimeoutMs = parseIntOrDefault(value, 3000);
				}
			} else if ("security".equals(section) && "trusted-proxies".equals(key)) {
				listKey = key;
				if (!trustedProxyListSeen) {
					trustedProxyLines.clear();
					trustedProxyListSeen = true;
				}
			} else if ("security".equals(section) && "blocked-ips".equals(key)) {
				listKey = key;
				if (!blockedIpListSeen) {
					blockedIpLines.clear();
					blockedIpListSeen = true;
				}
			} else if ("security".equals(section) && "blocked-countries".equals(key)) {
				listKey = key;
				if (!blockedCountryListSeen) {
					blockedCountryLines.clear();
					blockedCountryListSeen = true;
				}
			} else if ("geoip".equals(section)) {
				if ("enabled".equals(key)) {
					geoIpEnabledLocal = isTruthy(value);
				} else if ("database-path".equals(key)) {
					geoIpDatabasePathLocal = unquote(s.substring(idx + 1).trim());
				} else if ("auto-update".equals(key)) {
					geoIpAutoUpdateLocal = isTruthy(value);
				} else if ("update-days".equals(key)) {
					geoIpUpdateDaysLocal = parseIntOrDefault(value, 7);
				} else if ("connect-timeout-ms".equals(key)) {
					geoIpConnectTimeoutMsLocal = parseIntOrDefault(value, 5000);
				} else if ("read-timeout-ms".equals(key)) {
					geoIpReadTimeoutMsLocal = parseIntOrDefault(value, 15000);
				}
			}
		}

		trustedProxies = TrustedProxyList.parseEntries(trustedProxyLines);
		blockedIps = IpBlockList.parseEntries(blockedIpLines);
		blockedCountries = BlockedCountryList.parseEntries(blockedCountryLines);
		geoIpEnabled = geoIpEnabledLocal;
		geoIpDatabasePath = geoIpDatabasePathLocal;
		geoIpAutoUpdate = geoIpAutoUpdateLocal;
		geoIpUpdateDays = geoIpUpdateDaysLocal;
		geoIpConnectTimeoutMs = geoIpConnectTimeoutMsLocal;
		geoIpReadTimeoutMs = geoIpReadTimeoutMsLocal;
	}

	private static boolean isTruthy(String value) {
		return "true".equals(value) || "yes".equals(value) || "on".equals(value) || "1".equals(value);
	}

	private static int parseIntOrDefault(String value, int defaultValue) {
		try {
			int parsed = Integer.parseInt(value);
			return parsed > 0 ? parsed : defaultValue;
		} catch (NumberFormatException e) {
			return defaultValue;
		}
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


