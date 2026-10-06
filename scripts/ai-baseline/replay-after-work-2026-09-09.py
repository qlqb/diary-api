#!/usr/bin/env python3
"""
"근무 후 이동" 재현 — 실제 LLM 호출로 모델이 AFTER 기준을 내고 서버가 저장된 근무를
쓰는지 본다. 2026-09-09 handoff의 §9 확인용.

replay-2026-09-08.py의 HTTP/SSE 헬퍼를 그대로 쓴다(중복 구현하지 않는다).

세 시나리오. 각각 새 대화이고, 합성 계정·합성 근무만 쓴다:
  new     새 대화 한 문장: "이번 주 모든 근무 후에 이동시간 블록 1시간짜리 만들어줘.
          일회성으로. 근무시간은 서버에 있는 거 사용해."
  follow  진행 중 요청에 이어서: 모호한 첫 발화 뒤 "근무 후 1시간이야. 이번 주 일회성으로 만들어줘."
  before  앞 이동 회귀: "이번 주 모든 근무 전에 이동 1시간씩 만들어줘."

관찰: 후보 개수와 시각, 근무 종료 직후인지, 불필요한 재질문이 있었는지.
--apply를 주면 만들어진 후보를 실제로 적용해 파생 컬럼까지 확인한다(합성 데이터 한정).

사용 예:
  python scripts/ai-baseline/replay-after-work-2026-09-09.py --seed --apply
  python scripts/ai-baseline/replay-after-work-2026-09-09.py --seed --scenario new

메시지 1개당 OpenAI 호출 1회라는 서버 보장을 깨지 않는다 — 재시도하지 않는다.
"""
import argparse
import importlib.util
import io
import json
import os
import sys
import time
from datetime import date, datetime, timedelta

sys.stdout.reconfigure(encoding="utf-8")

_HERE = os.path.dirname(os.path.abspath(__file__))
_spec = importlib.util.spec_from_file_location(
    "replay_base", os.path.join(_HERE, "replay-2026-09-08.py"))
base_mod = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(base_mod)

json_call = base_mod.json_call
send_turn = base_mod.send_turn


SCENARIOS = {
    "new": [
        "이번 주 모든 근무 후에 이동시간 블록 1시간짜리 만들어줘. 일회성으로. 근무시간은 서버에 있는 거 사용해.",
    ],
    "follow": [
        "근무 끝나고 나서 이동하는 시간이 좀 필요해",
        "근무 후 1시간이야. 이번 주 일회성으로 만들어줘.",
    ],
    "before": [
        "이번 주 모든 근무 전에 이동 1시간씩 만들어줘.",
    ],
}


def seed(base_url):
    """합성 계정 + 이번 주 근무 2건. 운영 사용자의 일정은 건드리지 않는다."""
    stamp = int(time.time())
    email = f"after-work-{stamp}@example.com"
    password = "after-work-1234"
    json_call(base_url, "POST", "/api/auth/signup", None,
              {"email": email, "password": password, "nickname": f"aw{stamp}"})
    token = json_call(base_url, "POST", "/api/auth/login", None,
                      {"email": email, "password": password})["token"]

    today = date.today()
    shifts = []
    for offset, (start, end) in enumerate([("18:00", "23:00"), ("17:00", "22:00")]):
        day = (today + timedelta(days=offset * 2)).isoformat()
        created = json_call(base_url, "POST", "/api/commitments", token,
                            {"title": "근무", "startAt": f"{day}T{start}", "endAt": f"{day}T{end}"})
        shifts.append({"commitmentId": created["commitmentId"],
                       "startAt": created["startAt"], "endAt": created["endAt"]})
    print(f"[seed] user={email}", file=sys.stderr)
    for s in shifts:
        print(f"[seed] 근무 #{s['commitmentId']}: {s['startAt']} → {s['endAt']}", file=sys.stderr)
    return token, shifts


def expected_after(shift, minutes=60):
    end = datetime.fromisoformat(shift["endAt"])
    return end.isoformat(timespec="minutes"), (end + timedelta(minutes=minutes)).isoformat(timespec="minutes")


def expected_before(shift, minutes=60):
    start = datetime.fromisoformat(shift["startAt"])
    return (start - timedelta(minutes=minutes)).isoformat(timespec="minutes"), start.isoformat(timespec="minutes")


