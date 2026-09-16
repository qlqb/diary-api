"""
상담 → 합의 → OFFER → 기간 계획 초안 → 다시 만들기 → 실행 기록 반영을 실제 서버·실제 모델로 돌리고, 호출 수·토큰·지연·
합의 상태·전략·인용 출처를 표준 출력과 JSON으로 남긴다(2026-09-17 지시서 §13.2).

합성 계정·합성 자료만 쓴다. 사용자 실제 계정·파일·원문은 쓰지 않는다. 비밀번호는 코드에 두지 않는다(환경 변수).
시나리오마다 새 합성 계정을 만든다 — 일일 상담 한도(50)를 시나리오끼리 나눠 쓰지 않게 하고, 다른 대화의 합의가
섞이지 않게 하기 위해서다.

시나리오(§13.2):
  absent      — 지난주 결석·복습 안 함. 옮긴 항목·기록 없는 항목이 실행 기록으로 실리는가, 파일을 다시 묻지 않는가.
  deadline    — 과제 마감 임박. 마감이 사실로 실리고 항목 마감이 ASSIGNMENT 출처로 붙는가.
  exam        — 시험 전. 범위 합의(SCOPE)가 저장되고 계획 전략에 kept/deferred로 남는가.
  agree       — AI 제안에 "좋아, 그대로". 합의 저장소에서 ASSISTANT 제안이 수락 상태로 바뀌고 초안이 그 결정을 지키는가.
  stuck       — 이전 항목에서 막힘(부분 수행 기록 + 메모). 관찰과 원인을 나누고 핵심 질문 하나만 하는가. 답이 합의로 남는가.
  change      — OFFER 이후 변경. 바뀐 조건이 새 초안에 반영되고, 같은 조건 재요청은 선택 호출을 생략(REUSED)하는가.
                확정·배치 뒤 기록을 남기고 다시 짜면 기존 항목이 조정(existingItems)으로 오는가.

사용 예(로컬 DB 계정 정보는 application-local.properties에서 읽는다):
  VERIFY_PASSWORD=... python scripts/ai-baseline/verify-ai-plan-connection-2026-09-17.py all --repeat 1
  VERIFY_PASSWORD=... python scripts/ai-baseline/verify-ai-plan-connection-2026-09-17.py stuck agree

표준 라이브러리 + mysql CLI만 쓴다. 한글 본문은 UTF-8로 보낸다. 결과 JSON은 build/synthetic/ 아래(커밋하지 않는다).
"""
import argparse
import codecs
import io
import json
import os
import pathlib
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid
from datetime import date, datetime, timedelta

OUT = io.open(sys.stdout.fileno(), "w", encoding="utf-8", closefd=False)
ROOT = pathlib.Path(__file__).resolve().parents[2]
BASE = os.environ.get("VERIFY_BASE", "http://localhost:8081")
KOREAN_DAYS = ["MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY"]


def log(msg=""):
    OUT.write(msg + "\n")
    OUT.flush()


# ===== HTTP =====

def request(method, path, token=None, body=None, stream=False, timeout=300):
    headers = {"Accept": "text/event-stream" if stream else "application/json"}
    data = None
    if body is not None:
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
        headers["Content-Type"] = "application/json; charset=utf-8"
    if token:
        headers["Authorization"] = "Bearer " + token
    return urllib.request.Request(BASE + path, data=data, method=method, headers=headers), timeout


def call(method, path, token=None, body=None, timeout=300):
    req, timeout = request(method, path, token, body, timeout=timeout)
    started = time.time()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            text = resp.read().decode("utf-8")
            return resp.status, (json.loads(text) if text else None), time.time() - started
    except urllib.error.HTTPError as e:
        text = e.read().decode("utf-8", errors="replace")
        try:
            return e.code, json.loads(text), time.time() - started
        except Exception:
            return e.code, text, time.time() - started


def must(method, path, token=None, body=None):
    status, data, _ = call(method, path, token, body)
    if status >= 300:
        raise SystemExit(f"{method} {path} -> {status}: {json.dumps(data, ensure_ascii=False)[:300]}")
    return data


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


