#!/usr/bin/env python3
"""
상담 도구 호출·변경 묶음(계획 docs/handoff/consult-tools-plan-2026-10-25.md) S6 — 실제 서버·실제 모델 시나리오.

합성 계정·합성 일정만 쓴다. 격리 DB(memo_tools_verify)에 붙은 서버(8098)에만 돌린다. 비밀번호는 출력하지 않는다.

  A. 근무 빼기: "오늘 쿠팡알바 근무 빼줘" → 회차 제외 제안(묶음), 적용 전 원본 그대로
  B. 그 대화에서 공부 시간: "그럼 오늘 2시부터 8시까지 공부 잡아줘" → 초안이 묶음에 기대고(근무 빠진 가정) 14~20시 안, 쉬는 시간 따로
  C. 확정 → 묶음과 계획이 함께 적용, 회차 예외 1건. 같은 묶음 재적용(새 키) 거절, 예외 1건 그대로
  D. 근무 유지 + 시간 요청: 근무가 있는 내일 "내일 2시부터 8시까지 공부" → 근무와 겹친다고 알리거나 묻는다(조용히 바꾸지 않음)
  E. 말한 시간 + 식사: "오늘 2시부터 8시까지 공부할 건데 6시부터 7시는 저녁 먹어" → 18~19시 비움
  F. 이미 지난 시작: 지금보다 이른 시각부터 → 지난 부분을 알리거나 지금부터
  G. 자정 넘김: "오늘 밤 11시부터 새벽 1시까지 공부" → 23~01시, 날짜 넘김
  H. 부분 거절·검토 뒤 변경·중복 적용: 근무 옮기기 묶음 → 원본을 손으로 바꾼 뒤 적용 거절 / 같은 키 두 번 → 같은 결과
  I. 완료·실제 시간: "자료구조 복습 끝냈어, 50분 했어" → 완료 제안(50분)
  J. 과제 마감 정정: "운영체제 과제 마감 금요일로 바뀌었어" → 마감 변경 제안

사용:
  python scripts/ai-baseline/verify-consult-tools-2026-10-26.py --base http://localhost:8098 --out build/verify-consult-tools.json
"""
import argparse
import codecs
import datetime
import io
import json
import os
import pathlib
import secrets
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid

OUT = io.open(sys.stdout.fileno(), "w", encoding="utf-8", closefd=False)
ROOT = pathlib.Path(__file__).resolve().parents[2]
REPORT = {"scenarios": [], "turns": []}
BASE = "http://localhost:8098"
DB = "memo_tools_verify"


def log(msg=""):
    OUT.write(msg + "\n")
    OUT.flush()


def scenario(name, said, expected, actual, ok):
    REPORT["scenarios"].append({"name": name, "input": said, "expected": expected, "actual": actual, "ok": ok})
    log(("PASS " if ok else "FAIL ") + name + " :: " + actual[:600])


