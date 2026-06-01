# NoVanillaLog

Paper / Folia / Luminol 插件 — 解决原版 Minecraft 指令日志刷屏，同时对高危操作独立存档审计。

## 问题

```
[20:27:54 INFO]: Applied effect Strength to Mozz        ← × 12,549
[20:27:54 INFO]: Displaying particle minecraft:flame     ← × 584,546
[20:27:55 INFO]: Changed the block at (...)              ← × 33,541
[20:27:55 INFO]: Successfully filled 1527 blocks         ← × 15,604
[20:27:56 INFO]: Target is invulnerable to the given...  ← ×  1,562
```

一个 `/particle` 指令循环就能日积 58 万行，把日志从 20 MB 撑到 **400+ MB**。更烦的是管理员游戏里左下角也疯狂刷这些提示。

NoVanillaLog 从 **三个层面** 彻底解决。

## 三层拦截

```
原版指令输出
   │
   ├─→ 控制台 / log 文件  → Log4j Filter  → DENY  （不写盘）
   ├─→ 管理员游戏内聊天    → Netty Handler → DROP  （不发包到客户端）
   └─→ 审计规则匹配        → AuditAppender → audit/audit.log  （单独存档）
```

| 拦截层 | 效果 |
|--------|------|
| **控制台** | 垃圾日志完全不写入 `luminol.log` |
| **游戏内** | 管理员左下角不再刷 "Applied effect" / "Displaying particle" 等 |
| **审计** | `/give` `/money` `/op` `/ban` 等单独存入审计文件，超 10MB 自动滚动 |

## 安装

```bash
# 编译（需要 Maven + Java 21）
mvn -f NoVanillaLog package

# 部署
cp NoVanillaLog/target/NoVanillaLog-1.1.0.jar <服务器目录>/plugins/

# 重启服务器
```

## 配置

首次启动自动生成 `plugins/NoVanillaLog/config.yml`：

```yaml
# ── Console + in-game spam suppression ──────────────────
# 匹配到任一关键词 → 控制台不写 + 管理员游戏内不显示
filtered-patterns:
  - "Displaying particle"         # /particle
  - "Changed the block"           # /setblock
  - "Applied effect"              # /effect
  - "Successfully filled"         # /fill
  - "Target is invulnerable"      # /damage
  - "Summoned "                   # /summon
  - "Given "                      # /give (< 1.21)
  - "Gave "                       # /give (1.21+)
  - "Teleported "                 # /tp

# false = 管理员左下角仍然显示指令反馈
suppress-in-game: true

# ── Audit archive ──────────────────────────────────────
audit:
  enabled: true
  max-file-size-mb: 10

  patterns:
    # /give
    - "issued server command: /give"
    - "issued server command: /minecraft:give"
    # Economy
    - "issued server command: /money give"
    - "issued server command: /playercurrency"
    - "issued server command: /gmp money"
    - "issued server command: /ply give"
    # High-risk administration
    - "issued server command: /op "
    - "issued server command: /deop "
    - "issued server command: /ban "
    - "issued server command: /ban-ip "
    - "issued server command: /kick "
    - "issued server command: /pardon "
    - "issued server command: /whitelist "
    - "issued server command: /gamemode "
```

## 命令

| 命令 | 说明 | 权限 |
|------|------|------|
| `/novanillalog` | 查看拦截/审计状态和文件路径 | OP |
| `/novanillalog reload` | 热重载 config.yml，显示变更 diff | OP |
| `/nvl` | 别名 | OP |

> 整个命令仅 OP 可执行。

## 添加自定义规则

在 `config.yml` 加一行关键词 → `/novanillalog reload`，无需重启：

```yaml
filtered-patterns:
  - "还剩下 60 秒清理"          # 新增：某个清理插件的倒计时刷屏
  - "issued server command: /plk"  # 连玩家命令日志一起屏蔽

audit:
  patterns:
    - "issued server command: /coins give"  # 新增
    - "issued server command: /pay"         # 新增
```

匹配是**包含关系**—— `"money give"` 同时命中 `/money give awa 1000` 和 `/money give Lone_City 500`。

## 兼容性

| 项目 | 版本 |
|------|------|
| Paper | 1.20.x – 1.21.1 |
| Folia | 1.20.x – 1.21.1 |
| Luminol | 1.20.x – 1.21.1 |
| Java | 21+ |
| log4j | 2.19+（服务端自带） |

Folia 兼容：

- 无全局调度器，无 `BukkitRunnable`
- 文件 I/O `synchronized`，region 间线程安全
- `plugin.yml` 已声明 `folia-supported: true`
- 网络层拦截走反射调用 Paper API，无编译期内部类依赖

## 无外部依赖

插件仅使用 Paper API + 服务端自带的 Log4j / Netty。无需安装 ProtocolLib。游戏内拦截通过 `java.lang.reflect.Proxy` 动态代理注册到 Paper 的 `ChannelInitializeListenerHolder`，编译时零内部 API 依赖。

> **注意**：游戏内屏蔽在首次安装或升级 jar 后需要**重启服务器**才能生效（需要重新注册 Netty pipeline handler）。`/novanillalog reload` 只更新匹配关键词，不重注册网络层钩子。

## 为什么 rm 日志文件后不会新建

Linux 下 `rm luminol.log` 只删目录条目，进程仍抓着 inode 继续写，磁盘空间不释放。正确做法：

```bash
> luminol.log   # 清空内容但不删文件
```

或者装 NoVanillaLog 从源头拦截，根本不需要清理。

## License

[WTFPL](http://www.wtfpl.net/) — Do What The Fuck You Want To Public License.