def turn(token, conversation_id, message=None, action="AUTO", period_plan=None, source_message_id=None):
    """상담 턴 하나. SSE 프레임을 읽어 결과로 접는다. 진행 단계(period_plan.progress)는 순서대로 남긴다."""
    body = {"requestedAction": action, "idempotencyKey": str(uuid.uuid4())}
    if message is not None:
        body["message"] = message
    if period_plan is not None:
        body["periodPlan"] = period_plan
    if source_message_id is not None:
        body["sourceMessageId"] = source_message_id
    result = {"action": action, "message": message, "responseType": None, "reply": "", "offer": None, "error": None,
              "events": [], "stages": [], "draft": None, "userMessageId": None, "assistantMessageId": None,
              "quickReplies": [], "seconds": None}
    started = time.time()
    req, timeout = request("POST", f"/api/ai/conversations/{conversation_id}/messages", token, body, stream=True)
    try:
        resp = urllib.request.urlopen(req, timeout=timeout)
    except urllib.error.HTTPError as e:
        result["error"] = f"HTTP {e.code} {e.read().decode('utf-8', errors='replace')[:200]}"
        result["seconds"] = round(time.time() - started, 1)
        return result
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
                    result["offer"] = payload.get("offerAction") or result["offer"]
                    result["userMessageId"] = payload.get("userMessageId")
                    result["assistantMessageId"] = payload.get("assistantMessageId")
                    result["quickReplies"] = payload.get("quickReplies") or []
                    if payload.get("periodPlanDraft"):
                        result["draft"] = payload["periodPlanDraft"]
                elif name == "message.delta":
                    result["reply"] += payload.get("text") or ""
                elif name == "offer.ready":
                    result["offer"] = payload.get("offerAction")
                elif name == "period_plan.progress":
                    result["stages"].append(payload.get("stage"))
                elif name == "period_plan.ready":
                    result["draft"] = payload
                elif name == "message.error":
                    result["error"] = f"{payload.get('code')} {payload.get('message')}"
    result["seconds"] = round(time.time() - started, 1)
    return result


# ===== DB (로컬 memo) =====

def db_props():
    props = {}
    path = ROOT / "src" / "main" / "resources" / "application-local.properties"
    for line in path.read_text(encoding="utf-8").splitlines():
        if "=" in line and not line.strip().startswith("#"):
            k, v = line.split("=", 1)
            props[k.strip()] = v.strip()
    return props


def mysql(sql):
    props = db_props()
    env = dict(os.environ, MYSQL_PWD=props["spring.datasource.password"])
    proc = subprocess.run(["mysql", "-u", props["spring.datasource.username"], "--default-character-set=utf8mb4",
                           "-N", "-B", "memo"], input=sql.encode("utf-8"), capture_output=True, env=env)
    if proc.returncode != 0:
        raise RuntimeError(proc.stderr.decode("utf-8", errors="replace"))
    return [line.split("\t") for line in proc.stdout.decode("utf-8").splitlines() if line]


def mysql_rows(sql, attempts=3):
    for i in range(attempts):
        rows = mysql(sql)
        if rows:
            return rows
        log(f"   (mysql 빈 결과 — 다시 조회 {i + 1}/{attempts})")
        time.sleep(1)
    raise RuntimeError("조회 결과가 계속 비어 있다: " + sql[:120])


def sql_str(s):
    return "'" + s.replace("\\", "\\\\").replace("'", "''") + "'"


def usage_by_workflow(workflow_id):
    rows = mysql(f"SELECT feature, input_tokens, output_tokens, result_status FROM ai_usage_logs "
                 f"WHERE workflow_id = {sql_str(workflow_id)} ORDER BY usage_log_id;")
    return [{"feature": r[0], "input": int(r[1]) if r[1] != "NULL" else None,
             "output": int(r[2]) if r[2] != "NULL" else None, "status": r[3]} for r in rows]


def brief_of(conversation_id):
    rows = mysql(f"SELECT brief_id, version, items, last_proposal_id FROM ai_plan_briefs WHERE conversation_id = {conversation_id};")
    if not rows:
        return None
    items = json.loads(rows[0][2].replace("\\n", "\n")) if rows[0][2] else []
    return {"briefId": int(rows[0][0]), "version": int(rows[0][1]), "lastProposalId": None if rows[0][3] == "NULL" else int(rows[0][3]),
            "items": [{k: it.get(k) for k in ("id", "kind", "text", "speaker", "accepted", "rejected", "scope", "revision",
                                              "sourceMessageId", "acceptedByMessageId", "topicId", "executionItemId")}
                      for it in items]}


# ===== 합성 계정과 자료 =====

WEEKS = [("배열과 연결 리스트", "LEARNED"), ("스택과 큐", "LEARNED"), ("재귀", "IN_PROGRESS"), ("트리", None),
         ("힙과 우선순위 큐", None), ("해시", None)]


