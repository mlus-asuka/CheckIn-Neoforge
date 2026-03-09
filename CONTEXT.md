# CheckIn Mod — AI 开发上下文文档

> **用途**：在新的 AI 会话中粘贴此文档，以便快速了解项目全貌并继续开发。  
> **最后更新**：2026-03-09

---

## 1. 项目概况

| 属性 | 值 |
|---|---|
| 项目路径 | `c:\Users\ADMIN\IdeaProjects\CheckIn` |
| Mod ID | `checkin` |
| 包名 | `cn.mlus.checkin` |
| Minecraft | 1.21.1 |
| NeoForge | 21.1.219 |
| moddev 插件 | 2.0.140 |
| Java | 21 |
| Gradle | 8.8 |
| 许可证 | MIT |
| 版本 | 1.0-SNAPSHOT |

**功能摘要**：每日签到获取随机积分 + 内嵌 Web 积分商店 + 物品回收商店（可开关、每日限量） + OP 管理后台（侧边栏分页布局） + 兑换记录 + 商品图标 + 每日限购。

---

## 2. 目录结构与文件清单

```
src/main/java/cn/mlus/checkin/
├── Checkin.java              (81行)  Mod 主类，事件注册，Web 服务器生命周期
├── CheckinCommands.java     (287行)  Brigadier 命令注册（含回收商店开关命令）
├── CheckinSavedData.java    (138行)  NBT SavedData 持久化
├── Config.java               (69行)  ModConfigSpec 配置（含 recycleShopEnabled）
├── PlayerLoginHandler.java   (36行)  登录自动签到
├── PointsManager.java       (288行)  积分管理 API（含分页排行榜）
└── web/
    ├── CheckinWebServer.java (854行)  内嵌 HTTP 服务器 + REST API + 物品贴图 + 回收 API
    ├── RecycleShopManager.java(272行)  回收商店管理 + 回收逻辑 + 每日限量
    ├── RedemptionLog.java    (125行)  兑换/回收记录持久化（JSON）+ 每日限量查询
    ├── ShopManager.java      (314行)  商品管理 + 兑换逻辑 + 每日限购
    └── WebAuthManager.java   (126行)  登录码 + Session 管理

src/main/resources/
├── checkin.mixins.json                (空 mixin 配置)
└── assets/checkin/
    ├── lang/
    │   ├── en_us.json                 (英文语言文件)
    │   └── zh_cn.json                 (中文语言文件)
    └── web/
        └── index.html                 (SPA 前端，~1966行)

src/main/templates/META-INF/
└── neoforge.mods.toml                 (模板，Gradle 替换变量)
```

---

## 3. 核心架构

### 3.1 签到系统

- **CheckinSavedData**：继承 `SavedData`，内部 `PlayerCheckinInfo` 记录 `points`、`lastCheckinDate`、`consecutiveDays`、`totalCheckins`
- **PointsManager**：封装 SavedData 操作，提供完整 API：`getPoints`、`setPoints`、`addPoints`、`removePoints`、`hasEnoughPoints`、`transferPoints`、`checkIn`、`hasCheckedInToday`、`getConsecutiveDays`、`getTotalCheckins`、`getLastCheckinDate`、`getTopPlayers`（支持分页 offset/limit）、`getTotalPlayerCount`、`getAllPoints`、`resetPlayer`
- **PlayerLoginHandler**：监听 `PlayerEvent.PlayerLoggedInEvent`，自动签到（受 `Config.autoCheckin` 控制）
- **Config**：字段 — `minPoints`(10)、`maxPoints`(50)、`autoCheckin`(true)、`consecutiveBonus`(1)、`maxConsecutiveBonus`(10)、`webEnabled`(true)、`webPort`(25580)、`recycleShopEnabled`(true)

### 3.2 Web 商店系统

- **WebAuthManager**：
  - `generateCode(UUID, String, boolean isOp)` → 6 位登录码（5 分钟过期）
  - `redeemCode(String)` → `SessionInfo(token, uuid, playerName, isOp)`
  - `validateToken(String)` → UUID
  - `isAdmin(String token)` → boolean
  - Session 1 小时过期
- **ShopManager**：
  - 商品类型：`"item"`（给予物品）和 `"command"`（执行指令，`{player}` 替换为玩家名）
  - 每日限购：`dailyLimit` 字段控制每人每日可购买次数（0 = 不限）
  - CRUD：`addItem()`、`updateItem()`、`deleteItem()`、`reorderItems()`
  - `load()` / `save()` 从 `checkin_shop.json` 读写
  - `buyItem()` 在服务器线程执行，物品类型通过 Registry 查找给予，指令类型通过 `performPrefixedCommand()` 执行
  - 兑换前检查每日限购次数（通过 `RedemptionLog.getPlayerItemCountToday()`）
  - 兑换成功后自动记录到 `RedemptionLog`
