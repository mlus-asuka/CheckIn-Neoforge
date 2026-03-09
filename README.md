# ⛏️ CheckIn — Minecraft 签到积分商店 Mod

一个 **NeoForge 1.21.1** 服务端 Mod，为 Minecraft 服务器提供每日签到积分系统、内嵌 Web 积分商店和物品回收商店。

![Minecraft](https://img.shields.io/badge/Minecraft-1.21.1-green)
![NeoForge](https://img.shields.io/badge/NeoForge-21.1.219-orange)
![Java](https://img.shields.io/badge/Java-21-blue)
![License](https://img.shields.io/badge/License-MIT-yellow)

## ✨ 功能特性

### 📅 每日签到
- 玩家每日首次登录自动签到（可配置手动签到）
- 随机获得 10~50 积分（可配置）
- 连续签到额外奖励，每天 +1 分，最高 +10
- 完整的积分管理 API：查询、设置、增减、转账、重置

### 🛒 Web 积分商店
- 内嵌 HTTP 服务器，随 MC 服务器启动，无需额外部署
- 美观的暗色赛博风格 SPA 前端
- 支持分类筛选、商品卡片展示、积分排行榜（分页）
- 游戏内 `/checkin code` 获取 6 位登录码（点击复制），网页端支持粘贴按钮

### 🖼️ 商品图标
- 物品类型商品自动显示 Minecraft 原始贴图（像素风格渲染）
- 智能贴图解析：直接路径 → 模型 JSON 解析 → parent 链递归，自动处理动态贴图
- 支持所有已加载 mod 的物品贴图（通过 NeoForge ModList 加载）
- 指令类型商品支持自定义图标 URL

### 📋 兑换记录
- 每次兑换自动记录，支持查看个人兑换历史
- OP 可查看全服兑换记录
- 支持分页浏览

### 🔒 每日限购
- 每个商品可设置每人每日购买次数上限（`dailyLimit`，0 = 不限）
- 商品卡片实时显示剩余可购次数，达上限后自动禁用购买按钮
- OP 管理后台支持设置和编辑限购数量

### ♻️ 物品回收商店
- 玩家可以将背包中的物品出售给系统，获得对应积分
- 管理员可通过命令或管理后台开关回收商店
- 每个回收商品可设置每日回收数量上限（`dailyLimit`，0 = 不限）
- 回收页面实时显示今日已回收/限量，达上限后自动禁用
- 回收记录写入兑换日志，统一审计

### ⚡ 双类型商品
- **物品类型**：兑换后直接给予 Minecraft 物品到背包
- **指令类型**：兑换后服务器执行预设指令，支持 `{player}` 占位符自动替换为玩家名

### ⚙️ OP 管理后台
- OP 玩家登录网页后可见管理标签页
- **侧边栏分页布局**：出售商店管理 / 回收商店管理 标签切换
- 出售商店：商品增删改查、每日限购设置、重新加载配置
- 回收商店：开关状态切换、回收商品增删改查、每日限量设置、重新加载配置
- 移动端自动转为水平标签栏布局

## 📦 安装

1. 确保服务端已安装 **NeoForge 21.1.x**（Minecraft 1.21.1）
2. 下载最新 Release 的 `.jar` 文件
3. 放入服务器 `mods/` 目录
4. 启动服务器，Mod 会自动生成配置文件

## ⚙️ 配置

首次启动后在服务器配置目录生成以下文件：

### 签到配置（`checkin-server.toml`）

| 配置项 | 默认值 | 说明 |
|---|---|---|
| `minPoints` | 10 | 签到最低积分 |
| `maxPoints` | 50 | 签到最高积分 |
| `autoCheckin` | true | 登录时自动签到 |
| `consecutiveBonus` | 1 | 连续签到每天额外积分 |
| `maxConsecutiveBonus` | 10 | 连续签到奖励上限 |
| `webEnabled` | true | 是否启用 Web 服务器 |
| `webPort` | 25580 | Web 服务器端口 |
| `recycleShopEnabled` | true | 是否启用回收商店 |

### 商品配置（`checkin_shop.json`）

启动后自动生成包含 14 个示例商品（含指令商品）的默认配置。也可以通过 Web 管理后台编辑。

<details>
<summary>商品配置示例</summary>

```json
[
  {
    "id": 1,
    "type": "item",
    "itemId": "minecraft:diamond",
    "command": "",
    "displayName": "钻石",
    "description": "闪闪发光的钻石",
    "price": 100,
    "count": 1,
    "category": "矿物",
    "iconUrl": "",
    "dailyLimit": 0
  },
  {
    "id": 13,
    "type": "command",
    "itemId": "",
    "command": "effect give {player} minecraft:regeneration 60 2",
    "displayName": "生命恢复 III",
    "description": "获得 60 秒的生命恢复 III 效果",
    "price": 50,
    "count": 1,
    "category": "指令",
    "iconUrl": "",
    "dailyLimit": 3
  }
]
```
</details>

### 回收商品配置（`checkin_recycle_shop.json`）

启动后自动生成包含 16 个默认回收商品（矿物、战利品分类）。也可通过 Web 管理后台编辑。

<details>
<summary>回收商品配置示例</summary>

```json
[
  {
    "id": 1,
    "itemId": "minecraft:cobblestone",
    "displayName": "圆石",
    "description": "最基础的建筑材料",
    "price": 1,
    "category": "矿物",
    "dailyLimit": 0
  },
  {
    "id": 5,
    "itemId": "minecraft:diamond",
    "displayName": "钻石",
    "description": "闪闪发光的钻石",
    "price": 25,
    "category": "矿物",
    "dailyLimit": 64
  }
]
```
</details>

## 📝 游戏内命令

| 命令 | 说明 | 权限 |
|---|---|---|
| `/checkin` | 手动签到 | 所有人 |
| `/checkin code` | 获取网页登录码（5 分钟有效，点击复制） | 所有人 |
| `/checkin info [玩家]` | 查看签到信息 | 自己/OP |
| `/checkin top [数量]` | 积分排行榜 | 所有人 |
| `/checkin set <玩家> <数量>` | 设置积分 | OP |
| `/checkin add <玩家> <数量>` | 增加积分 | OP |
| `/checkin remove <玩家> <数量>` | 扣除积分 | OP |
| `/checkin transfer <玩家> <数量>` | 转账积分 | 所有人 |
| `/checkin reset <玩家>` | 重置玩家数据 | OP |
| `/checkin recycle enable` | 开启回收商店 | OP |
| `/checkin recycle disable` | 关闭回收商店 | OP |

## 🌐 Web 商店使用

1. 确保服务器配置中 `webEnabled = true`
2. 在游戏内输入 `/checkin code` 获取 6 位登录码（点击复制）
3. 浏览器访问 `http://服务器IP:25580`
4. 输入登录码登录
5. 浏览商品并用积分兑换

**OP 管理**：OP 玩家登录后会在导航栏看到 **⚙️ 管理** 标签页，可以直接在网页上管理出售商店和回收商店（侧边栏切换）。

## 🔧 开发构建

```bash
# 克隆项目
git clone <repo-url>
cd CheckIn

# 构建
./gradlew build

# 开发运行
./gradlew runServer   # 服务端
./gradlew runClient   # 客户端
```

**环境要求**：JDK 21+、Gradle 8.8+

## 📐 技术架构

```
cn.mlus.checkin
├── Checkin.java              # @Mod 主类，事件注册
├── Config.java               # ModConfigSpec 配置
├── CheckinCommands.java      # Brigadier 命令
├── CheckinSavedData.java     # NBT 持久化存储
├── PlayerLoginHandler.java   # 登录事件处理
├── PointsManager.java        # 积分管理核心 API
└── web/
    ├── CheckinWebServer.java  # 内嵌 HTTP 服务器（含回收 API）
    ├── RecycleShopManager.java# 回收商店管理 & 回收逻辑
    ├── RedemptionLog.java     # 兑换/回收记录持久化
    ├── ShopManager.java      # 商品管理 & 兑换逻辑
    └── WebAuthManager.java   # 登录码 & Session 鉴权
```

- **无外部依赖**：HTTP 服务器使用 JDK 内置 `com.sun.net.httpserver`，JSON 使用 Minecraft 自带 Gson
- **线程安全**：兑换操作通过 `mcServer.execute()` 回到服务器主线程执行
- **前端**：单文件 SPA（`index.html`），从 classpath 加载，无需额外 Web 框架

## 📄 License

[MIT](LICENSE)
