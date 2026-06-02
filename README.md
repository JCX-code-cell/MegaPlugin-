# MegaPlugin

一个功能全面的 Minecraft Purpur/Paper 插件，替代 CMI（Content Management Interface），为你的服务器提供一站式的管理解决方案。

## ✨ 功能模块

| 模块 | 说明 |
|------|------|
| 🏠 **家园系统** | `/sethome` `/home` `/delhome` `/homes` - 多家园管理 |
| 🧭 **地标传送** | `/warp` `/setwarp` `/delwarp` - 可视化 GUI 地标 |
| 📨 **玩家传送** | `/tpa` `/tpahere` `/tpaccept` `/tpdeny` `/tp` `/back` - 点击接受/拒绝 |
| 🏷️ **出生点** | `/spawn` `/setspawn` |
| 💰 **经济系统** | `/bal` `/pay` `/eco` `/baltop` - 转账/管理/富豪榜 |
| 🛠️ **管理工具** | `/gmc` `/gms` `/fly` `/god` `/heal` `/vanish` `/invsee` |
| 💬 **聊天工具** | `/nick` `/ec` `/whois` `/bc` - 昵称/末影箱/全服广播 |
| 🎁 **礼包系统** | `/kit` `/kits` `/createkit` `/deletekit` - 冷却时间管理 |
| 📋 **服务器菜单** | `/menu` - 全功能 GUI 主菜单 |
| 🔗 **物品绑定** | `/bind` `/unbind` `/binds` - 手持右键执行命令 |
| 🏪 **玩家市场** | `/market` `/ah` `/shop` - 可视化交易系统 |
| 🔐 **登录系统** | 铁砧 GUI 登录/注册，离线模式安全保护 |

## 🚀 快速开始

### 编译（需要 JDK 21）

```bash
# 使用 Maven
mvn clean package

# 或手动编译
javac -cp "purpur-api-1.21.11.jar" -d target/classes src/main/java/com/megaplugin/**/*.java
jar cf MegaPlugin-1.0.0.jar -C target/classes .
```

### 安装

将 `MegaPlugin-1.0.0.jar` 复制到服务器的 `plugins/` 目录，重启服务器。

### 配置

`plugins/MegaPlugin/config.yml`：
```yaml
messages:
  prefix: "&8[&6Mega&ePlugin&8]&r"
  no-permission: "{prefix} &c你没有权限！"
  player-only: "{prefix} &c只有玩家可以使用此命令！"
  player-not-found: "{prefix} &c玩家未找到！"
```
权限节点

| 权限 | 默认 | 说明 |
|------|------|------|
| `megaplugin.*` | OP | 所有权限 |
| `megaplugin.home` | 所有人 | 家园基础权限 |
| `megaplugin.admin` | OP | 管理命令 |
| `megaplugin.economy.admin` | OP | 经济管理 |
| `megaplugin.market` | 所有人 | 使用市场 |

技术栈
- Java 21
- Bukkit/Paper/Purpur API
- SHA-256 + 盐值密码加密
- YAML 数据持久化
- Adventure 组件（可点击消息）

许可证
GPL-3.0
