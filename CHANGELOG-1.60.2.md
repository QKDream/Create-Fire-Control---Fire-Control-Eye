# 火控之眼 1.60.2 更新日志（火控学 0.9.1 兼容）

> 构建：`firecontrolcompat-1.60.2.jar`　适配：Create Fire Control **0.9.1**（同时兼容 0.8.5）

## 修复：火控学 0.9.1 下自动防御制导注入失败

**现象**（0.9.1 实例日志）：
```
[modloading-worker-0/FATAL] [mixin/]: Mixin apply for mod firecontrolcompat failed
  firecontrolcompat.mixins.json:AutoDefenseGuidanceMixin
InvalidInjectionException: Invalid descriptor
  ->@Inject::firecontrolcompat$resolveTarget(Entity, ConnectedDisplayBlockEntity$Contact, CallbackInfoReturnable)
```

**原因**：0.9.1 重写了自动防御的制导：
- 0.8.5：`resolveTarget(Entity, ConnectedDisplayBlockEntity$Contact)` → `Vec3`
- 0.9.1：`resolveTarget(Entity, AutoDefenseMissileGuidance$Guided)` → `ResolvedTarget`（record）

且 `Guided` 是**包私有**类，跨包的 mixin **无法书写该参数类型**，因此这条注入在新版里无法存在。

**修复**：删除 `resolveTarget` 注入。它原本只做"Shaolib 威胁返回其坐标"的兜底，而这条链的关键（`findEntity` 重定向提供代理实体 + `isThreatAlive` 判定）在 0.9.1 中**签名未变**，仍然生效 ⇒ 面包学自动防御对 TAOV/Shaolib 目标依旧能拦截，功能不丢失。

同时把清理代理状态的时机改到 `canLaunch`（0.9.1 仍存在，签名未变；`require = 0` 保证 0.8.5 也能加载）。

## 兼容性

| 项 | 说明 |
|---|---|
| 火控学 0.9.1 | ✅ 不再报注入失败 |
| 火控学 0.8.5 | ✅ 仍可用（注入为 `require = 0`） |
| 其余依赖 | 未改动 |

## 说明

- 本次崩溃包的**直接原因不是本模组**：`java.lang.RuntimeException: Sodium mod config not found`（Sodium 0.8.12 + Sinytra Connector 2.0.0-beta.14 冲突，`Description: Rendering overlay`）。请删除 Connector 或改用与 Connector 匹配的 Sodium/iris 版本。
