# Changelog / 更新日志

## v2.8 — 2026-09-28

**English**
- ⚖️ Balance: penetration raised for APFSDS (6 → 9), APHE (5 → 7), SAP (4 → 6) and Heavy Explosive Shell (4 → 6), with toughness and durability mass scaled to match, so the buff applies both with vanilla CBC ballistics and with CBC Terminal Ballistics
- 📝 In-game penetration tooltips updated to the new values
- 📝 Version bumped to 2.8

**中文**
- ⚖️ 平衡性调整：尾翼稳定脱壳穿甲弹（APFSDS）穿深 6 → 9、APHE 穿深 5 → 7、SAP 穿深 4 → 6、重爆弹穿深 4 → 6，韧性与耐久质量同步上调，无论是否安装《终端弹道学》都能生效
- 📝 游戏内穿深悬浮提示同步更新为新数值
- 📝 版本号提升至 2.8

**Русский**
- ⚖️ Баланс: бронепробитие повышено — APFSDS (6 → 9), APHE (5 → 7), SAP (4 → 6) и тяжёлый фугасный снаряд (4 → 6); прочность и масса снаряда масштабированы соответственно, поэтому усиление работает и с обычной баллистикой CBC, и с CBC Terminal Ballistics
- 📝 Подсказки бронепробития в игре обновлены под новые значения
- 📝 Версия повышена до 2.8

## v2.7fix2 — 2026-09-08

**English**
- 🔧 Universal Proximity Fuze now recognizes every Fire Control Eye missile (beam-riding, infrared, aircraft infrared, loitering munition and heavy air-defense) as a valid proximity target. Their `firecontrolcompat:*_tanshe` entity keys previously fell into the Mianbao branch and were filtered out, so the fuze never triggered against them

**中文**
- 🔧 通用近炸引信新增对火控之眼全部导弹（驾束导弹、红外导弹、机载红外导弹、巡飞弹、重型防空导弹）的近炸感应。此前它们的 `firecontrolcompat:*_tanshe` 实体键名会落入面包学分支并被筛除，导致引信对其完全不生效

**Русский**
- 🔧 Универсальный неконтактный взрыватель теперь распознаёт все ракеты Fire Control Eye (лучевые, инфракрасные, авиационные ИК, барражирующие боеприпасы и тяжёлые ЗУР) как цели. Их ключи `firecontrolcompat:*_tanshe` раньше попадали в ветку Mianbao и отбрасывались, поэтому взрыватель на них не срабатывал

## v2.7fix1 — 2026-09-03

**English**
- 🔧 Fixed a dedicated-server crash: the proximity fuze tooltip referenced the client-only `Minecraft` class from `appendHoverText`, which runs on both sides; the sneak check is now gated behind `FMLEnvironment.dist.isClient()` and delegates to a client-only helper, so servers never load client classes

**中文**
- 🔧 修复专用服务器崩溃：近炸引信的物品悬浮提示在两端都会执行，却直接引用了客户端专属的 `Minecraft` 类，导致服务器抛出 `NoClassDefFoundError`；潜行判定现改为 `FMLEnvironment.dist.isClient()` 环境守卫并委托给客户端专用工具类，服务器不再加载任何客户端类

**Русский**
- 🔧 Исправлен краш выделенного сервера: подсказка неконтактного взрывателя обращалась к клиентскому классу `Minecraft` из `appendHoverText`, который выполняется на обеих сторонах; проверка приседания теперь ограничена `FMLEnvironment.dist.isClient()` и делегирована клиентскому помощнику — сервер больше не загружает клиентские классы

## v2.7 — 2026-08-26

**English**
- 🆕 Universal Proximity Fuze now detonates against CBC Military Supplement munitions: the full torpedo family (short/medium/long-range, deepwater, highspeed, reductive, shrapnel and more), HE/APHE rockets (single, dual and loitering), depth charges, and HE/APHE bombs including bouncing bombs — anywhere along their flight path
- 🆕 Universal Proximity Fuze now detects TAOV Weapons' TAU and Hellfire missiles. These are not Minecraft entities but Shaolib non-entity projectiles, so the fuze integrates through a reflective soft dependency: TAOV not installed = zero impact on this mod; installed = in-flight TAU/Hellfire trigger detonation, and missiles embedded in SABLE ship structures are resolved to world coordinates. Detection uses swept segment-to-segment distance, so even high-speed crossing shots fuse reliably
- 🔧 Fixed: entity matching used `EntityType.toShortString()`, which on 1.21.1 strips the namespace — CBCMS torpedoes/depth charges were silently never recognized. Matching now reads canonical registry keys (`BuiltInRegistries.ENTITY_TYPE`), and the Vestalihy / Mianbao branches were rewritten on the same canonical-key basis to prevent the same class of bug
- 🔧 Shaolib reflective class loading now falls back across the thread context / own mod / system class loaders, reducing silent detection failures in different modpack environments

