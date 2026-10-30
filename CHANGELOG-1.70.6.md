# 火控之眼 1.70.6 更新日志

> 基线：1.70.5。本版集中在 **结构导出（选区导出）**、**蓝图持久化**、**地形遮挡性能** 与 **专用服务器隔离** 四条线。

---

## 群聊版（可直接复制）

```
【火控之眼 1.70.6 —— 结构导出 + 服务端修复】

■ 新增：选区导出（/sableexport region）
· 蓝图工具划选区，或 /sableexport select 左键两次，然后 /sableexport region 名字
· 从"活世界"读真实方块与方块实体，Sable 子关卡（船/履带车/炮塔）一起导
· 含实体与机械动力装配体（轴承上的旋转结构、CBC 炮车）
· 导出 OBJ + MTL + 贴图，附带 Blender 脚本（自动分成 World / Sable_* 集合）

■ 修掉四类"导出看起来不对"的真根因
· 机械部件缺失 = Flywheel 把几何送进 GPU，渲染器一个顶点都不写
· 装配体缺失 = 实体渲染路径漏开同一道闸门
· 多结构错位 = float 相消误差（1~3 格 → 现在 4e-6 格）
· 炮管/履带白模 = 第 4 个顶点的 UV 被提前 flush（288/288 面恒为 0,0）

■ 贴图
· 玻璃/染色玻璃：按渲染层写 d/Tr，Blender 默认导入即半透
· 栏杆/玻璃板：补 map_d 遮罩，镂空不再变实心
· 履带条绑定 tracks:block/belt；炮管按 UV 反查图集真实 tile
· 物品方块建模（ModelData 两路合并 + 物品模型回退）

■ 蓝图复制后不再丢设置
· 声呐绑定、呼号、鱼雷游深/激活、提前量、驾束模式、望远镜标记
· 全部改为随结构保存（镜像到方块实体数据 + 按新身份重建索引）

■ 雷达地形遮挡
· 新增 /firecontrol occlusion [on|off]（非 OP 只读）
· 性能：平均耗时降 50~57%（只缓存"可见"结果 + 自适应采样步长）
· 宁多判可见，不误判被挡

■ 专用服务器修复（玩家报的启动崩溃）
· 蓝图导出器不再在公共初始化里创建（服务端不再加载 Screen）
· 殉爆逻辑去掉客户端类依赖
· Yuushya / Vestalihy 兼容入口加依赖与接口守卫（缺失时跳过并记一行）

■ 升级提示
· 需要完整重启客户端
· 拆结构再放会带着旧绑定（潜行扳手点声呐可清除）
· 声呐与电脑必须同一个蓝图，否则仍需重绑
```

---

## CurseForge 版

### 新增：选区导出（`/sableexport region`）

- 选区来源：Sable 蓝图工具（两角点）或 `/sableexport select` 后**左键两次**（方块不会被破坏，`Shift+左键` 清空）
- 数据来自**活世界**：真实 `BlockState` + 真实 `BlockEntity` 实例，不经 NBT 重建
- 支持 **Sable 子关卡**：世界坐标 ↔ plot 存储坐标归一，多个子关卡各自独立变换
- 支持 **实体与机械动力装配体**（contraption），保留真实渲染几何与 UV
- 产出 `OBJ + MTL + textures`，并附 `<名字>_blender.py`（自动生成 `World` / `Sable_*` / `parts` 集合）与 `README.txt`
- 导出页新增「选区导出」按钮；原 `.nbt` 蓝图导出**默认停用**（`blueprintExportEnabled=false`，代码保留）

### 修复：导出缺失与错位（四类根因）

1. **机械部件（齿轮/传动轴/轴承座）缺失**：Create/CBC 渲染器在 Flywheel 开启时整段跳过顶点输出。导出期间临时把 Flywheel 后端置为关闭，`finally` 还原（方块与**实体**两条路径都覆盖）
2. **装配体缺失**：`ContraptionEntityRenderer` 里有同一道 `supportsVisualization` 判断，此前只有方块路径开了闸门
3. **多结构错位**：`T(-origin)·pose·T(base)` 全程 float 合成，两项都在 ±2.05e7（ULP=2.0），相消后残留 1–3 格且逐空间不同。改为 `Matrix4d` 合成后窄化（实测最大残差 4e-6 格）
4. **炮管 / 履带白模**：渲染器链式 `addVertex().setColor().setUv()`，而收集器在第 4 个顶点到来时就 flush，第 4 角 UV 恒为 `(0,0)`。改为延迟一个 quad，并修正复位/解绑顺序

