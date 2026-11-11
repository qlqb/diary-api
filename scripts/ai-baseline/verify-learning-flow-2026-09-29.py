#!/usr/bin/env python3
"""
계획 이해·학습 실행·교재와 실제 수업 반영(2026-09-29)을 실제 서버·실제 모델로 끝까지 돌리고 각 단계를 남긴다.

모킹 테스트가 증명하지 못하는 것만 본다(판정 규칙은 DB 테스트가 한다):
  1. 교재: 합성 PDF(표지·판권면·목차·본문)를 올리면 자동 분석 흐름에서 교재 서지·목차가 후보로 뜨는가(옛 버튼 없이).
     제목만 있는 과목은 목차 미확보로 말하는가.
  2. 목차 → 첫 골격: 빈 트리에서 정리를 누르면 목차 장·절이 골격으로 오고, 강의·실습 구간이 그 장에 이어지는가(실제 모델).
  3. 계획: 기록 없는 과목에서 초안의 목표·범위·가정·항목의 할 일/완료 기준/시작 자료가 채워지는가.
  4. 실행: 적용 뒤 작업 공간·자세히(모델)·메모·시작 도움(모델)이 같은 항목에 이어지는가, 부분 수행 뒤 남은 조각에서도.
  5. 기록 → 다음 계획: 도움받아 일부 수행하고 막힌 단계를 남긴 뒤 새 초안의 입력(전달 기록)에 그 사실이 관련 항목으로
     실리는가, 전략의 달라진 점에 기록이 드러나는가(모델 판단 — 결과를 그대로 적는다).
  6. 구조 조정: "교재 2장을 1장보다 먼저 수업했어 / 중간고사에는 3장이 빠져"를 해석해 CLASS·SCOPE_EXCLUDE로 옮기는가,
     적용 뒤 계획 후보에서 3장이 빠지는가(사유 SCOPE:중간고사).

합성 계정·합성 자료만 쓴다. 격리 DB(memo_flow_verify)에 붙은 서버(8081)에만 돌린다. 비밀번호는 출력하지 않는다.

사용:
  python scripts/ai-baseline/verify-learning-flow-2026-09-29.py --base http://localhost:8081/api --out build/verify-flow.json
"""
import argparse
import datetime
import io
import json
import os
import secrets
import sys
import time
import urllib.error
import urllib.request
import uuid

OUT = io.open(sys.stdout.fileno(), "w", encoding="utf-8", closefd=False)
REPORT = {"steps": []}


def log(msg=""):
    OUT.write(msg + "\n")
    OUT.flush()


def record(step, ok, **data):
    REPORT["steps"].append({"step": step, "ok": ok, **data})
    log(("OK  " if ok else "!!  ") + step + ("" if not data else " · " + json.dumps(data, ensure_ascii=False)[:600]))


def call(base, method, path, token=None, body=None, raw=None, content_type=None, timeout=400):
    headers = {}
    data = None
    if raw is not None:
        data = raw
        headers["Content-Type"] = content_type
    elif body is not None:
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
        headers["Content-Type"] = "application/json; charset=utf-8"
    if token:
        headers["Authorization"] = "Bearer " + token
    req = urllib.request.Request(base + path, data=data, method=method, headers=headers)
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


def multipart(fields, files):
    boundary = "----flow" + uuid.uuid4().hex
    out = io.BytesIO()
    for key, value in fields.items():
        out.write(("--%s\r\n" % boundary).encode())
        out.write(('Content-Disposition: form-data; name="%s"\r\n\r\n' % key).encode())
        out.write(str(value).encode("utf-8") + b"\r\n")
    for name, filename, ctype, content in files:
        out.write(("--%s\r\n" % boundary).encode())
        out.write(('Content-Disposition: form-data; name="%s"; filename="%s"\r\n' % (name, filename)).encode("utf-8"))
        out.write(("Content-Type: %s\r\n\r\n" % ctype).encode())
        out.write(content + b"\r\n")
    out.write(("--%s--\r\n" % boundary).encode())
    return out.getvalue(), "multipart/form-data; boundary=" + boundary


