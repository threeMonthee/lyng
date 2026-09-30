# Current Work

更新于 2026-09-30。

## 当前阶段：核心体验验证

- [requires.md](../docs/requires.md) 与 [PROJECT_BIBLE.md](../docs/PROJECT_BIBLE.md) 已于 2026-09-30 定稿。
- 验证方案见 [core-validation.md](core-validation.md)：接入（OpenRouter）、候选（便宜快速模型为主）与预算（总计 10 美元）已裁定；**待放行 `openrouter.ai`、配置 `OPENROUTER_API_KEY` 并授权付费调用**。
- 验证结论出来前不写产品代码、不定详细技术方案。

## 下一步

1. 不花钱的部分先做：盲测集、原型脚本、Prompt 快照测试、确定性替身跑通。
2. 用户放行网络、配置凭据、授权试跑。
3. 试跑 → 用户确认 → 正式盲测 → 用户盲评 → 记录结论。
4. **技术设计**：含客户端与服务端选型（“跟上 AI 发展”是考量之一），以验证结论为输入。

## 已登记、暂不解决

- 走向 C 端前：原作版权、成人向内容合规、单用户成本。
