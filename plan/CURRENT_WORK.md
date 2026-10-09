# Current Work

更新于 2026-10-09。

## 当前阶段：实现第一个可用版本

- 核心体验验证已于 2026-10-09 结束，结论与记录见 [core-validation.md](core-validation.md) §8（思考强度实验的结论在 §8.12 末尾）。
- 技术设计：[docs/tech-design.md](../docs/tech-design.md)（编写中）。
- 目标：第一刀纵切在本机跑通：
  1. 一句话创作世界与人物 → 一页预览（可手改）→ 确认诞生；
  2. 选附身谁、写一句开场 → 叙事者开场 → 一场双人戏（说 / 做 / 静候，流式气泡，长按看心声，由用户结束）；
  3. 散场后经历落库并异步生成回想，第二场能接上第一场；
  4. 技术失败可见、可重试。
- 旁观、群戏、穿越、路人留下、成人向开关这一刀都不做，在技术设计里留了位置。

## 已确认的产品与技术输入

- 产品：[requires.md](../docs/requires.md) 与 [PROJECT_BIBLE.md](../docs/PROJECT_BIBLE.md)（2026-10-09 经用户确认修改 requires §2 末句与 §3 第三条：选型以实际使用体验为主，盲测集保留、按需选用）。
- 模型：默认 `deepseek/deepseek-v4.1-flash`，默认推理，经 OpenRouter；固定首选服务商、故障回退，每次调用记录服务商。
- 栈：Java 25 + Spring Boot（MVC 阻塞式 + 虚拟线程 + SSE）+ PostgreSQL；客户端 TypeScript + React + Vite，PWA。

## 进度

- [x] 盲测集 v1、原型脚本（[validation/](../validation/README.txt)）与全部实验（[core-validation.md](core-validation.md) §8）。付费调用累计 6.79 美元（验证方案总预算 10 美元）。
- [x] 小修：群戏调度排除附身人物（验证原型的 Prompt、解析、测试与快照）；记录思考强度实验结论；requires.md 与 AGENTS.md 同步。
- [ ] 技术设计 → 实现 → 验证（见 REPORT-v4.txt，完成后更新本节）。

## 已登记、暂不解决

- 走向 C 端前：原作版权、成人向内容合规、单用户成本。
- “说”在 v2 里变长了，要不要再收（验证期遗留，未裁决）。
