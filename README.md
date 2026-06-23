# ProxyIdentity

[![Downloads](https://img.shields.io/github/downloads/QingJunXue/ProxyIdentity-Plugin/total?style=for-the-badge)](https://github.com/QingJunXue/ProxyIdentity-Plugin/releases)
[![License](https://img.shields.io/github/license/QingJunXue/ProxyIdentity-Plugin?style=for-the-badge)](https://github.com/QingJunXue/ProxyIdentity-Plugin/blob/main/LICENSE)

ProxyIdentity 是一个适用于 Bukkit/Spigot、BungeeCord 与 Velocity 的 Minecraft 服务端代理身份插件。它用于在 FRP、HAProxy 或其他反向代理环境中解析 PROXY protocol，恢复玩家真实 IP，并提供可信代理校验、IP/CIDR 封禁与国家/地区访问控制。

## 功能特性

- 支持 PROXY protocol v1 文本头与 v2 二进制头。
- 支持强制要求所有连接携带 PROXY protocol 头。
- 支持恢复经过代理转发后的真实客户端 IP。
- 支持可信代理列表，防止玩家伪造 PROXY protocol 来源地址。
- 支持单个 IP、域名与 CIDR 网段作为可信代理。
- 支持封禁指定 IP 或 CIDR 网段。
- 支持基于 GeoIP2 Country 数据库封禁指定国家/地区。
- 支持服务器启动后异步同步 GeoIP 数据库，不阻塞启动流程。
- 支持 GeoIP 数据库多下载源与 SHA256 校验。
- 支持 Bukkit/Spigot、BungeeCord、Velocity 三个平台。
- 支持 bStats 匿名统计。

## 安全提醒

PROXY protocol 头本身可以被伪造。若服务器端口允许玩家绕过真实代理直接连接，恶意玩家可能自行发送伪造的 PROXY protocol 头，让后端服务器误判其真实 IP。

因此建议：

- 后端服务器端口只允许你的 FRP、HAProxy 或可信代理访问。
- 在 `security.trusted-proxies` 中只填写你实际信任的代理地址。
- 生产环境保持 `proxy-protocol.require-header: true`。
- 首次部署时临时开启 `debug: true` 排查链路，确认正常后改回 `false`。

## 平台说明

### Bukkit / Spigot

- 需要安装与你服务端版本兼容的 [ProtocolLib](https://github.com/dmulloy2/ProtocolLib)。
- 如果缺少 ProtocolLib 或版本不兼容，插件会在启动阶段输出中文错误提示并自行禁用。
- 默认构建为 Java 8 字节码，兼容老版本 Bukkit/Spigot 环境。
- 支持 Bukkit 1.7.x / ProtocolLib 3.x 的专用 PROXY protocol 注入路径。

### BungeeCord

- 需要在 BungeeCord 的 `config.yml` 中开启 `proxy_protocol`。
- 不要将 BungeeCord 的 `proxy_protocol` 与 Paper 配置中的同名选项混淆。

### Velocity

- 可以不启用 Velocity 内置 `haproxy-protocol`，插件会自行在连接管线中检测 PROXY protocol。
- 已针对 Velocity 3.5.x 多种 pipeline 顺序和 `read-timeout` 处理器变化做兼容。
- 插件会静默处理初始连接读超时，减少端口探测导致的控制台噪音。

### Paper

- 新版本 Paper 自带 PROXY protocol 支持，但通常只面向纯代理连接场景。
- 如果使用 ProxyIdentity，请关闭 Paper 内置 `proxy-protocol`，避免两个实现争用同一段握手管线。

## 配置文件

插件首次启动会在插件数据目录生成 `config.yml`。旧配置会在启动时自动补齐新配置项，请不要手动降低 `config-version`。

```yaml
# ProxyIdentity 配置文件
#
# 配置版本。请勿手动降低版本号，旧版本会在启动时自动补齐新配置项。
config-version: 3

# 是否输出调试日志。排查连接问题时可临时开启。
debug: false

proxy-protocol:
  # 是否启用 PROXY protocol 解析。FRP/HAProxy 必须发送该协议头，否则插件无法还原真实 IP。
  enabled: true
  # 是否强制要求所有连接都携带 PROXY protocol 头。开启后，直连和端口探测会被提前关闭。
  require-header: true
  # 是否接受 PROXY protocol v1 文本头。
  accept-v1: true
  # 是否接受 PROXY protocol v2 二进制头。
  accept-v2: true
  # 初始握手超时时间(毫秒)。未在此时间内识别为 PROXY protocol 的连接将被主动关闭。
  # 用于减少 MCSManager 等状态探测工具造成的长连接超时日志。
  handshake-timeout-ms: 3000

security:
  # 只有这些代理地址可以提交替换后的真实 IP。
  # 支持单个 IP、域名和 CIDR 网段；域名仅在启动时解析一次。
  trusted-proxies:
    - 127.0.0.1
    - ::1
    - 10.0.0.0/8
    - 172.16.0.0/12
    - 192.168.0.0/16

  # 封禁的源站 IP / 网段。命中后会直接拒绝连接。
  blocked-ips:
  # - 127.0.0.1
  # - 192.168.0.0/16

  # 封禁的国家/地区代码。仅在 geoip.enabled 为 true 且 GeoIP 数据库加载成功后生效。
  blocked-countries:
  # - CN

geoip:
  # 是否启用国家/地区封禁。
  enabled: false
  # GeoIP2 Country 数据库文件路径。相对路径以插件数据目录为基准。
  database-path: GeoLite2-Country.mmdb
  # 是否在服务器启动后异步同步 GeoIP 数据库。下载源由插件内置，不会写入配置文件。
  auto-update: true
  # GeoIP 数据库自动同步间隔(天)。
  update-days: 7
  # GeoIP 数据库同步连接超时时间(毫秒)。
  connect-timeout-ms: 10000
  # GeoIP 数据库同步读取超时时间(毫秒)。
  read-timeout-ms: 30000
```

布尔配置支持 `true`、`yes`、`on`、`1` 作为启用值，其他值按关闭处理。

## GeoIP 说明

- `security.blocked-ips` 不依赖 GeoIP，配置后始终生效。
- `security.blocked-countries` 需要 `geoip.enabled: true` 且 GeoIP 数据库成功加载后才会生效。
- `GeoLite2-Country.mmdb` 不会内置进插件 jar。
- 启用 GeoIP 自动同步后，插件会在服务器启动后异步下载数据库。
- 如果 GitHub 下载失败，插件会自动尝试备用 CDN。
- 如果所有下载源都失败，插件会继续使用本地旧数据库；没有可用数据库时仅国家/地区封禁失效。

## bStats

本插件使用 [bStats](https://bstats.org) 统计匿名使用数据，用于查看服务器数量、玩家数量、版本分布等趋势图。你可以在 `plugins/bStats/` 目录下修改配置以选择退出。

<div align="center">
  <p><strong>ProxyIdentity Bukkit</strong></p>
  <a href="https://bstats.org/plugin/bukkit/ProxyIdentity/32098">
    <img src="https://bstats.org/signatures/bukkit/ProxyIdentity%20Bukkit.svg" alt="ProxyIdentity Bukkit bStats">
  </a>

  <p><strong>ProxyIdentity BungeeCord</strong></p>
  <a href="https://bstats.org/plugin/bungeecord/ProxyIdentity/32099">
    <img src="https://bstats.org/signatures/bungeecord/ProxyIdentity%20BungeeCord.svg" alt="ProxyIdentity BungeeCord bStats">
  </a>

  <p><strong>ProxyIdentity Velocity</strong></p>
  <a href="https://bstats.org/plugin/velocity/ProxyIdentity/32100">
    <img src="https://bstats.org/signatures/velocity/ProxyIdentity%20Velocity.svg" alt="ProxyIdentity Velocity bStats">
  </a>
</div>

## 构建

请先确认本机已安装 JDK 8+ 与 Maven。项目默认构建 Java 8 字节码，适合老版本 Bukkit/Spigot 与现代代理端共同使用。

```bash
mvn clean package
```

构建完成后，插件 jar 会生成在 `target/` 目录下，文件名格式为 `proxy-identity-版本号.jar`。

## 问题反馈

提交 Issue 时请尽量附上：

- 服务端类型与版本。
- Java 版本。
- ProxyIdentity 版本。
- ProtocolLib 版本，若使用 Bukkit/Spigot。
- 前置代理类型，例：FRP、HAProxy。
- 完整启动日志和报错日志。
- 与问题相关的 `config.yml` 配置片段。

## 许可证与致谢

- 原作者：Andy Li（GitHub：[@andylizi](https://github.com/andylizi)）
- 源仓库：[`andylizi/haproxy-detector`](https://github.com/andylizi/haproxy-detector)
- 当前维护仓库：[`QingJunXue/ProxyIdentity-Plugin`](https://github.com/QingJunXue/ProxyIdentity-Plugin)
- 许可证：GNU Lesser General Public License v3，详见本仓库中的 `LICENSE` 文件。

## 协作与接管

- 欢迎熟悉 Java、Minecraft 服务端或网络代理部署的开发者提交 Issue/PR。
- 如果你愿意参与长期维护，也可以通过 Issue 与当前维护者联系。
