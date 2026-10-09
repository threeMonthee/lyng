# lyng

进入你喜爱的作品世界，遇见那个人：人物像真人一样拥有连续人生与记忆；你可以附身任一人物亲身经历，也可以隐身旁观 TA 们活着的样子，由 AI 叙事者让世界运转。

当前阶段：**第一个可用版本**（第一刀纵切）。需求与产品圣经已定稿，技术设计见 [docs/tech-design.md](docs/tech-design.md)。

这一刀能做的事：

1. 一句话创作世界与人物 → 一页预览（可手改）→ 确认诞生；
2. 选附身谁、写一句开场 → 叙事者开场 → 一场双人戏：说 / 做 / 静候，流式气泡，长按人物的话看心声，由你结束这场戏；
3. 散场后经历落库，异步生成每个人物的回想；第二场能接上第一场；
4. 技术失败看得见、可以重试；
5. 每次模型调用记账（服务商、耗时、费用、推理 token），可设费用硬上限。

旁观、群戏、穿越、路人留下、成人向开关这一刀不做，留位见 [tech-design §12](docs/tech-design.md#12-留位这一刀不做的事)。

## 启动

需要：JDK 25、Maven、Node.js、Docker（本机 PostgreSQL）。

```bash
cp .env.example .env     # 然后填 LYNG_PASSWORD 和 OPENROUTER_API_KEY
scripts/start.sh
```

打开 <http://localhost:8080>，用 `.env` 里的 `LYNG_USER`（默认 `me`）和 `LYNG_PASSWORD` 登录。

脚本做四件事：用 Docker Compose 起 PostgreSQL → 构建客户端 → 构建服务端 → 运行。想先不花钱地看一遍界面，用确定性模型替身：

```bash
scripts/start.sh --fake
```

在手机上用：同一局域网下打开 `http://<这台电脑的 IP>:8080`，再“添加到主屏幕”。PWA 需要 HTTPS 才能在非本机地址上完整安装；部署不在这一刀的范围内。

## 配置

只从环境读取：进程环境变量，或仓库根的 `.env`（已 gitignore，不进仓库）。

| 变量 | 默认 | 说明 |
| --- | --- | --- |
| `LYNG_PASSWORD` | 无，必填 | 登录口令。没设置服务不会启动 |
| `LYNG_USER` | `me` | 登录名 |
| `OPENROUTER_API_KEY` | 无 | 真实模型的密钥；fake 模式不需要 |
| `LYNG_MODEL_BACKEND` | `openrouter` | `fake` 是确定性替身，不发网络请求 |
| `LYNG_MODEL` | `deepseek/deepseek-v4.1-flash` | 四种角色共用的默认模型 |
| `LYNG_REASONING` | 空（模型默认，开推理） | `off` / `low` / `medium` / `high` |
| `LYNG_PROVIDER` | `Parasail` | OpenRouter 首选服务商，故障时回退；空表示不指定 |
| `LYNG_BUDGET_USD` | 不设 | 累计费用硬上限（美元），达到就停止调用 |
| `LYNG_PORT` | `8080` | 服务端口 |
| `LYNG_DB_URL`、`LYNG_DB_USER`、`LYNG_DB_PASSWORD` | 本机 Compose 的库 | 指向别的 PostgreSQL 时设置（此时脚本跳过 Docker） |

人物、叙事者（开场与收尾）、叙事者（逐轮决定）、创作、回想各自用哪个模型，可以在应用里“设置”页随时改，不用重启。写法是 `模型ID` 或 `模型ID@推理设置`。

## 开发与测试

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 25)
cd server && mvn test        # 嵌入式 PostgreSQL，不依赖 Docker，也不发任何网络请求
cd client && npm test        # SSE 解析与事件 reducer
cd client && npm run dev     # 开发服务器（代理 /api 到 8080）
```

- Prompt 有快照测试，改了 Prompt 之后：`UPDATE_SNAPSHOTS=1 mvn test -Dtest=PromptSnapshotTest`，检查差异后再提交。
- 人物行为用确定性替身测试（`server/src/test/java/app/lyng/scene/SceneBehaviorTest`）；完整流程（创作 → 第一场 → 散场 → 第二场、按用户隔离）见 `web/ApiFlowTest`。
- 真实模型只用于冒烟测试，需要在对话里单独授权场景与预算。

## 目录

| 目录 | 内容 |
| --- | --- |
| [docs/](docs/README.md) | 长期产品事实：需求、产品圣经、技术设计；[reference/](docs/reference/) 保存前身项目的访谈结论与实验教训 |
| [plan/](plan/CURRENT_WORK.md) | 当前阶段、待裁决事项与进度；核心体验验证的方案与全部实验记录 |
| [server/](server/) | Java 服务（JDK 25、Spring Boot、Spring MVC + 虚拟线程、PostgreSQL） |
| [client/](client/) | 网页客户端（TypeScript、React、Vite，PWA） |
| [validation/](validation/README.txt) | 已结束的核心体验验证：盲测集与 Python 原型（Prompt、解析、测试的移植来源，不再演进） |
| [scripts/](scripts/start.sh) | 启动脚本 |

本项目是 [threeMonthee/x-chat-ai](https://github.com/threeMonthee/x-chat-ai)（此间有灵）的 0→1 重写。原项目只作参考，不是约束。