def wait_for(fn, predicate, timeout_s, label):
    started = time.time()
    last = None
    while time.time() - started < timeout_s:
        last = fn()
        if predicate(last):
            return last, time.time() - started
        time.sleep(4)
    log("!! %s: %.0f초 안에 끝나지 않았다" % (label, timeout_s))
    return last, time.time() - started


# ---------------------------------------------------------------------
# 합성 PDF (reportlab + 맑은 고딕). 내용은 이 스크립트가 지어낸 것이다.
# ---------------------------------------------------------------------

def pdf(pages):
    from reportlab.lib.pagesizes import A4
    from reportlab.pdfbase import pdfmetrics
    from reportlab.pdfbase.ttfonts import TTFont
    from reportlab.pdfgen import canvas
    try:
        pdfmetrics.registerFont(TTFont("Malgun", "C:/Windows/Fonts/malgun.ttf"))
    except Exception:
        pass
    buf = io.BytesIO()
    c = canvas.Canvas(buf, pagesize=A4)
    for lines in pages:
        y = 800
        for line in lines:
            c.setFont("Malgun", 12)
            c.drawString(60, y, line)
            y -= 22
        c.showPage()
    c.save()
    return buf.getvalue()


TEXTBOOK = [
    ["쉽게 배우는 자료구조 (검증용 합성 교재)", "개정 4판", "예시 저자 지음"],
    ["쉽게 배우는 자료구조 (개정 4판)", "개정 4판 1쇄 발행 2025년 3월 2일", "지은이 예시 저자",
     "펴낸곳 가상출판사", "ISBN 979-11-5664-567-2 93000"],
    ["목차", "CHAPTER 01 자료구조와 알고리즘 ........ 13", "1.1 자료와 정보 ........ 14",
     "1.2 알고리즘의 성능 분석 ........ 20", "CHAPTER 02 배열 ........ 41", "2.1 배열의 개념 ........ 42",
     "2.2 다차원 배열 ........ 48", "CHAPTER 03 연결 리스트 ........ 71", "3.1 단순 연결 리스트 ........ 72",
     "3.2 이중 연결 리스트 ........ 88", "CHAPTER 04 스택 ........ 101", "4.1 스택의 연산 ........ 102"],
    ["CHAPTER 01 자료구조와 알고리즘", "자료구조는 자료를 효율적으로 저장하고 꺼내는 방법이다.",
     "알고리즘의 성능은 시간 복잡도와 공간 복잡도로 분석한다. 빅오 표기법을 쓴다.",
     "연습문제 1-1: 반복문 두 개가 중첩된 코드의 시간 복잡도를 구하라."],
    ["CHAPTER 02 배열", "배열은 같은 형의 원소를 연속된 메모리에 저장한다. 인덱스로 O(1)에 접근한다.",
     "예제 2-1: 크기 10인 정수 배열을 선언하고 합을 구한다.",
     "연습문제 2-3: 배열에서 최댓값의 위치를 찾는 함수를 직접 작성하라."],
    ["CHAPTER 03 연결 리스트", "연결 리스트는 노드가 다음 노드의 주소를 가리킨다.",
     "예제 3-1: 빈 리스트에 첫 노드를 삽입할 때 head를 새 노드로 바꾼다.",
     "연습문제 3-2: 중간 삽입과 끝 삽입 함수를 직접 작성하고 빈 리스트·한 개·여러 개에서 확인하라."],
]

LECTURE = [
    ["2주차 강의: 배열과 시간 복잡도", "오늘 수업: 교재 2장 배열, 1장의 성능 분석을 함께 본다.",
     "실습: 배열 원소의 합과 최댓값을 구하는 함수를 작성한다.", "다음 시간: 연결 리스트"],
    ["배열 실습 안내", "1) 예제 2-1을 따라 친다  2) 조건만 바꿔 실행한다  3) 최댓값 함수를 직접 작성한다",
     "완료 기준: 세 가지 입력에서 결과가 맞는지 확인한다."],
]

