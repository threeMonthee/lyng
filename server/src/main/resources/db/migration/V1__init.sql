-- 所有业务表都带 user_id，子表用 (user_id, x_id) 复合外键指向父表，保证子行不可能挂到别的用户的父行上。

CREATE TABLE users (
    id            uuid PRIMARY KEY,
    name          text NOT NULL UNIQUE,
    password_hash text NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE sessions (
    token_hash text PRIMARY KEY,
    user_id    uuid NOT NULL REFERENCES users (id),
    created_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NOT NULL
);

CREATE TABLE settings (
    user_id uuid NOT NULL REFERENCES users (id),
    key     text NOT NULL,
    value   text NOT NULL,
    PRIMARY KEY (user_id, key)
);

CREATE TABLE worlds (
    id            uuid PRIMARY KEY,
    user_id       uuid NOT NULL REFERENCES users (id),
    name          text NOT NULL,
    public_text   text NOT NULL,
    narrator_text text NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now(),
    UNIQUE (user_id, id),
    UNIQUE (user_id, name)
);

CREATE TABLE characters (
    id            uuid PRIMARY KEY,
    user_id       uuid NOT NULL REFERENCES users (id),
    home_world_id uuid NOT NULL,
    name          text NOT NULL,
    profile       text NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now(),
    UNIQUE (user_id, id),
    UNIQUE (user_id, home_world_id, name),
    FOREIGN KEY (user_id, home_world_id) REFERENCES worlds (user_id, id)
);

CREATE TABLE scenes (
    id           uuid PRIMARY KEY,
    user_id      uuid NOT NULL REFERENCES users (id),
    number       integer NOT NULL,
    world_id     uuid NOT NULL,
    possessed_id uuid,
    tone         text NOT NULL,
    scale        text NOT NULL,
    setup        text NOT NULL,
    status       text NOT NULL CHECK (status IN ('open', 'ended')),
    started_at   timestamptz NOT NULL DEFAULT now(),
    ended_at     timestamptz,
    UNIQUE (user_id, id),
    UNIQUE (user_id, number),
    FOREIGN KEY (user_id, world_id) REFERENCES worlds (user_id, id),
    FOREIGN KEY (user_id, possessed_id) REFERENCES characters (user_id, id)
);

CREATE TABLE scene_characters (
    user_id      uuid NOT NULL,
    scene_id     uuid NOT NULL,
    character_id uuid NOT NULL,
    PRIMARY KEY (scene_id, character_id),
    FOREIGN KEY (user_id, scene_id) REFERENCES scenes (user_id, id),
    FOREIGN KEY (user_id, character_id) REFERENCES characters (user_id, id)
);

-- 一场戏里发生的事：只追加，写入后不改。
CREATE TABLE scene_events (
    id         bigserial PRIMARY KEY,
    user_id    uuid NOT NULL,
    scene_id   uuid NOT NULL,
    seq        integer NOT NULL,
    round      integer NOT NULL,
    kind       text NOT NULL CHECK (kind IN ('narration', 'user', 'character', 'failure', 'resolved')),
    speaker_id uuid,
    action     text,
    text       text NOT NULL DEFAULT '',
    beats      jsonb NOT NULL DEFAULT '[]',
    inner_voice text,
    silent     boolean NOT NULL DEFAULT false,
    step       text,
    ref_seq    integer,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (scene_id, seq),
    FOREIGN KEY (user_id, scene_id) REFERENCES scenes (user_id, id),
    FOREIGN KEY (user_id, speaker_id) REFERENCES characters (user_id, id)
);

-- 回想是整理产物，可以重新生成；原始经历永远在 scene_events。
CREATE TABLE recollections (
    id           uuid PRIMARY KEY,
    user_id      uuid NOT NULL,
    character_id uuid NOT NULL,
    scene_id     uuid NOT NULL,
    status       text NOT NULL CHECK (status IN ('done', 'failed')),
    memory       text,
    views        jsonb NOT NULL DEFAULT '{}',
    error        text,
    created_at   timestamptz NOT NULL DEFAULT now(),
    UNIQUE (character_id, scene_id),
    FOREIGN KEY (user_id, character_id) REFERENCES characters (user_id, id),
    FOREIGN KEY (user_id, scene_id) REFERENCES scenes (user_id, id)
);

CREATE TABLE model_calls (
    id               bigserial PRIMARY KEY,
    user_id          uuid NOT NULL REFERENCES users (id),
    scene_id         uuid,
    kind             text NOT NULL,
    subject          text,
    model            text NOT NULL,
    reasoning        text,
    provider         text,
    outcome          text NOT NULL,
    error            text,
    cost             numeric(12, 8),
    latency_ms       integer,
    first_token_ms   integer,
    prompt_tokens    integer,
    completion_tokens integer,
    reasoning_tokens integer,
    finish_reason    text,
    created_at       timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX scene_events_scene ON scene_events (scene_id, seq);
CREATE INDEX model_calls_user ON model_calls (user_id, created_at);