def seed(scenario):
    """새 합성 계정 하나: 자료구조(6주차, 개념/문제 구간, 진행 상태, 과제)와 영어. 다음 수업 루틴. 시나리오별 실행 기록."""
    stamp = str(int(time.time() * 1000))[-10:]
    email = f"plan-conn-{scenario}-{stamp}@example.com"
    password = os.environ["VERIFY_PASSWORD"]
    s, body, _ = call("POST", "/api/auth/signup", body={"email": email, "password": password, "nickname": f"pc{stamp}"})
    assert s in (200, 201), (s, body)
    token = must("POST", "/api/auth/login", body={"email": email, "password": password})["token"]
    user_id = int(mysql_rows(f"SELECT user_id FROM users WHERE email = {sql_str(email)};")[0][0])
    ds = must("POST", "/api/courses", token, {"title": "자료구조"})["courseId"]
    en = must("POST", "/api/courses", token, {"title": "영어회화"})["courseId"]
    today = date.today()
    # 다음 수업: 화·목 14:00~15:15. 오늘이 화요일이면 내일부터 유효하게 해 "지금 이후 첫 수업"이 반드시 있게 한다.
    must("POST", "/api/routines", token, {
        "courseId": ds, "title": "자료구조 수업", "daysOfWeek": ["TUESDAY", "THURSDAY"], "startTime": "14:00",
        "endTime": "15:15", "effectiveFrom": (today - timedelta(days=21)).isoformat(),
        "effectiveUntil": (today + timedelta(days=90)).isoformat()})
    must("POST", "/api/routines", token, {
        "courseId": en, "title": "영어회화 수업", "daysOfWeek": ["WEDNESDAY"], "startTime": "10:00",
        "endTime": "11:00", "effectiveFrom": (today - timedelta(days=21)).isoformat(),
        "effectiveUntil": (today + timedelta(days=90)).isoformat()})

    h = f"pc{stamp}".ljust(64, "0")[:64]
    lines = ["SET NAMES utf8mb4;", "START TRANSACTION;"]
    lines.append(
        "INSERT INTO course_materials (user_id, original_filename, stored_filename, storage_path, size_bytes, page_count, "
        f"file_hash, extraction_status, extracted_text, status) VALUES ({user_id}, '자료구조_강의자료.pdf', 'none.pdf', "
        f"{sql_str(f'{user_id}/synthetic-plan-conn.pdf')}, 1, 12, '{h}', 'SUCCESS', '합성', 'ACTIVE');")
    lines.append("SET @m = LAST_INSERT_ID();")
    lines.append(f"INSERT INTO material_links (user_id, material_id, course_id, material_type) VALUES ({user_id}, @m, {ds}, 'PROFESSOR_SLIDE');")
    lines.append("INSERT INTO material_analysis_jobs (user_id, material_id, course_id, job_kind, file_hash, analysis_version, "
                 f"priority, status, attempt, max_attempts, next_run_at, finished_at) VALUES ({user_id}, @m, 0, 'CONTENT', "
                 f"'{h}', 1, 10, 'DONE', 1, 3, NOW(), NOW());")
    page = 0
    topic_vars = {}
    for wi, (subject, progress) in enumerate(WEEKS):
        week = wi + 1
        lines.append("INSERT INTO course_topics (user_id, course_id, title, order_index, source_type, source_locator, status) "
                     f"VALUES ({user_id}, {ds}, {sql_str(f'{week}주차 {subject}')}, {wi}, 'SOURCE', '{week}주차', 'ACTIVE');")
        lines.append(f"SET @t{week} = LAST_INSERT_ID();")
        topic_vars[week] = f"@t{week}"
        if progress:
            lines.append(f"INSERT INTO topic_progress (user_id, topic_id, status, last_studied_at) VALUES ({user_id}, @t{week}, "
                         f"'{progress}', NOW() - INTERVAL {(7 - wi) * 2} DAY);")
        for role, kind in [("CONCEPT", "개념 설명"), ("EXERCISE", "연습 문제")]:
            page += 1
            body_text = (f"[{week}주차 {subject} {kind}] " + (
                f"{subject}의 정의와 동작 원리를 설명한다. 그림으로 흐름을 따라가고 핵심 용어를 정리한다. " * 4
                if role == "CONCEPT" else
                f"문제 {week}-1: {subject}을(를) 직접 구현하라. 문제 {week}-2: 입력 크기를 바꿔 수행 시간을 비교하라. "
                f"문제 {week}-3: 반복문 대신 재귀로 다시 쓰고 종료 조건을 설명하라. " * 2))
            lines.append("INSERT INTO material_text_units (user_id, material_id, file_hash, unit_index, unit_type, unit_no, char_count, text) "
                         f"VALUES ({user_id}, @m, '{h}', {page}, 'PDF_PAGE', {page}, {len(body_text)}, {sql_str(body_text)});")
            lines.append("INSERT INTO material_sections (user_id, material_id, file_hash, analysis_version, chunk_index, unit_type, "
                         "unit_start, unit_end, display_title, roles_json, task_text, excerpt, assignment_cue, dedupe_key, status) "
                         f"VALUES ({user_id}, @m, '{h}', 1, 0, 'PDF_PAGE', {page}, {page}, {sql_str(f'{week}주차 {subject} {kind}')}, "
                         f"'[\"{role}\"]', {sql_str(f'{subject} 문제 풀이') if role == 'EXERCISE' else 'NULL'}, "
                         f"{sql_str(body_text[:160])}, 0, {sql_str(f'k{stamp}-{page}')}, 'ACTIVE');")
            lines.append("INSERT INTO topic_material_links (user_id, course_id, topic_id, material_id, section_id, role, locator, origin, status) "
                         f"VALUES ({user_id}, {ds}, @t{week}, @m, LAST_INSERT_ID(), '{role}', 'p.{page}', 'PROPOSAL_APPLIED', 'ACTIVE');")
    due = today + timedelta(days=3)
    lines.append("INSERT INTO course_assignments (user_id, course_id, material_id, topic_id, title, confirm_status, due_kind, "
                 f"due_date, due_source, dedupe_key, version) VALUES ({user_id}, {ds}, @m, @t3, "
                 f"{sql_str('과제 2 · 재귀 함수 구현 보고서')}, 'CONFIRMED', 'DATE', '{due.isoformat()}', 'SOURCE', {sql_str(f'pc{stamp}-open')}, 1);")
    lines.append("INSERT INTO course_assignments (user_id, course_id, material_id, topic_id, title, confirm_status, due_kind, "
                 f"due_date, due_source, completed_at, dedupe_key, version) VALUES ({user_id}, {ds}, @m, @t1, "
                 f"{sql_str('과제 1 · 배열 구현 제출')}, 'CONFIRMED', 'DATE', '{(today - timedelta(days=10)).isoformat()}', 'SOURCE', "
                 f"NOW() - INTERVAL 11 DAY, {sql_str(f'pc{stamp}-done')}, 1);")
    lines.append("COMMIT;")
    mysql("\n".join(lines))
    topic3 = int(mysql_rows(f"SELECT topic_id FROM course_topics WHERE user_id = {user_id} AND title LIKE '3주차%';")[0][0])

    # 실행 기록: 지난주 항목들. 옮긴 것, 시간을 적은 것, 시간 없이 완료한 것, 막혀서 일부만 한 것.
    history = seed_history(token, ds, topic3, today, scenario)
    log(f"seeded {email} user={user_id} ds={ds} en={en} topic3={topic3} history={history}")
    return {"email": email, "userId": user_id, "token": token, "courseIds": {"ds": ds, "en": en}, "topic3": topic3,
            "history": history, "assignmentDue": due.isoformat()}