LAB = [
    ["3주차 실습: 연결 리스트 삽입", "과제 아님(연습). 교재 3.1절 예제 3-1을 먼저 따라 친다.",
     "1) 빈 리스트에 첫 노드 삽입  2) 중간 삽입  3) 끝 삽입을 직접 작성한다.",
     "막히면 교재 그림 3-2의 포인터 변화를 손으로 그린다."],
]

SYLLABUS = [
    ["자료구조 강의계획서 (검증용 합성)", "교재: 쉽게 배우는 자료구조(가상출판사)", "1주차 오리엔테이션",
     "2주차 배열", "3주차 연결 리스트", "4주차 스택", "8주차 중간고사", "15주차 기말고사"],
]


def upload(base, token, course_id, filename, content, material_type):
    raw, ctype = multipart({}, [("file", filename, "application/pdf", content)])
    return call(base, "POST", "/courses/%s/materials?materialType=%s" % (course_id, material_type), token,
                raw=raw, content_type=ctype)


def wait_analysis(base, token, material_ids, timeout_s):
    def statuses():
        return [call(base, "GET", "/materials/%s/analysis-status" % m, token)[1] or {} for m in material_ids]
    return wait_for(statuses, lambda ss: all((s.get("state") in ("DONE", "PARTIAL", "FAILED", "NO_TEXT", "UNAVAILABLE"))
                                            for s in ss), timeout_s, "자료 분석")


def tidy_until_done(base, token, course_id, timeout_s, body=None, refresh=True):
    status, view = call(base, "POST", "/courses/%s/tidy?refresh=%s" % (course_id, "true" if refresh else "false"),
                        token, body=body)
    view, took = wait_for(lambda: call(base, "GET", "/courses/%s/tidy" % course_id, token)[1],
                          lambda v: v and not (v.get("job") or {}).get("status") in ("QUEUED", "RUNNING"),
                          timeout_s, "정리")
    return view, took


def apply_all(base, token, view, exclude=()):
    return call(base, "POST", "/project-tidy/%s/apply" % view["proposalId"], token, {
        "revision": view["revision"], "editRevision": view.get("editRevision") or 0,
        "baseTreeVersion": view["baseTreeVersion"],
        "selectedChangeIds": [c["changeId"] for c in view["changes"] if c["changeId"] not in exclude],
        "titleOverrides": {},
    })


