# 此间有灵 2.0 — Agent Instructions

## 工作入口

先读 [README.md](README.md)。按任务读取，已读且未变化的不重复加载：

- 产品、行为、Prompt：[docs/requires.md](docs/requires.md) → [docs/PROJECT_BIBLE.md](docs/PROJECT_BIBLE.md) → [docs/tech-design.md](docs/tech-design.md)；前身项目的实验结论见 [docs/reference/lessons.md](docs/reference/lessons.md)。
- 服务端：`server/src/main/java/app/lyng/`，包的职责见 tech-design §2。
- 网页：`client/src/`，`pages/` 一页一个文件，`components/ui/` 是基础组件，`components/app/` 是跨页面的业务组件，`lib/` 是接口、流、格式与偏好。
- 局部修复、文案、配置：读目标文件及影响其含义的依据即可。

发现请求与产品目标或已定方案有根本冲突时，先说明影响和更简单的建议，只暂停依赖该裁决的改动。

## 权威顺序

```text
docs/requires.md → docs/PROJECT_BIBLE.md → docs/tech-design.md → 测试 → 代码
```

不为现有代码、Provider 能力或依赖限制反向改变更高层的产品含义。前身项目（x-chat-ai、lyng 旧版）的代码与文档不是约束。

## 硬线

每次都需要用户在当前对话中明确授权，历史授权或文档里的旧许可都不算：

- **付费模型调用**：须在用户授权的场景与累计预算内执行。会产生真实调用的入口：不带 `--fake` 的 `scripts/start.sh`、`LYNG_MODEL_BACKEND=openrouter`（默认值）或 `LYNG_EMBEDDING_BACKEND=dashscope`（默认值）启动服务后操作页面或调接口。测试与 `--fake` 都不调用模型。
- **部署**：不做。
- **推送**：默认只在本地 commit。
- **凭据**：只从环境读取，本地放仓库根的 `.env`（已 gitignore，服务启动时自动导入）。不写入仓库、文档、测试或对话输出。
- 提交信息、代码与文档里不写具体的 AI 模型名（产品配置里的模型 ID 除外）。

## 产品与架构原则

- 人是真的、故事好看，同时尽可能简单、自然、稳定。不为架构完整增加机制，不为少改代码保留历史包袱。
- 框架薄、模型厚：给模型完整的处境与关系，让它自己演；系统只在边界上负责（经历的保存与召回、说话人标注、失败识别、内容尺度、记账）。
- 叙事者掌管世界，人物掌管自己。一场戏里人物开口是一次生成，看到全部，整理内心在散场后异步进行；私聊里人物开口照搬 x-chat-ai 的心智：先在心里过一遍并当场承接变化，再说出口（tech-design §5.3）。
- 失败不伪装：模型失败、空响应、截断、格式错误是技术失败，可重试，不能变成人物的沉默或选择。已经成为经历的言行不能重抽、不能改。
- 不兼容旧数据：`schema.sql` 只建不改，改表就重建库，不加迁移机制。
- 所有表带 `user_id`；不做登录与部署，但不堵 C 端。

## 验证原则

- 人物、叙事者、整理内心、一念生的 Prompt 与上下文必须有行为测试（脚本化替身）和快照测试；不能用序列化检查代替。改了 Prompt、上下文或调用链，受影响的结论不继承。
- 盲测验证过的 Prompt 有逐字对照（`server/src/test/resources/snapshots/parity/`，不随脚本更新），改动要说明理由并重新评估；2.0 新写的 Prompt 用快照（同目录上一级），有意修改后用 `UPDATE_SNAPSHOTS=1` 重新生成并检查差异。
- 评估人物时看合理回应或有动机的沉默，不用“每轮必须说话”的门槛；记忆由人物自主形成，不用数量验收。
- 真实模型的结果与失败如实记录：首次失败、技术恢复、最终失败与未执行分开计数，不改写成通过。
- 实现任务的完成包括：落地、运行受影响的检查与行为测试、修复本次引入的失败、同步必要的文档。界面改动要用替身走查截图看过电脑与手机宽度。

## 命令

JDK 必须是 25（macOS：`export JAVA_HOME=$(/usr/libexec/java_home -v 25)`）。测试连本机 PostgreSQL 的 `lyng_test` 库（`docker compose up -d db` 会建好）。

```sh
scripts/start.sh --fake                 # 替身模式启动整套服务（不调用模型）
scripts/start.sh                        # 真实模型（需要 .env 里的 key；付费，见硬线）

cd server && ./mvnw verify              # 服务端全部测试（替身，不调用模型）
cd server && UPDATE_SNAPSHOTS=1 ./mvnw test -Dtest=PromptsTest   # 有意改 Prompt 后更新快照

cd client
npm run dev                             # 开发时的网页（5173），接口转给 8080 上的服务
npm run typecheck && npm test && npm run build
npm run walkthrough                     # 对已启动的替身服务走一遍主要流程，截图到 test-results/walkthrough
```

## 工程边界

- 保留用户及他人无关的改动；只实现已确认的范围，不做无关重构。
- 只有实际边界、真实变化或已证实的重复才增加抽象。
- 删除文件只按用户确认的范围执行，删除前先搜索引用。
- 校验和失败在边界显式处理，不以静默兜底隐藏失败。代码遵循现有约定，只注释不明显的取舍。