def run_scenario(base_url, token, name, shifts, do_apply):
    conversation = json_call(base_url, "POST", "/api/ai/conversations", token, {})
    cid = conversation["conversationId"]
    print(f"\n=== 시나리오 {name} (conversationId={cid}) ===")

    turns = []
    for i, message in enumerate(SCENARIOS[name]):
        idem = f"aw-{name}-{cid}-{i}-{int(time.time() * 1000)}"
        print(f"  [{i + 1}] > {message}")
        turn = send_turn(base_url, token, cid, message, idem)
        turns.append(turn)
        reply = (turn["reply"] or "").replace("\n", " ")
        print(f"      < {reply[:220]}")
        if turn["error"]:
            print(f"      !! {turn['error']}")
        for s in turn["scheduleSuggestions"]:
            payload = s.get("payload") or {}
            derived = payload.get("derivedFrom") or {}
            print(f"      후보 #{s.get('suggestionId')} {payload.get('title')} "
                  f"{payload.get('startAt')} ~ {payload.get('endAt')} "
                  f"derivedFrom={derived or '(없음)'}")

    suggestions = [s for t in turns for s in t["scheduleSuggestions"]]
    asked = sum(1 for t in turns if "?" in (t["reply"] or ""))

    # ---- 판정 ----
    want = []
    for shift in shifts:
        if name == "before":
            want.append(expected_before(shift))
        else:
            want.append(expected_after(shift))
    got = sorted((( (s.get("payload") or {}).get("startAt"), (s.get("payload") or {}).get("endAt"))
                  for s in suggestions))
    want_sorted = sorted(want)

    def norm(pair):
        return tuple(datetime.fromisoformat(x).isoformat(timespec="minutes") if x else x for x in pair)

    got_norm = sorted(norm(p) for p in got)
    ok_times = got_norm == want_sorted
    ok_count = len(suggestions) == len(shifts)
    print(f"  기대 {want_sorted}")
    print(f"  실제 {got_norm}")
    print(f"  후보 {len(suggestions)}건 / 질문 {asked}회 / 시각일치={ok_times}")

    applied = None
    if do_apply and suggestions:
        applied = []
        for s in suggestions:
            sid = s.get("suggestionId")
            try:
                res = json_call(base_url, "POST", f"/api/ai/schedule-suggestions/{sid}/apply", token, {})
                applied.append({"suggestionId": sid, "commitmentId": res.get("commitmentId"), "status": "APPLIED"})
                print(f"  적용 #{sid} → OK")
            except Exception as e:  # noqa: BLE001 - 재현 스크립트라 사유를 그대로 남긴다
                applied.append({"suggestionId": sid, "status": f"FAILED {e}"})
                print(f"  적용 #{sid} → {e}")

    return {
        "scenario": name, "conversationId": cid, "turns": len(turns), "asks": asked,
        "suggestions": len(suggestions), "expected": want_sorted, "actual": got_norm,
        "timesMatch": ok_times, "countMatch": ok_count, "applied": applied,
        "replies": [(t["reply"] or "")[:400] for t in turns],
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base-url", default="http://localhost:8081")
    ap.add_argument("--token")
    ap.add_argument("--seed", action="store_true", help="합성 계정과 이번 주 근무 2건을 만든다")
    ap.add_argument("--scenario", choices=sorted(SCENARIOS) + ["all"], default="all")
    ap.add_argument("--apply", action="store_true", help="만들어진 후보를 실제로 적용해 본다(합성 데이터 한정)")
    ap.add_argument("--out", help="결과 JSON을 이 파일에 남긴다")
    args = ap.parse_args()

    if args.seed:
        token, shifts = seed(args.base_url)
    elif args.token:
        token = args.token
        shifts = []
        print("--token만 주면 기대 시각을 계산할 근무를 알 수 없다. --seed를 권장한다.", file=sys.stderr)
    else:
        print("--seed 또는 --token이 필요하다.", file=sys.stderr)
        return 2

    names = sorted(SCENARIOS) if args.scenario == "all" else [args.scenario]
    results = []
    for name in names:
        # 시나리오마다 새 합성 계정을 쓴다 — 앞 시나리오가 만든 이동이 뒤 시나리오의 기존 항목이 되면
        # "중복 방지"와 "생성 실패"를 구분할 수 없다.
        if args.seed and results:
            token, shifts = seed(args.base_url)
        results.append(run_scenario(args.base_url, token, name, shifts, args.apply))

    print("\n=== 요약 ===")
    for r in results:
        mark = "PASS" if (r["timesMatch"] and r["countMatch"]) else "FAIL"
        print(f"  {mark}  {r['scenario']}: 후보 {r['suggestions']}건, 질문 {r['asks']}회")
    if args.out:
        with io.open(args.out, "w", encoding="utf-8") as f:
            json.dump(results, f, ensure_ascii=False, indent=2)
    return 0 if all(r["timesMatch"] and r["countMatch"] for r in results) else 1


if __name__ == "__main__":
    sys.exit(main())
