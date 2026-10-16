-- 启动时执行，只建不改。改了表结构就重建库（没有旧数据要兼容）。
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS settings (
    user_id     TEXT PRIMARY KEY,
    scale       TEXT NOT NULL DEFAULT 'mild' CHECK (scale IN ('mild', 'adult')),
    budget_usd  NUMERIC(10, 4),
    last_presence TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS worlds (
    id             UUID PRIMARY KEY,
    user_id        TEXT NOT NULL,
    name           TEXT NOT NULL,
    public_text    TEXT NOT NULL,
    narrator_text  TEXT NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS characters (
    id          UUID PRIMARY KEY,
    user_id     TEXT NOT NULL,
    world_id    UUID NOT NULL REFERENCES worlds (id),
    name        TEXT NOT NULL,
    profile     TEXT NOT NULL,
    -- 同一次诞生的人物按写下的先后排
    created_at  TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (world_id, name)
);

-- 如今的自我：每次改变追加一行，最新一行是当前。
CREATE TABLE IF NOT EXISTS selves (
    id            BIGSERIAL PRIMARY KEY,
    user_id       TEXT NOT NULL,
    character_id  UUID NOT NULL REFERENCES characters (id),
    text          TEXT NOT NULL,
    run_id        UUID,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 看法：每次追加完整的当前看法；初见时由创作写入（run_id 为空）。
CREATE TABLE IF NOT EXISTS views (
    id            BIGSERIAL PRIMARY KEY,
    user_id       TEXT NOT NULL,
    character_id  UUID NOT NULL REFERENCES characters (id),
    other_id      UUID NOT NULL REFERENCES characters (id),
    text          TEXT NOT NULL,
    run_id        UUID,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS views_pair ON views (character_id, other_id, id DESC);

CREATE TABLE IF NOT EXISTS concerns (
    id            UUID PRIMARY KEY,
    user_id       TEXT NOT NULL,
    character_id  UUID NOT NULL REFERENCES characters (id),
    about_id      UUID REFERENCES characters (id),
    text          TEXT NOT NULL,
    status        TEXT NOT NULL CHECK (status IN ('active', 'background', 'resolved')),
    resolution    TEXT,
    run_id        UUID,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS memories (
    id            UUID PRIMARY KEY,
    user_id       TEXT NOT NULL,
    character_id  UUID NOT NULL REFERENCES characters (id),
    event         TEXT NOT NULL,
    meaning       TEXT NOT NULL,
    people        UUID[] NOT NULL DEFAULT '{}',
    source_kind   TEXT NOT NULL CHECK (source_kind IN ('scene', 'chat', 'birth')),
    source_id     UUID,
    source_label  TEXT NOT NULL,
    run_id        UUID,
    embedding     vector,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS scenes (
    id            UUID PRIMARY KEY,
    user_id       TEXT NOT NULL,
    world_id      UUID NOT NULL REFERENCES worlds (id),
    number        INT NOT NULL,
    tone          TEXT NOT NULL CHECK (tone IN ('daily', 'adventure')),
    scale         TEXT NOT NULL CHECK (scale IN ('mild', 'adult')),
    setup         TEXT NOT NULL,
    possessed_id  UUID REFERENCES characters (id),
    follow_id     UUID REFERENCES characters (id),
    status        TEXT NOT NULL CHECK (status IN ('open', 'closed')),
    pending       TEXT[] NOT NULL DEFAULT '{}',
    failure       TEXT,
    round         INT NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    closed_at     TIMESTAMPTZ,
    UNIQUE (world_id, number)
);

CREATE TABLE IF NOT EXISTS scene_cast (
    scene_id      UUID NOT NULL REFERENCES scenes (id),
    character_id  UUID NOT NULL REFERENCES characters (id),
    position      INT NOT NULL,
    PRIMARY KEY (scene_id, character_id)
);

CREATE TABLE IF NOT EXISTS lines (
    id          BIGSERIAL PRIMARY KEY,
    user_id     TEXT NOT NULL,
    scene_id    UUID NOT NULL REFERENCES scenes (id),
    round       INT NOT NULL,
    kind        TEXT NOT NULL CHECK (kind IN ('narration', 'say', 'do', 'wait', 'inner', 'closing')),
    speaker_id  UUID REFERENCES characters (id),
    text        TEXT NOT NULL,
    by_user     BOOLEAN NOT NULL DEFAULT false,
    run_id      UUID,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS lines_scene ON lines (scene_id, id);

CREATE TABLE IF NOT EXISTS conversations (
    id            UUID PRIMARY KEY,
    user_id       TEXT NOT NULL,
    a_id          UUID NOT NULL REFERENCES characters (id),
    b_id          UUID NOT NULL REFERENCES characters (id),
    played_id     UUID REFERENCES characters (id),
    pending       TEXT[] NOT NULL DEFAULT '{}',
    failure       TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (a_id < b_id),
    UNIQUE (a_id, b_id)
);

CREATE TABLE IF NOT EXISTS messages (
    id               BIGSERIAL PRIMARY KEY,
    user_id          TEXT NOT NULL,
    conversation_id  UUID NOT NULL REFERENCES conversations (id),
    speaker_id       UUID NOT NULL REFERENCES characters (id),
    kind             TEXT NOT NULL CHECK (kind IN ('say', 'inner')),
    text             TEXT NOT NULL,
    by_user          BOOLEAN NOT NULL DEFAULT false,
    opportunity      TEXT NOT NULL DEFAULT 'reply' CHECK (opportunity IN ('reply', 'continue', 'reach', 'user')),
    unread           BOOLEAN NOT NULL DEFAULT false,
    run_id           UUID,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS messages_conversation ON messages (conversation_id, id);

-- 每个人物自己的亲历痕迹：按可见性从 lines / messages 复制，写成感知材料的一行。
CREATE TABLE IF NOT EXISTS traces (
    id            BIGSERIAL PRIMARY KEY,
    user_id       TEXT NOT NULL,
    character_id  UUID NOT NULL REFERENCES characters (id),
    source_kind   TEXT NOT NULL CHECK (source_kind IN ('scene', 'chat')),
    source_id     UUID NOT NULL,
    source_label  TEXT NOT NULL,
    ref_id        BIGINT NOT NULL,
    text          TEXT NOT NULL,
    embedding     vector,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (character_id, source_kind, ref_id)
);
CREATE INDEX IF NOT EXISTS traces_character ON traces (character_id, id);

-- 待整理的内心：一个人物 + 一个来源（某场戏 / 某段私聊到某条）。
CREATE TABLE IF NOT EXISTS consolidations (
    id            UUID PRIMARY KEY,
    user_id       TEXT NOT NULL,
    character_id  UUID NOT NULL REFERENCES characters (id),
    source_kind   TEXT NOT NULL CHECK (source_kind IN ('scene', 'chat')),
    source_id     UUID NOT NULL,
    from_ref      BIGINT NOT NULL,
    to_ref        BIGINT NOT NULL,
    status        TEXT NOT NULL CHECK (status IN ('pending', 'done', 'failed')),
    -- 失败过几次；三次后不再自动重做（幕后能看到原因）
    tries         INT NOT NULL DEFAULT 0,
    failure       TEXT,
    run_id        UUID,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    done_at       TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS consolidations_character ON consolidations (character_id, status);

CREATE TABLE IF NOT EXISTS runs (
    id               UUID PRIMARY KEY,
    user_id          TEXT NOT NULL,
    purpose          TEXT NOT NULL,
    title            TEXT NOT NULL,
    character_id     UUID,
    scene_id         UUID,
    conversation_id  UUID,
    draft_id         UUID,
    status           TEXT NOT NULL CHECK (status IN ('running', 'ok', 'failed')),
    request          JSONB,
    output           TEXT,
    reasoning        TEXT,
    tools            JSONB NOT NULL DEFAULT '[]',
    outcome          JSONB,
    failure          TEXT,
    attempts         INT NOT NULL DEFAULT 0,
    cost             NUMERIC(12, 6) NOT NULL DEFAULT 0,
    started_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at      TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS runs_character ON runs (character_id, started_at DESC);
CREATE INDEX IF NOT EXISTS runs_started ON runs (user_id, started_at DESC);

CREATE TABLE IF NOT EXISTS model_calls (
    id                 BIGSERIAL PRIMARY KEY,
    user_id            TEXT NOT NULL,
    run_id             UUID NOT NULL,
    attempt            INT NOT NULL,
    step               INT NOT NULL,
    purpose            TEXT NOT NULL,
    model              TEXT NOT NULL,
    provider           TEXT,
    outcome            TEXT NOT NULL CHECK (outcome IN ('ok', 'tool', 'error', 'refused')),
    error              TEXT,
    cost               NUMERIC(12, 6),
    latency_ms         INT,
    first_token_ms     INT,
    prompt_tokens      INT,
    completion_tokens  INT,
    reasoning_tokens   INT,
    finish_reason      TEXT,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS model_calls_run ON model_calls (run_id, id);

CREATE TABLE IF NOT EXISTS drafts (
    id          UUID PRIMARY KEY,
    user_id     TEXT NOT NULL,
    world_id    UUID REFERENCES worlds (id),
    request     TEXT NOT NULL,
    status      TEXT NOT NULL CHECK (status IN ('asking', 'writing', 'ready', 'born', 'failed')),
    -- 还没追问过时为空
    questions   JSONB,
    answers     JSONB,
    content     JSONB,
    check_result JSONB,
    failure     TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
