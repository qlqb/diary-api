#!/usr/bin/env python3
"""
2026-09-08 00:54~01:06 로그의 대화("수업 전 이동시간 블록 반복 + 근무 전 이동 블록 1회성")를
실제 서버에 다시 흘려 넣고, 턴별 응답 종류를 표로 남긴다.

왜 파이썬인가: 저장소 스크립트는 .sh 하나뿐이지만, 이 머신(Windows Git Bash)에서는 셸이
한글을 CP949로 넘겨 서버가 `Invalid UTF-8 start byte`로 500을 낸다. 요청 본문이 전부
한글인 이 스크립트는 UTF-8을 명시하는 파이썬으로 두는 편이 재현이 된다.

두 모드:
  baseline  — 13개 발화를 서버 응답과 무관하게 순서대로 보낸다(Stage A 베이스라인).
  gate      — 첫 발화만 고정하고, 이후는 서버 질문을 보고 자동 응답한다(Stage B-9 게이트):
              "첫 수업/모든 수업/수업 전마다"를 물으면 "첫 수업만", 종료일을 물으면
              "12월 11일", 그 외는 "응". 일정 후보가 내려오고 더 물을 것이 없으면 멈춘다.

사용 예:
  python scripts/ai-baseline/replay-2026-09-08.py --email me@example.com --password '****'
  python scripts/ai-baseline/replay-2026-09-08.py --token "$JWT" --course-id 31 --runs 3
  python scripts/ai-baseline/replay-2026-09-08.py --seed --mode gate      # 새 사용자+시간표 픽스처 생성 후 실행

--seed는 새 계정(ai-baseline-<epoch>@example.com)을 만들고 로그와 같은 모양의 시간표
(화 14:00 / 수·목·금 10:00 첫 수업, 종강 12/11)와 이번 주 근무 4건을 넣는다. 실사용자
계정으로 돌리려면 --email/--password 또는 --token을 준다.

표준 라이브러리만 쓴다(requests 없음). 재시도는 하지 않는다 — 사용자 메시지 1개당
OpenAI 호출 1회라는 서버 보장을 스크립트가 깨면 안 된다.
"""
import argparse
import codecs
import io
import json
import re
import sys
import time
import urllib.error
import urllib.request
from datetime import date, datetime, timedelta

UTTERANCES = [
    "수업 시간 전에 1시간 이동시간 블록 반복일정으로 만들어주고 근무 전에는 1시간 이동 블록 1회성으로 만들어줘",
    "응",
    "매일 수업 첫시간에만",
    "매주반복일정으로 만들어줘 이번학기동안",
    "12월11일까지",
    "그냥 루틴만 만들어줘",
    "지금부터",
    "매일 첫수업 1시간전",
    "지금부터",
    "아니다 수업 시작 시각에 맞춰 자동으로 예약되게",
    "수업 있는 날에만",
    "1시간동안 이동시간 블록 만들어줘",
    "2026년 12월 11일",
]

GATE_MAX_TURNS = 8


def http(base, method, path, token=None, body=None, stream=False):
    data = None
    headers = {"Accept": "text/event-stream" if stream else "application/json"}
    if body is not None:
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
        headers["Content-Type"] = "application/json; charset=utf-8"
    if token:
        headers["Authorization"] = "Bearer " + token
    req = urllib.request.Request(base + path, data=data, method=method, headers=headers)
    return urllib.request.urlopen(req, timeout=180)


def json_call(base, method, path, token=None, body=None):
    try:
        with http(base, method, path, token, body) as resp:
            raw = resp.read().decode("utf-8")
            return json.loads(raw) if raw else None
    except urllib.error.HTTPError as e:
        detail = e.read().decode("utf-8", errors="replace")
        raise SystemExit(f"{method} {path} -> {e.code}: {detail[:300]}")


