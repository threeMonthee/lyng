# 此间有灵 2.0

进入你喜爱的作品世界，和几个像真人一样的人物相处：附身其中一人亲身经历，或者隐身旁观；散场后，他们会把这段经历整理进自己的心里，记得你们之间发生过的事。不在一起的时候，也可以附身一个人物给另一个人发消息。

它是 [x-chat-ai](https://github.com/threeMonthee/x-chat-ai)（此间有灵 1.0）的 2.0 版本：形态来自 lyng 定稿的“世界 + 叙事者 + 一场戏”，内核来自 x-chat-ai 的人物心智、私聊和一念生。产品定义见 [docs/requires.md](docs/requires.md)。

## 能做什么

- **一念生**：一句话写下想进入的世界、想遇见的人 → 回答几个追问 → 逐段过目，手改或让 AI 改某一段，一致性检查 → 诞生。也可以给已有的世界添人物。
- **一场戏**：选在场的人、你附身谁（或旁观）、基调和开场处境。叙事者推动世界，人物自己决定说什么、做什么；你可以说、做、静候，随时换身份；结束时叙事者收尾。
- **私聊**：附身一个人物和另一个人物文字往来。可以等 TA 接着说；TA 可以不回；心里有牵挂的人，可能在你回来时主动找你。
- **人物主页**：出生档案、如今的自己、牵挂、记忆、对每个人的看法和它的变化、亲历的原样经历。
- **心声与幕后**：每次开口都附一两句没说出口的心声（可以隐藏）；幕后能看到每一次生成发给模型的全部内容、思考、回想、结果、重试、耗时和花费。
- **设置**：内容尺度（含蓄 / 成人）、费用上限、用量、浅色与深色。

空库第一次启动会放入盲测用过的诛仙世界（陆雪琪、张小凡、碧瑶）。

## 在本机运行

需要 JDK 25、Node 22+，以及 PostgreSQL + pgvector（没有的话装 Docker，脚本会自动起数据库容器）。

```sh
cp .env.example .env        # 填 OPENROUTER_API_KEY 与 DASHSCOPE_API_KEY
scripts/start.sh            # 构建并启动，打开 http://127.0.0.1:8080
scripts/start.sh --fake     # 不填 key 也能看：人物的话由确定性替身拼出，不调用模型、不产生费用
```

手机打开：在 `.env` 里设 `LYNG_HOST=0.0.0.0`，手机和电脑连同一个 Wi-Fi，访问 `http://电脑的局域网IP:8080`。没有登录，只在可信的网络里这样用。

真实模型默认是 OpenRouter 上的 `deepseek/deepseek-v4.1-flash`（首选服务商 Parasail），记忆向量用百炼 `qwen3.7-text-embedding`。可在设置页设费用上限；其余可调项见 [.env.example](.env.example)。

## 目录

| 路径 | 内容 |
| --- | --- |
| `server/` | Java 25 + Spring Boot 服务：模型网关、人物心智、一场戏、私聊、一念生、接口；也托管构建好的网页 |
| `client/` | React + TypeScript + Vite + Tailwind 的响应式网页 |
| `docs/` | 需求、产品圣经、技术设计与参考资料，入口 [docs/README.md](docs/README.md) |
| `validation/` | 盲测用的诛仙世界、人物与场景，见 [validation/README.md](validation/README.md) |
| `scripts/` | 启动脚本与数据库初始化 |

参与开发（人或 AI）先读 [AGENTS.md](AGENTS.md)。