**中文**
- 🆕 通用近炸引信新增对《机械动力火炮：武器拓展》(CBC Military Supplement) 弹药的近炸感应，覆盖其全部弹药谱系：全系列鱼雷（短程/中程/长程/深水/高速/减速/梭鱼/榴霰等 30 余种）、HE/APHE 火箭弹（单发、双联与巡飞弹）、深水炸弹，以及 HE/APHE 航空炸弹与跳弹炸弹。目标飞行路径的任何一段进入引信感应半径即起爆
- 🆕 通用近炸引信新增对 TAOV Weapons 的 TAU 与地狱火导弹的近炸感应。这两种导弹并非 Minecraft 实体，而是 Shaolib 体系的非实体弹，因此采用反射软依赖接入：未安装 TAOV 时对本 mod 零影响；安装后飞行中的 TAU/地狱火会触发近炸，嵌入 SABLE 舰体结构的导弹会换算为世界坐标后参与判定。判定使用弹道扫掠（线段-线段最近距离），高速交错也能可靠起爆
- 🔧 修复 1.21.1 下实体类型匹配失效：`EntityType.toShortString()` 在该版本会剥掉命名空间，导致 CBCMS 鱼雷、深水炸弹等目标此前始终无法被识别。现改为读取注册表规范键名（`BuiltInRegistries.ENTITY_TYPE`）进行命名空间+名称双重匹配，vestalihy、面包学等既有兼容分支也一并按规范键名重写，杜绝同类问题
- 🔧 Shaolib 反射类加载增加多级回退（线程上下文 → 本模组 → 系统类加载器），降低不同整合包环境下的静默失效概率
- 📝 游戏内版本号提升至 2.7；简介页面同步更新引信兼容目标与可选前置说明

**Русский**
- 🆕 Универсальный неконтактный взрыватель теперь срабатывает по боеприпасам CBC Military Supplement: всему семейству торпед (ближние/средние/дальние, глубоководные, скоростные, редуктивные, шрапнельные и др.), ракетам HE/APHE (одиночным, спаренным и барражирующим), глубинным бомбам, а также авиабомбам HE/APHE и прыгающим бомбам — на любом участке траектории
- 🆕 Универсальный неконтактный взрыватель теперь обнаруживает ракеты TAU и Hellfire из TAOV Weapons. Это не сущности Minecraft, а не-сущностные снаряды Shaolib, поэтому интеграция выполнена через рефлексивную мягкую зависимость: без TAOV мод работает без изменений; с TAOV летящие TAU/Hellfire вызывают подрыв, а ракеты, встроенные в SABLE-конструкции, пересчитываются в мировые координаты. Используется заметание траектории (расстояние отрезок-отрезок), надёжно срабатывает даже на высоких скоростях
- 🔧 Исправлено сопоставление типов сущностей: в 1.21.1 `EntityType.toShortString()` отбрасывает пространство имён, из-за чего торпеды и глубинные бомбы CBCMS никогда не распознавались. Теперь используются канонические ключи реестра (`BuiltInRegistries.ENTITY_TYPE`); ветки Vestalihy и Mianbao переписаны на ту же основу
- 🔧 Рефлексивная загрузка классов Shaolib получила многоуровневый фолбэк загрузчиков (контекст потока → собственный → системный), снижая риск тихих отказов в разных сборках
- 📝 Внутриигровая версия повышена до 2.7; страница описания обновлена

## v2.6hotfix1 — 2026-08-23

**English**
- ⚖️ Balance fix: with CBC Terminal Ballistics installed the previous AP penetration nerfs had no effect — CTB's kinetic model is driven by projectile mass (CBC `durability_mass`), and our AP rounds carried 10–50× reference mass, swamping the JSON penetration values
- ⚖️ Rebalanced `durability_mass` to match the nerfed penetration design: APFSDS 100 → 5.0, SAP 30 → 3.5, APHE 25 → 3.5, Heavy Explosive 35 → 3.0 (reference: vanilla CBC AP = 2.0, CBCMW APDS = 3.5); under CTB the steel dart now lands at ~1.4× the CBCMW APDS energy
- 📝 Vanilla (no CTB) pass-through behavior is unchanged (still driven by penetration/toughness); only the previously absurd per-hit block damage scales down

