# lyng

进入你喜爱的作品世界，遇见那个人：人物像真人一样拥有连续人生与记忆；你可以附身任一人物亲身经历，也可以隐身旁观 TA 们活着的样子，由 AI 叙事者让世界运转。

当前阶段：**第一个可用版本**（2026-10-10 重写）。需求与产品圣经已定稿，技术设计见 [docs/tech-design.md](docs/tech-design.md)。

这一版能做的事：

1. 一句话创作世界与人物（或在已有世界里添人物）→ 一页预览，全部可以手改 → 诞生；
2. 开一场戏：选在场的人（一个或多个）、附身谁或旁观、基调、开场处境 → 叙事者开场；
3. 一场戏里：附身时说 / 做 / 静候，旁观时点“继续”；随时切换附身的人或转为旁观；双人戏与群戏；流式；点一下人物的话看心声；由你结束，叙事者收尾；
4. 散场后每个在场人物用自己的口吻写回想与对别人的看法，下一场接得上；人物页能看不能改；
5. 技术失败看得见、可以重试；每次模型调用记账，可设费用硬上限。

穿越、路人留下、世界事实、成人向开关、撤回自己的话、移动端这一版不做，留位见 [tech-design §2](docs/tech-design.md#2-范围)。

## 启动

需要：JDK 25、Maven、Node.js。

```bash
cp .env.example .env     # 然后填 OPENROUTER_API_KEY
scripts/start.sh
```

打开 <http://localhost:8080>。脚本做三件事：构建客户端 → 构建服务端 → 运行。数据在 `data/lyng.db`（SQLite，已 gitignore）。服务只监听本机，不需要登录。

想先不花钱地看一遍界面，用确定性模型替身（数据另存在 `data/fake.db`，不和真实的混在一起）：

```bash
scripts/start.sh --fake
```

## 配置

只从环境读取：进程环境变量，或仓库根的 `.env`（已 gitignore，不进仓库）。

| 变量 | 默认 | 说明 |
| --- | --- | --- |
| `OPENROUTER_API_KEY` | 无 | 真实模型的密钥；`--fake` 不需要 |
| `LYNG_MODEL_BACKEND` | `openrouter` | `fake` 是确定性替身，不发网络请求 |
| `LYNG_MODEL` | `deepseek/deepseek-v4.1-flash` | 所有角色的默认模型，可写成 `模型ID@推理设置`（`off` / `low` / `medium` / `high`） |
| `LYNG_MODEL_CHARACTER`、`LYNG_MODEL_NARRATOR`、`LYNG_MODEL_NARRATOR_ROUND`、`LYNG_MODEL_CREATION`、`LYNG_MODEL_RECALL` | 跟随 `LYNG_MODEL` | 单独指定人物、叙事者（开场与收尾）、叙事者逐轮决定（默认关推理）、创作、回想的模型 |
| `LYNG_PROVIDER` | `Parasail` | OpenRouter 首选服务商，故障时回退；空表示不指定 |
| `LYNG_BUDGET_USD` | 不设 | 累计费用硬上限（美元），达到就停止调用 |
| `LYNG_PORT` | `8080` | 服务端口 |
| `LYNG_DB` | `../data/lyng.db` | SQLite 文件，路径相对 `server/` |

## 开发与测试

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 25)
cd server && mvn test        # 临时 SQLite 文件，不发任何网络请求
cd client && npm test        # 排版分块与 SSE 解析
cd client && npm run dev     # 开发服务器（代理 /api 到 8080）
```

- Prompt 有快照测试；叙事者的 Prompt 另与验证原型的快照逐字节比对（`server/src/test/resources/snapshots/parity`）。改了 Prompt 之后：`UPDATE_SNAPSHOTS=1 mvn test -Dtest=PromptSnapshotTest`，检查差异后再提交。
- 一场戏的行为用确定性替身测试（`server/src/test/java/app/lyng/story/SceneTest`）；完整流程（创作 → 第一场 → 散场 → 第二场）见 `web/ApiFlowTest`。
- 真实模型只用于冒烟测试，需要在对话里单独授权场景与预算。

## 目录

| 目录 | 内容 |
| --- | --- |
| [docs/](docs/README.md) | 长期产品事实：需求、产品圣经、技术设计；[reference/](docs/reference/) 保存前身项目的访谈结论与实验教训 |
| [plan/](plan/CURRENT_WORK.md) | 当前阶段、待裁决事项与进度；核心体验验证的方案与全部实验记录 |
| [server/](server/) | Java 服务（JDK 25、Spring Boot、Spring MVC 阻塞式 + 虚拟线程、SQLite） |
| [client/](client/) | 网页客户端（TypeScript、React、Vite） |
| [validation/](validation/README.txt) | 已结束的核心体验验证：盲测集与 Python 原型（Prompt 与调用链的来源，不再演进） |
| [scripts/](scripts/start.sh) | 启动脚本 |

本项目是 [threeMonthee/x-chat-ai](https://github.com/threeMonthee/x-chat-ai)（此间有灵）的 0→1 重写。原项目只作参考，不是约束。
