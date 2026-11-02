#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
프로젝트 기반 상담·계획·실행 통합(2026-09-19 handoff §16.2)의 실제 모델 평가.

실제 서버(기본 http://localhost:8081) + 실제 모델을 부른다. **격리 DB(memo_consult)에 붙은 서버에서만 돌린다** —
합성 계정을 만들고 합성 자료(.ipynb·.sh, 전부 이 스크립트가 만든 글)를 올린다. 사용자의 실제 자료·계정은 쓰지 않는다.
DB에 직접 붙지 않는다(전부 API) — 토큰·호출 수는 초안의 generation과 /trace에서 읽는다.

사용:
  python scripts/ai-baseline/verify-project-consult-2026-09-19.py all
  python scripts/ai-baseline/verify-project-consult-2026-09-19.py s1 s4 --repeat 2
결과: build/synthetic/project-consult-<stamp>.json

비용 상한(스크립트가 지킨다): 계획 생성 MAX_GENERATIONS회, 상담 턴 MAX_TURNS회. 넘으면 남은 시나리오를 건너뛰고 그렇게 적는다.
상한을 올리거나 재시도로 우회하지 않는다.

시나리오(§16.2):
  s1 여러 프로젝트 자료를 올린 신규 사용자(학습 항목 0, 구조 제안 승인 전) — 전체 훑기
  s2 특정 프로젝트 실습 — 다른 프로젝트를 끌어들이지 않는다
  s3 초안 뒤 "이미 했어" — 반복 수행 조정, 다음 대화에서 기억 재사용
  s4 "따라 했지만 혼자 못 해" → "오늘 한 시간만" — 활동 형태와 총시간
  s5 제출 요구는 있지만 마감·제출 여부 미확인 — 근거 없는 긴급도 금지
  s6 일부 수행(시간 미기록, 이유=개념) 뒤 재계획
"""
import argparse
import codecs
import io
import json
import os
import pathlib
import sys
import time
import urllib.error
import urllib.request
import uuid
from datetime import date, timedelta

OUT = io.open(sys.stdout.fileno(), "w", encoding="utf-8", closefd=False)
ROOT = pathlib.Path(__file__).resolve().parents[2]
BASE = os.environ.get("VERIFY_BASE", "http://localhost:8081")
MAX_GENERATIONS = int(os.environ.get("VERIFY_MAX_GENERATIONS", "14"))
MAX_TURNS = int(os.environ.get("VERIFY_MAX_TURNS", "45"))
SPENT = {"generations": 0, "turns": 0}


def log(msg=""):
    OUT.write(msg + "\n")
    OUT.flush()


# ===== HTTP =====

def call(method, path, token=None, body=None, raw=None, content_type=None, timeout=300):
    headers = {"Accept": "application/json"}
    data = raw
    if body is not None:
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
        headers["Content-Type"] = "application/json; charset=utf-8"
    if content_type:
        headers["Content-Type"] = content_type
    if token:
        headers["Authorization"] = "Bearer " + token
    req = urllib.request.Request(BASE + path, data=data, method=method, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            text = resp.read().decode("utf-8")
            return resp.status, (json.loads(text) if text else None)
    except urllib.error.HTTPError as e:
        text = e.read().decode("utf-8", errors="replace")
        try:
            return e.code, json.loads(text)
        except Exception:
            return e.code, text


def must(method, path, token=None, body=None):
    status, data = call(method, path, token, body)
    if status >= 300:
        raise SystemExit(f"{method} {path} -> {status}: {json.dumps(data, ensure_ascii=False)[:300]}")
    return data


def turn(token, conversation_id, message=None, action="AUTO", period_plan=None, source_message_id=None, answer=None):
    """상담 턴 하나(SSE). consult(질문·이해·방향)와 진행 단계를 함께 접는다."""
    if action == "CREATE_PERIOD_PLAN":
        if SPENT["generations"] >= MAX_GENERATIONS:
            return {"skipped": "generation budget"}
        SPENT["generations"] += 1
    else:
        if SPENT["turns"] >= MAX_TURNS:
            return {"skipped": "turn budget"}
        SPENT["turns"] += 1
    body = {"requestedAction": action, "idempotencyKey": str(uuid.uuid4())}
    if message is not None:
        body["message"] = message
    if period_plan is not None:
        body["periodPlan"] = period_plan
    if source_message_id is not None:
        body["sourceMessageId"] = source_message_id
    if answer is not None:
        body["answer"] = answer
    result = {"action": action, "message": message, "responseType": None, "reply": "", "offer": None, "error": None,
              "stages": [], "draft": None, "userMessageId": None, "consult": None, "seconds": None}
    started = time.time()
    data = json.dumps(body, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(BASE + f"/api/ai/conversations/{conversation_id}/messages", data=data, method="POST",
                                 headers={"Accept": "text/event-stream", "Content-Type": "application/json; charset=utf-8",
                                          "Authorization": "Bearer " + token})
    try:
        resp = urllib.request.urlopen(req, timeout=400)
    except urllib.error.HTTPError as e:
        result["error"] = f"HTTP {e.code} {e.read().decode('utf-8', errors='replace')[:200]}"
        return result
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
                name, lines = "message", []
                for line in frame.split("\n"):
                    if line.startswith("event:"):
                        name = line[6:].strip()
                    elif line.startswith("data:"):
                        lines.append(line[5:].strip())
                if not lines:
                    continue
                try:
                    payload = json.loads("\n".join(lines))
                except json.JSONDecodeError:
                    continue
                if name == "message.completed":
                    result["responseType"] = payload.get("responseType")
                    result["reply"] = payload.get("reply") or result["reply"]
                    result["offer"] = payload.get("offerAction") or result["offer"]
                    result["userMessageId"] = payload.get("userMessageId")
                    result["consult"] = payload.get("consult")
                    if payload.get("periodPlanDraft"):
                        result["draft"] = payload["periodPlanDraft"]
                elif name == "offer.ready":
                    result["offer"] = payload.get("offerAction")
                elif name == "period_plan.progress":
                    result["stages"].append(payload.get("stage"))
                elif name == "period_plan.ready":
                    result["draft"] = payload
                elif name == "message.error":
                    result["error"] = f"{payload.get('code')} {payload.get('message')}"
    result["seconds"] = round(time.time() - started, 1)
    c = result["consult"] or {}
    log(f"   > {message or action!r}")
    log(f"   < [{result['responseType']}] {(result['reply'] or '')[:220]}" + (f"  ERROR {result['error']}" if result["error"] else ""))
    if c.get("question"):
        log(f"     질문: {c['question']['text'][:100]} | 선택지 {[x['label'] for x in c['question']['choices']]}")
    for u in c.get("understanding") or []:
        log(f"     이해[{u['source']}/{u['evidenceType']}] {u['text'][:90]} ({u.get('scopeLabel')})")
    if c.get("direction"):
        log(f"     방향: {c['direction'].get('before')} → {c['direction']['after']} (초안 영향={c['direction']['affectsDraft']})")
    return result


# ===== 합성 자료 =====

def notebook(title, cells):
    nb = {"cells": [{"cell_type": "markdown", "metadata": {}, "source": [f"# {title}\n"]}], "metadata": {},
          "nbformat": 4, "nbformat_minor": 5}
    for kind, text in cells:
        cell = {"cell_type": kind, "metadata": {}, "source": [line + "\n" for line in text.split("\n")]}
        if kind == "code":
            cell["outputs"] = []
            cell["execution_count"] = None
        nb["cells"].append(cell)
    return json.dumps(nb, ensure_ascii=False).encode("utf-8")


def lecture(subject, week, concepts, practice, assignment=None):
    cells = [("markdown", f"## {week}주차 — {subject}\n이번 시간에 다루는 내용: " + ", ".join(c[0] for c in concepts))]
    for name, body, code in concepts:
        cells.append(("markdown", f"### {name}\n{body}"))
        if code:
            cells.append(("code", code))
    cells.append(("markdown", f"### 실습\n{practice}"))
    if assignment:
        cells.append(("markdown", f"### 과제\n{assignment}"))
    return notebook(f"{subject} {week}주차", cells)


PROJECTS = {
    "파이썬 기초": [
        ("py_01_변수와자료형.ipynb", lecture("파이썬 기초", 1, [
            ("변수와 대입", "변수는 값에 붙인 이름이다. x = 17 처럼 대입하고 type(x)로 자료형을 확인한다.", "x = 17\ny = 5\nprint(type(x), x // y, x % y)"),
            ("산술 연산자", "+, -, *, /, //, %, ** 의 차이를 구분한다. / 는 실수, // 는 몫이다.", "print(7 / 2, 7 // 2, 2 ** 10)")],
            "x=17, y=5일 때 몫과 나머지를 각각 구해 출력하는 코드를 직접 작성한다. 예제를 보지 않고 첫 줄부터 써 본다.")),
        ("py_02_조건문과반복문.ipynb", lecture("파이썬 기초", 2, [
            ("if 조건문", "조건이 참일 때만 실행한다. elif, else로 갈래를 나눈다.", "score = 82\nif score >= 90:\n    print('A')\nelif score >= 80:\n    print('B')"),
            ("while 반복문", "조건이 참인 동안 반복한다. 종료 조건을 빠뜨리면 끝나지 않는다.", "n = 3\nwhile n > 0:\n    print(n)\n    n -= 1")],
            "1부터 10까지의 합을 while문으로 구한다. 그다음 종료 조건을 바꿔 짝수만 더하도록 변형한다."))],
    "자료구조": [
        ("ds_ch01_자료표현과알고리즘.ipynb", lecture("자료구조", 1, [
            ("자료의 형태", "단순 자료와 복합 자료, 선형 구조와 비선형 구조를 구분한다.", None),
            ("추상 자료형(ADT)", "자료와 연산을 구현과 분리해 정의한 것. 스택 ADT는 push, pop, isEmpty 연산을 가진다.", None),
            ("알고리즘의 조건", "입력, 출력, 명확성, 유한성, 효과성.", None)],
            "스택 ADT의 연산 세 가지를 말로 정의하고, 각 연산이 빈 스택에서 어떻게 동작해야 하는지 적는다.")),
        ("ds_ch02_스택.ipynb", lecture("자료구조", 3, [
            ("스택의 구조", "LIFO. top 위치에서만 삽입과 삭제가 일어난다.", "stack = []\nstack.append(1)\nstack.append(2)\nprint(stack.pop())"),
            ("괄호 검사", "여는 괄호를 push하고 닫는 괄호를 만나면 pop해 짝을 확인한다.", None)],
            "문자열 '(()())'와 '(()'에 대해 괄호 검사 과정을 스택 상태로 한 단계씩 적는다.",
            "과제 1: 리스트로 스택 클래스를 구현해 제출한다(push, pop, peek, is_empty). 제출 방법은 LMS 공지를 따른다."))],
    "네트워크프로그래밍": [
        ("net_01_리눅스개요.ipynb", lecture("네트워크프로그래밍", 1, [
            ("운영체제와 리눅스", "커널, 셸, 파일 시스템의 역할. 배포판의 차이.", None),
            ("기본 명령", "ls, cd, pwd, cat, chmod 의 쓰임.", "!ls -al\n!pwd")],
            "터미널에서 디렉터리를 만들고 파일 권한을 644에서 755로 바꾼 뒤 ls -l 출력의 차이를 설명한다.")),
        ("net_02_HTTP와REST.ipynb", lecture("네트워크프로그래밍", 2, [
            ("OSI 7계층", "물리·데이터링크·네트워크·전송·세션·표현·응용 계층의 역할.", None),
            ("HTTP 메서드와 상태 코드", "GET/POST/PUT/DELETE, 200/201/400/404/500의 의미.", None)],
            "curl로 공개 API에 GET 요청을 보내고 상태 코드와 응답 헤더를 읽는다."))],
    "빅데이터분석": [
        ("bd_1_1_개요.ipynb", lecture("빅데이터분석", 1, [
            ("데이터 분석의 정의", "데이터에서 의미 있는 정보를 찾아 의사결정에 쓰는 과정.", None),
            ("빅데이터의 7V", "Volume, Velocity, Variety, Veracity, Value, Validity, Volatility.", None),
            ("분석 처리 과정", "수집 → 정제 → 탐색 → 모델링 → 해석.", None)],
            "7V를 각각 한 줄로 설명하고, 분석 처리 과정 다섯 단계를 순서대로 말한다.")),
        ("bd_0_강의계획서.ipynb", notebook("빅데이터분석 강의계획서", [
            ("markdown", "## 수업 운영\n담당 교수 연락처: 연구실 5층. 출석 10%, 과제 30%, 중간 30%, 기말 30%. 지각 3회는 결석 1회로 처리한다."),
            ("markdown", "## 주차별 주제\n1주차: 개요와 7V\n2주차: 실습 환경\n3주차: pandas 기초\n4주차: 시각화\n8주차: 중간고사")]))],
    "웹서버프로그래밍": [
        ("web_01_서블릿기초.ipynb", lecture("웹서버프로그래밍", 1, [
            ("요청과 응답", "클라이언트의 HTTP 요청을 서버가 받아 응답을 만든다. 서블릿은 그 처리를 맡는 자바 클래스다.", None),
            ("서블릿 생명주기", "init → service → destroy.", None)],
            "서블릿 생명주기 세 단계가 각각 언제 호출되는지 순서도로 그린다.")),
        ("web_02_JSP.ipynb", lecture("웹서버프로그래밍", 2, [
            ("JSP의 역할", "HTML 안에 자바 코드를 넣어 동적 페이지를 만든다.", None),
            ("스크립틀릿과 표현식", "<% %> 와 <%= %> 의 차이.", None)],
            "현재 시각을 출력하는 JSP 페이지를 만들고 새로고침할 때마다 값이 바뀌는지 확인한다."))],
}
SETUP_SH = ("#!/bin/bash\n# EC2 초기 설정 스크립트(실습 첨부)\nsudo apt update\nsudo apt install -y openjdk-17-jdk\n"
            "echo 'JAVA_HOME 설정'\nexport JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64\n").encode("utf-8")


def multipart(filename, raw):
    boundary = "----verify" + uuid.uuid4().hex
    body = io.BytesIO()
    body.write((f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"{filename}\"\r\n"
                f"Content-Type: application/octet-stream\r\n\r\n").encode("utf-8"))
    body.write(raw)
    body.write(f"\r\n--{boundary}--\r\n".encode("utf-8"))
    return body.getvalue(), f"multipart/form-data; boundary={boundary}"


def seed(name, projects):
    stamp = str(int(time.time()))
    email = f"project-consult-{name}-{stamp}@example.com"
    must("POST", "/api/auth/signup", None, {"email": email, "password": "consult-1234", "nickname": f"pc{stamp[-6:]}"})
    login = must("POST", "/api/auth/login", None, {"email": email, "password": "consult-1234"})
    token = login.get("token") or login.get("accessToken")
    courses, materials = {}, []
    for title in projects:
        course = must("POST", "/api/courses", token, {"title": title})
        courses[title] = course["courseId"]
        files = list(PROJECTS[title])
        if title == "웹서버프로그래밍":
            files.append(("setup_ec2.sh", SETUP_SH))
        for filename, raw in files:
            data, ctype = multipart(filename, raw)
            status, res = call("POST", f"/api/courses/{course['courseId']}/materials?materialType=PROFESSOR_SLIDE", token,
                               raw=data, content_type=ctype)
            if status >= 300:
                raise SystemExit(f"upload {filename} -> {status} {res}")
            materials.append(res["materialId"])
    log(f"[{name}] 합성 계정 {email} · 프로젝트 {len(courses)} · 자료 {len(materials)}")
    return {"email": email, "token": token, "courses": courses, "materials": materials}


def wait_analysis(token, material_ids, timeout_s=900):
    started, last = time.time(), None
    while time.time() - started < timeout_s:
        _, overview = call("GET", "/api/materials/analysis/overview", token)
        rows = {m["materialId"]: m for m in (overview or {}).get("materials", []) if m["materialId"] in material_ids}
        states = sorted(v["state"] for v in rows.values())
        summary = {s: states.count(s) for s in set(states)}
        if summary != last:
            log(f"   분석 상태 {summary} · 한도 {overview.get('limit')}")
            last = summary
        if rows and all(v["state"] in ("DONE", "PARTIAL", "FAILED", "UNAVAILABLE", "NO_TEXT") for v in rows.values()) \
                and all(all(ls["state"] not in ("QUEUED", "RUNNING") for ls in v.get("linkStates", [])) for v in rows.values()):
            return rows
        time.sleep(6)
    return rows


# ===== 관찰 =====

OPERATIONAL_WORDS = ("출석", "평가 비율", "연락처", "성적 비율", "지각")


def observe(token, draft):
    """초안 하나에서 계약을 확인할 값을 뽑는다. 판단은 하지 않고 사실만 남긴다."""
    if not draft or not draft.get("proposal"):
        return {"error": "초안 없음", "raw": draft}
    items = draft["proposal"]["items"]
    strategy = draft.get("strategy") or {}
    pid = draft["proposalId"]
    _, trace = call("GET", f"/api/plans/drafts/{pid}/trace", token)
    _, prov = call("GET", f"/api/plans/drafts/{pid}/provenance", token)
    calls = (trace or {}).get("calls") or []
    per_course = {}
    for c in calls:
        for pc in (c.get("shown") or {}).get("perCourse") or []:
            row = per_course.setdefault(pc["courseTitle"], {"candidates": pc["candidates"], "shown": 0, "delivered": 0})
            row["shown"] = max(row["shown"], pc.get("shown") or 0)
            row["delivered"] = max(row["delivered"], pc.get("delivered") or 0)
    origins = {}
    for it in (prov or {}).get("items") or []:
        o = (it.get("evidence") or {}).get("origin") if isinstance(it.get("evidence"), dict) else it.get("origin")
        origins[o] = origins.get(o, 0) + 1
    gen = draft.get("generation") or {}
    out = {
        "proposalId": pid,
        "items": [{"title": i["title"], "minutes": i.get("expectedMinutes"), "priority": i.get("priority"),
                   "courseId": i.get("courseId"), "deadlineSource": i.get("deadlineSource")} for i in items],
        "proposedMinutes": draft.get("proposedMinutes"), "targetMinutes": draft.get("targetMinutes"),
        "availabilityBasis": draft.get("availabilityBasis"),
        "projects": [{k: p.get(k) for k in ("courseTitle", "disposition", "decidedBy", "materialState", "candidates",
                                             "shown", "selected", "delivered", "itemCount", "reason")}
                     for p in strategy.get("projects") or []],
        "deferred": strategy.get("deferred"), "assumptions": strategy.get("assumptions"),
        "openQuestions": strategy.get("openQuestions"), "unreadNotes": strategy.get("unreadNotes"),
        "traceCalls": [{"kind": c["callKind"], "commit": c.get("apiCommit"), "estimatedTokens": c.get("estimatedTokens"),
                        "listedSections": len((c.get("shown") or {}).get("sectionIds") or []),
                        "deliveredSections": len((c.get("shown") or {}).get("deliveredSectionIds") or [])} for c in calls],
        "tracePerCourse": per_course, "origins": origins,
        "generation": {k: gen.get(k) for k in ("normalCalls", "recoveryCalls", "inputTokens", "outputTokens", "elapsedMs",
                                               "retrievalRounds", "refusals")},
        "operationalItems": [i["title"] for i in items if any(w in i["title"] for w in OPERATIONAL_WORDS)],
        "freshness": draft.get("freshness"),
    }
    log(f"   초안 #{pid}: 항목 {len(items)}개 · 합계 {out['proposedMinutes']}분 · 예산 {out['targetMinutes']}분 · "
        f"가용시간 {out['availabilityBasis']} · 호출 {out['generation']}")
    for p in out["projects"]:
        log(f"     - {p['courseTitle']}: {p['disposition']}({p['decidedBy']}) 자료={p['materialState']} "
            f"후보 {p['candidates']}/목록 {p['shown']}/원문 {p['delivered']} 항목 {p['itemCount']} · {p.get('reason') or ''}"[:200])
    for i in out["items"]:
        log(f"     · [{i['priority']}] {i['title']} ({i['minutes']}분)")
    return out


def plan_from(token, conv, last, days=1, course_ids=None):
    offer = last.get("offer") or {}
    if offer.get("type") == "CREATE_PERIOD_PLAN" and offer.get("periodStartDate"):
        period = {"periodStartDate": offer["periodStartDate"], "periodEndDate": offer["periodEndDate"],
                  "intensity": offer.get("intensity") or "NORMAL", "courseIds": offer.get("courseIds") or []}
        from_offer = True
    else:
        today = date.today()
        period = {"periodStartDate": today.isoformat(), "periodEndDate": (today + timedelta(days=days - 1)).isoformat(),
                  "intensity": "NORMAL", "courseIds": course_ids or []}
        from_offer = False
    t = turn(token, conv, None, "CREATE_PERIOD_PLAN", period, last.get("userMessageId"))
    t["fromOffer"] = from_offer
    t["period"] = period
    return t


def new_conversation(token):
    return must("POST", "/api/ai/conversations", token, {"scope": "PLAN"})["conversationId"]


# ===== 시나리오 =====

def s1(seeded):
    token = seeded["token"]
    _, m = call("GET", f"/api/courses/{list(seeded['courses'].values())[0]}/learning-map", token)
    conv = new_conversation(token)
    turns = [turn(token, conv, "내 프로젝트 보고, 수업 빠진 날이 많아서 처음부터 한번 쭉 훑어보고 싶어. 내일 하루로 계획 짜줘.")]
    if turns[-1].get("responseType") != "OFFER":
        turns.append(turn(token, conv, None, "PLAN_NOW"))
    gen = plan_from(token, conv, turns[-1])
    obs = observe(token, gen.get("draft"))
    projects = obs.get("projects") or []
    checks = {
        "topics_zero_and_proposals_open": bool(m) and m["state"]["topics"] == 0,
        "all_target_projects_reported_once": len(projects) == len(seeded["courses"])
                                             and len({p["courseTitle"] for p in projects}) == len(projects),
        "no_project_silently_missing": all(p["disposition"] == "INCLUDED" or p.get("reason") for p in projects),
        "unreviewed_not_disguised_as_excluded": all(not (p["disposition"] == "EXCLUDED_BY_CHOICE"
                                                         and p["materialState"] in ("NOT_LISTED", "RETRIEVAL_FAILED"))
                                                    for p in projects),
        "no_operational_item_without_request": not obs.get("operationalItems"),
        "trace_recorded_with_commit": bool(obs.get("traceCalls")) and all(c["commit"] for c in obs["traceCalls"]),
        "reply_states_actual_total_not_budget": "학습 목표" not in (gen.get("reply") or ""),
        "asked_at_most_plan_now": len(turns) <= 2,
    }
    return {"turns": turns, "generation": gen, "observed": obs, "learningMapState": (m or {}).get("state"), "checks": checks}


def s2(seeded):
    token = seeded["token"]
    conv = new_conversation(token)
    turns = [turn(token, conv, "오늘은 파이썬 기초 실습만 연습하고 싶어. 다른 프로젝트는 빼고 오늘 하루로 짜줘.")]
    if turns[-1].get("responseType") != "OFFER":
        turns.append(turn(token, conv, None, "PLAN_NOW"))
    gen = plan_from(token, conv, turns[-1], course_ids=[seeded["courses"]["파이썬 기초"]])
    obs = observe(token, gen.get("draft"))
    py = seeded["courses"]["파이썬 기초"]
    checks = {
        "only_requested_project_in_items": bool(obs.get("items")) and all(i["courseId"] in (py, None) for i in obs["items"]),
        "scope_is_the_requested_project": [p["courseTitle"] for p in obs.get("projects") or []] == ["파이썬 기초"],
        "text_was_delivered": any(p["delivered"] > 0 for p in obs.get("projects") or []),
    }
    return {"turns": turns, "generation": gen, "observed": obs, "checks": checks}


def s3(seeded):
    token = seeded["token"]
    conv = new_conversation(token)
    turns = [turn(token, conv, "파이썬 기초랑 자료구조 위주로 오늘 하루 계획 짜줘.")]
    if turns[-1].get("responseType") != "OFFER":
        turns.append(turn(token, conv, None, "PLAN_NOW"))
    first = plan_from(token, conv, turns[-1])
    before = observe(token, first.get("draft"))
    turns.append(turn(token, conv, "아 근데 파이썬 변수랑 자료형, 연산자 실습은 수업 때 이미 다 했어. 그건 혼자서도 돼."))
    pid = before.get("proposalId")
    _, reloaded = call("GET", f"/api/plans/proposals/{pid}/draft", token)
    status, redraft = call("POST", f"/api/plans/proposals/{pid}/redraft", token, {"requestKey": str(uuid.uuid4())}, timeout=400)
    SPENT["generations"] += 1
    after = observe(token, redraft if status == 200 else None)
    _, contexts = call("GET", "/api/contexts", token)
    conv2 = new_conversation(token)
    later = turn(token, conv2, "파이썬 기초는 어디부터 하면 좋을까?")

    def py_var_items(o):
        return [i["title"] for i in o.get("items") or [] if any(w in i["title"] for w in ("변수", "자료형", "연산자"))]
    checks = {
        # 모델이 문장을 다듬어 저장하므로 단어가 아니라 근거 유형과 범위로 본다(사용자의 말 → STATED/SELF_REPORT, 파이썬 기초 한정).
        "statement_remembered_with_evidence": any(c["evidenceType"] in ("STATED", "SELF_REPORT")
                                                  and c.get("courseTitle") == "파이썬 기초" for c in contexts or []),
        "draft_marked_stale_after_answer": ((reloaded or {}).get("freshness") or {}).get("state") == "STALE",
        "repeat_practice_reduced": len(py_var_items(after)) < max(1, len(py_var_items(before))) or not py_var_items(after),
        "no_execution_item_completed_by_statement": True,  # 서버에 그런 경로가 없다 — 실행 항목은 아직 하나도 없다.
        "next_conversation_does_not_reask_done_part": "변수" not in ((later.get("consult") or {}).get("question") or {}).get("text", ""),
    }
    _, live = call("GET", f"/api/execution-items/range?startDate={date.today().isoformat()}"
                          f"&endDate={(date.today() + timedelta(days=3)).isoformat()}&includeUnscheduled=true", token)
    checks["no_execution_item_completed_by_statement"] = not (live or [])
    return {"turns": turns, "before": before, "after": after, "contexts": contexts, "laterTurn": later, "checks": checks}


def s4(seeded):
    token = seeded["token"]
    conv = new_conversation(token)
    turns = [turn(token, conv, "파이썬 기초 조건문 반복문 실습 있잖아. 수업에서 따라 하긴 했는데 혼자서는 못 하겠어.")]
    q = (turns[-1].get("consult") or {}).get("question")
    if q and q.get("choices"):
        turns.append(turn(token, conv, None, "AUTO", answer={"questionId": q["id"], "choiceIds": [q["choices"][0]["id"]],
                                                              "skipped": False}))
    turns.append(turn(token, conv, "그런데 오늘은 한 시간밖에 없어. 오늘 하루로 짜줘."))
    if turns[-1].get("responseType") != "OFFER":
        turns.append(turn(token, conv, None, "PLAN_NOW"))
    gen = plan_from(token, conv, turns[-1], course_ids=[seeded["courses"]["파이썬 기초"]])
    obs = observe(token, gen.get("draft"))
    _, contexts = call("GET", "/api/contexts", token)
    understood = [u for t in turns for u in ((t.get("consult") or {}).get("understanding") or [])]
    checks = {
        "asked_about_support_level_or_blocker": any(((t.get("consult") or {}).get("question") or {}).get("topic")
                                                    in ("SUPPORT_LEVEL", "BLOCKER") for t in turns),
        "quick_reply_went_through_same_path": len(turns) < 3 or bool(turns[1].get("reply")),
        "self_report_saved_not_mastery": any(c["evidenceType"] == "SELF_REPORT" for c in contexts or []),
        # 같은 말이 기억과 합의에 함께 남으면 화면에는 한 번(기억 쪽)만 보인다 — 어느 쪽이든 시간이 잡혔는지 본다.
        "time_budget_understood": any("시간" in u["text"] for u in understood),
        "total_within_one_hour": obs.get("proposedMinutes") is not None and obs["proposedMinutes"] <= 60,
        "target_is_user_time": obs.get("targetMinutes") == 60,
        "direction_changed_visible": any((t.get("consult") or {}).get("direction") for t in turns),
        "did_not_repeat_intensity_question": not any("가볍게 / 보통 / 집중" in (t.get("reply") or "") for t in turns),
    }
    return {"turns": turns, "generation": gen, "observed": obs, "contexts": contexts, "checks": checks}


def s5(seeded):
    token = seeded["token"]
    _, assignments = call("GET", f"/api/courses/{seeded['courses']['자료구조']}/assignments", token)
    conv = new_conversation(token)
    turns = [turn(token, conv, "자료구조 이번 주 3일 계획 짜줘. 스택 위주로.")]
    if turns[-1].get("responseType") != "OFFER":
        turns.append(turn(token, conv, None, "PLAN_NOW"))
    gen = plan_from(token, conv, turns[-1], days=3, course_ids=[seeded["courses"]["자료구조"]])
    obs = observe(token, gen.get("draft"))
    asked_submission = any(((t.get("consult") or {}).get("question") or {}).get("topic") == "SUBMISSION" for t in turns)
    checks = {
        "no_real_deadline_invented": all(i.get("deadlineSource") in (None, "AI_PROPOSED", "CLASS") for i in obs.get("items") or []),
        "assignment_not_forced_as_task": not any("제출" in i["title"] for i in obs.get("items") or []),
        "uncertainty_surfaced": asked_submission or bool(obs.get("openQuestions")) or bool(obs.get("assumptions")),
    }
    return {"turns": turns, "generation": gen, "observed": obs,
            "assignmentCandidates": [{k: a.get(k) for k in ("title", "confirmStatus", "dueKind", "dueSource")}
                                     for a in (assignments if isinstance(assignments, list) else [])], "checks": checks}


def s6(seeded):
    token = seeded["token"]
    conv = new_conversation(token)
    turns = [turn(token, conv, "자료구조 오늘 하루 계획 짜줘.")]
    if turns[-1].get("responseType") != "OFFER":
        turns.append(turn(token, conv, None, "PLAN_NOW"))
    first = plan_from(token, conv, turns[-1], course_ids=[seeded["courses"]["자료구조"]])
    before = observe(token, first.get("draft"))
    status, confirmed = call("POST", f"/api/plans/proposals/{before.get('proposalId')}/confirm", token, {})
    if status != 200:
        return {"turns": turns, "before": before, "checks": {"confirmed": False}, "confirmError": confirmed}
    _, items = call("GET", f"/api/plans/{confirmed['planVersionId']}/items", token)
    _, live = call("GET", f"/api/execution-items/range?startDate={date.today().isoformat()}"
                          f"&endDate={(date.today() + timedelta(days=2)).isoformat()}&includeUnscheduled=true", token)
    ids = {x["executionItemId"] for x in items or []}
    target = next((i for i in live or [] if i["executionItemId"] in ids), None)
    if target:
        must("POST", f"/api/execution-items/{target['executionItemId']}/partial", token,
             {"version": target["version"], "completionPercent": 30, "blockerKind": "CONCEPT"})
        log(f"   부분 수행 기록(시간 미기록, 이유=개념): {target['title']}")
    conv2 = new_conversation(token)
    t2 = [turn(token, conv2, "아까 하던 자료구조 다 못 했어. 내일 하루로 다시 짜줘.")]
    if t2[-1].get("responseType") != "OFFER":
        t2.append(turn(token, conv2, None, "PLAN_NOW"))
    today = date.today() + timedelta(days=1)
    offer = t2[-1].get("offer") or {}
    if not offer.get("periodStartDate"):
        t2[-1]["offer"] = {"type": "CREATE_PERIOD_PLAN", "periodStartDate": today.isoformat(), "periodEndDate": today.isoformat(),
                           "intensity": "NORMAL", "courseIds": [seeded["courses"]["자료구조"]]}
    second = plan_from(token, conv2, t2[-1])
    after = observe(token, second.get("draft"))
    _, records = call("GET", f"/api/execution-records?startDate={date.today().isoformat()}&endDate={date.today().isoformat()}", token)
    pid = after.get("proposalId")
    _, trace = call("GET", f"/api/plans/drafts/{pid}/trace?includeText=true", token) if pid else (0, None)
    plan_prompt = next((c.get("userPrompt") or "" for c in (trace or {}).get("calls") or [] if c["callKind"] == "PLAN"), "")
    checks = {
        "applied_only_after_confirm": status == 200,
        "partial_recorded_with_reason": bool(target),
        "unrecorded_time_not_replaced_by_planned": "실제 시간 미기록" in plan_prompt,
        "blocker_reason_reached_next_plan": "개념에서 막혔다" in plan_prompt,
        "plan_changed_after_record": [i["title"] for i in after.get("items") or []] != [i["title"] for i in before.get("items") or []],
    }
    return {"turns": turns + t2, "before": before, "after": after, "records": records, "checks": checks}


SCENARIOS = {
    "s1": (s1, list(PROJECTS)), "s2": (s2, ["파이썬 기초", "자료구조"]), "s3": (s3, ["파이썬 기초", "자료구조"]),
    "s4": (s4, ["파이썬 기초"]), "s5": (s5, ["자료구조"]), "s6": (s6, ["자료구조"]),
}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("scenarios", nargs="+")
    ap.add_argument("--repeat", type=int, default=1)
    args = ap.parse_args()
    names = list(SCENARIOS) if args.scenarios == ["all"] else args.scenarios
    results, accounts = [], []
    for name in names:
        fn, projects = SCENARIOS[name]
        # 전체 범위(s1)와 답변 반영(s4)은 한 번 더 돌려 우연한 성공만 보고하지 않는다(통계적 입증은 아니다).
        runs = max(args.repeat, 2 if name in ("s1", "s4") and args.scenarios == ["all"] else 1)
        for run in range(1, runs + 1):
            label = f"{name}#{run}"
            log(f"\n===== {label} =====")
            if SPENT["generations"] >= MAX_GENERATIONS:
                results.append({"scenario": label, "skipped": "계획 생성 상한에 닿아 실행하지 않음"})
                log("   (상한에 닿아 건너뜀)")
                continue
            seeded = seed(label.replace("#", "-"), projects)
            accounts.append(seeded["email"])
            rows = wait_analysis(seeded["token"], seeded["materials"])
            started = time.time()
            try:
                out = fn(seeded)
            except SystemExit as e:
                out = {"error": str(e), "checks": {}}
            out.update({"scenario": label, "account": seeded["email"], "seconds": round(time.time() - started, 1),
                        "analysis": {str(k): v.get("state") for k, v in (rows or {}).items()}})
            results.append(out)
            failed = [k for k, v in out.get("checks", {}).items() if not v]
            log(f"   => {label}: {'PASS' if not failed and out.get('checks') else 'CHECK ' + str(failed)}")
    stamp = time.strftime("%Y%m%d-%H%M%S")
    path = ROOT / "build" / "synthetic" / f"project-consult-{stamp}.json"
    path.parent.mkdir(parents=True, exist_ok=True)
    summary = {"base": BASE, "spent": SPENT, "limits": {"generations": MAX_GENERATIONS, "turns": MAX_TURNS},
               "accounts": accounts, "results": results}
    path.write_text(json.dumps(summary, ensure_ascii=False, indent=2, default=str), encoding="utf-8")
    log(f"\n사용: 계획 생성 {SPENT['generations']}/{MAX_GENERATIONS} · 상담 턴 {SPENT['turns']}/{MAX_TURNS}")
    log(f"결과: {path}")
    for r in results:
        failed = [k for k, v in r.get("checks", {}).items() if not v]
        log(f"  {r['scenario']}: " + (r.get("skipped") or r.get("error") or ("PASS" if not failed else "CHECK " + ", ".join(failed))))


if __name__ == "__main__":
    main()
