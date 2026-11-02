#!/usr/bin/env python3
"""
교재 목차 자동 검색(2026-10-04)을 실제 서버·실제 웹 검색·실제 모델로 끝까지 돌리고 각 단계를 남긴다.

모킹 테스트가 증명하지 못하는 것만 본다(판정 규칙은 DB 테스트가 한다):
  1. 교재 정보만 있는 강의계획서(합성 PDF — 실제 강의계획서의 표 모양을 따랐다)를 올리면, 화면을 열지 않아도 서버가
     교재 단서를 읽고 웹에서 책·판·목차를 찾는가. 같은 제목의 판이 여럿이면 고르게 하는가.
  2. 판을 고르면 그 판의 목차(원문)로 학습 구조 변경안이 자동으로 생기고, 적용하면 항목에 웹 출처가 남는가.
  3. 계획: 독학·시험 준비 목적에서 생성 입력에 교재·목적·범위 원칙이 실리고, 목차만 있는 단원에 대화문·정답을 지어내지 않는가.
  4. 상담: 정해진 교재를 다시 묻지 않는가.
  5. 정정: "실제로는 B책"으로 고치면 B로 다시 찾고, 기존 트리에는 B에만 있는 장만 더하며(이름 바꾸기·합치기 없음),
     A의 항목·기록은 지우지 않고 이전 교재 항목으로 표시하는가.
  6. 링크 입력: 책 페이지가 아닌 링크는 "못 찾음"이고 접속 실패나 지어낸 책이 아니다.

합성 계정·합성 자료만 쓴다(실제 강의계획서 원본·개인 정보 없음). 격리 DB(memo_tbook_verify)에 붙은 서버에만 돌린다.

사용:
  python scripts/ai-baseline/verify-textbook-web-toc-2026-10-04.py --base http://localhost:8096/api --out build/verify-tbook.json
"""
import argparse
import codecs
import datetime
import io
import json
import secrets
import sys
import time
import urllib.error
import urllib.request
import uuid

OUT = io.open(sys.stdout.fileno(), "w", encoding="utf-8", closefd=False)
REPORT = {"steps": []}
BASE = None


def log(msg=""):
    OUT.write(msg + "\n")
    OUT.flush()


def record(step, ok, **data):
    REPORT["steps"].append({"step": step, "ok": ok, **data})
    log(("OK  " if ok else "!!  ") + step + ("" if not data else " · " + json.dumps(data, ensure_ascii=False)[:900]))


def call(method, path, token=None, body=None, raw=None, content_type=None, timeout=400):
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
            return e.code, {"raw": text}


def multipart(files):
    boundary = "----tbook" + uuid.uuid4().hex
    out = io.BytesIO()
    for name, filename, ctype, content in files:
        out.write(("--%s\r\n" % boundary).encode())
        out.write(('Content-Disposition: form-data; name="%s"; filename="%s"\r\n' % (name, filename)).encode("utf-8"))
        out.write(("Content-Type: %s\r\n\r\n" % ctype).encode())
        out.write(content + b"\r\n")
    out.write(("--%s--\r\n" % boundary).encode())
    return out.getvalue(), "multipart/form-data; boundary=" + boundary


def wait_for(fn, predicate, timeout_s, label, every=4):
    started = time.time()
    last = None
    while time.time() - started < timeout_s:
        last = fn()
        if predicate(last):
            return last, time.time() - started
        time.sleep(every)
    log("!! %s: %.0f초 안에 끝나지 않았다" % (label, timeout_s))
    return last, time.time() - started


def syllabus_pdf():
    """실제 강의계획서 1쪽의 교재 표 모양을 따른 합성 PDF. 연락처·이름은 합성 값이다."""
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
    c.setFont("Malgun", 11)
    rows = [
        (60, 800, "교과목명  영어회화(교재 검증용 합성)"),
        (60, 780, "담당 교수   + 전 화  : 02-0000-0000"),
        (60, 765, "  + E-MAIL : prof@example.invalid"),
        (60, 740, "과목 개요   회화 중심 교양 영어. 주차별 진도는 수업에서 안내한다."),
        (60, 700, "도서명"), (300, 700, "저자"), (420, 700, "출판사"), (500, 700, "비고"),
        (300, 685, "Michael"),
        (60, 675, "주교재"), (110, 675, "NEW English Conversation Arts 1"), (420, 675, "형설출판사"),
        (300, 665, "Putlack, 이현호"),
        (60, 630, "성적평가 비율   중간 40  기말 40  출석 20"),
    ]
    for x, y, text in rows:
        c.drawString(x, y, text)
    c.showPage()
    c.save()
    return buf.getvalue()