**中文**
- ⚖️ 平衡修复：装有终端弹道学时，此前穿深削弱不生效——CTB 的动能模型以弹体质量（CBC 的 durability_mass）为能量来源，我方穿甲弹质量高达参考弹的 10~50 倍，淹没了 JSON 穿深削弱
- ⚖️ 将 durability_mass 调整到与削弱后穿深匹配：APFSDS 100→5.0、SAP 30→3.5、APHE 25→3.5、重爆弹 35→3.0（参考：原版 CBC AP=2.0，CBCMW APDS=3.5）；弹道学下钢针动能约为 CBCMW APDS 的 1.4 倍
- 📝 无弹道学时原版穿透判定不变（仍由穿深/韧性决定），仅修正了此前质量过大导致的超量方块破坏

**Русский**
- ⚖️ Баланс: при установленном CBC Terminal Ballistics прежние ослабления пробития не работали — кинетическая модель CTB использует массу снаряда (durability_mass из CBC) как источник энергии, а наши бронебойные снаряды имели массу в 10–50 раз больше эталона, полностью перекрывая ослабление из JSON
- ⚖️ durability_mass приведён в соответствие ослабленному пробитию: APFSDS 100 → 5.0, SAP 30 → 3.5, APHE 25 → 3.5, тяжёлый фугас 35 → 3.0 (эталоны: CBC AP = 2.0, CBCMW APDS = 3.5); под CTB кинетическая энергия стрелы ≈ 1.4× APDS из CBCMW
- 📝 Без CTB логика пробития ванили не изменилась (по-прежнему определяется пробитием/прочностью); скорректирован только избыточный урон блокам от ранее завышенной массы
## v2.6 — 2026-08-23

**English**
- 🆕 Self-destruct: cbcaddon projectiles that stay airborne for 20 seconds are silently discarded (no explosion, no debris)
- 🆕 Out-of-chunk cleanup: airborne projectiles are removed as soon as they leave loaded chunks, and airborne projectiles are discarded when their chunk unloads — frozen projectiles no longer linger or reappear
- 🔧 Fixed: the chunk-unload handler previously lived in the mixin package, which crashed mod construction (IllegalClassLoadError → chain-crash of the whole mod state); moved to a plain package

**中文**
- 🆕 自毁机制：本 mod 弹体飞行满 20 秒后静默消失（直接删除，不爆炸、无残骸）
- 🆕 区块外清理：飞行中飞出已加载区块立即删除；区块卸载时清除其中仍在飞行的弹体（不再冻结残留、不再随区块加载复现）
- 🔧 修复：区块卸载监听器此前位于 mixin 专属包，导致 mod 构造崩溃（IllegalClassLoadError → 全模组状态连锁崩溃）；已移至普通包

**Русский**
- 🆕 Самоликвидация: снаряды cbcaddon, находящиеся в полёте 20 секунд, бесшумно удаляются (без взрыва и обломков)
- 🆕 Очистка вне чанков: летящие снаряды удаляются сразу после выхода из загруженных чанков; при выгрузке чанка летящие снаряды в нём удаляются (не зависают и не появляются вновь)
- 🔧 Исправлено: обработчик выгрузки чанка находился в mixin-пакете, что ломало конструкцию мода (IllegalClassLoadError → каскадный сбой состояния всех модов); перенесён в обычный пакет
## v2.5hotfix5 — 2026-08-21

**English**
- 🔧 Fixed: Universal Proximity Fuze failed to detonate on mianbao missiles in multiplayer — added an explicit mianbaos_modernwarfare entity whitelist (missile / rocket / AGM / JDAM projectiles)
- 🎯 Proximity check upgraded from point-vs-segment to segment-vs-segment, rebuilding each missile's tick path from its own velocity — fast missiles no longer slip between ticks (tunneling)

**中文**
- 🔧 修复：多人下通用近炸引信无法被面包学导弹引爆 — 新增 mianbaos_modernwarfare 实体白名单（missile / rocket / agm_ / jdam 弹射物）
- 🎯 近炸判定由"点对线段"升级为"线段对线段"（按导弹自身速度重建本 tick 飞行路径），修复高速导弹两帧之间隧穿漏判

**Русский**
- 🔧 Исправлено: универсальный неконтактный взрыватель не срабатывал по ракетам mianbao в мультиплеере — добавлен явный белый список сущностей mianbaos_modernwarfare (missile / rocket / agm_ / jdam)
- 🎯 Проверка близости улучшена с «точка-отрезок» до «отрезок-отрезок» (путь ракеты за тик восстанавливается по её скорости) — быстрые ракеты больше не проскакивают между тиками
## v2.5hotfix4 — 2026-08-19

