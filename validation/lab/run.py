"""跑盲测集。用法见 validation/lab/README.txt。"""

import argparse
import hashlib
import json
import random
import sys
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path

import dataset
import render
from models import FakeModel, OpenRouterClient
from scene import Ledger, SceneResult, SceneRun

LAB_DIR = Path(__file__).resolve().parent
CODES = "甲乙丙丁戊己庚辛壬癸"


@dataclass
class Unit:
    code: str
    model_id: str
    scene: str
    scale: str
    repeat: int
    result: SceneResult | None = None
    status: str = "not_run"
    reason: str | None = None

    def to_dict(self) -> dict:
        base = {"code": self.code, "scene": self.scene, "scale": self.scale, "repeat": self.repeat}
        if self.result:
            return base | self.result.to_dict()
        return base | {"status": self.status, "reason": self.reason, "events": [], "calls": []}


def parse_args(argv):
    p = argparse.ArgumentParser(description="lyng 核心体验盲测原型")
    p.add_argument("--model", action="append", required=True, help="OpenRouter 模型 ID，可重复；用 --fake 时只作标签")
    p.add_argument("--adult-model", action="append", default=[], help="支持成人向的模型 ID（须同时出现在 --model 中）")
    p.add_argument("--scenes", default="all", help="逗号分隔的场景编号，默认全部")
    p.add_argument("--repeats", type=int, default=2, help="每个模型每个场景跑几次，默认 2")
    p.add_argument("--budget", type=float, help="本次运行的费用硬上限（美元）；真实调用时必填")
    p.add_argument("--max-tokens", type=int, default=2000, help="每次调用的输出上限")
    p.add_argument("--backoff", type=float, default=10.0, help="429 / 5xx 后重试前等待的秒数")
    p.add_argument("--fake", action="store_true", help="使用确定性模型替身，不发任何网络请求")
    p.add_argument("--seed", type=int, help="代号分配的随机种子；默认随机")
    p.add_argument("--out", type=Path, default=LAB_DIR / "runs", help="输出根目录")
    args = p.parse_args(argv)
    if len(set(args.model)) != len(args.model):
        p.error("--model 有重复")
    if len(args.model) > len(CODES):
        p.error(f"最多 {len(CODES)} 个模型")
    if not set(args.adult_model) <= set(args.model):
        p.error("--adult-model 必须同时出现在 --model 中")
    if args.budget is None:
        if not args.fake:
            p.error("真实调用必须用 --budget 指定费用上限")
        args.budget = 1.0
    if args.repeats < 1:
        p.error("--repeats 至少为 1")
    return args


def plan_units(ds: dataset.Dataset, scene_ids: list[str], models: dict[str, str], adult: set[str], repeats: int) -> list[Unit]:
    """按“次 → 场景 → 尺度 → 模型”交错排列，预算提前用完时各模型的完成度相近。"""
    units = []
    for repeat in range(1, repeats + 1):
        for sid in scene_ids:
            for scale in ds.scenes[sid].scales:
                for code, model_id in models.items():
                    if scale == "成人向" and model_id not in adult:
                        continue
                    units.append(Unit(code, model_id, sid, scale, repeat))
    return units


def run_units(ds: dataset.Dataset, units: list[Unit], clients: dict[str, object], ledger: Ledger, max_tokens: int,
              backoff: float = 10.0, sleep=None) -> str | None:
    done: dict[tuple, Unit] = {}
    stop_reason = None
    for u in units:
        if stop_reason:
            u.reason = f"运行已停止：{stop_reason}"
            continue
        scene = ds.scenes[u.scene]
        previous = None
        if scene.previous:
            prev_scene = ds.scenes[scene.previous]
            prev_scale = u.scale if u.scale in prev_scene.scales else prev_scene.scales[0]
            prev = done.get((u.code, u.repeat, scene.previous, prev_scale))
            if prev is None or prev.status != "completed":
                u.reason = f"依赖的 {scene.previous} 同次运行未完成"
                continue
            previous = prev.result.events
        u.result = SceneRun(ds, scene, u.scale, clients[u.code], ledger, max_tokens, previous, backoff, sleep).run()
        u.status, u.reason = u.result.status, u.result.reason
        done[(u.code, u.repeat, u.scene, u.scale)] = u
        print(f"[{u.code}] {u.scene}·{u.scale}·第{u.repeat}次：{u.status}  累计 {ledger.spent:.6f} 美元", file=sys.stderr)
        if u.status == "stopped":
            stop_reason = u.reason
    return stop_reason