def textbook(token, cid):
    return call("GET", "/courses/%s/textbook" % cid, token)[1] or {}


def searching(tb):
    lk = (tb or {}).get("lookup")
    return lk is not None and lk.get("status") in ("QUEUED", "RUNNING")


def wait_lookup(token, cid, timeout_s=300):
    return wait_for(lambda: textbook(token, cid), lambda tb: tb.get("lookup") and not searching(tb), timeout_s,
                    "교재 조회")


def tidy_view(token, cid):
    return call("GET", "/courses/%s/tidy" % cid, token)[1] or {}


def wait_proposal(token, cid, timeout_s=400, exclude=None):
    def ready(v):
        job = v.get("job") or {}
        if job.get("status") in ("QUEUED", "RUNNING"):
            return False
        if job.get("status") in ("FAILED", "UNAVAILABLE", "CANCELLED"):
            return True  # 실패도 끝난 것이다 — 기다리지 않고 결과로 기록한다
        if v.get("status") == "EMPTY":
            return True  # 바꿀 것이 없다는 결과도 끝난 것이다
        return v.get("proposalId") and v.get("proposalId") != exclude
    return wait_for(lambda: tidy_view(token, cid), ready, timeout_s, "정리안")


def apply_all(token, view):
    return call("POST", "/project-tidy/%s/apply" % view["proposalId"], token, {
        "revision": view["revision"], "editRevision": view.get("editRevision") or 0,
        "baseTreeVersion": view["baseTreeVersion"],
        "selectedChangeIds": [c["changeId"] for c in view["changes"]], "titleOverrides": {},
    })


def choose_newest(token, cid, tb):
    lk = tb["lookup"]
    eds = sorted(lk["editions"], key=lambda e: e.get("publishedDate") or "", reverse=True)
    status, after = call("POST", "/courses/%s/textbook/web/choose" % cid, token, {
        "lookupId": lk["lookupId"], "revisionId": eds[0]["bestRevisionId"], "expectedVersion": tb["current"]["version"]})
    return status, after, eds[0]


def send(token, conversation_id, message):
    body = {"message": message, "requestedAction": "AUTO", "idempotencyKey": uuid.uuid4().hex}
    result = {"message": message, "reply": "", "consult": None, "error": None}
    req = urllib.request.Request(BASE + "/ai/conversations/%s/messages" % conversation_id,
                                 data=json.dumps(body, ensure_ascii=False).encode("utf-8"), method="POST",
                                 headers={"Accept": "text/event-stream", "Content-Type": "application/json; charset=utf-8",
                                          "Authorization": "Bearer " + token})
    try:
        resp = urllib.request.urlopen(req, timeout=300)
    except urllib.error.HTTPError as e:
        result["error"] = "HTTP %s" % e.code
        return result
    decoder = codecs.getincrementaldecoder("utf-8")(errors="replace")
    buffer, deltas = "", ""
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
                if name == "message.delta":
                    deltas += payload.get("text", "")
                elif name == "message.completed":
                    result["reply"] = payload.get("reply") or deltas
                    result["consult"] = payload.get("consult")
                elif name == "message.error":
                    result["error"] = payload
    return result