- **RecycleShopManager**：
  - 管理可回收物品列表，支持 CRUD
  - `RecycleItem` record：`id`、`itemId`、`displayName`、`description`、`price`（单价积分）、`category`、`dailyLimit`（每日限量，0 = 不限）
  - `sellItem()` 在服务器主线程执行：检查每日限量 → 检查背包物品数量 → 移除物品 → 给予积分
  - 背包操作：`countItemInInventory()` / `removeItemFromInventory()` 遍历 `player.getInventory()`
  - `load()` / `save()` 从 `checkin_recycle_shop.json` 读写
  - 回收记录写入 `RedemptionLog`（type = `"recycle"`，price 为负值表示获得积分）
  - `createDefaultConfig()` 生成 16 个默认回收商品（矿物、战利品分类）
  - 管理员可通过命令或 API 开关回收商店
- **RedemptionLog**：
  - 兑换记录持久化，使用 `CopyOnWriteArrayList` 保证线程安全
  - `load()` / `save()` 从 `checkin_redemptions.json` 读写
  - 支持按玩家查询和全量查询，均支持分页（offset/limit）
  - `getPlayerItemCountToday(UUID, int itemId)` 查询玩家当日某商品的已购次数
  - 记录字段：playerUUID、playerName、itemId、itemDisplayName、itemType、price、timestamp
- **CheckinWebServer**：
  - 使用 `com.sun.net.httpserver.HttpServer`（无外部依赖）
  - 线程池大小 4
  - 静态文件从 classpath `/assets/checkin/web/` 加载
  - SPA 路由回退到 index.html
  - 物品贴图解析：直接路径 → 模型 JSON `textures.layer0` → 递归 `parent` 链（最大深度 5），支持动态贴图自动裁剪首帧

### 3.3 API 端点

| 方法 | 路径 | 说明 | 权限 |
|---|---|---|---|
| POST | `/api/login` | 登录码兑换 token | 公开 |
| GET | `/api/user` | 用户信息（含 `isAdmin`） | 登录 |
| GET | `/api/shop` | 商品列表（含 `type`/`command`/`iconUrl`/`dailyLimit`/`todayPurchased`） | 公开 |
| POST | `/api/shop/buy` | 兑换商品 | 登录 |
| GET | `/api/leaderboard` | 积分排行（支持 `page`/`size` 分页） | 公开 |
| GET | `/api/redemptions` | 兑换记录（`scope=mine\|all`，分页） | 登录（all 需 OP） |
| GET | `/api/item-icon/{itemId}` | 物品贴图（通过 NeoForge ModList 加载，支持模组） | 公开 |
| POST | `/api/logout` | 退出登录 | 登录 |
| POST | `/api/admin/shop/add` | 添加商品 | OP |
| POST | `/api/admin/shop/update` | 更新商品 | OP |
| POST | `/api/admin/shop/delete` | 删除商品 | OP |
| POST | `/api/admin/shop/reload` | 重载配置 | OP |
| GET | `/api/recycle` | 回收商品列表（含 `dailyLimit`/`todayRecycled`） | 公开 |
| POST | `/api/recycle/sell` | 回收物品（扣背包，给积分） | 登录 |
| POST | `/api/admin/recycle/add` | 添加回收商品 | OP |
| POST | `/api/admin/recycle/update` | 更新回收商品 | OP |
| POST | `/api/admin/recycle/delete` | 删除回收商品 | OP |
| POST | `/api/admin/recycle/reload` | 重载回收配置 | OP |
| POST | `/api/admin/recycle/toggle` | 开关回收商店 | OP |

### 3.4 游戏内命令

| 命令 | 说明 | 权限 |
|---|---|---|
| `/checkin` | 手动签到 | 所有人 |
| `/checkin info [player]` | 查看签到信息 | 自己/OP |
| `/checkin top [count]` | 积分排行榜 | 所有人 |
| `/checkin set <player> <amount>` | 设置积分 | OP (level 2) |
| `/checkin add <player> <amount>` | 增加积分 | OP |
| `/checkin remove <player> <amount>` | 扣除积分 | OP |
| `/checkin transfer <player> <amount>` | 转账积分 | 所有人 |
| `/checkin reset <player>` | 重置数据 | OP |
| `/checkin code` | 获取网页登录码 | 所有人 |

### 3.5 前端 (index.html)

