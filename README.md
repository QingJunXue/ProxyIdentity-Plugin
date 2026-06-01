# ProxyIdentity

[![](https://img.shields.io/github/downloads/QingJunXue/proxy-identity/total?style=for-the-badge)](https://github.com/QingJunXue/proxy-identity/releases) [![](https://img.shields.io/github/license/QingJunXue/proxy-identity?style=for-the-badge)](https://github.com/QingJunXue/proxy-identity/blob/main/LICENSE) [![](https://img.shields.io/bstats/servers/12604?label=Spigot%20Servers&style=for-the-badge)](https://bstats.org/plugin/bukkit/ProxyIdentity/12604) [![](https://img.shields.io/bstats/servers/12605?label=BC%20Servers&style=for-the-badge)](https://bstats.org/plugin/bungeecord/ProxyIdentity/12605) [![](https://img.shields.io/bstats/servers/14442?label=Velocity%20Servers&style=for-the-badge)](https://bstats.org/plugin/velocity/ProxyIdentity/14442)

这是一个适用于 [BungeeCord](https://github.com/SpigotMC/BungeeCord/)、[Spigot](https://www.spigotmc.org/wiki/spigot/) 与 [Velocity](https://velocitypowered.com/) 的插件，允许在同一端口同时接受直连与通过 HAProxy 转发的代理连接。关于 [HAProxy](https://www.haproxy.org/) 与 PROXY protocol 的用途，可参阅 HAProxy 官方文档。

## 安全警告

同时允许直连与代理连接存在显著的安全风险：恶意玩家可以自行搭建 HAProxy 并伪造 PROXY 协议头，从而让服务器误以为其来源于虚假 IP。

为降低风险，本插件实现了“可信代理列表”。**默认允许本机与常见内网网段作为代理来源**（直连不受影响）。你可以在插件数据目录中的 `config.yml` 文件里，添加受信任的 HAProxy/FRP 实例的 IP、域名或网段。

<details>
    <summary>白名单格式详情</summary>

```
security:
  # 只有这些代理地址可以提交替换后的真实 IP。
  # 支持单个 IP、域名和 CIDR 网段；域名仅在启动时解析一次。
  trusted-proxies:
    - 127.0.0.1
    - ::1
    - 10.0.0.0/8
    - 172.16.0.0/12
    - 192.168.0.0/16
```

</details>

## 各平台注意事项

#### BungeeCord

- 需要在 BungeeCord 的 `config.yml` 中开启 `proxy_protocol`（不要与 `paper.yml` 中的同名选项混淆）。

#### Spigot 及其分支

- Bukkit/Spigot 需要安装并启用与你的服务端版本兼容的 [ProtocolLib](https://github.com/dmulloy2/ProtocolLib)。如果没有安装，插件会在启用阶段自行禁用并输出提示。
- 默认构建面向 Java 8 / 老 Bukkit 兼容环境（例如 1.7.10/1.12.2），并兼容 ProtocolLib 3.x/4.x 可用的注入路径。

#### Paper

- 新版本 Paper 自带 HAProxy 支持（仅代理连接）。与本插件同时启用会争用同一段握手管线，请在 Paper 配置中关闭内置 `proxy-protocol` 后再使用本插件。

#### Velocity

- 可以不启用 Velocity 内置 `haproxy-protocol`；插件会在连接管线中自行检测 PROXY protocol，并允许直连与代理连接混用。
- 默认构建面向 Velocity 3.4.x。插件元数据由 `@Plugin` 注解处理器生成，无需维护手写 `velocity-plugin.json`。

## 配置文件

插件首次启动会在各平台插件数据目录中创建 `config.yml`，所有插件配置都集中在这个文件中：

```yaml
proxy-protocol:
  # 是否启用 PROXY protocol 解析。FRP/HAProxy 必须发送该协议头，否则插件无法还原真实 IP。
  enabled: true
  # 是否接受 PROXY protocol v1 文本头。
  accept-v1: true
  # 是否接受 PROXY protocol v2 二进制头。
  accept-v2: true

security:
  # 只有这些代理地址可以提交替换后的真实 IP。
  # 支持单个 IP、域名和 CIDR 网段；域名仅在启动时解析一次。
  trusted-proxies:
    - 127.0.0.1
    - ::1
    - 10.0.0.0/8
    - 172.16.0.0/12
    - 192.168.0.0/16

# 是否输出调试日志。排查连接问题时可临时开启。
debug: false
```

布尔配置支持 `true`、`yes`、`on`、`1` 作为启用值；其他值按关闭处理。

## 构建

构建：

```
mvn clean package
```

当前发布前最低验证项：

```
mvn clean test
mvn clean package
```

真实服务端 smoke test 建议覆盖 Bukkit/Spigot + ProtocolLib、BungeeCord、Velocity 三个平台的启动和一次直连/HAProxy 连接握手。

## 统计信息（bStats）

本插件使用 [bStats](https://bStats.org) 统计匿名使用数据（如服务器数量、玩家总数、白名单条目数量等）。你可以随时在 `plugins/bStats/` 目录下修改配置以选择退出。

## 许可证与致谢

- 原作者：Andy Li（GitHub：[@andylizi](https://github.com/andylizi)）
- 源仓库：[`andylizi/haproxy-detector`](https://github.com/andylizi/haproxy-detector)
- 许可证：GNU Lesser General Public License v3。详见本仓库中的 `LICENSE` 文件。

## 协作与接管

- 欢迎熟悉 Java 的开发者提交 Issue/PR。
- 如果你愿意参与长期维护，也可以通过 Issue 与当前维护者联系。
