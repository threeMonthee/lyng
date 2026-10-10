-- 所有表都带 user_id（现阶段固定为 me），所有查询都带它。启动时执行，表已存在就跳过。

CREATE TABLE IF NOT EXISTS worlds (
    id            TEXT PRIMARY KEY,
    user_id       TEXT NOT NULL,
    name          TEXT NOT NULL,
    public_text   TEXT NOT NULL,
    narrator_text TEXT NOT NULL,
    created_at    TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    UNIQUE (user_id, name)
);

CREATE TABLE IF NOT EXISTS characters (
    id         TEXT PRIMARY KEY,
    user_id    TEXT NOT NULL,
    world_id   TEXT NOT NULL REFERENCES worlds (id),
    name       TEXT NOT NULL,
    profile    TEXT NOT NULL,
    created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    UNIQUE (user_id, world_id, name)
);

-- cast_ids：在场人物的 id，逗号分隔、按入场顺序。possessed_id 为空表示旁观，此时 follow_id 是镜头跟随的人物。
-- pending：这一场还没做完的步骤（JSON），error：最近一步失败的原因。
CREATE TABLE IF NOT EXISTS scenes (
    id           TEXT PRIMARY KEY,
    user_id      TEXT NOT NULL,
    number       INTEGER NOT NULL,
    world_id     TEXT NOT NULL REFERENCES worlds (id),
    cast_ids     TEXT NOT NULL,
    possessed_id TEXT REFERENCES characters (id),
    follow_id    TEXT REFERENCES characters (id),
    tone         TEXT NOT NULL,
    scale        TEXT NOT NULL,
    setup        TEXT NOT NULL,
    status       TEXT NOT NULL CHECK (status IN ('open', 'ended')),
    pending      TEXT,
    error        TEXT,
    started_at   TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    ended_at     TEXT,
    UNIQUE (user_id, number)
);

-- 一场戏里发生的事，一行一件，只追加。kind：narration 叙事 / say 说 / do 行 / wait 没有开口 / inner 心声。
CREATE TABLE IF NOT EXISTS lines (
    scene_id   TEXT NOT NULL REFERENCES scenes (id),
    seq        INTEGER NOT NULL,
    user_id    TEXT NOT NULL,
    round      INTEGER NOT NULL,
    kind       TEXT NOT NULL CHECK (kind IN ('narration', 'say', 'do', 'wait', 'inner')),
    speaker_id TEXT REFERENCES characters (id),
    by_user    INTEGER NOT NULL DEFAULT 0,
    text       TEXT NOT NULL,
    created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    PRIMARY KEY (scene_id, seq)
);

-- 散场后的回想：整理产物，可以重新生成。views：{人物 id: 怎么看 TA}。
CREATE TABLE IF NOT EXISTS memories (
    character_id TEXT NOT NULL REFERENCES characters (id),
    scene_id     TEXT NOT NULL REFERENCES scenes (id),
    user_id      TEXT NOT NULL,
    memory       TEXT NOT NULL,
    views        TEXT NOT NULL,
    created_at   TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    PRIMARY KEY (character_id, scene_id)
);

CREATE TABLE IF NOT EXISTS model_calls (
    id                INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id           TEXT NOT NULL,
    scene_id          TEXT,
    kind              TEXT NOT NULL,
    subject           TEXT,
    model             TEXT NOT NULL,
    provider          TEXT,
    outcome           TEXT NOT NULL,
    error             TEXT,
    cost              REAL,
    latency_ms        INTEGER,
    first_token_ms    INTEGER,
    prompt_tokens     INTEGER,
    completion_tokens INTEGER,
    reasoning_tokens  INTEGER,
    finish_reason     TEXT,
    created_at        TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
);

CREATE INDEX IF NOT EXISTS model_calls_user ON model_calls (user_id, created_at);