def seed_history(token, course_id, topic_id, today, scenario):
    out = {}
    last_wed = today - timedelta(days=(today.weekday() - 2) % 7 or 7)

    def create(title, day, minutes):
        item = must("POST", "/api/execution-items", token, {"courseId": course_id, "title": title,
                                                            "scheduledDate": day.isoformat(), "expectedMinutes": minutes})
        return item

    a = create("2주차 스택과 큐 개념 정리", last_wed - timedelta(days=1), 40)
    a = must("POST", f"/api/execution-items/{a['executionItemId']}/complete", token, {"version": a["version"], "actualMinutes": 25})
    out["measured"] = a["executionItemId"]
    b = create("2주차 큐 연습 문제", last_wed - timedelta(days=1), 30)
    b = must("POST", f"/api/execution-items/{b['executionItemId']}/complete", token, {"version": b["version"]})
    out["unmeasured"] = b["executionItemId"]
    c = create("3주차 재귀 개념 읽기", last_wed, 40)
    c = must("POST", f"/api/execution-items/{c['executionItemId']}/move", token,
             {"toDate": (last_wed + timedelta(days=1)).isoformat(), "version": c["version"]})
    c = must("POST", f"/api/execution-items/{c['executionItemId']}/move", token,
             {"toDate": (last_wed + timedelta(days=3)).isoformat(), "version": c["version"]})
    out["moved"] = c["executionItemId"]
    if scenario in ("stuck", "agree", "change", "exam"):
        d = create("3주차 재귀 연습 문제 3-1~3-3", last_wed + timedelta(days=2), 45)
        d = must("POST", f"/api/execution-items/{d['executionItemId']}/partial", token,
                 {"version": d["version"], "completionPercent": 40, "actualMinutes": 20,
                  "note": "3-3에서 종료 조건을 못 잡아서 멈춤"})
        out["partial"] = d["executionItemId"]
    return out


# ===== 요약 =====