def call(method, path, token=None, body=None, timeout=400):
    headers = {}
    data = None
    if body is not None:
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
        headers["Content-Type"] = "application/json; charset=utf-8"
    if token:
        headers["Authorization"] = "Bearer " + token
    req = urllib.request.Request(BASE + "/api" + path, data=data, method=method, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            text = resp.read().decode("utf-8")
            return resp.status, (json.loads(text) if text else None)
    except urllib.error.HTTPError as e:
        text = e.read().decode("utf-8", errors="replace")
        try:
            return e.code, json.loads(text)
        except Exception:
            return e.code, {"raw": text}


def parse_frame(frame):
    name, lines = "message", []
    for line in frame.split("\n"):
        if line.startswith("event:"):
            name = line[6:].strip()
        elif line.startswith("data:"):
            lines.append(line[5:].strip())
    if not lines:
        return None, None
    try:
        return name, json.loads("\n".join(lines))
    except json.JSONDecodeError:
        return None, None


def turn(token, cid, message=None, action="AUTO", period_plan=None, source_message_id=None):
    body = {"requestedAction": action, "idempotencyKey": str(uuid.uuid4())}
    if message is not None:
        body["message"] = message
    if period_plan is not None:
        body["periodPlan"] = period_plan
    if source_message_id is not None:
        body["sourceMessageId"] = source_message_id
    r = {"action": action, "message": message, "responseType": None, "reply": "", "offer": None, "error": None,
         "draft": None, "userMessageId": None, "bundle": None, "progress": [], "question": None}
    started = time.time()
    req = urllib.request.Request(BASE + "/api/ai/conversations/%s/messages" % cid,
                                 data=json.dumps(body, ensure_ascii=False).encode("utf-8"), method="POST",
                                 headers={"Accept": "text/event-stream", "Content-Type": "application/json; charset=utf-8",
                                          "Authorization": "Bearer " + token})
    try:
        resp = urllib.request.urlopen(req, timeout=420)
    except urllib.error.HTTPError as e:
        r["error"] = "HTTP %s %s" % (e.code, e.read().decode("utf-8", errors="replace")[:300])
        return r
    decoder = codecs.getincrementaldecoder("utf-8")(errors="replace")
    buffer = ""
    with resp:
        while True:
            chunk = resp.read(1)
            if not chunk:
                break
            buffer += decoder.decode(chunk)
            while "\n\n" in buffer:
                frame, buffer = buffer.split("\n\n", 1)
                name, payload = parse_frame(frame)
                if name is None:
                    continue
                if name == "message.completed":
                    r["responseType"] = payload.get("responseType")
                    r["reply"] = payload.get("reply") or r["reply"]
                    r["offer"] = payload.get("offerAction") or r["offer"]
                    r["userMessageId"] = payload.get("userMessageId")
                    r["question"] = (payload.get("consult") or {}).get("question")
                    if payload.get("changeBundle"):
                        r["bundle"] = payload["changeBundle"]
                    if payload.get("periodPlanDraft"):
                        r["draft"] = payload["periodPlanDraft"]
                elif name == "message.delta":
                    r["reply"] += payload.get("text") or ""
                elif name == "offer.ready":
                    r["offer"] = payload.get("offerAction")
                elif name == "period_plan.ready":
                    r["draft"] = payload
                elif name == "tool.progress":
                    r["progress"].append(payload.get("label"))
                elif name == "change_bundle.ready":
                    r["bundle"] = payload
                elif name == "message.error":
                    r["error"] = "%s %s" % (payload.get("code"), payload.get("message"))
    r["seconds"] = round(time.time() - started, 1)
    if r["userMessageId"]:
        steps = mysql("SELECT kind, IFNULL(name,''), IFNULL(result_code,''), IFNULL(latency_ms,0) FROM consult_turn_steps "
                      "WHERE request_message_id=%d ORDER BY seq" % int(r["userMessageId"]))
        r["steps"] = [{"kind": k, "name": n, "code": c, "ms": int(ms)} for k, n, c, ms in steps]
        r["modelCalls"] = sum(1 for s in r["steps"] if s["kind"] == "MODEL")
        r["toolCalls"] = sum(1 for s in r["steps"] if s["kind"] == "TOOL")
    REPORT["turns"].append({k: v for k, v in r.items() if k != "draft"})
    log("--- [%s] %ss type=%s model=%s tool=%s bundle=%s error=%s" % (
        action, r["seconds"], r["responseType"], r.get("modelCalls"), r.get("toolCalls"),
        [(i.get("opType"), i.get("selected")) for i in (r["bundle"] or {}).get("items", [])], r["error"]))
    log("    나: " + (message or "(" + action + ")"))
    log("    AI: " + (r["reply"] or "")[:600].replace("\n", " / "))
    return r


def mysql(sql):
    props = {}
    for line in (ROOT / "src" / "main" / "resources" / "application-local.properties").read_text(encoding="utf-8").splitlines():
        if "=" in line and not line.strip().startswith("#"):
            k, v = line.split("=", 1)
            props[k.strip()] = v.strip()
    env = dict(os.environ, MYSQL_PWD=props["spring.datasource.password"])
    proc = subprocess.run([os.environ.get("MYSQL", "C:/Program Files/MySQL/MySQL Server 8.0/bin/mysql.exe"), "-u",
                           props["spring.datasource.username"], "--default-character-set=utf8mb4", "-N", "-B", "-r", DB],
                          input=sql.encode("utf-8"), capture_output=True, env=env)
    if proc.returncode != 0:
        raise RuntimeError(proc.stderr.decode("utf-8", errors="replace"))
    return [line.split("\t") for line in proc.stdout.decode("utf-8").splitlines() if line]


def conversation(token):
    status, conv = call("POST", "/ai/conversations", token, {"scope": "TODAY"})
    return conv["conversationId"]


def preview(token, draft):
    """초안 항목은 날짜만 있고 시각은 미리보기 배치가 붙인다 — 화면과 같은 미리보기 API로 시각을 읽는다."""
    pid = (draft or {}).get("proposalId")
    if not pid:
        return [], [], None
    st, res = call("POST", "/ai/proposals/%s/schedule-preview" % pid, token, {})
    if st != 200:
        return [], [], "preview %s %s" % (st, (res or {}).get("code"))
    placed = [(p.get("title"), p.get("scheduledStartAt"), p.get("scheduledEndAt"), p.get("proposalItemId"), p.get("placementType"),
               p.get("scheduledDate")) for p in (res.get("placedItems") or []) if p.get("scheduledStartAt")]
    unplaced = [(u.get("title"), u.get("reason")) for u in (res.get("unplacedItems") or [])]
    return placed, unplaced, None


def minutes_of(items):
    total = 0
    for _, s_, e_, *rest in items:
        a = datetime.datetime.fromisoformat(s_)
        b = datetime.datetime.fromisoformat(e_)
        total += int((b - a).total_seconds() // 60)
    return total


def hm(ts):
    return ts[11:16]


def make_plan(token, cid, last, today):
    offer = last.get("offer") or {}
    if not offer:
        nxt = turn(token, cid, "응 그대로 오늘 계획 만들어줘")
        offer = nxt.get("offer") or {}
        last = nxt
    period = {"periodStartDate": offer.get("periodStartDate") or today.isoformat(),
              "periodEndDate": offer.get("periodEndDate") or today.isoformat(),
              "intensity": offer.get("intensity") or "NORMAL"}
    if offer.get("courseIds"):
        period["courseIds"] = offer["courseIds"]
    made = turn(token, cid, None, "CREATE_PERIOD_PLAN", period, last.get("userMessageId"))
    return made


def within(items, day, start, end, holes=()):
    """모든 항목이 day의 start~end(끝이 다음 날이면 넘김) 안이고 holes와 겹치지 않는가."""
    bad = []
    for title, s, e, *_ in items:
        if s < start or e > end:
            bad.append("%s %s~%s 범위 밖" % (title, s[5:16], e[5:16]))
        for hs, he in holes:
            if s < he and e > hs:
                bad.append("%s %s~%s 제외 구간과 겹침" % (title, s[5:16], e[5:16]))
    return bad


def main():
    global BASE
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default=BASE)
    ap.add_argument("--out", default="build/verify-consult-tools.json")
    ap.add_argument("--only", default="")
    args = ap.parse_args()
    BASE = args.base.rstrip("/")
    only = set(args.only.split(",")) if args.only else None
    now = datetime.datetime.now()
    today = now.date()
    tomorrow = today + datetime.timedelta(days=1)
    REPORT["startedAt"] = now.isoformat()

    email = "tools-%s@memo-test.invalid" % uuid.uuid4().hex[:8]
    password = secrets.token_urlsafe(16)
    call("POST", "/auth/signup", body={"email": email, "password": password, "nickname": "도구검증"})
    status, body = call("POST", "/auth/login", body={"email": email, "password": password})
    token = body["token"]
    uid = int(mysql("SELECT user_id FROM users WHERE email='%s'" % email)[0][0])
    REPORT["account"] = email
    log("합성 계정 " + email + " user_id=" + str(uid))

    status, course = call("POST", "/courses", token, {"title": "자료구조"})
    course_id = course["courseId"]
    status, os_course = call("POST", "/courses", token, {"title": "운영체제"})
    status, routine = call("POST", "/routines", token, {
        "title": "쿠팡알바", "daysOfWeek": ["MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY"],
        "startTime": "14:00", "endTime": "20:00", "effectiveFrom": (today - datetime.timedelta(days=7)).isoformat()})
    routine_id = routine.get("routineId")
    log("근무 루틴 %s %s" % (status, routine_id))

    def exceptions(day):
        return int(mysql("SELECT COUNT(*) FROM routine_exceptions WHERE routine_id=%d AND exception_date='%s'"
                         % (routine_id, day.isoformat()))[0][0])

    def run(key):
        return only is None or key in only

    # ---------- A·B·C ----------
    if run("A"):
        cid = conversation(token)
        a = turn(token, cid, "오늘 쿠팡알바 근무 빼줘")
        items = (a["bundle"] or {}).get("items", [])
        skip = [i for i in items if i.get("opType") == "ROUTINE_SKIP"]
        scenario("A 근무 빼기 제안", "오늘 쿠팡알바 근무 빼줘",
                 "오늘 회차 제외 제안 1건(선택됨), 적용 전 원본 예외 0건",
                 "묶음 항목 %s, 원본 예외 %d건, 답: %s" % ([(i.get("opType"), i.get("selected")) for i in items],
                                                     exceptions(today), a["reply"][:120]),
                 len(skip) == 1 and skip[0].get("selected") and exceptions(today) == 0)
        bundle_id = (a["bundle"] or {}).get("bundleId")

        b = turn(token, cid, "그럼 오늘 2시부터 8시까지 자료구조 공부 잡아줘")
        made = make_plan(token, cid, b, today)
        draft = made.get("draft") or {}
        its, unplaced, perr = preview(token, draft)
        bad = within(its, today, "%sT14:00" % today, "%sT20:00" % today)
        minutes = minutes_of(its)
        scen = draft.get("scenarioChanges") or []
        scenario("B 근무 뺀 가정에서 2~8시 공부", "그럼 오늘 2시부터 8시까지 자료구조 공부 잡아줘",
                 "초안이 근무 제외 변경에 기대고(scenarioChanges), 항목이 14~20시 안, 공부량 < 360분(쉬는 시간 따로), 원본 예외 0건",
                 "배치 %s, 못 넣음 %s, 기대는 변경 %s, 합계 %d분, 원본 예외 %d건, 오류 %s, 범위 밖 %s" % (
                     [(t, hm(s), hm(e)) for t, s, e, *_ in its], unplaced, scen, minutes, exceptions(today),
                     made.get("error") or perr, bad),
                 bool(its) and not bad and bool(scen) and 0 < minutes < 360 and exceptions(today) == 0)
        REPORT["draftB"] = draft.get("scopeReport")

        pid = draft.get("proposalId")
        if pid:
            edited = [{"proposalItemId": pi, "placementType": pt or "TIME_FIXED", "scheduledStartAt": s_, "scheduledEndAt": e_,
                       "scheduledDate": sd or s_[:10]} for _, s_, e_, pi, pt, sd in its]
            st, plan = call("POST", "/plans/proposals/%s/confirm" % pid, token, {"editedItems": edited})
            convo_bundles = call("GET", "/ai/conversations/%s/change-bundles" % cid, token)[1] or []
            skips = [(b.get("bundleId"), b.get("status")) for b in convo_bundles
                     for i in b.get("items", []) if i.get("opType") == "ROUTINE_SKIP"]
            bst = call("GET", "/ai/change-bundles/%s" % bundle_id, token)[1] if bundle_id else {}
            ex1 = exceptions(today)
            st2, again = call("POST", "/ai/change-bundles/%s/apply" % bundle_id, token,
                              {"expectedRevision": (bst or {}).get("revision", 1), "selectedItemIds": [],
                               "idempotencyKey": str(uuid.uuid4())}) if bundle_id else (None, None)
            ex2 = exceptions(today)
            scenario("C 확정 시 묶음 함께 적용, 재적용 거절", "(미리보기 시각으로 계획 확정 → 같은 묶음 새 키로 적용)",
                     "확정 200, 대화의 근무 빼기 제안은 하나뿐이고 APPLIED, 예외 1건, 재적용 409, 예외 1건 그대로",
                     "확정 %s %s, 대화의 근무 빼기 제안 %s, 예외 %d건, 재적용 %s %s, 예외 %d건" % (
                         st, (plan or {}).get("code", ""), skips, ex1, st2, (again or {}).get("code"), ex2),
                     st == 200 and (bst or {}).get("status") == "APPLIED" and len(skips) == 1 and ex1 == 1
                     and st2 == 409 and ex2 == 1)

    # ---------- D ----------
    if run("D"):
        cid = conversation(token)
        d = turn(token, cid, "내일 2시부터 8시까지 자료구조 공부 잡아줘")
        made = make_plan(token, cid, d, tomorrow) if (d.get("offer") or not d.get("question")) else {}
        its, _, _ = preview(token, (made or {}).get("draft"))
        overlap = [x for x in its if x[1] < "%sT20:00" % tomorrow and x[2] > "%sT14:00" % tomorrow]
        text = d["reply"] + " " + ((made or {}).get("draft") or {}).get("scopeReport", {}).__str__()
        mentions = "알바" in text or "근무" in text or "겹" in text
        scenario("D 근무 유지 + 같은 시간 공부 요청", "내일 2시부터 8시까지 자료구조 공부 잡아줘",
                 "근무(14~20시)와 겹친다고 알리거나 묻는다. 근무 시간에 공부 항목을 몰래 넣지 않는다",
                 "답: %s / 근무 시간과 겹친 항목 %s, 근무 언급 %s" % (d["reply"][:200], [(t, hm(s), hm(e)) for t, s, e, *_ in overlap],
                                                       mentions),
                 mentions and not overlap)

    # ---------- E ----------
    if run("E"):
        # 오늘 근무는 A·C에서 빠졌다(적용됨). 빠지지 않았으면 이 시나리오는 D와 같아진다.
        cid = conversation(token)
        e = turn(token, cid, "오늘 2시부터 8시까지 자료구조 공부할 건데 6시부터 7시는 저녁 먹어")
        made = make_plan(token, cid, e, today)
        its, unplaced, perr = preview(token, made.get("draft"))
        bad = within(its, today, "%sT14:00" % today, "%sT20:00" % today,
                     [("%sT18:00" % today, "%sT19:00" % today)])
        tc = ((made.get("draft") or {}).get("scopeReport") or {}).get("timeCondition") or {}
        scenario("E 말한 시간 + 저녁 제외", "오늘 2시부터 8시까지 공부할 건데 6시부터 7시는 저녁 먹어",
                 "항목 14~20시 안, 18~19시 비움, 시간 조건 ADOPTED",
                 "배치 %s, 못 넣음 %s, 조건 %s, 문제 %s, 오류 %s" % ([(t, hm(s), hm(e2)) for t, s, e2, *_ in its], unplaced,
                                                         tc.get("status"), bad, made.get("error") or perr),
                 bool(its) and not bad and tc.get("status") == "ADOPTED")

    # ---------- F ----------
    if run("F"):
        past = max(0, now.hour - 2)
        said = "오늘 %d시부터 %d시까지 공부 계획 짜줘" % (past if past <= 12 else past - 12, min(23, now.hour + 3) % 12 or 12)
        if past < 12 and now.hour + 3 < 12:
            said = "오늘 오전 %d시부터 오전 %d시까지 공부 계획 짜줘" % (past, now.hour + 3)
        cid = conversation(token)
        f = turn(token, cid, said)
        made = make_plan(token, cid, f, today)
        its, _, _ = preview(token, made.get("draft"))
        early = [x for x in its if x[1] < now.strftime("%Y-%m-%dT%H:%M")]
        tc = ((made.get("draft") or {}).get("scopeReport") or {}).get("timeCondition") or {}
        scenario("F 이미 지난 시작", said, "지난 시각에 항목을 넣지 않는다. 지금부터로 줄었다고 알린다",
                 "배치 %s, 지난 시각 항목 %s, 조건 메모 %s" % ([(t, hm(s), hm(e)) for t, s, e, *_ in its], early,
                                                  (tc.get("notes") or [])[:3]),
                 not early)

    # ---------- G ----------
    if run("G"):
        cid = conversation(token)
        g = turn(token, cid, "오늘 밤 11시부터 새벽 1시까지 자료구조 공부 잡아줘")
        made = make_plan(token, cid, g, today)
        its, unplaced, perr = preview(token, made.get("draft"))
        bad = within(its, today, "%sT23:00" % today, "%sT01:00" % tomorrow)
        tc = ((made.get("draft") or {}).get("scopeReport") or {}).get("timeCondition") or {}
        scenario("G 자정 넘김", "오늘 밤 11시부터 새벽 1시까지 자료구조 공부 잡아줘",
                 "항목이 23:00~다음 날 01:00 안, 시간 조건 ADOPTED",
                 "배치 %s, 못 넣음 %s, 조건 %s, 문제 %s, 오류 %s" % ([(t, s[5:16], e[5:16]) for t, s, e, *_ in its], unplaced,
                                                         tc.get("status"), bad, made.get("error") or perr),
                 bool(its) and not bad and tc.get("status") == "ADOPTED")

    # ---------- H ----------
    if run("H"):
        cid = conversation(token)
        day = today + datetime.timedelta(days=2)
        h = turn(token, cid, "%d월 %d일 쿠팡알바 근무를 저녁 6시부터 10시로 옮겨줘" % (day.month, day.day))
        bundle = h["bundle"] or {}
        items = bundle.get("items", [])
        scenario("H1 근무 옮기기 제안", "%s 근무를 18~22시로 옮겨줘" % day,
                 "회차 옮기기 제안, 적용 전 원본 그대로",
                 "항목 %s, 원본 예외 %d건" % ([(i.get("opType"), i.get("summary")) for i in items], exceptions(day)),
                 any(i.get("opType") == "ROUTINE_MOVE" for i in items) and exceptions(day) == 0)
        if bundle.get("bundleId"):
            bid = bundle["bundleId"]
            # 검토 뒤 원본이 바뀜: 손으로 그날을 빼 둔다
            st0, _ = call("POST", "/routines/%d/exceptions" % routine_id, token,
                          {"exceptionDate": day.isoformat(), "type": "SKIP"})
            st, res = call("POST", "/ai/change-bundles/%d/apply" % bid, token,
                           {"expectedRevision": bundle.get("revision", 1), "selectedItemIds": [i["itemId"] for i in items if i.get("selected")],
                            "idempotencyKey": str(uuid.uuid4())})
            scenario("H2 검토 뒤 원본이 바뀌면 적용 거절", "(묶음 적용 전 같은 날 예외를 손으로 추가)",
                     "409, 원본은 손으로 넣은 1건뿐",
                     "손 추가 %s, 적용 %s %s, 예외 %d건" % (st0, st, (res or {}).get("code"), exceptions(day)),
                     st == 409 and exceptions(day) == 1)

        day2 = today + datetime.timedelta(days=3)
        h3 = turn(token, cid, "%d월 %d일 쿠팡알바 근무 빼줘" % (day2.month, day2.day))
        b3 = h3["bundle"] or {}
        if b3.get("bundleId"):
            key = str(uuid.uuid4())
            req = {"expectedRevision": b3.get("revision", 1),
                   "selectedItemIds": [i["itemId"] for i in b3.get("items", []) if i.get("selected")], "idempotencyKey": key}
            s1, r1 = call("POST", "/ai/change-bundles/%d/apply" % b3["bundleId"], token, req)
            s2, r2 = call("POST", "/ai/change-bundles/%d/apply" % b3["bundleId"], token, req)
            scenario("H3 같은 키 두 번 적용(응답 유실 뒤 재시도)", "%s 근무 빼줘 → 적용 2회" % day2,
                     "둘 다 200, 같은 결과, 예외 1건",
                     "%s/%s, 상태 %s/%s, 예외 %d건" % (s1, s2, (r1 or {}).get("status"), (r2 or {}).get("status"), exceptions(day2)),
                     s1 == 200 and s2 == 200 and (r1 or {}).get("applyResult") == (r2 or {}).get("applyResult")
                     and exceptions(day2) == 1)

    # ---------- I ----------
    if run("I"):
        st, item = call("POST", "/execution-items", token, {
            "courseId": course_id, "title": "자료구조 복습", "scheduledDate": today.isoformat(), "expectedMinutes": 60})
        item_id = (item or {}).get("executionItemId")
        cid = conversation(token)
        i1 = turn(token, cid, "오늘 자료구조 복습 끝냈어, 50분 했어")
        items = (i1["bundle"] or {}).get("items", [])
        status_now = mysql("SELECT status FROM execution_items WHERE execution_item_id=%d" % item_id)[0][0] if item_id else None
        scenario("I 완료 + 실제 시간", "오늘 자료구조 복습 끝냈어, 50분 했어",
                 "완료 제안(50분), 적용 전 원본 상태 그대로",
                 "항목 %s, 원본 상태 %s" % ([(i.get("opType"), i.get("summary")) for i in items], status_now),
                 any(i.get("opType") == "EXECUTION_COMPLETE" and "50" in (i.get("summary") or "") for i in items)
                 and status_now not in ("COMPLETED", "DONE"))

    # ---------- J ----------
    if run("J"):
        due0 = today + datetime.timedelta(days=10)
        st, asg = call("POST", "/assignments", token, {"courseId": os_course["courseId"], "title": "운영체제 과제 2",
                                                       "dueKind": "DATE", "dueDate": due0.isoformat()})
        friday = today + datetime.timedelta(days=(4 - today.weekday()) % 7 or 7)
        cid = conversation(token)
        j = turn(token, cid, "운영체제 과제 2 마감이 이번 주 금요일로 바뀌었어")
        items = (j["bundle"] or {}).get("items", [])
        scenario("J 과제 마감 정정", "운영체제 과제 2 마감이 이번 주 금요일로 바뀌었어",
                 "마감 변경 제안(%s), 적용 전 원본 마감 %s 그대로" % (friday, due0),
                 "항목 %s, 원본 마감 %s" % ([(i.get("opType"), i.get("summary")) for i in items],
                                       mysql("SELECT due_date FROM course_assignments WHERE assignment_id=%d"
                                             % asg["assignmentId"])[0][0] if asg and asg.get("assignmentId") else None),
                 any(i.get("opType") == "ASSIGNMENT_DUE" for i in items))

    tool_turns = [t for t in REPORT["turns"] if t.get("modelCalls")]
    REPORT["summary"] = {
        "passed": sum(1 for s in REPORT["scenarios"] if s["ok"]), "total": len(REPORT["scenarios"]),
        "toolTurns": len(tool_turns),
        "modelCalls": [t.get("modelCalls") for t in tool_turns], "toolCalls": [t.get("toolCalls") for t in tool_turns],
        "seconds": [t.get("seconds") for t in REPORT["turns"]],
        "errors": [t.get("error") for t in REPORT["turns"] if t.get("error")],
    }
    log(json.dumps(REPORT["summary"], ensure_ascii=False))
    out = ROOT / args.out
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(REPORT, ensure_ascii=False, indent=2, default=str), encoding="utf-8")
    log("보고서: " + str(out))


if __name__ == "__main__":
    main()