def main():
    global BASE
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="http://localhost:8096/api")
    ap.add_argument("--out", default="build/verify-tbook.json")
    ap.add_argument("--creds", default=None, help="합성 계정 정보를 남길 파일(저장소 밖)")
    args = ap.parse_args()
    BASE = args.base
    today = datetime.date.today()

    email = "tbook-%s@memo-test.invalid" % uuid.uuid4().hex[:8]
    password = secrets.token_urlsafe(16)
    call("POST", "/auth/signup", body={"email": email, "password": password, "nickname": "교재검증"})
    status, body = call("POST", "/auth/login", body={"email": email, "password": password})
    token = body["token"]
    if args.creds:
        with open(args.creds, "w", encoding="utf-8") as f:
            f.write(json.dumps({"email": email, "password": password}))
    record("합성 계정", status == 200, email=email)

    # ===== 1. 강의계획서만 올린다(교재 정보만, 주차 진도·목차·본문 없음) =====
    status, course = call("POST", "/courses", token, {"title": "영어회화(교재 검증)"})
    cid = course["courseId"]
    raw, ctype = multipart([("file", "영어회화_강의계획서_합성.pdf", "application/pdf", syllabus_pdf())])
    status, mat = call("POST", "/courses/%s/materials?materialType=SYLLABUS" % cid, token, raw=raw, content_type=ctype)
    record("강의계획서 업로드(화면은 열지 않는다)", status in (200, 201), materialId=(mat or {}).get("materialId"))

    # 교재 GET을 부르지 않고 분석 완료 → 서버가 스스로 조회를 등록했는지 본다(DB 조회 없이 상태 API로).
    def analysis():
        return call("GET", "/materials/%s/analysis-status" % mat["materialId"], token)[1] or {}
    st, took = wait_for(analysis, lambda s: s.get("state") in ("DONE", "PARTIAL", "FAILED", "NO_TEXT", "UNAVAILABLE"),
                        600, "자료 분석")
    record("자동 분석", st.get("state") in ("DONE", "PARTIAL"), state=st.get("state"), seconds=round(took))

    tb, took = wait_lookup(token, cid, 360)
    lk = tb.get("lookup") or {}
    record("교재 단서 → 웹 조회(서버 백그라운드)", lk.get("status") in ("FOUND", "NEEDS_CHOICE"), seconds=round(took),
           syllabusClues=[{k: c.get(k) for k in ("role", "title", "author", "publisher", "source")}
                          for c in tb.get("syllabusClues", [])],
           status=lk.get("status"), sent=lk.get("query"), searchedWith=lk.get("searchedWith"),
           editions=[{k: e.get(k) for k in ("isbn13", "publishedDate", "site", "url", "tocCoverage", "tocEntryCount",
                                             "sameTocAs")} for e in lk.get("editions", [])],
           rejected=[{"url": c.get("url"), "verdict": c.get("verdict"), "reasons": c.get("reasons")}
                     for c in lk.get("candidates", []) if c.get("verdict") != "MATCH"],
           failures=lk.get("failures"), current=tb.get("current"), nextAction=tb.get("nextAction"))
    record("찾은 책을 지금 교재로 조용히 정하지 않음", not (tb.get("current") or {}).get("title"),
           current=tb.get("current"))

    if lk.get("status") == "NEEDS_CHOICE":
        status, after, chosen = choose_newest(token, cid, tb)
        record("판 선택(가장 최근 판)", status == 200, chosen={k: chosen.get(k) for k in ("isbn13", "publishedDate")})
        tb = textbook(token, cid)
    toc = tb.get("toc") or {}
    record("웹 목차 확보", toc.get("status") == "FOUND" and toc.get("kind") == "WEB", label=toc.get("label"),
           coverage=toc.get("coverage"), entries=[(e.get("number"), e.get("title"), e.get("page")) for e in toc.get("entries", [])],
           current=tb.get("current"))

    # ===== 2. 자동 정리안 → 적용 =====
    view, took = wait_proposal(token, cid, 420)
    record("목차로 정리안 자동 생성", bool(view.get("proposalId")), seconds=round(took), changes=len(view.get("changes", [])),
           tocLabel=view.get("tocLabel"), recordsTextbook=view.get("recordsTextbook"),
           sample=[c.get("text") for c in view.get("changes", [])[:4]])
    status, applied = apply_all(token, view)
    topics = call("GET", "/courses/%s/topics" % cid, token)[1] or []
    record("변경안 적용", status == 200, topics=len(topics), titles=[t["title"] for t in topics][:12])
    lmap = call("GET", "/courses/%s/learning-map" % cid, token)[1] or {}
    record("학습 지도에 교재·웹 출처", bool(lmap.get("textbook"))
           # 목차에서 온 항목(Unit n)만 웹 출처다. 혼합 모드에서 강의계획서 구간으로 만든 항목(과목 개요 등)은 출처가 없다.
           and all(t.get("tocOrigin") == "WEB" for t in lmap.get("topics", []) if t.get("title", "").startswith("Unit "))
           and sum(1 for t in lmap.get("topics", []) if t.get("tocOrigin") == "WEB") >= 12,
           textbook=lmap.get("textbook"), origins=list({t.get("tocOrigin") for t in lmap.get("topics", [])}))

    # ===== 3. 계획(실제 모델) =====
    start = today + datetime.timedelta(days=1)
    for purpose, instruction in (("SELF_STUDY", None), ("EXAM", None)):
        status, draft = call("POST", "/plans/draft", token, {
            "startDate": start.isoformat(), "endDate": (start + datetime.timedelta(days=6)).isoformat(),
            "courseIds": [cid], "purpose": purpose, "instruction": instruction, "requestKey": uuid.uuid4().hex,
        }, timeout=600)
        draft = draft or {}
        items = (draft.get("proposal") or {}).get("items") or []
        strat = draft.get("strategy") or {}
        ask = draft.get("ask")
        # 시험 준비인데 시험 범위를 모르면 묻거나(ask) 가정을 밝힌 초안이어야 한다 — 둘 다 정상이다.
        ok = status == 200 and (len(items) > 0 or ask is not None)
        record("계획 초안(%s)" % purpose, ok, status=status, proposalId=draft.get("proposalId"), ask=ask,
               items=[{"title": i.get("title"), "desc": (i.get("description") or "")[:200]} for i in items[:10]],
               questions=strat.get("questions"), assumptions=strat.get("assumptions"),
               unread=strat.get("unreadNotes"), goal=strat.get("goal") or draft.get("goalSummary"))
        if draft.get("proposalId"):
            st, trace = call("GET", "/plans/drafts/%s/trace?includeText=true" % draft["proposalId"], token)
            plan_calls = [c for c in (trace or {}).get("calls", []) if c.get("callKind", "").startswith("PLAN")]
            prompt = (plan_calls[0].get("userPrompt") or "") if plan_calls else ""
            sel = [c for c in (trace or {}).get("calls", []) if c.get("callKind", "").startswith("SELECTION")]
            sel_prompt = (sel[0].get("userPrompt") or "") if sel else ""
            label = {"SELF_STUDY": "독학", "EXAM": "시험 준비"}[purpose]
            record("생성 입력에 목적·교재·범위(%s)" % purpose,
                   ("[계획 목적: " + label) in prompt and "[교재 범위]" in prompt and "New English Conversation Arts 1" in prompt
                   and (purpose != "EXAM" or "← 첫 미학습" not in prompt),
                   purposeInPlan=("[계획 목적: " + label) in prompt, purposeInSelection=("[계획 목적: " + label) in sel_prompt,
                   textbookLine=next((l for l in prompt.splitlines() if "교재:" in l), None),
                   scopeLine=next((l.strip() for l in prompt.splitlines() if "[교재 범위]" in l), None),
                   firstAnchor="← 첫 미학습" in prompt)

    # ===== 4. 상담: 정해진 교재를 다시 묻지 않는다 =====
    status, conv = call("POST", "/ai/conversations", token, {"scope": "PLAN", "courseId": cid})
    turn = send(token, conv["conversationId"], "이 과목 교재로 다음 주에 뭐부터 공부하면 좋을까?")
    q = (turn.get("consult") or {}).get("question") or {}
    asks_textbook = "교재" in (q.get("text") or "") and ("어떤" in (q.get("text") or "") or "무슨" in (q.get("text") or ""))
    record("상담이 교재를 다시 묻지 않음", not asks_textbook and not turn.get("error"), reply=(turn.get("reply") or "")[:700],
           question=q.get("text"))

    # ===== 5. 정정: 실제로는 B책 =====
    tb = textbook(token, cid)
    status, _ = call("PATCH", "/courses/%s" % cid, token, {
        "title": "영어회화(교재 검증)", "textbookTitle": "C로 배우는 쉬운 자료구조", "textbookAuthor": "이지영",
        "textbookPublisher": "한빛아카데미", "textbookIsbn": None, "textbookEdition": None,
        "expectedTextbookVersion": tb["current"]["version"]})
    record("교재 정정(A→B)", status == 200)
    tb, took = wait_lookup(token, cid, 360)
    lk = tb.get("lookup") or {}
    record("B로 다시 찾음(A로 되돌아가지 않음)", (lk.get("query") or {}).get("title") == "C로 배우는 쉬운 자료구조",
           status=lk.get("status"), sent=lk.get("query"),
           editions=[{k: e.get(k) for k in ("isbn13", "publishedDate", "title", "tocEntryCount", "tocCoverage")}
                     for e in lk.get("editions", [])])
    prev_proposal = view.get("proposalId")
    if lk.get("status") == "NEEDS_CHOICE":
        status, after, chosen = choose_newest(token, cid, tb)
        record("B 판 선택(가장 최근 판)", status == 200, chosen={k: chosen.get(k) for k in ("isbn13", "publishedDate", "title")})
        tb = textbook(token, cid)
    record("B 목차", (tb.get("toc") or {}).get("status") == "FOUND", label=(tb.get("toc") or {}).get("label"),
           entries=len((tb.get("toc") or {}).get("entries", [])), current=tb.get("current"))
    if (tb.get("toc") or {}).get("status") == "FOUND":
        view2, took = wait_proposal(token, cid, 480, exclude=prev_proposal)
        ops = [c.get("op") for c in view2.get("changes", [])]
        record("기존 트리 + B 목차 → 정리안", bool(view2.get("proposalId")) and len(ops) > 0, status=view2.get("status"), job=view2.get("job"), seconds=round(took), ops=ops,
               structural=[c.get("text") for c in view2.get("changes", []) if c.get("op") in ("RENAME", "MERGE", "SPLIT", "MOVE")],
               sample=[c.get("text") for c in view2.get("changes", [])[:6]], summary=(view2.get("summary") or {}).get("headline"))
        if view2.get("proposalId") and ops:
            status, _ = apply_all(token, view2)
            lmap = call("GET", "/courses/%s/learning-map" % cid, token)[1] or {}

            def walk(ns, out):
                for n in ns or []:
                    out.append(n)
                    walk(n.get("children"), out)
                return out
            flat = walk(lmap.get("topics"), [])
            prior = [t["title"] for t in flat if t.get("priorTextbook")]
            record("B 적용 뒤: A 항목은 지우지 않고 이전 교재로 표시", status == 200 and len(prior) >= 12,
                   textbook=lmap.get("textbook"), prior=len(prior), total=len(flat),
                   newTitles=[t["title"] for t in flat if not t.get("priorTextbook")][:10])

    # ===== 6. 책 페이지가 아닌 링크 =====
    status, after = call("POST", "/courses/%s/textbook/web/link" % cid, token, {"url": "https://example.com/"})
    tb, took = wait_lookup(token, cid, 120)
    lk = tb.get("lookup") or {}
    record("책 페이지가 아닌 링크는 못 찾음", lk.get("status") == "NOT_FOUND", status=lk.get("status"), note=lk.get("note"),
           failures=lk.get("failures"))
    status, bad = call("POST", "/courses/%s/textbook/web/link" % cid, token, {"url": "http://127.0.0.1:8096/api"})
    record("내부 주소 링크는 받지 않음", status == 400, code=(bad or {}).get("code"))

    with open(args.out, "w", encoding="utf-8") as f:
        json.dump(REPORT, f, ensure_ascii=False, indent=2)
    fails = [s["step"] for s in REPORT["steps"] if not s["ok"]]
    log("\n실패 %d개: %s" % (len(fails), fails))


if __name__ == "__main__":
    main()