**English**
- ⚖️ Balance: nerfed all kinetic AP warheads in sync — APHE 10/5 → 5/2.5, SAP 8/4 → 4/2, Heavy Explosive 7/3 → 4/2 (still above the CBCMW 3/1 baseline)
- ⚖️ Balance: APFSDS steel dart penetration 120/60 → 6/3 with a small final callback (slightly stronger than CBCMW APDS 3/1)

**中文**
- ⚖️ 平衡：同步削弱动能穿甲弹头 — APHE 10/5 → 5/2.5，SAP 8/4 → 4/2，重爆弹 7/3 → 4/2（仍强于 CBCMW 3/1 基线）
- ⚖️ 平衡：钢针(APFSDS)穿深 120/60 下调至 6/3，末尾小幅回调，略强于 CBCMW APDS（3/1）

**Русский**
- ⚖️ Баланс: синхронно ослаблены кинетические бронебойные боеприпасы — APHE 10/5 → 5/2.5, SAP 8/4 → 4/2, тяжёлый фугас 7/3 → 4/2 (всё ещё выше базовой линии CBCMW 3/1)
- ⚖️ Баланс: APFSDS-стрела — пробитие 120/60 снижено до 6/3 с небольшим финальным откатом (немного сильнее APDS из CBCMW 3/1)

## v2.5hotfix2 — 2026-08-18

**English**
- 🔧 Fixed: projectiles visibly spinning/rotating in flight again — projectile orientation now resyncs to the actual velocity every tick (restored the orientation sync removed during the terminal-ballistics refactor)

**中文**
- 🔧 修复：炮弹再次出现旋转着飞出的视觉问题 — 弹体朝向每 tick 与真实速度方向同步（恢复终端弹道学重构中移除的朝向同步代码）

**Русский**
- 🔧 Исправлено: снаряды снова визуально вращались в полёте — ориентация снаряда теперь каждый тик синхронизируется с фактическим вектором скорости (восстановлена синхронизация, удалённая при рефакторинге терминальной баллистики)

## v2.5hotfix — 2026-08-18

**English**
- 🔧 Added an in-game item description for the Universal Proximity Fuze — the tooltip now always shows a function summary, hold Shift for the detonation distance

**中文**
- 🔧 通用近炸引信新增游戏内物品描述 — 提示栏常驻显示功能简介，按住 Shift 显示当前近炸距离

**Русский**
- 🔧 Добавлено игровое описание универсального неконтактного взрывателя — в подсказке всегда видно описание функции, Shift показывает радиус подрыва

## v2.5 — 2026-08-17

**English**
- 🆕 New: Universal Proximity Fuze — stackable; right-click opens CBC's fuze GUI to set the detonation distance; hold Shift to view the current distance
- 🎯 Proximity detonation against SABLE structures (sublevel bounds detection)
- 🚀 Proximity detonation against in-flight missiles — vestalihy PTUR / TOW / PTUR-Jet / Malytka, plus any entity whose type name contains "missile" or "rocket" (e.g. mianbao missiles)
- 🔧 Fixed: crash when opening the fuze distance GUI
- 🌐 Removed in-game en_us / ru_ru translations (Chinese only in-game); multilingual text is kept for the changelog

**中文**
- 🆕 新增：通用近炸引信 — 可堆叠，右键打开 CBC 同款调距界面设置近炸距离，按住 Shift 查看当前距离
- 🎯 对 SABLE 结构近炸（检测副等级结构边界盒）
- 🚀 对飞行中导弹近炸 — vestalihy 的 PTUR / TOW / PTUR-Jet / Malytka，以及类型名包含 missile / rocket 的实体（如面包学的导弹）
- 🔧 修复：打开引信调距界面闪退
- 🌐 移除游戏内 en_us / ru_ru 语言文件（游戏内仅中文），多语言仅用于日志

**Русский**
- 🆕 Новое: универсальный неконтактный взрыватель — стакается; ПКМ открывает меню радиуса подрыва (как у CBC), Shift показывает текущий радиус
- 🎯 Подрыв у SABLE-структур (по границам суб-уровней)
- 🚀 Подрыв у летящих ракет — vestalihy PTUR / TOW / PTUR-Jet / Malytka, а также сущности с "missile" / "rocket" в имени типа (например, ракеты mianbao)
- 🔧 Исправлено: вылет при открытии меню радиуса взрывателя
- 🌐 Убраны игровые переводы en_us / ru_ru (в игре только китайский); мультиязычность — только в журнале изменений