def send_turn(base, token, conversation_id, message, idem):
    """SSE 프레임(event:/data:)을 읽어 턴 결과 하나로 접는다."""
    body = {"message": message, "requestedAction": "AUTO", "idempotencyKey": idem}
    result = {"responseType": None, "reply": "", "offer": None, "scheduleSuggestions": [],
              "proposalItems": 0, "quickReplies": [], "error": None, "events": []}
    try:
        resp = http(base, "POST", f"/api/ai/conversations/{conversation_id}/messages", token, body, stream=True)
    except urllib.error.HTTPError as e:
        result["error"] = f"HTTP {e.code} {e.read().decode('utf-8', errors='replace')[:200]}"
        return result
    # 바이트를 하나씩 읽되 디코딩은 증분 디코더에 맡긴다 — 한글 한 글자는 3바이트라 바이트마다
    # decode하면 전부 깨진다(첫 실행에서 실제로 그렇게 됐다).
    decoder = codecs.getincrementaldecoder("utf-8")(errors="replace")
    buffer = ""
    with resp:
        while True:
            chunk = resp.read(1)
            if not chunk:
                buffer += decoder.decode(b"", final=True)
                break
            buffer += decoder.decode(chunk)
            while "\n\n" in buffer:
                frame, buffer = buffer.split("\n\n", 1)
                name, payload = parse_frame(frame)
                if name is None:
                    continue
                result["events"].append(name)
                if name == "message.completed":
                    result["responseType"] = payload.get("responseType")
                    result["reply"] = payload.get("reply") or result["reply"]
                    result["offer"] = payload.get("offerAction")
                    result["proposalItems"] = len(payload.get("proposalItems") or [])
                    result["quickReplies"] = payload.get("quickReplies") or []
                elif name == "message.delta":
                    result["reply"] += payload.get("text") or ""
                elif name == "offer.ready":
                    result["offer"] = payload.get("offerAction")
                elif name == "schedule.suggestions.ready":
                    result["scheduleSuggestions"].extend(payload.get("suggestions") or [])
                elif name == "message.error":
                    result["error"] = f"{payload.get('code')} {payload.get('message')}"
    return result


def parse_frame(frame):
    name = "message"
    data_lines = []
    for line in frame.split("\n"):
        if line.startswith("event:"):
            name = line[len("event:"):].strip()
        elif line.startswith("data:"):
            data_lines.append(line[len("data:"):].strip())
    if not data_lines:
        return None, None
    raw = "\n".join(data_lines)
    try:
        return name, json.loads(raw)
    except json.JSONDecodeError:
        return name, {"text": raw}


def classify(turn):
    """표에 적을 한 단어. ASK는 서버가 CHAT으로 내려주므로 물음표로 본다."""
    if turn["error"]:
        return "ERROR"
    if turn["scheduleSuggestions"]:
        return "PROPOSAL"
    if turn["responseType"] == "PROPOSAL":
        return "PROPOSAL"
    if turn["responseType"] == "OFFER" or turn["offer"]:
        return "OFFER"
    if "?" in (turn["reply"] or "") or turn["quickReplies"]:
        return "ASK"
    return "CHAT"


def auto_reply(reply):
    text = reply or ""
    if re.search(r"첫\s*수업|모든\s*수업|수업\s*전마다|매\s*수업", text):
        return "첫 수업만"
    if re.search(r"종료일|언제까지|종강|끝나는|마지막", text):
        return "12월 11일"
    return "응"


def run_once(base, token, course_id, mode, run_no):
    conv = json_call(base, "POST", "/api/ai/conversations", token,
                     {"scope": "TODAY", "courseId": course_id})
    cid = conv["conversationId"]
    turns = []
    if mode == "baseline":
        for i, text in enumerate(UTTERANCES):
            turn = send_turn(base, token, cid, text, f"replay-{run_no}-{i}-{int(time.time() * 1000)}")
            turn["user"] = text
            turns.append(turn)
            if turn["error"]:
                break
    else:
        text = UTTERANCES[0]
        for i in range(GATE_MAX_TURNS):
            turn = send_turn(base, token, cid, text, f"gate-{run_no}-{i}-{int(time.time() * 1000)}")
            turn["user"] = text
            turns.append(turn)
            if turn["error"]:
                break
            kind = classify(turn)
            if kind == "PROPOSAL" and "?" not in (turn["reply"] or ""):
                break
            text = auto_reply(turn["reply"])
    return cid, turns


def summarize(run_no, cid, turns):
    kinds = [classify(t) for t in turns]
    asks = sum(1 for k in kinds if k == "ASK")
    first_offer = next((i + 1 for i, k in enumerate(kinds) if k in ("OFFER", "PROPOSAL")), None)
    last = (turns[-1]["reply"] or turns[-1]["error"] or "").replace("\n", " ").strip()[:60] if turns else ""
    routine = sum(1 for t in turns for s in t["scheduleSuggestions"] if s.get("kind") == "ROUTINE")
    commitment = sum(1 for t in turns for s in t["scheduleSuggestions"] if s.get("kind") == "COMMITMENT")
    return {
        "run": run_no, "conversationId": cid, "turns": len(turns), "asks": asks,
        "firstOfferTurn": first_offer, "last": last, "routine": routine, "commitment": commitment,
        "kinds": kinds,
    }


