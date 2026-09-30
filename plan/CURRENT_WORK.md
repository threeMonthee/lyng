# Current Work

更新于 2026-09-30。

## 当前阶段：核心体验验证

- [requires.md](../docs/requires.md) 与 [PROJECT_BIBLE.md](../docs/PROJECT_BIBLE.md) 已于 2026-09-30 定稿。
- 验证方案见 [core-validation.md](core-validation.md)：接入（OpenRouter）、候选（便宜快速模型为主）与预算（总计 10 美元）已裁定；**待放行 `openrouter.ai`、配置 `OPENROUTER_API_KEY` 并授权付费调用**。
- 验证结论出来前不写产品代码、不定详细技术方案。

## 进度

- [x] 盲测集 v1（[validation/](../validation/README.txt)）：诛仙世界概要，陆雪琪、张小凡、碧瑶三份叙述式档案，9 个场景的开场、场前经历与用户脚本。
- [x] 原型脚本（[validation/lab/](../validation/lab/README.txt)）：一场戏的循环、OpenRouter 客户端、费用硬上限、请求失败分类（技术失败 / 服务商拒绝 / 整次停止）、〔需同意〕检查、匿名盲评页；Prompt 快照与行为测试；确定性替身跑通全部 9 个场景。
- [ ] 真实调用：尚未发生任何付费调用。

## 下一步

1. 用户放行网络、配置凭据、授权试跑（A1 × 3 个模型 × 1 次，上限 1 美元）；开跑前在 OpenRouter 核对模型 ID 与价格并写入实验记录。
2. 试跑 → 用户确认（盲评页排版尚未在浏览器里目视检查，一并确认）→ 正式盲测 → 用户盲评 → 记录结论。
3. **技术设计**：含客户端与服务端选型（“跟上 AI 发展”是考量之一），以验证结论为输入。

## 已登记、暂不解决

- 走向 C 端前：原作版权、成人向内容合规、单用户成本。