def summarize_draft(name, draft, token):
    if not draft:
        return {"name": name, "missing": True}
    strategy = draft.get("strategy") or {}
    gen = draft.get("generation") or {}
    items = (draft.get("proposal") or {}).get("items") or []
    record = {
        "name": name, "proposalId": draft.get("proposalId"), "period": [draft.get("startDate"), draft.get("endDate")],
        "briefId": draft.get("briefId"), "briefVersion": draft.get("briefVersion"),
        "generation": {k: gen.get(k) for k in ("normalCalls", "recoveryCalls", "maxNormalCalls", "maxTotalCalls", "retrievalRounds",
                                               "maxRetrievalRounds", "inputTokens", "outputTokens", "elapsedMs", "selectionReused", "calls")},
        "selection": {k: (draft.get("materialSelection") or {}).get(k) for k in ("status", "mode", "selectionCalls", "candidateTotal",
                                                                                "candidateShown", "insufficientEvidence", "note")},
        "picked": [{k: s.get(k) for k in ("sectionId", "title", "outcome", "reason")}
                   for s in (draft.get("materialSelection") or {}).get("sections", [])],
        "strategy": {k: strategy.get(k) for k in ("goal", "reach", "strategySummary", "keptDecisions", "assumptions", "openQuestions",
                                                  "unreadNotes", "changes", "existingDecisions", "deferred", "courses")},
        "items": [{k: i.get(k) for k in ("title", "expectedMinutes", "priority", "targetDate", "deadlineAt", "deadlineDate",
                                         "deadlineSource", "topicId", "actionType", "doneCriteria", "operation",
                                         "targetExecutionItemId", "reason")} for i in items],
        "previousDraft": draft.get("previousDraft"),
    }
    s2, prov, _ = call("GET", f"/api/plans/drafts/{draft['proposalId']}/provenance", token)
    if s2 == 200:
        sources = {p["refId"]: p for p in prov.get("providedSources", [])}
        types = {}
        for p in prov.get("providedSources", []):
            types[p["sourceType"]] = types.get(p["sourceType"], 0) + 1
        cited = {}
        unknown = 0
        for ev in prov.get("items", []):
            unknown += ev.get("unknownRefCount") or 0
            for ref in ev.get("refIds") or []:
                src = sources.get(ref)
                t = src["sourceType"] if src else "UNKNOWN"
                cited[t] = cited.get(t, 0) + 1
        record.update({"providedByType": types, "citedByType": cited, "unknownRefCount": unknown,
                       "generationId": prov.get("generationId")})
        if prov.get("generationId"):
            record["usage"] = usage_by_workflow(prov["generationId"])
    log(f"[{name}] proposal={record['proposalId']} gen={record['generation']} selection={record['selection']}")
    log(f"   strategy: goal={record['strategy']['goal']!r} kept={record['strategy']['keptDecisions']} "
        f"questions={record['strategy']['openQuestions']} changes={record['strategy']['changes']} "
        f"existing={record['strategy']['existingDecisions']} unread={record['strategy']['unreadNotes']}")
    for it in record["items"]:
        log(f"   item: {it['operation'] or 'CREATE'} {it['title']} · {it['expectedMinutes']}분 · {it['targetDate']} · "
            f"마감 {it['deadlineAt'] or it['deadlineDate']} ({it['deadlineSource']}) · topic {it['topicId']} · 완료: {it['doneCriteria']}")
    log(f"   provided={record.get('providedByType')} cited={record.get('citedByType')} unknown={record.get('unknownRefCount')} "
        f"usage={record.get('usage')}")
    return record


def log_turn(name, t):
    log(f"[{name}] {t['seconds']}s type={t['responseType']} offer={(t['offer'] or {}).get('type')} events={t['events']} "
        f"stages={t['stages']} error={t['error']}")
    log("   reply: " + (t["reply"] or "")[:600].replace("\n", " / "))


def offer_or_default(t, seeded, days=7):
    offer = t.get("offer") or {}
    if offer.get("type") == "CREATE_PERIOD_PLAN" and offer.get("periodStartDate"):
        return {"periodStartDate": offer["periodStartDate"], "periodEndDate": offer["periodEndDate"],
                "intensity": offer.get("intensity") or "NORMAL", "courseIds": offer.get("courseIds") or []}, True
    today = date.today()
    return {"periodStartDate": today.isoformat(), "periodEndDate": (today + timedelta(days=days - 1)).isoformat(),
            "intensity": "NORMAL", "courseIds": []}, False


def asks_for_files(reply):
    return any(w in (reply or "") for w in ("파일을 올려", "자료를 올려", "자료를 보내", "어떤 자료", "무슨 자료", "업로드"))


# ===== 시나리오 =====

def run_consultation(token, conv, messages, seeded, name):
    turns = []
    for i, m in enumerate(messages):
        t = turn(token, conv, m)
        log_turn(f"{name}#{i + 1}", t)
        turns.append(t)
        if t["error"]:
            break
    return turns