- 单文件 SPA，暗色赛博风格主题
- CSS 变量：`--bg-primary: #0f0f1a`、`--accent: #e94560`、`--gold: #f39c12`
- 页面：登录页 → 商店页（分类筛选 + 商品卡片 + 每日限购标识）→ 回收页（分类筛选 + 回收卡片 + 每日限量标识）→ 排行榜（分页）→ 兑换记录（我的/全部切换）→ 管理页(OP)
- 回收商店：物品回收页面，显示可回收物品及单价，输入数量确认回收，回收商店未开启时显示横幅提示
- 商品图标：物品类型商品通过 `/api/item-icon/` 显示 Minecraft 原始贴图（`image-rendering: pixelated`），指令类型商品支持自定义 `iconUrl`
- 每日限购：商品卡片显示限购标识（如 "限购 3/日"），达上限后按钮禁用
- 每日限量回收：回收商品卡片显示限量标识（如 "限回收 2/10"），达上限后按钮禁用
- 登录：游戏内 `/checkin code` 获取登录码（点击复制到剪贴板）
- 管理页功能：**侧边栏标签页布局**（出售商店管理 / 回收商店管理），桌面端侧边栏 sticky 定位，移动端自动转为水平标签栏
  - 出售商店管理：添加商品表单（物品/指令切换，指令类型可设 iconUrl，可设每日限购）、商品表格、编辑弹窗、删除确认、重载配置
  - 回收商店管理：开关状态显示与切换、添加回收商品表单（含每日限量）、回收商品表格（含限量列）、编辑弹窗、删除确认、重载配置
- 使用 `localStorage` 存储 token

---

## 4. 配置文件格式

### checkin_shop.json 示例

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
    "iconUrl": "https://example.com/icon.png",
    "dailyLimit": 3
  }
]
```

### checkin_redemptions.json（自动生成）

兑换记录文件，每次兑换成功自动追加。格式：

```json
[
  {
    "playerUUID": "uuid-string",
    "playerName": "Steve",
    "itemId": 1,
    "itemDisplayName": "钻石",
    "itemType": "item",
    "price": 100,
    "timestamp": 1709280000000
  }
]
```

### checkin_recycle_shop.json（自动生成）

回收商品配置，首次启动自动生成 16 个默认商品。

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
  }
]
```

### checkin-server.toml（自动生成）

```toml
[checkin]
minPoints = 10
maxPoints = 50
autoCheckin = true
consecutiveBonus = 1
maxConsecutiveBonus = 10
webEnabled = true
webPort = 25580
recycleShopEnabled = true
```

---

## 5. 构建与运行

```bash
# 构建
./gradlew build
# 输出 JAR 在 build/libs/

# 开发运行
./gradlew runServer   # 服务端
./gradlew runClient   # 客户端
```

**注意事项**：
- 构建时 `EventBusSubscriber.Bus` 有弃用警告，不影响编译
- PowerShell 下 Gradle 的 stderr 警告可能导致 `$LASTEXITCODE=1`，实际构建成功看输出 `BUILD SUCCESSFUL`
- 首次运行会在配置目录生成 `checkin_shop.json`，格式变更后需删除旧文件重新生成

---

## 6. 已知技术细节

- `Gson` 和 `LogUtils` 来自 Minecraft 内置依赖，不需要额外声明
- `com.sun.net.httpserver` 是 JDK 标准库，无需依赖
- `BuiltInRegistries.ITEM` 用于通过 ResourceLocation 查找物品
- 兑换操作通过 `mcServer.execute()` 在服务器主线程执行，使用 `CompletableFuture.get(5, TimeUnit.SECONDS)` 异步等待结果，不阻塞 HTTP 线程池
- 指令执行使用 `server.createCommandSourceStack()` 以服务器身份运行
- 物品贴图通过 `ModList.get().getModFileById(namespace)` + `IModFile.findResource()` 从 mod JAR 中直接读取，支持所有已加载 mod（包括原版 minecraft）
- 物品贴图解析采用三级回退：直接路径（`textures/item/`、`textures/block/`）→ 解析物品模型 JSON 的 `textures.layer0` → 递归跟随 `parent` 链（如 `enchanted_golden_apple` → `golden_apple`），最大深度 5
- 动态贴图（animated texture）PNG 高度 > 宽度时，自动裁剪返回第一帧
- 服务端 `ResourceManager` 只包含 data pack 资源，不含贴图等 resource pack 资源，因此不能用于加载物品贴图
- 登录码消息使用纯 Component API 构建（`Component.literal` + `Style` + `ClickEvent.COPY_TO_CLIPBOARD`），避免 `§` 格式码干扰子组件样式

---

## 7. 可能的后续开发方向

- [x] 商品图标支持（物品类型用 Minecraft 贴图，指令类型用 iconUrl）
- [x] 积分排行榜分页
- [x] 兑换记录页面（含分页、我的/全部切换）
- [x] 登录码点击复制
- [x] 商品限购（每人每日限购）
- [x] 物品回收商店（管理员可开关，玩家出售物品获得积分）
- [x] 回收每日限量（每人每日回收次数上限）
- [x] 管理页面侧边栏布局（出售商店 / 回收商店标签页切换）
- [ ] 商品全局限购（全服总量）
- [ ] 商品上下架状态
- [ ] WebSocket 实时通知
- [ ] HTTPS 支持
- [ ] Session 持久化（当前仅内存）
- [ ] 更多指令占位符（如 `{uuid}`、`{x}` `{y}` `{z}`）
