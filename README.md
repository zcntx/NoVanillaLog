# NoVanillaLog

Paper / Folia / Luminol 插件 — 从控制台和游戏内拦截原版指令刷屏消息。

## 问题

```
[20:27:54 INFO]: Applied effect Strength to Mozz        ← × 12,549
[20:27:54 INFO]: Displaying particle minecraft:flame     ← × 584,546
[20:27:55 INFO]: Summoned new Lightning Bolt             ← ×  3,210
[20:27:55 INFO]: Applied 5.0 damage to zentx             ← ×  1,562
```

一个 `/particle` 循环就能日积 58 万行，把日志撑到 **400+ MB**。管理员游戏内左下角也疯狂刷这些提示。

NoVanillaLog 从 **两个层面** 拦截。

## 双层拦截

```
原版指令输出
   │
   ├─→ 控制台 / log 文件     → Log4j LogFilter         → DENY  （不写盘）
   └─→ 游戏内（管理员视角）   → Netty PacketFilter      → 拦截  （不到达客户端）
```

| 拦截层 | 技术 | 效果 |
|--------|------|------|
| **控制台** | Log4j `AbstractFilter` | 匹配的日志完全不写入 `latest.log` |
| **游戏内** | Netty `ChannelOutboundHandlerAdapter` | 拦截 `ClientboundSystemChatPacket` 数据包，管理员左下角不再刷屏 |

## 安装

```bash
# 编译（需要 Maven + Java 21）
mvn clean package

# 部署
cp target/NoVanillaLog-1.2.8.jar <服务器目录>/plugins/

# 重启服务器
```

**无需安装 ProtocolLib 或任何其他依赖。** 插件使用 Paper 原生的 `ChannelInitializeListenerHolder` API 注入 Netty 通道处理器。

## 配置

首次启动自动生成 `plugins/NoVanillaLog/config.yml`：

```yaml
# ── Console + in-game spam suppression ──────────────────
# Messages matching ANY of these patterns are:
#   • removed from the server console + log file
#   • blocked from appearing in OPs' in-game chat (when suppress-in-game is true)
filtered-patterns:
  # ── English text (matches AdventureComponent packets) ──
  - "已将"              # /effect (中文)
  - "effect"            # /effect (英文)
  - "Applied"           # /effect, /damage (英文)
  - "Summoned"          # /summon (英文)
  - "particle"          # /particle
  # ── Translation keys (matches NMS MutableComponent packets) ──
  - "commands.effect"
  - "commands.summon"
  - "commands.damage"
  - "commands.particle"

# Set to false if you want matching messages to still appear in OPs' chat
suppress-in-game: true
```

### 匹配机制

匹配是**不区分大小写**的**包含关系**：

- `"Applied"` 同时命中 `Applied effect Resistance to Mozz` 和 `Applied 5.0 damage to zentx`
- `"effect"` 命中 `effect`、`Effect`、`EFFECT`

### 翻译键模式

服务器发往客户端的数据包有两种格式：

| 数据包类型 | 内容示例 | 匹配方式 |
|-----------|---------|---------|
| `AdventureComponent` (Paper) | 纯文本 `"Applied 5.0 damage to zentx"` | 匹配英文/中文文本 |
| `MutableComponent` (NMS) | 翻译键 `translation{key='commands.damage.success', ...}` | 匹配翻译键 |

配置中同时包含英文文本和翻译键，确保两种格式都能被拦截。

## 命令

| 命令 | 说明 | 权限 |
|------|------|------|
| `/novanillalog reload` | 热重载 config.yml，显示变更 diff | OP |
| `/nvl` | 别名 | OP |

## 添加自定义规则

在 `config.yml` 加一行关键词 → `/novanillalog reload`，无需重启：

```yaml
filtered-patterns:
  - "还剩下 60 秒清理"           # 某个清理插件的倒计时刷屏
  - "issued server command: /plk"  # 连玩家命令日志一起屏蔽
  - "commands.teleport"           # /tp 翻译键
```

## 工作原理

```
                    ┌─────────────────────────┐
                    │   Minecraft Server      │
                    │                         │
  /effect give ... ─┤  Command executes       │
                    │  ↓                      │
                    │  ClientboundSystemChat  │
                    │  Packet created         │
                    │  ↓                      │
                    │  ┌───────────────────┐  │
                    │  │ Netty Pipeline    │  │
                    │  │                   │  │
                    │  │  ┌─────────────┐  │  │
                    │  │  │ PacketFilter│  │  │    ← 我们的拦截器
                    │  │  │ (outbound)  │  │  │
                    │  │  └──────┬──────┘  │  │
                    │  │         │         │  │
                    │  │  ┌──────▼──────┐  │  │
                    │  │  │  encoder    │  │  │
                    │  │  └──────┬──────┘  │  │
                    │  │         │         │  │
                    │  │  ┌──────▼──────┐  │  │
                    │  │  │ prepender   │  │  │
                    │  │  └──────┬──────┘  │  │
                    │  └─────────┼─────────┘  │
                    └────────────┼────────────┘
                                 ↓
                           ✗ 被拦截 / ✓ 到达客户端
```

通过 Paper 的 `ChannelInitializeListenerHolder` API 注入 Netty 通道处理器：
- 新玩家连接时自动注入
- 插件启动时对已在线玩家补注入
- `/novanillalog reload` 时清除旧处理器并重新注入

## 兼容性

| 项目 | 版本 |
|------|------|
| Paper | 1.21+ |
| Folia | 1.21+ |
| Luminol | 1.21+ |
| Java | 21+ |

- `plugin.yml` 已声明 `folia-supported: true`
- 无外部依赖（不需要 ProtocolLib）
- 通过 Paper 原生 API 注入 Netty 通道，零反射访问 NMS

## License

[WTFPL](http://www.wtfpl.net/) — Do What The Fuck You Want To Public License.
