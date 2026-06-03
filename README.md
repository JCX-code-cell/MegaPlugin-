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
| 🔐 **登录系统** | 安全，快速简便的登录系统 |

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