def create_plan(token, conv, last_turn, seeded, name):
    period, from_offer = offer_or_default(last_turn, seeded)
    t = turn(token, conv, None, "CREATE_PERIOD_PLAN", period, last_turn.get("userMessageId"))
    log_turn(name, t)
    t["fromOffer"] = from_offer
    t["draftSummary"] = summarize_draft(name, t.get("draft"), token)
    return t


def scenario_absent(seeded):
    token = seeded["token"]
    conv = must("POST", "/api/ai/conversations", token, {"scope": "PLAN"})["conversationId"]
    turns = run_consultation(token, conv, [
        "지난주 자료구조 수업을 결석했고 복습도 못 했어. 이번 주 어떻게 하지?",
        "응, 다음 수업 전에 3주차는 따라잡고 싶어. 이대로 계획 짜 줘",
    ], seeded, "absent")
    plan = create_plan(token, conv, turns[-1], seeded, "absent-plan")
    brief = brief_of(conv)
    checks = {
        "no_file_question": not any(asks_for_files(t["reply"]) for t in turns),
        "history_provided": bool((plan["draftSummary"].get("providedByType") or {}).get("EXECUTION_HISTORY")),
        "strategy_present": bool((plan["draftSummary"].get("strategy") or {}).get("goal")),
        "items_present": len(plan["draftSummary"].get("items") or []) > 0,
        "calls_within_limit": (plan["draftSummary"].get("generation") or {}).get("normalCalls", 99) <= 3,
        "stages_streamed": "PLANNING" in plan["stages"],
    }
    return {"conversationId": conv, "turns": turns, "plan": plan, "brief": brief, "checks": checks}


def scenario_deadline(seeded):
    token = seeded["token"]
    conv = must("POST", "/api/ai/conversations", token, {"scope": "PLAN"})["conversationId"]
    turns = run_consultation(token, conv, [
        "과제 2 마감이 코앞인데 아직 시작도 못 했어. 재귀를 아직 잘 몰라",
        "과제 작업 시간도 계획에 넣어 줘. 이번 주 계획 만들어 줘",
    ], seeded, "deadline")
    plan = create_plan(token, conv, turns[-1], seeded, "deadline-plan")
    items = plan["draftSummary"].get("items") or []
    checks = {
        "assignment_provided": bool((plan["draftSummary"].get("providedByType") or {}).get("ASSIGNMENT")),
        "deadline_from_fact": any(i.get("deadlineSource") in ("ASSIGNMENT", "CLASS") for i in items),
        "no_past_deadline": all((i.get("deadlineDate") or "9999") >= date.today().isoformat() for i in items if i.get("deadlineDate")),
        "strategy_present": bool((plan["draftSummary"].get("strategy") or {}).get("goal")),
    }
    return {"conversationId": conv, "turns": turns, "plan": plan, "brief": brief_of(conv), "checks": checks}


def scenario_exam(seeded):
    token = seeded["token"]
    conv = must("POST", "/api/ai/conversations", token, {"scope": "PLAN"})["conversationId"]
    turns = run_consultation(token, conv, [
        "다음 주 화요일에 자료구조 중간고사야. 범위는 1주차부터 4주차까지. 영어회화는 이번 주 쉬어도 돼",
        "좋아. 그 범위로 이번 주 계획 만들어 줘",
    ], seeded, "exam")
    plan = create_plan(token, conv, turns[-1], seeded, "exam-plan")
    brief = brief_of(conv)
    kinds = {i["kind"] for i in (brief or {}).get("items", [])}
    checks = {
        "brief_saved": brief is not None and len(brief["items"]) > 0,
        "scope_or_exclude_recorded": bool(kinds & {"SCOPE", "EXCLUDE", "PRIORITY", "GOAL"}),
        "brief_cited": bool((plan["draftSummary"].get("providedByType") or {}).get("PLAN_BRIEF")),
        "kept_or_deferred": bool((plan["draftSummary"].get("strategy") or {}).get("keptDecisions")
                                 or (plan["draftSummary"].get("strategy") or {}).get("deferred")),
    }
    return {"conversationId": conv, "turns": turns, "plan": plan, "brief": brief, "checks": checks}


