# Locate Plus（Forge 1.20.1 移植版）完整命令表

jar：`locateplus-forge-2.2.0+1.20.1-forge.jar`
权限：配置里 `permission_level` 默认 **2**，单人存档开了作弊 / 服务器 OP 即可用。
所有命令**纯服务端**，客户端不用装。

## 一、定位

| 命令 | 作用 | 例子 |
| --- | --- | --- |
| `/locate block <方块id\|#标签> [<数量> chunks\|blocks] [forceload]` | 找**世界里已放置的方块**，最近的一个，并高亮 | `/locate block minecraft:diamond_ore 128 blocks` |
| `/locate entity <实体id\|#标签> [<数量> chunks\|blocks] [forceload]` | 找最近的生物/实体，并让它发光 | `/locate entity minecraft:zombie 64 blocks` |
| `/locate item <物品id\|#标签> [<数量> chunks\|blocks] [export] [forceload]` | 找**物品形态**的东西：容器内、地上掉落、生物装备/展示框、玩家背包与末影箱 | `/locate item minecraft:diamond 128 blocks` |
| `/locate biome <生物群系id>` | 原版寻群系，带安全传送按钮 | `/locate biome minecraft:jungle` |
| `/locate structure <结构id>` | 原版寻结构（会生成地形） | `/locate structure minecraft:village_plains` |
| `/locate poi <兴趣点id>` | 原版寻兴趣点（床/钟/传送门等） | `/locate poi minecraft:armorer` |

要点：
- **不带半径 = 只扫当前已加载的区块**（你那次 841 个），远处的东西找不到。
- 半径必须**数字 + 单位**，两种写法：`64 blocks` 或 `4 chunks`。
- 末尾可加 `forceload`：把范围内未加载的区块临时强加载进来（大范围会慢）。
- `locate item` 可加 `export`：把全部结果写成 txt，落在 `config/locate-plus/exports/`。
- 上限（config 可改）：`max_block_radius` 16000、`max_chunk_radius` 1000。

## 二、勘察

| 命令 | 作用 |
| --- | --- |
| `/lp analyze blocks <n> chunks\|blocks [export]` | 统计范围内方块种类与数量 |
| `/lp analyze entities <n> chunks\|blocks [export]` | 统计范围内实体种类与数量 |
| `/lp analyze both <n> chunks\|blocks [export]` | 两者都统计 |
| `/lp inspect <x> <y> <z> [forceload]` | 看某个坐标的一切信息（红石、光照、刷怪、作物、容器、实体） |

例子：`/lp analyze both 4 chunks export`、`/lp inspect ~ ~-1 ~`

## 三、动作

| 命令 | 作用 | 例子 |
| --- | --- | --- |
| `/safetp <x> <y> <z>` | 传送到该位置**附近能站立的安全点** | `/safetp ~ ~ ~10` |
| `/safetp <目标> <x> <y> <z>` | 把别人/别的实体安全传送过去 | `/safetp @s 96 140 -32` |
| `/glow <实体id\|#标签\|@选择器> [<n> blocks\|chunks]` | 让实体透视发光 | `/glow @e[type=minecraft:zombie] 64 blocks` |
| `/lp visualize <n> chunks\|blocks [秒数]` | 用粒子勾出扫描会覆盖的范围 | `/lp visualize 32 chunks 300` |

## 四、管理

| 命令 | 作用 |
| --- | --- |
| `/lp` 或 `/lp help` | 游戏内命令索引（点一行自动填入示例） |
| `/lp config` | 打印当前所有生效设置 |
| `/lp reload` | 重读 `config/locate-plus/config.json`（加/删命令仍需重启） |
| `/lp stop` | 取消正在跑的扫描 |
| `/lp clear` | 清掉所有发光与方块标记 |

## 五、针对你那个存档的实测命令（已按存档内容核对）

```
/locate block minecraft:bone_block 400 blocks
```
应报 ≈ `3808 3184`（存档里仅 2 个区块有骨块，约 310 格外，需要 forceload 也能加：
`/locate block minecraft:bone_block 400 blocks forceload`）。

```
/locate item minecraft:bone 250 blocks
```
应命中 3 处箱子/石棺：`3653 92 3546`、`3534 83 3551`、`3536 83 3551`。

```
/locate item minecraft:amethyst_shard 250 blocks
```
应命中 `sarcophagus 3539 83 3553`。

```
/locate item minecraft:dirt 400 blocks
```
应命中 `3375 5 3219`、`3374 5 3218` 一带的木桶。

```
/locate item minecraft:bread
```
不带半径 → 扫已加载区块 + 你自己背包，应马上报 `in <你的ID>`。

```
/lp analyze both 4 chunks export
```
跑一次就能确认：扫描、统计、导出三条链路都正常（结果文件在
`config/locate-plus/exports/`）。

## 六、config.json 里跟"找不到"最相关的开关

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `search_containers` | true | 是否翻容器 |
| `search_dropped_items` | true | 是否找地上掉落物 |
| `search_entity_inventories` | true | 是否找生物装备/展示框 |
| `search_player_inventories` | true | 是否翻玩家背包与末影箱 |
| `search_inside_shulker_boxes` | true | 是否拆潜影盒 |
| `scan_min_y` / `scan_max_y` | -64 / 320 | 垂直搜索范围 |
| `permission_level` | 2 | 0=所有人，2=OP，4=服主 |

这几个任何一个被改成 false，`/locate item` 就会"看不到"对应位置的东西，而且空结果时会在聊天里提示
`Not searched, switched off in the config: ...`。

改完文件执行 `/lp reload` 生效。