### 修复：材质与透明度

- 玻璃 / 染色玻璃 / 流体罐：`translucent` 一律写 `d` + `Tr`（贴图不透明的取 0.6）
- 栏杆 / 玻璃板：`cutout` 一律补 `map_d` 指向二值遮罩
- 履带条：显式绑定 `tracks:block/belt`（该贴图不在方块模型中，旧实现钉成了轮架）
- 炮管：按 quad 的 UV 质心反查图集真实 tile
- 材质层判定改为单一来源（此前名字与 MTL 体两次独立判定，导致 24 个材质"名字说半透、内容写不透明"）
- 物品方块建模：`ModelData` 改为 `ModelDataManager` + `BlockEntity#getModelData` + 渲染附件三路合并；无渲染器的方块回退到物品模型

### 修复：蓝图复制后设置丢失

此前这些设置存在 `SavedData`，按“子关卡 UUID / 方块坐标”做身份索引，蓝图复制后身份变化即读不到。现统一改为**镜像到方块实体数据 + 按新身份重建索引 + 双写**：

- 声呐绑定（含频点与三个遥控通道）、呼号、鱼雷游深/激活、提前量（炮族+装药）、驾束模式
- 望远镜标记改为镜像到操作员实体数据（键是操作员、值是目标结构，无法挂到方块）
- 兼容旧存档：两向惰性迁移 + 首次读取时补写镜像
- 拆下再放会保留绑定（数据写入掉落物，放置时还原）

### 修复：雷达地形遮挡

- 新增 `/firecontrol occlusion`（查看状态与统计）与 `/firecontrol occlusion on|off`（需权限等级 2，持久化到配置）
- 关闭时遮挡判定直接返回“可见”，不查方块、不建缓存
- 性能：平均单次判定耗时下降 50–57%（只缓存“可见”结果、tick 级 TTL、采样步长随距离自适应、root/plot 双查合一）
- 保守策略：宁可多判可见，不把可见目标误判为被挡

### 修复：专用服务器隔离（玩家实测复现）

- **蓝图导出器不再在公共初始化中创建**：注册移入 `Dist.CLIENT` 的注册类；`FireControlCompat` 常量池不再出现任何 `net/minecraft/client/` 与 `BlueprintExporter` 引用
- **殉爆逻辑去掉客户端类依赖**：`isRemoteVisual` 的纯数据判断移入公共工具类
- **Yuushya 兼容入口**：`register` / `initialize` / `onModifyBakingResult` 首行检查 `yuushya_modelling`，缺失时跳过并记一行
- **Vestalihy 操控入口**：改为反射探测 + 结果缓存，缺失或接口不匹配时跳过并记一行

### 已知边界

- 服务端隔离为**静态证明 + 运行验证步骤**，未在本机实跑专用服务器
- 选区导出以选区为界；体积上限默认 64³（`regionMaxBlocks`）
- 个别四角跨多个图集 tile 的面会保留无贴图材质，并打 `cannon-tex: why=not-in-atlas` 点名方块
- 望远镜标记：目标结构被重新打印后旧 UUID 不存在，标记按规则失效（不可恢复）
- 区块未加载的远端目标不参与导出的实体扫描（与游戏渲染一致）

---

## 验证判据（日志）

```
region: selection source=<sable|clicks> aabb=… volume=…
region: baked blocks=… quads=… entities=… ber=… materials=… skipped=… spaces=…
region-space-check: space=n … delta=(0.00,0.00,0.00) verdict=ok
region-entity: type=create:stationary_contraption … quads>0 flywheel=gated
cannon-tex: … tex=<真实 PNG 路径> uv-space=atlas
material: alpha-d count=<n> / material: cutout-mask name=… map_d=…_mask.png
itemmodel-hit: … / itemmodel-miss: … quads=0
bind-store: save store=<name> be=<pos> keys=[…]
migrate nbt->index store=<name> entries=<n>
occlusion: enabled=<true|false> source=<config|command> hits=… avg-us=…
blueprintexporter: disabled because firecontrolcompat is loaded   （独立导出器）
```

服务端期望：启动到 `Done`，无 `Attempted to load class net/minecraft/client/gui/screens/Screen for invalid dist DEDICATED_SERVER`。