def scenario_agree(seeded):
    token = seeded["token"]
    conv = must("POST", "/api/ai/conversations", token, {"scope": "PLAN"})["conversationId"]
    turns = run_consultation(token, conv, [
        "이번 주 자료구조랑 영어회화 둘 다 해야 하는데 시간이 별로 없어. 어떻게 나눌지 제안해 줘",
        "좋아, 그대로 하자",
    ], seeded, "agree")
    brief_before_plan = brief_of(conv)
    plan = create_plan(token, conv, turns[-1], seeded, "agree-plan")
    brief = brief_of(conv)
    items = (brief or {}).get("items", [])
    accepted_ai = [i for i in items if i.get("speaker") == "ASSISTANT" and i.get("accepted")]
    pending_ai = [i for i in items if i.get("speaker") == "ASSISTANT" and not i.get("accepted") and not i.get("rejected")]
    checks = {
        "ai_proposal_recorded": any(i.get("speaker") == "ASSISTANT" for i in items),
        "accepted_after_geudaero": len(accepted_ai) > 0,
        "accepted_by_user_message": all(i.get("acceptedByMessageId") for i in accepted_ai) if accepted_ai else False,
        "kept_decisions_in_plan": bool((plan["draftSummary"].get("strategy") or {}).get("keptDecisions")),
        "brief_cited": bool((plan["draftSummary"].get("providedByType") or {}).get("PLAN_BRIEF")),
    }
    log(f"   brief: accepted_ai={[i['text'] for i in accepted_ai]} pending_ai={[i['text'] for i in pending_ai]}")
    return {"conversationId": conv, "turns": turns, "plan": plan, "briefBeforePlan": brief_before_plan, "brief": brief,
            "checks": checks}


def scenario_stuck(seeded):
    token = seeded["token"]
    conv = must("POST", "/api/ai/conversations", token, {"scope": "PLAN"})["conversationId"]
    first = turn(token, conv, "지난주에 재귀 연습 문제 하다가 막혀서 멈췄어. 이번 주 계획은 어떻게 잡지?")
    log_turn("stuck#1", first)
    reply = first["reply"] or ""
    question_marks = reply.count("?") + reply.count("？")
    second = turn(token, conv, "종료 조건을 어디에 둬야 하는지 개념이 헷갈렸어. 문제 유형은 알겠어")
    log_turn("stuck#2", second)
    third = turn(token, conv, "그럼 그걸 반영해서 이번 주 계획 만들어 줘")
    log_turn("stuck#3", third)
    turns = [first, second, third]
    plan = create_plan(token, conv, third, seeded, "stuck-plan")
    brief = brief_of(conv)
    kinds = {i["kind"] for i in (brief or {}).get("items", [])}
    items = plan["draftSummary"].get("items") or []
    checks = {
        "history_note_provided": bool((plan["draftSummary"].get("providedByType") or {}).get("EXECUTION_HISTORY")),
        "one_question_first": question_marks == 1,
        "no_more_than_two_questions": question_marks <= 2,
        "cause_or_difficulty_saved": bool(kinds & {"CAUSE", "DIFFICULTY"}),
        "reflected_in_items": any("반영" in " ".join(str(v) for v in i.values() if v) or "종료 조건" in (i.get("title") or "")
                                  + (i.get("doneCriteria") or "") for i in items),
        "not_auto_replanned": all(t["responseType"] != "PROPOSAL" for t in turns[:2]),
    }
    return {"conversationId": conv, "turns": turns, "plan": plan, "brief": brief, "checks": checks,
            "firstReplyQuestionMarks": question_marks}