def seed_fixture(base):
    """새 계정 + 로그와 같은 모양의 시간표/근무. 실사용자 데이터는 건드리지 않는다."""
    stamp = int(time.time())
    email = f"ai-baseline-{stamp}@example.com"
    password = "baseline-1234"
    json_call(base, "POST", "/api/auth/signup", None,
              {"email": email, "password": password, "nickname": f"baseline{stamp}"})
    token = json_call(base, "POST", "/api/auth/login", None, {"email": email, "password": password})["token"]
    today = date.today()
    semester_from = (today - timedelta(days=14)).isoformat()
    semester_until = f"{today.year}-12-11"
    courses = [
        ("자료구조", ["TUESDAY"], "14:00", "17:00"),
        ("웹서버프로그래밍", ["WEDNESDAY"], "10:00", "13:00"),
        ("빅데이터분석", ["THURSDAY"], "10:00", "13:00"),
        ("네트워크프로그래밍", ["FRIDAY"], "10:00", "13:00"),
        ("스마트앱프로젝트", ["FRIDAY"], "14:00", "17:00"),
    ]
    first_course_id = None
    for title, days, start, end in courses:
        course = json_call(base, "POST", "/api/courses", token, {"title": title})
        first_course_id = first_course_id or course["courseId"]
        json_call(base, "POST", "/api/routines", token, {
            "courseId": course["courseId"], "title": title, "daysOfWeek": days,
            "startTime": start, "endTime": end,
            "effectiveFrom": semester_from, "effectiveUntil": semester_until,
        })
    for offset, (start, end) in enumerate([("18:00", "23:00"), ("18:00", "23:00"), ("17:00", "22:00"), ("18:00", "23:00")]):
        day = (today + timedelta(days=offset)).isoformat()
        json_call(base, "POST", "/api/commitments", token,
                  {"title": "근무", "startAt": f"{day}T{start}", "endAt": f"{day}T{end}"})
    print(f"seeded user={email} courseId={first_course_id}", file=sys.stderr)
    return token, first_course_id


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base-url", default="http://localhost:8081")
    ap.add_argument("--token")
    ap.add_argument("--email")
    ap.add_argument("--password")
    ap.add_argument("--course-id", type=int)
    ap.add_argument("--runs", type=int, default=3)
    ap.add_argument("--mode", choices=["baseline", "gate"], default="baseline")
    ap.add_argument("--seed", action="store_true", help="새 사용자와 시간표 픽스처를 만들고 그 계정으로 돌린다")
    ap.add_argument("--out", help="턴별 원본 결과(JSON)를 이 파일에 남긴다")
    args = ap.parse_args()

    token = args.token
    course_id = args.course_id
    if args.seed:
        token, seeded_course = seed_fixture(args.base_url)
        course_id = course_id or seeded_course
    elif not token:
        if not (args.email and args.password):
            ap.error("--token 또는 --email/--password 또는 --seed 중 하나가 필요하다")
        token = json_call(args.base_url, "POST", "/api/auth/login", None,
                          {"email": args.email, "password": args.password})["token"]

    out = io.open(sys.stdout.fileno(), "w", encoding="utf-8", closefd=False)
    summaries = []
    raw_runs = []
    for run_no in range(1, args.runs + 1):
        cid, turns = run_once(args.base_url, token, course_id, args.mode, run_no)
        summaries.append(summarize(run_no, cid, turns))
        raw_runs.append({"conversationId": cid, "turns": turns})
        for i, t in enumerate(turns, 1):
            out.write(f"[run {run_no} turn {i}] {classify(t):8} 사용자: {t['user']}\n")
            out.write(f"      AI: {(t['reply'] or t['error'] or '').replace(chr(10), ' ')[:160]}\n")
            if t["scheduleSuggestions"]:
                for s in t["scheduleSuggestions"]:
                    p = s.get("payload") or {}
                    out.write(f"      후보 {s.get('kind')}: {p.get('title')} "
                              f"{p.get('daysOfWeek') or ''} {p.get('startTime') or p.get('startAt')}"
                              f"~{p.get('endTime') or p.get('endAt')}\n")
        out.flush()

    out.write("\n| run | 턴 수 | ASK 수 | OFFER/PROPOSAL 첫 등장 턴 | ROUTINE 후보 | COMMITMENT 후보 | 마지막 응답 요지 |\n")
    out.write("|---|---|---|---|---|---|---|\n")
    for s in summaries:
        out.write(f"| {s['run']} | {s['turns']} | {s['asks']} | {s['firstOfferTurn'] or '-'} | "
                  f"{s['routine']} | {s['commitment']} | {s['last']} |\n")
    out.write("\n턴별 분류: " + json.dumps([s["kinds"] for s in summaries], ensure_ascii=False) + "\n")
    out.flush()

    if args.out:
        with io.open(args.out, "w", encoding="utf-8") as f:
            json.dump({"mode": args.mode, "at": datetime.now().isoformat(), "runs": raw_runs},
                      f, ensure_ascii=False, indent=2)


if __name__ == "__main__":
    main()