def stats(units: list[Unit]) -> dict:
    calls = [c for u in units if u.result for c in u.result.calls]
    outcomes = [c.outcome for c in calls]
    return {
        "calls": len(calls),
        "first_attempt_ok": outcomes.count("ok"),
        "first_attempt_failed": sum(1 for c in calls if c.attempts and "error" in c.attempts[0]),
        "recovered": outcomes.count("recovered"),
        "final_failed": outcomes.count("failed"),
        "refused": outcomes.count("refused"),
        "stopped": outcomes.count("stopped"),
        "units": {s: sum(1 for u in units if u.status == s) for s in ("completed", "failed", "refused", "stopped", "not_run")},
    }


def main(argv=None) -> int:
    args = parse_args(argv)
    ds = dataset.load()
    scene_ids = list(ds.scenes) if args.scenes == "all" else [s.strip() for s in args.scenes.split(",")]
    if unknown := [s for s in scene_ids if s not in ds.scenes]:
        raise SystemExit(f"未知场景：{unknown}")
    for sid in scene_ids:
        prev = ds.scenes[sid].previous
        if prev and (prev not in scene_ids or scene_ids.index(prev) > scene_ids.index(sid)):
            raise SystemExit(f"{sid} 依赖 {prev}，须同时选择并排在它之前")

    shuffled = list(args.model)
    random.Random(args.seed).shuffle(shuffled)
    models = dict(zip(CODES, shuffled))
    if args.fake:
        clients = {code: FakeModel(mid) for code, mid in models.items()}
    else:
        clients = {code: OpenRouterClient(mid) for code, mid in models.items()}

    ledger = Ledger(args.budget)
    units = plan_units(ds, scene_ids, models, set(args.adult_model), args.repeats)
    started = datetime.now().astimezone()
    run_id = started.strftime("%Y%m%d-%H%M%S") + ("-fake" if args.fake else "")
    stop_reason = run_units(ds, units, clients, ledger, args.max_tokens, args.backoff)

    out = args.out / run_id
    out.mkdir(parents=True, exist_ok=False)
    record = {
        "run_id": run_id,
        "started_at": started.isoformat(timespec="seconds"),
        "finished_at": datetime.now().astimezone().isoformat(timespec="seconds"),
        "fake": args.fake,
        "dataset": {"version": "v1", "sha256": ds.digest},
        "prompt_sha256": hashlib.sha256((LAB_DIR / "prompts.py").read_bytes()).hexdigest(),
        "settings": {"scenes": scene_ids, "repeats": args.repeats, "max_tokens": args.max_tokens, "backoff": args.backoff},
        "codes": list(models),
        "adult_codes": [c for c, m in models.items() if m in args.adult_model],
        "ledger": {"budget": args.budget, "spent": round(ledger.spent, 8), "stop_reason": stop_reason},
        "stats": stats(units),
        "units": [u.to_dict() for u in units],
    }
    (out / "record.json").write_text(json.dumps(record, ensure_ascii=False, indent=2), encoding="utf-8")
    (out / "blind.html").write_text(render.blind_html(ds, record), encoding="utf-8")
    key = "模型代号对照（盲评结束前不要打开）\n\n" + "".join(f"{c}\t{m}\n" for c, m in models.items())
    (out / "key.txt").write_text(key, encoding="utf-8")
    print(f"输出：{out}", file=sys.stderr)
    return 1 if stop_reason else 0


if __name__ == "__main__":
    sys.exit(main())