def scenario_change(seeded):
    token = seeded["token"]
    conv = must("POST", "/api/ai/conversations", token, {"scope": "PLAN"})["conversationId"]
    turns = run_consultation(token, conv, [
        "이번 주 자료구조 3주차 따라잡는 계획 짜 줘. 평일 저녁에 할 수 있어",
    ], seeded, "change")
    offer_turn = turns[-1]
    changed = turn(token, conv, "아 근데 금요일 저녁은 약속이 있어서 비워 줘. 그리고 영어회화는 하루 15분만")
    log_turn("change#2", changed)
    turns.append(changed)
    plan = create_plan(token, conv, changed, seeded, "change-plan")
    brief = brief_of(conv)
    first_summary = plan["draftSummary"]
    checks = {
        "offer_before_change": (offer_turn.get("offer") or {}).get("type") == "CREATE_PERIOD_PLAN",
        "change_recorded_in_brief": any("금요일" in (i.get("text") or "") or "15분" in (i.get("text") or "")
                                        for i in (brief or {}).get("items", [])),
        "change_in_strategy": any("금요일" in json.dumps(first_summary.get("strategy"), ensure_ascii=False)
                                  or "15분" in json.dumps(first_summary.get("strategy"), ensure_ascii=False) for _ in [0]),
    }
    result = {"conversationId": conv, "turns": turns, "plan": plan, "brief": brief, "checks": checks}
    pid = first_summary.get("proposalId")
    if not pid:
        return result

    # 같은 조건으로 다시 만들기 → 근거가 같으니 선택 호출을 생략해야 한다(REUSED).
    s, d, el = call("POST", f"/api/plans/proposals/{pid}/redraft", token, {"requestKey": str(uuid.uuid4())})
    if s == 200:
        redraft = summarize_draft("change-redraft-same", d, token)
        redraft["seconds"] = round(el, 1)
        result["redraftSame"] = redraft
        checks["redraft_reused_selection"] = bool((redraft.get("generation") or {}).get("selectionReused"))
        checks["redraft_no_changes"] = not ((redraft.get("previousDraft") or {}).get("changes"))
        checks["redraft_calls_le_2"] = (redraft.get("generation") or {}).get("normalCalls", 99) <= 2
        stored = mysql_rows(f"SELECT status FROM ai_proposals WHERE proposal_id = {pid};")[0][0]
        checks["old_superseded"] = stored != "PROPOSED"
        pid = redraft.get("proposalId") or pid
    else:
        result["redraftSame"] = {"status": s, "error": d}

    # 확정 → 배치 → 기록 → 다시 짜기: 기존 항목이 existingItems 조정으로 오고 같은 내용이 중복되지 않아야 한다.
    s, plan_resp, _ = call("POST", f"/api/plans/proposals/{pid}/confirm", token, {})
    if s != 200:
        result["confirm"] = {"status": s, "error": plan_resp}
        return result
    plan_version = plan_resp["planVersionId"]
    s, placed, _ = call("POST", f"/api/plans/{plan_version}/place", token, {})
    placed_items = (placed or {}).get("placed") or [] if s == 200 else []
    result["confirm"] = {"planVersionId": plan_version, "placed": len(placed_items), "unplaced": len((placed or {}).get("unplaced") or [])}
    log(f"[change-confirm] planVersion={plan_version} placed={len(placed_items)} unplaced={result['confirm']['unplaced']}")
    if placed_items:
        first_item = placed_items[0]
        s, item, _ = call("GET", f"/api/execution-items/range?startDate={date.today().isoformat()}"
                                 f"&endDate={(date.today() + timedelta(days=13)).isoformat()}&includeUnscheduled=true", token)
        live = next((i for i in (item or []) if i.get("executionItemId") == first_item["executionItemId"]), None)
        if live:
            must("POST", f"/api/execution-items/{live['executionItemId']}/partial", token,
                 {"version": live["version"], "completionPercent": 30, "actualMinutes": 15, "note": "생각보다 오래 걸림"})
    replan_turn = turn(token, conv, "첫 항목 하다가 시간이 부족했어. 남은 기간 다시 짜 줘")
    log_turn("change#replan", replan_turn)
    turns.append(replan_turn)
    replan = create_plan(token, conv, replan_turn, seeded, "change-replan")
    result["replan"] = replan
    items = replan["draftSummary"].get("items") or []
    existing_titles = {p["title"] for p in placed_items}
    checks["replan_has_adjustments"] = any(i.get("operation") and i["operation"] != "CREATE" for i in items) \
        or bool((replan["draftSummary"].get("strategy") or {}).get("existingDecisions"))
    checks["replan_no_duplicate_titles"] = not any(i.get("title") in existing_titles for i in items if not i.get("operation") or i["operation"] == "CREATE")
    checks["replan_existing_provided"] = bool((replan["draftSummary"].get("providedByType") or {}).get("EXECUTION_ITEM_PLANNED"))
    return result


SCENARIOS = {"absent": scenario_absent, "deadline": scenario_deadline, "exam": scenario_exam, "agree": scenario_agree,
             "stuck": scenario_stuck, "change": scenario_change}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("scenarios", nargs="+", help="all 또는 " + " ".join(SCENARIOS))
    parser.add_argument("--repeat", type=int, default=1)
    parser.add_argument("--out", default=None)
    args = parser.parse_args()
    names = list(SCENARIOS) if args.scenarios == ["all"] else args.scenarios
    records = []
    started = time.time()
    for r in range(args.repeat):
        for name in names:
            log(f"\n===== {name} (반복 {r + 1}/{args.repeat}) =====")
            seeded = seed(name)
            try:
                result = SCENARIOS[name](seeded)
            except SystemExit as e:
                result = {"fatal": str(e)}
            except Exception as e:  # noqa: BLE001 — 한 시나리오의 실패가 다른 시나리오를 막지 않게
                result = {"fatal": f"{type(e).__name__}: {e}"}
            result.update({"scenario": name, "repeat": r + 1, "account": seeded["email"], "userId": seeded["userId"],
                           "seed": {k: seeded[k] for k in ("courseIds", "topic3", "history", "assignmentDue")}})
            log(f"checks[{name}]: {result.get('checks')} fatal={result.get('fatal')}")
            records.append(result)
    out = pathlib.Path(args.out or ROOT / "build" / "synthetic" / f"ai-plan-connection-{int(time.time())}.json")
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(records, ensure_ascii=False, indent=2, default=str), encoding="utf-8")
    log(f"\nsaved {out} ({round(time.time() - started)}s)")
    log("합성 계정은 남겨 둔다(정리는 사용자 판단): " + ", ".join(sorted({r['account'] for r in records})))


if __name__ == "__main__":
    main()
