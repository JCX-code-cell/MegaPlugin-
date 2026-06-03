# MegaPlugin v2.0

> 全能型 Minecraft Purpur/Paper 服务端插件 — 一站式管理解决方案

[![Java](https://img.shields.io/badge/Java-21-orange)](https://adoptium.net/)
[![Paper](https://img.shields.io/badge/Paper-1.21.1-blue)](https://papermc.io/)
[![License](https://img.shields.io/badge/License-GPL--3.0-green)](LICENSE)

## ✨ 功能模块

| 模块 | 命令 | 说明 |
|------|------|------|
| 🏠 **家园系统** | `/sethome` `/home` `/delhome` `/homes` | 多家园管理，支持查看他人家园 |
| 🧭 **地标传送** | `/warp` `/setwarp` `/delwarp` | 可视化 GUI 地标选择 |
| 📨 **玩家传送** | `/tpa` `/tpahere` `/tpaccept` `/tpdeny` | 可点击接受/拒绝按钮 |
| 🏷️ **出生点** | `/spawn` `/setspawn` | 全局出生点 |
| 💰 **经济系统** | `/bal` `/pay` `/eco` `/baltop` | 转账/管理/富豪榜 |
| 🛠️ **管理工具** | `/gmc` `/gms` `/fly` `/god` `/heal` `/vanish` `/invsee` | 一键管理 |
| 💬 **聊天工具** | `/nick` `/ec` `/whois` `/bc` | 昵称/末影箱/全服广播 |
| 🎁 **礼包系统** | `/kit` `/kits` `/createkit` `/deletekit` | 冷却时间管理 |
| 📋 **服务器菜单** | `/menu` | Shift+F 或命令打开 GUI |
| 🔗 **物品绑定** | `/bind` `/unbind` `/binds` | 手持右键执行命令 |
| 🏪 **玩家市场** | `/market` `/ah` `/shop` | GUI 上架/购买/下架 |
| 🔐 **登录系统** | `/register` `/login` `/changepassword` | SHA-256 加密 |
| 🎲 **随机传送** | `/rtp` | 安全检查 + 冷却 |
| 🛡️ **反作弊桥接** | `/gb` | GrimAC 代理命令 |
| ⚡ **渐进惩罚** | `/megapunish` | 跨重启累计违规自动封禁 |
| 🏡 **领地系统** | `/claim` | GUI 全功能：创建/扩展/收缩/出售/出租/Flag |

## 🔑 权限节点

| 权限 | 默认 | 说明 |
|------|------|------|
| `megaplugin.*` | OP | 所有权限 |
| `megaplugin.home` | 所有人 | 家园基础 |
| `megaplugin.home.other` | OP | 查看他人家园 |
| `megaplugin.warp` | 所有人 | 使用地标 |
| `megaplugin.warp.admin` | OP | 管理地标 |
| `megaplugin.tpa` | 所有人 | 传送请求 |
| `megaplugin.tp` | OP | 强制传送 |
| `megaplugin.back` | 所有人 | 返回上一位置 |
| `megaplugin.spawn` | 所有人 | 传送到出生点 |
| `megaplugin.spawn.set` | OP | 设置出生点 |
| `megaplugin.economy` | 所有人 | 经济基础 |
| `megaplugin.economy.admin` | OP | 经济管理 |
| `megaplugin.admin` | OP | 管理命令 |
| `megaplugin.chat` | 所有人 | 聊天工具 |
| `megaplugin.chat.admin` | OP | 聊天管理 |
| `megaplugin.kit` | 所有人 | 领取礼包 |
| `megaplugin.kit.admin` | OP | 管理礼包 |
| `megaplugin.market` | 所有人 | 使用市场 |
| `megaplugin.market.sell` | 所有人 | 上架商品 |
| `megaplugin.claim` | 所有人 | 领地基础 |
| `megaplugin.claim.admin` | OP | 领地管理 |
| `megaplugin.bind` | 所有人 | 物品绑定 |
| `megaplugin.punish` | OP | 惩罚管理 |

## 🛠 构建

```bash
# 需要 Maven + JDK 21
git clone https://github.com/JCX-code-cell/MegaPlugin-.git
cd MegaPlugin-
mvn clean package
# 输出: target/MegaPlugin-1.0.0.jar
```

## ⚠️ 注意事项

- **仓库仅包含源码，不提供预编译 JAR 文件。请自行构建。**
- **当前版本存在部分已知问题**，正在持续修复中。
- 欢迎反馈 bug 或贡献代码。

## 📧 联系方式

- **QQ:** 3957394226
- **邮箱:** 15116355055@163.com
- **GitHub:** [https://github.com/JCX-code-cell/MegaPlugin-](https://github.com/JCX-code-cell/MegaPlugin-)

## 📄 许可证

GPL-3.0