def topics_flat(base, token, course_id):
    status, topics = call(base, "GET", "/courses/%s/topics" % course_id, token)
    out = []

    def walk(ns, depth=0):
        for n in ns or []:
            out.append((depth, n["topicId"], n["title"]))
            walk(n.get("children"), depth + 1)
    walk(topics)
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="http://localhost:8081/api")
    ap.add_argument("--out", default="build/verify-flow.json")
    ap.add_argument("--creds", default=None, help="합성 계정 정보를 남길 파일(저장소 밖)")
    args = ap.parse_args()
    base = args.base
    today = datetime.date.today()

    email = "flow-%s@memo-test.invalid" % uuid.uuid4().hex[:8]
    password = secrets.token_urlsafe(16)
    call(base, "POST", "/auth/signup", body={"email": email, "password": password, "nickname": "흐름검증"})
    status, body = call(base, "POST", "/auth/login", body={"email": email, "password": password})
    token = body["token"]
    if args.creds:
        with open(args.creds, "w", encoding="utf-8") as f:
            f.write(json.dumps({"email": email, "password": password}))
    record("합성 계정", status == 200, email=email)

    # ===== 1. 교재 =====
    status, course = call(base, "POST", "/courses", token, {"title": "자료구조(흐름 검증)"})
    cid = course["courseId"]
    ids = {}
    for name, pages, mtype in [("쉽게배우는자료구조_앞부분.pdf", TEXTBOOK, "TEXTBOOK_TOC"),
                               ("2주차_배열_강의.pdf", LECTURE, "PROFESSOR_SLIDE"),
                               ("3주차_연결리스트_실습.pdf", LAB, "OTHER"),
                               ("강의계획서.pdf", SYLLABUS, "SYLLABUS")]:
        status, res = upload(base, token, cid, name, pdf(pages), mtype)
        ids[name] = (res or {}).get("materialId")
        record("업로드 " + name, status in (200, 201), materialId=ids[name])
    statuses, took = wait_analysis(base, token, list(ids.values()), 900)
    record("자동 분석 종료", all(s.get("state") in ("DONE", "PARTIAL") for s in statuses), seconds=round(took),
           states=[s.get("state") for s in statuses])

    status, tb = call(base, "GET", "/courses/%s/textbook" % cid, token)
    fields = {f["field"]: f["value"] for c in (tb or {}).get("candidates", []) for f in c["fields"]}
    record("교재 후보(옛 버튼 없이)", status == 200 and fields.get("isbn") == "9791156645672", state=tb.get("state"),
           fields=fields, toc=(tb.get("toc") or {}).get("entryCount"), nextAction=tb.get("nextAction"))
    cand = next((c for c in tb.get("candidates", []) if any(f["field"] == "isbn" for f in c["fields"])), None)
    if cand:
        pick = {f["field"]: f["value"] for f in cand["fields"] if f["field"] in ("isbn", "edition", "publisher")}
        status, tb2 = call(base, "POST", "/courses/%s/textbook/apply" % cid, token,
                           {"materialId": cand["materialId"], "values": pick, "expected": {k: None for k in pick}})
        record("교재 칸 적용", status == 200 and tb2["current"]["source"] == "MATERIAL", current=tb2.get("current"))
        status, again = call(base, "POST", "/courses/%s/textbook/apply" % cid, token,
                             {"materialId": cand["materialId"], "values": pick, "expected": {k: None for k in pick}})
        record("옛 값을 본 적용은 409", status == 409, code=(again or {}).get("code"))

    status, course2 = call(base, "POST", "/courses", token, {"title": "네트워크(제목만)"})
    call(base, "PATCH", "/courses/%s" % course2["courseId"], token,
         {"title": "네트워크(제목만)", "textbookTitle": "가상 네트워크 입문"})
    status, tb_only = call(base, "GET", "/courses/%s/textbook" % course2["courseId"], token)
    record("제목만 있는 과목", tb_only.get("state") == "TITLE_ONLY" and tb_only["toc"]["entries"] == [],
           state=tb_only.get("state"), nextAction=tb_only.get("nextAction"))

    # ===== 2. 목차 → 골격 =====
    view, took = tidy_until_done(base, token, cid, 600)
    adds = [c for c in view.get("changes", []) if c["op"] == "ADD"]
    toc_adds = [c for c in adds if c.get("by") == "TOC"]
    links_to_new = [c for c in view.get("changes", []) if c["op"] == "LINK" and (c.get("payload") or {}).get("topicId") is None]
    record("정리안: 목차 골격 + 구간 연결", len(toc_adds) >= 3, seconds=round(took), adds=len(adds), tocAdds=len(toc_adds),
           linksToSkeleton=len(links_to_new), headline=(view.get("summary") or {}).get("headline"),
           texts=[c["text"] for c in view.get("changes", [])][:12])
    status, applied = apply_all(base, token, view)
    tree = topics_flat(base, token, cid)
    record("정리 적용", status == 200, topics=[("  " * d) + t for d, _, t in tree][:30])

    # ===== 3. 계획(기록 없음) =====
    start = today
    end = today + datetime.timedelta(days=6)
    status, draft = call(base, "POST", "/plans/draft", token, {
        "startDate": start.isoformat(), "endDate": end.isoformat(), "intensity": "NORMAL",
        "courseIds": [cid], "instruction": "처음 보는 과목이라 이번 주에 수업을 따라갈 수 있게 해 줘",
        "requestKey": uuid.uuid4().hex})
    items = ((draft or {}).get("proposal") or {}).get("items") or []
    strat = (draft or {}).get("strategy") or {}
    record("초안 생성", status == 200 and len(items) > 0, items=len(items), goal=strat.get("goal"),
           assumptions=strat.get("assumptions"), unread=strat.get("unreadNotes"),
           first=[{"title": i["title"], "description": i.get("description"), "doneCriteria": i.get("doneCriteria"),
                   "startSource": i.get("startSource")} for i in items[:3]])
    with_start = [i for i in items if i.get("startSource")]
    record("항목의 시작 자료(인용 근거)", len(with_start) > 0, withStart=len(with_start), total=len(items))
    status, reloaded = call(base, "GET", "/plans/proposals/%s/draft" % draft["proposalId"], token)
    record("새로고침한 초안의 목표", bool((reloaded or {}).get("goalSummary")), goalSummary=(reloaded or {}).get("goalSummary"))

    first_item = items[0]
    status, det = call(base, "POST", "/plans/drafts/items/%s/detail" % first_item["proposalItemId"], token)
    record("초안 항목 자세히(모델)", status == 200 and det.get("available"), steps=len((det or {}).get("steps") or []))
    status, memo = call(base, "PUT", "/plans/drafts/items/%s/memo" % first_item["proposalItemId"], token,
                        {"userText": "예제부터 따라 치기"})
    record("초안에서 메모", status == 200 and memo.get("userText") == "예제부터 따라 치기")

    status, plan = call(base, "POST", "/plans/proposals/%s/confirm" % draft["proposalId"], token, {"title": "흐름 검증 주간"})
    record("초안 적용", status == 200, planVersionId=(plan or {}).get("planVersionId"), goalSummary=(plan or {}).get("goalSummary"))
    status, created = call(base, "GET", "/plans/%s/items" % plan["planVersionId"], token)
    exec_items = created or []
    target = next((e for e in exec_items if e["title"] == first_item["title"]), exec_items[0])
    eid = target["executionItemId"]

    # ===== 4. 실행 =====
    status, ws = call(base, "GET", "/execution-items/%s/workspace" % eid, token)
    record("작업 공간(확정 항목)", status == 200 and ws.get("proposalItemId") == first_item["proposalItemId"],
           guidanceState=ws.get("guidanceState"), memo=((ws.get("guidance") or {}).get("userText")),
           startSource=ws.get("startSource"), doneCriteria=ws.get("doneCriteria"))
    status, help1 = call(base, "POST", "/execution-items/%s/start-help" % eid, token,
                         {"kind": "WHERE_TO_START", "text": "어디서 시작할지 모르겠어요"})
    record("시작 도움(모델)", status == 200 and bool((help1 or {}).get("firstAction")), help=help1)

    # 오늘 날짜로 두고 일부 수행 + 도움받음 + 막힌 단계
    if not target.get("scheduledDate"):
        call(base, "POST", "/execution-items/%s/move" % eid, token, {"version": target["version"], "toDate": today.isoformat()})
        status, ws = call(base, "GET", "/execution-items/%s/workspace" % eid, token)
    version = ws["item"]["version"]
    status, _ = call(base, "POST", "/execution-items/%s/partial" % eid, token, {
        "version": version, "completionPercent": 50, "actualMinutes": 35, "blockerKind": "CONCEPT",
        "supportLevel": "GUIDED", "stuckStep": "조건을 바꾸는 건 됐지만 함수를 직접 작성하는 단계에서 막힘"})
    record("일부 수행 기록(도움받음·막힌 단계)", status == 200)
    status, recs = call(base, "GET", "/execution-items/records?startDate=%s&endDate=%s" % (today, today), token)
    remainder = next((r for r in recs or [] if r["executionItemId"] == eid), {})
    status, ws_rem = call(base, "GET", "/execution-items/%s/workspace" % (target["executionItemId"]), token)
    rem_id = None
    status, day_items = call(base, "GET", "/execution-items/by-course/%s" % cid, token)
    for it in day_items or []:
        if it.get("sourceExecutionItemId") == eid:
            rem_id = it["executionItemId"]
    if rem_id:
        status, ws2 = call(base, "GET", "/execution-items/%s/workspace" % rem_id, token)
        record("남은 조각의 작업 공간: 같은 안내·메모·앞 기록", status == 200
               and ws2.get("proposalItemId") == first_item["proposalItemId"]
               and ((ws2.get("guidance") or {}).get("userText") == "예제부터 따라 치기")
               and any(r.get("supportLevel") == "GUIDED" for r in ws2.get("records", [])),
               startHelpKept=bool(ws2.get("startHelp")), records=len(ws2.get("records", [])))
    else:
        record("남은 조각 찾기", False)

    # ===== 5. 기록 → 다음 계획 =====
    status, draft2 = call(base, "POST", "/plans/draft", token, {
        "startDate": (today + datetime.timedelta(days=1)).isoformat(),
        "endDate": (today + datetime.timedelta(days=7)).isoformat(), "intensity": "NORMAL",
        "courseIds": [cid], "instruction": "지난번 해 본 결과를 반영해서 다음 계획을 짜 줘", "requestKey": uuid.uuid4().hex})
    if status != 200 or not (draft2 or {}).get("proposalId"):
        record("다음 계획 초안", False, status=status, body=draft2)
        return finish(args)
    status, trace = call(base, "GET", "/plans/drafts/%s/trace?includeText=true" % draft2["proposalId"], token)
    prompts = "\n".join((c.get("userPrompt") or "") for c in (trace or {}).get("calls", []) if c.get("callKind", "").startswith("PLAN"))
    s2 = draft2.get("strategy") or {}
    record("다음 계획 입력에 사용자 진술이 실린다", "설명·예제를 보고 수행함" in prompts and "막힌 단계" in prompts,
           snippet=[l for l in prompts.splitlines() if "사용자 진술" in l][:2])
    record("다음 계획의 달라진 점(모델 판단, 참고)", True, changes=s2.get("changes"), assumptions=s2.get("assumptions"),
           items=[i["title"] for i in ((draft2.get("proposal") or {}).get("items") or [])][:8])

    # ===== 6. 구조 조정 =====
    tree = topics_flat(base, token, cid)
    status, req = call(base, "POST", "/courses/%s/structure/requests" % cid, token,
                       {"text": "교재 2장 배열을 1장보다 먼저 수업했어. 그리고 이번 중간고사에는 4장 스택이 빠져."})
    tv = (req or {}).get("tidy") or {}
    ops = [c["op"] for c in tv.get("changes", []) if c.get("by") == "REQUEST"]
    record("구조 조정 요청 해석", status == 200 and "CLASS" in ops and "SCOPE_EXCLUDE" in ops and "MOVE" not in ops,
           ops=ops, summary=(req or {}).get("summary"), question=(req or {}).get("question"), dropped=(req or {}).get("dropped"),
           texts=[c["text"] for c in tv.get("changes", []) if c.get("by") == "REQUEST"])
    if tv.get("proposalId"):
        status, applied = apply_all(base, token, tv)
        record("조정 적용", status == 200)
        status, corr = call(base, "GET", "/courses/%s/corrections" % cid, token)
        record("정정 유지(실제 수업·범위)", status == 200, corrections=corr)
        status, draft3 = call(base, "POST", "/plans/draft", token, {
            "startDate": (today + datetime.timedelta(days=1)).isoformat(),
            "endDate": (today + datetime.timedelta(days=7)).isoformat(), "intensity": "NORMAL",
            "courseIds": [cid], "instruction": "중간고사 준비", "requestKey": uuid.uuid4().hex})
        excluded = ((draft3 or {}).get("materialSelection") or {}).get("excludedTopics") or []
        record("계획 후보에서 범위 제외", any(str(e.get("reason", "")).startswith("SCOPE") for e in excluded),
               excluded=excluded, items=[i["title"] for i in ((draft3.get("proposal") or {}).get("items") or [])][:8])

    return finish(args)


def finish(args):
    os.makedirs(os.path.dirname(args.out) or ".", exist_ok=True)
    with open(args.out, "w", encoding="utf-8") as f:
        json.dump(REPORT, f, ensure_ascii=False, indent=2)
    failed = [s["step"] for s in REPORT["steps"] if not s["ok"]]
    log("\n실패 %d개: %s" % (len(failed), failed))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
