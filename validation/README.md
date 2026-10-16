# 验证材料

盲测验证过的诛仙世界、三个人物和场景集，服务端直接使用：

| 文件 | 用途 |
| --- | --- |
| `world.txt` / `world-narrator.txt` | 诛仙世界的公开设定与只有叙事者知道的事；空库启动时作为种子世界诞生 |
| `characters/*.txt` | 陆雪琪、张小凡、碧瑶的出生档案（叙述式小传）；种子人物与 Prompt 快照测试都用它 |
| `scenes/*.txt` | 盲测场景：A1–A5 双人日常（认错、吃醋、和好、主动、脆弱），B1–B3 冒险（开场、事件、狭路相逢），C1 亲密。每个写明你的位置、在场人物、基调、尺度与开场；换模型或大改 Prompt 时用来对比 |

构建时 `server/pom.xml` 把 `world*.txt` 与 `characters/` 复制进服务的 `seed/` 资源；种子人物的初始看法与牵挂在 `server/src/main/resources/seed/inner/`。改这里的文字会改变种子世界和快照测试的结果，需要同时更新快照（`UPDATE_SNAPSHOTS=1`）并重新评估受影响的结论。
