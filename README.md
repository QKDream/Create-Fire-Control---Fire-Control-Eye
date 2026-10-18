# Create Big Cannons: Autocannon Revolution

机械动力火炮：机炮弹药革命 — an addon for [Create Big Cannons](https://github.com/Cannoneers-of-Create/CreateBigCannons) adding modern autocannon munitions.

---

## 物品介绍 / Item Descriptions / Описание предметов

| 物品 | 中文 | English | Русский |
|------|------|---------|---------|
| **APFSDS Round** | 尾翼稳定脱壳穿甲弹。动能长杆穿透，穿深500，机炮最高穿甲弹头。 | Fin-stabilized discarding sabot. Kinetic long-rod penetrator, pen 500. Highest AP round. | Бронебойный оперённый подкалиберный снаряд. Кинетический сердечник, пробитие 250. |
| **APHE Round** | 穿甲高爆弹。穿透后引爆，穿深14，附带爆炸伤害。可装引信+灵魂火装置。 | Armor-piercing high-explosive. Penetrates then detonates, pen 14. Fuze + Soul Fire compatible. | Бронебойно-фугасный снаряд. Пробивает и взрывается, пробитие 14. |
| **SAP Round** | 半穿甲弹。兼顾穿透与爆炸，穿深12。可装引信+灵魂火装置。 | Semi-armor-piercing. Balanced pen (12) and explosive filler. Fuze + Soul Fire compatible. | Полубронебойный снаряд. Сбалансированная бронепробиваемость (12). |
| **Shrapnel Round** | 榴霰弹。引爆释放40枚破片，密集散布反人员。可装引信+灵魂火装置。 | Shrapnel shell. Releases 40 fragments on detonation, dense anti-personnel spread. | Шрапнельный снаряд. 40 поражающих элементов, противопехотный. |
| **Thermite Round** | 铝热剂弹头。撞击方块挖掘+引燃周围。可装灵魂火装置。 | Thermite warhead. Destroys impact block and ignites surroundings. Soul Fire compatible. | Термитный снаряд. Разрушает блок при попадании и поджигает область. |
| **Multi-Purpose Grenade** | 多用途榴弹。继承CBC现代战争的HEAT破甲行为：小规模金属射流+爆炸，规模、威力与穿甲均较原版HEAT更小。可装引信/灵魂火装置。 | Multi-purpose HEAT grenade. Inherits CBC Modern Warfare HEAT behavior: small shaped-charge jet + explosion, smaller scale, power and penetration than vanilla HEAT. Fuze + Soul Fire compatible. | Многоцелевая граната. Наследует кумулятивное поведение HEAT из CBC Modern Warfare: небольшая кумулятивная струя + взрыв, меньше масштаб, мощь и пробитие. |
| **Heavy Explosive Round** | 重爆弹。离膛后持续加速直至最高速度，命中后引发APHE式大威力爆炸（穿深18）。可装引信/灵魂火装置，支持高速机炮弹药。 | Heavy explosive round. Continuously accelerates after leaving the barrel up to a cap, then detonates on impact with a massive APHE-style blast (pen 18). Fuze + Soul Fire compatible, supports HV cartridge. | Тяжёлый фугасный снаряд. Постоянно разгоняется после вылета из ствола до предела, при попадании — мощный взрыв в стиле APHE (пробитие 18). |
| **Frag Grenade** | 低速破片榴弹。速度极低，8枚子母弹全向散射，碰撞/5tick二次引爆大量破片。不可用于高速弹药。 | Low-velocity frag grenade. 8 submunitions, omnidirectional, dual-phase detonation. Not for HV cartridge. | Низкоскоростная осколочная граната. 8 суббоеприпасов, двухфазный подрыв. |
| **Smoke/Potion Round** | 烟雾/药水弹。无穿甲。空装时产生烟幕云团；装载滞留药水后释放药水云团。Shift查看已装药水。不可装灵魂火装置。 | Smoke/potion round. No pen. Empty: smokescreen. Loaded with lingering potion: potion cloud. Shift to inspect. | Дымовой/зельевой снаряд. Без пробития. Пустой: дым. С зельем: облако эффектов. |
| **Soul Fire Device** | 灵魂火装置。与弹药合成，爆炸附加火焰+对3×3×3范围内生物造成25%最大生命伤害。与Incendiary Tip互斥。 | Soul fire device. Craft with ammo: adds fire field + 25% max HP damage in 3×3×3 area. Mutually exclusive with Incendiary Tip. | Устройство адского пламени. Добавляет огонь + 25% макс. HP урона в области 3×3×3. |
| **Universal Proximity Fuze** | 通用近炸引信。可堆叠，右键打开界面调节近炸距离；对 SABLE 结构、飞行中的导弹、CBC 武器拓展的鱼雷/火箭弹/深水炸弹/炸弹、TAOV 的 TAU 与地狱火近炸起爆。Shift 查看当前设定距离。 | Universal proximity fuze. Stackable; right-click to open the GUI and tune detonation distance. Detonates near SABLE structures, flying missiles, CBC Military Supplement torpedoes/rockets/depth charges/bombs, and TAOV TAU & Hellfire. Shift to inspect current range. | Универсальный неконтактный взрыватель. Настраиваемая дистанция подрыва; срабатывает у SABLE-конструкций, летящих ракет, торпед/ракет/глубинных бомб из CBC Military Supplement и ракет TAU и Hellfire из TAOV. |
| **High-Velocity Cartridge** | 高速机炮弹药。初速2倍，支持除破片榴弹外所有弹头。未装弹头时外观为装药弹壳，装弹头后外观为标准弹药。 | High-velocity cartridge. 2x muzzle velocity. Accepts all rounds except Frag Grenade. Empty: filled case model; loaded: standard cartridge model. | Высокоскоростной патрон. 2x начальная скорость. Принимает все снаряды кроме осколочной гранаты. |

---

## 弹药数据 / Ammunition Data / Характеристики боеприпасов

| 弹头 | 穿深 | 类型 | 可装引信 | 可装灵魂火 | 高速兼容 |
|------|------|------|----------|------------|----------|
| APFSDS | 60 | 动能穿甲 | ❌ | ❌ | ✅ |
| APHE | 14 | 穿甲高爆 | ✅ | ✅ | ✅ |
| SAP | 12 | 半穿甲 | ✅ | ✅ | ✅ |
| Shrapnel | — | 破片杀伤 | ✅ | ✅ | ✅ |
| Thermite | — | 燃烧/挖掘 | ❌ | ✅ | ✅ |
| Multi-Purpose | — | 破甲/爆炸 | ✅ | ✅ | ✅ |
| Heavy Explosive | 18 | 穿甲高爆 | ✅ | ✅ | ✅ |
| Frag Grenade | — | 子母破片 | ❌ | ❌ | ❌ |
| Smoke/Potion | — | 烟雾/药水 | ✅ | ❌ | ✅ |

**原版 AP 对比:** AP Autocannon Round — 穿深 20，纯动能，无引信，无爆炸。

---

## 已删除 / Removed / Удалено

- ~~Smart Fuze 智能引信~~ (v1.09)
- ~~Fuze Controller 引信控制器~~ (v1.09)

---

## 依赖 / Dependencies / Зависимости

- Minecraft 1.21.1
- NeoForge 21.1+
- Create Big Cannons 5.11+
- Create 6.0+
- Ritchie's Projectile Library 2.1+
- CBC Modern Warfare 0.0.6+

可选 / Optional: SABLE、Vestalihy、CBC Military Supplement、TAOV Weapons（TAU/地狱火）— 通用近炸引信对结构、导弹、鱼雷、火箭弹、深水炸弹、炸弹的近炸功能需要它们。

## 链接 / Links / Ссылки

- GitHub: https://github.com/QKDream/Create-Big-Cannon-Autogun-Revolution
- Pages: https://qkdream.github.io/Create-Big-Cannon-Autogun-Revolution/