## v2.4 — 2026-08-16
- 🎨 美术2.0：全部弹头/装置贴图更新为 32×32 像素画 — APFSDS、APHE、SAP、榴霰弹、铝热剂弹头、多用途榴弹、低速破片榴弹、烟雾药水弹、重爆弹、灵魂火装置

## v2.3 — 2026-08-13
- ⚖️ 平衡：全系穿甲弹穿深进一步下调 — APFSDS 240/120 → 120/60；APHE 16/8 → 10/5；SAP 11/6 → 8/4；重爆弹 10/6 → 7/3（仍明显强于 CBCMW 3/1 基线），物品提示显示值同步更新
- 🆕 重爆弹：穿深随飞行距离增长 — 每飞行 4 格 +1 穿深（上限 +10），韧度每 8 格 +1（上限 +5），飞行 40 格达到峰值，与离膛加速机制同款封顶
- ⚖️ 平衡：APHE/SAP/重爆弹爆炸威力下调 — 对实体 6.0 → 4.5，对方块 4.0 → 3.0
- ⚖️ 平衡：铝热剂弹头方块挖掘概率 100% → 33%
- 🔧 修复：装药弹引爆后弹体立即移除，避免二次爆炸

## v2.2hotfix1 — 2026-08-13
- 🔧 修复：重爆弹命中后不爆炸（终端弹道学会判定为穿透导致打穿目标；现改为命中即引爆，无需引信）
- 🆕 重爆弹新增自带尾迹（火焰+烟雾粒子）
## v2.2 — 2026-08-13
- 🆕 新增重爆弹 — 离膛后持续加速至最高速度（5.0格/tick），命中后引发APHE式大威力爆炸，穿深18，支持高速机炮弹药
- 🆕 多功能榴弹重做为HEAT行为 — 继承CBC现代战争的HEAP破甲弹（小规模金属射流+锥形破片，威力/规模/穿甲较原版更小）
- 🆕 新增前置依赖：CBC Modern Warfare 0.0.6+
- 🔧 修复多语言文件JSON缺失逗号问题
## v2.1hotfix3 — 2026-08-12
- 🔧 修复：恢复所有弹头属性硬编码覆写(getProjectileMass/getBallisticProperties/getDamageProperties)，确保终端弹道学正确读取弹头质量、穿深、韧性数据
- 🔧 修复：APFSDS钢针穿深500/韧性250/质量100，APHE穿深28/韧性14/质量25，SAP穿深20/韧性10/质量30
- 🔧 所有弹头类型（APFSDS/APHE/SAP/榴霰弹/铝热剂/多用途/破片榴弹/烟雾药水弹/子弹药）均已添加属性覆写

## v2.1 — 2026-08-11
- 🆕 新增低速破片榴弹 — 8枚子弹药，碰撞5tick引爆，全向抛射，对生物巨量伤害，不可用于高速弹药
- 🆕 新增烟雾/药水弹 — 无药水时产生烟雾云团，合成滞留药水后产生药水云团，不可装灵魂火
- 🔧 修复：烟雾弹和破片榴弹被排除在灵魂火装置合成之外
- 🔧 修复：高速机炮弹药装配禁用破片榴弹
- 🔧 修复：创造物品栏mod消失问题（JAR缺少class文件）
- 🔧 修复：1.02版本中文内容乱码

## v2.0 — 2026-08-10
- ⚖️ 平衡：钢针(APFSDS)穿深 300→60，韧性 80→40，伤害 40→30
- ⚖️ 增强：灵魂火装置伤害 15%→25%，火焰范围 1格→3×3×3
- ~~引信控制器~~ — v1.09已删除
- ~~智能引信~~ — v1.09已删除

## v1.06 — 2026-08-10
- 🆕 新增多用途榴弹

## v1.05 — 2026-08-09
- 🆕 新增铝热剂弹头、灵魂火装置

## v1.04 — 2026-08-08
- 🆕 新增高速机炮弹药

## v1.03 — 2026-08-08
- 🔧 修复 APFSDS 穿深丢失

## v1.02 — 2026-08-08
- 🔧 修复：SAP和APHE出膛即爆、引信组装问题

## v1.01 — 2026-08-08
- 🔧 修复：弹射物丢失问题
- 机炮弹药命名格式：[弹射物：弹头名称]

## v1.00beta — 2026-08-07
- 🎉 初始版本
