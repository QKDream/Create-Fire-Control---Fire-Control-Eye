# 火控之眼 1.70.1 → 1.70.5 更新日志

> 从 1.70.1（打包修复版）到 1.70.5，核心是 **蓝图/结构导出** 的一次彻底重做，外加弹药、制导与武器选择的若干修复。
> 依赖：`create_fire_control >= 0.9.4`（无上限）

---

## 群聊版（可直接复制）

```
【火控之眼 1.70.5 —— 导出重做】

■ 新增：直接导出你眼前的结构（不再依赖蓝图文件）
· /sableexport select → 左键点两个角 → /sableexport region 名字
· 或者打开导出页，点「选区导出」按钮
· 从"活世界"读：真实方块状态 + 真实方块实体实例
· Sable 子关卡（船/履带车/炮塔）里的零件也能导，含实体与机械动力装配体
· 产出 OBJ + MTL + 贴图，Blender 直接打开

■ 修掉四类"导出看起来不对"的真根因
· 机械部件缺失 = Flywheel 把几何送进 GPU（BER 一个顶点都不写）
· 装配体缺失 = 实体渲染路径漏开同一道 Flywheel 闸门
· 多结构错位 = float 相消误差（平均 1.08 格，最大 3.35 格 → 现在 4e-6）
· 炮管/履带白模 = 第 4 个顶点的 UV 被提前 flush（288/288 面恒为 0,0）

■ 贴图
· 玻璃/染色玻璃：按渲染层写 d/Tr，默认导入即可见半透
· 栏杆/玻璃板：补 map_d + 二值遮罩，镂空不再变实心
· 履带条：显式绑定 tracks:block/belt
· 炮管：按 UV 反查图集真实 tile，不再落到 atlas 占位材质

■ 弹药与武器
· 陆基计算机：普通/高速/红外/重型/涅瓦 五种防空弹可自由切换（不再"打空才能换"）
· 重型防空在 Sable 结构上打不到目标：修掉 mathTick 重复加速导致转弯半径 340~385 格（应为 150）
· 高速/重型防空补上发射间隔（20 / 12 / 50 tick）

■ 停用
· 本地蓝图文件导出（.nbt）默认关闭，改由选区导出取代
  想恢复：config 里 blueprintExportEnabled=true

■ 升级提示
· 需要完整重启客户端（有资源与类变化）
· 首次用选区导出建议只框一个车体
```

---

## CurseForge 版

## 1.70.5 — 结构导出重做

### 新增：世界选区导出
- `/sableexport select`：进入选区模式，**左键点两个角**（方块不会被敲掉，Shift+左键清空）
- `/sableexport region <名字>`：导出选区内容为 OBJ/MTL/贴图
- 导出页新增「选区导出」按钮（文件名 `region_<时间戳>`）
- 数据来源是**活世界**：真实 `BlockState` + 真实 `BlockEntity` 实例，不经过 NBT 重建
- 支持 **Sable 子关卡**：世界坐标 → plot 存储坐标归一，多个子关卡各自独立变换
- 支持 **实体与机械动力装配体**（contraption），保留其真实渲染几何与 UV

### 修复：导出缺失与错位（四类根因）
1. **机械部件（齿轮/传动轴/轴承座）缺失**：Create/CBC 的渲染器在 Flywheel 开启时整段跳过顶点输出（`VisualizationManager.supportsVisualization`）。导出期间临时关闭 Flywheel 后端，`finally` 还原。
2. **装配体（轴承上的旋转结构、CBC 炮车）缺失**：实体渲染路径没有开同一道闸门（`ContraptionEntityRenderer#render` 的 `ifne` 分支）。
3. **多物理结构错位**：`T(-origin)·pose·T(base)` 全程 float 合成，而两项都在 ±2.05e7，ULP=2.0，相消后残留 1–3 格且逐空间不同。改为 `Matrix4d` 合成后窄化。
4. **炮管 / 履带白模**：渲染器链式 `addVertex().setColor().setUv()`，而收集器在"第 4 个顶点到来时"就 flush，第 4 角的 UV 恒为 `(0,0)`。改为延迟一个 quad flush，并修正 `resetQuad` / `unpinMaterial` / `closePendingQuad` 的顺序。

### 修复：材质与透明度
- 玻璃 / 染色玻璃 / 流体罐：`translucent` 一律写 `d` + `Tr`（贴图不透明的取 0.6），Blender 默认导入即为半透
- 栏杆 / 玻璃板：`cutout` 一律补 `map_d` 指向二值遮罩，镂空不再被填实
- 履带条：显式绑定 `tracks:block/belt`（该贴图不在方块模型里，历史实现钉错成轮架）
- 炮管：按 quad 的 UV 质心反查图集真实 tile，不再退回 `minecraft:atlas/blocks` 占位材质
- 材质层判定改为单一来源（此前名字与 MTL 体两次独立判定，导致 24 个材质"名字说半透、内容写不透明"）

### 修复：弹药与武器选择
- **陆基武器选择**：五种防空弹（普通 / 高速 / 红外锁定 / 重型 / 涅瓦河）可在同一台计算机上自由切换，不再"必须打空一个才能用另一个"；所选弹种无弹时**拒发**而不是偷偷换架
- **重型防空打不到 Sable 结构目标**：`mathTick` 重复施加加速曲线，使速度比转向律预期快 2–4 倍，转弯半径从设计值 150 格恶化到 340–385 格，无法折返 167 格外的目标。已让被火控学 AA 制导接管的弹完全沿用其速度表
- **发射间隔**：高速防空 12 tick、重型防空 50 tick（普通 20 tick）；用备弹数下降识别"包自身发射的一发"
- 安全引信：解除/未解除两侧都记日志；引信实体扫描覆盖 root + 子关卡

### 停用
- 本地蓝图文件导出（`.nbt` → OBJ）默认关闭（`blueprintExportEnabled=false`），由选区导出取代；代码保留，可随时恢复

### 已知边界
- 选区导出以选区为界，跨选区的大型结构只导出选区内部分
- 个别四角跨多个图集 tile 的面会保留无贴图材质，并打 `cannon-tex: why=not-in-atlas` 点名方块 id
- 选区体积上限默认 64³（`regionMaxBlocks`）

---

## 验证判据（日志）

```
region: selection source=<sable|clicks> aabb=(…) volume=…
region: baked blocks=… examined=… quads=… entities=… ber=… materials=… skipped=… spaces=…
region-space-check: space=n … delta=(0.00,0.00,0.00) verdict=ok
region-entity: type=create:stationary_contraption … quads>0 flywheel=gated contraption-blocks=N
cannon-tex: id=… sprite=tile tex=<真实 PNG 路径> uv-space=atlas
material: alpha-d count=<n> / material: cutout-mask name=… map_d=…_mask.png
blueprint-export: disabled reason=config by=command|gui
```
