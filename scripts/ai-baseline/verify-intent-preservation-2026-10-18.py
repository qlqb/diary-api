#!/usr/bin/env python3
"""
사용자 의도 보존(2026-10-18, 계획 docs/handoff/plan-intent-preservation-plan-2026-10-18.md) 4단계 — 같은 요청을 처음부터 다시 돌린다.

초안 4453과 같은 구조를 합성 계정에 만든다: 4과목, 버전·복사 접미사가 붙은 파일명, 스마트앱에 SQLite 자료 없음, 영어회화는 강의계획서뿐.
그 사용자가 실제로 보낸 요청 문장(시험 안내 포함)을 상담으로 보내고, 대화에서 오늘 하루 초안을 만든 뒤 완료 기준 1~5로 대조한다.

  1. 사용자가 직접 말한 목표·범위·시험 방식이 미승인 AI 제안으로 낮아지지 않는다(합의 항목·요청 범위 확인 여부)
  2. 과목별 시험 범위가 반영되거나 이유가 드러난다(부분별 반영·확보 상태)
  3. 자료 미확보 때문에 목표가 범위 확인으로 바뀌지 않는다(스마트앱 코드 완성 연습)
  4. 필수가 덜 된 채 추가 과목을 넣었으면 그 판단을 설명한다(검증 결과)
  5. 처리하지 못한 범위가 있는데 완료처럼 보이지 않는다(서버 안내 문장)

합성 계정·합성 자료만 쓴다. 격리 DB(memo_intent_verify)에 붙은 서버(8081)에만 돌린다. 비밀번호는 출력하지 않는다.

사용:
  python scripts/ai-baseline/verify-intent-preservation-2026-10-18.py --base http://localhost:8081 --out build/verify-intent.json
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
REPORT = {"steps": [], "criteria": {}}
BASE = "http://localhost:8081"
DB = "memo_intent_verify"


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


def multipart(files):
    boundary = "----intent" + uuid.uuid4().hex
    out = io.BytesIO()
    for name, filename, ctype, content in files:
        out.write(("--%s\r\n" % boundary).encode())
        out.write(('Content-Disposition: form-data; name="%s"; filename="%s"\r\n' % (name, filename)).encode("utf-8"))
        out.write(("Content-Type: %s\r\n\r\n" % ctype).encode())
        out.write(content + b"\r\n")
    out.write(("--%s--\r\n" % boundary).encode())
    return out.getvalue(), "multipart/form-data; boundary=" + boundary


def wait_for(fn, predicate, timeout_s, label):
    started = time.time()
    value = fn()
    while not predicate(value):
        if time.time() - started > timeout_s:
            log("   (%s 대기 시간 초과)" % label)
            break
        time.sleep(5)
        value = fn()
    return value, time.time() - started


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
            c.drawString(50, y, line)
            y -= 22
        c.showPage()
    c.save()
    return buf.getvalue()


# ---------------------------------------------------------------------
# 합성 자료. 내용은 이 스크립트가 지어낸 것이다(실제 수업 자료가 아니다).
# ---------------------------------------------------------------------

def pages(title, topics):
    out = [[title, "검증용 합성 강의 자료", "이번 시간에 다루는 것: " + ", ".join(t for t, _ in topics)]]
    for t, body in topics:
        out.append([t] + body)
    return out


MATERIALS = {
    "빅데이터분석": [
        ("빅데이터분석.pdf", "SYLLABUS", [["빅데이터분석 강의계획서(합성)", "1주차 개요", "2주차 파이썬 기초", "3주차 넘파이",
                                        "4~5주차 판다스", "6~7주차 붓꽃·타이타닉 분석", "8주차 중간고사"]]),
        ("파이썬기초_S.pdf", "PROFESSOR_SLIDE", pages("2주차 파이썬 기초", [
            ("변수와 자료형", ["정수·실수·문자열·불리언", "예제: a = 3; b = 2.5; print(a + b)"]),
            ("리스트와 딕셔너리", ["리스트 슬라이싱 a[1:3]", "딕셔너리 d['key']", "연습: 학생 점수 딕셔너리 평균 구하기"]),
            ("조건문과 반복문", ["if/elif/else", "for i in range(5)", "연습: 1부터 100까지 짝수 합"])])),
        ("3_넘파이_S.pdf", "PROFESSOR_SLIDE", pages("3주차 넘파이", [
            ("배열 생성", ["np.array, np.arange, np.zeros", "shape, dtype"]),
            ("인덱싱과 브로드캐스팅", ["불리언 인덱싱 arr[arr > 0]", "브로드캐스팅 규칙", "연습: 2차원 배열 행 평균"])])),
        ("4.판다스실습_S.pdf", "OTHER", pages("4주차 판다스 실습", [
            ("데이터 확인", ["head(), info(), describe()", "value_counts()"]),
            ("선택과 결측", ["loc, iloc", "isnull().sum(), dropna(), fillna()", "연습: 결측치를 평균으로 채우기"]),
            ("그룹핑", ["groupby().mean()", "merge", "연습: 성별 평균 점수"])])),
        ("6.판다스활용_붓꽃데이터_타이타닉S (1).pdf", "OTHER", pages("6주차 붓꽃·타이타닉 분석", [
            ("붓꽃 데이터", ["seaborn load_dataset('iris')", "품종별 groupby 평균", "산점도"]),
            ("타이타닉 전처리", ["Age 결측치 중앙값으로", "Embarked 최빈값", "Cabin 열 삭제"]),
            ("생존 분석", ["성별·선실 등급별 생존율", "corr()와 히트맵", "과제: 항구별 생존율 표"])])),
    ],
    "네트워크프로그래밍": [
        ("네트워크프로그래밍.pdf", "SYLLABUS", [["네트워크프로그래밍 강의계획서(합성)", "2주차 리눅스와 실습 환경", "3주차 AWS와 SSH",
                                            "6주차 네트워크와 소켓", "7주차 소켓 시작하기와 주소 구조체", "8주차 중간고사"]]),
        ("2.리눅스_개요와_실습환경_구축_WSL2추가_v2.pdf", "PROFESSOR_SLIDE", pages("2주차 리눅스 개요와 실습 환경", [
            ("리눅스 개요", ["커널과 셸", "배포판"]),
            ("WSL2 실습 환경", ["wsl --install", "Ubuntu 사용자 만들기"]),
            ("기본 명령", ["ls, cd, pwd, mkdir", "연습: 홈에 week02 폴더 만들기"])])),
        ("3.AWS_구성하기_SSH실습.pdf", "PROFESSOR_SLIDE", pages("3주차 AWS 구성하기와 SSH", [
            ("서버와 클라이언트", ["OSI 7계층 개요", "서버·클라이언트 역할"]),
            ("SSH 접속", ["ssh 사용자@주소", "접속 오류 확인"]),
            ("EC2 설치 절차", ["인스턴스 생성 단계(시험 제외)", "보안 그룹 설정(시험 제외)"])])),
        ("06.네트워크와_소켓_프로그래밍_v5 (1).pdf", "PROFESSOR_SLIDE", pages("6주차 네트워크와 소켓 프로그래밍", [
            ("TCP/IP 구조", ["네트워크 접근·인터넷·전송·응용 계층"]),
            ("TCP와 UDP", ["연결 지향과 비연결", "신뢰성 비교"]),
            ("IP 주소와 포트", ["IPv4 주소", "포트 번호와 서비스"])])),
        ("07.소켓_시작하기와_주소_구조체_코드로_이해하기_v8.pdf", "PROFESSOR_SLIDE", pages("7주차 소켓 시작하기와 주소 구조체", [
            ("TCP 서버 흐름", ["socket → bind → listen → accept → recv/send → close"]),
            ("TCP 클라이언트 흐름", ["socket → connect → send/recv → close"]),
            ("주소 구조체", ["sockaddr_in, htons, inet_pton", "연습: 서버 주소 구조체 채우기(56번 장표까지)"]),
            ("주소 구조체 심화(57번 장표 이후)", ["getaddrinfo 심화 — 이번 시험 범위 밖"])])),
    ],
    "스마트앱프로젝트": [
        ("스마트앱프로젝트.pdf", "SYLLABUS", [["스마트앱프로젝트 강의계획서(합성)", "3주차 이벤트 처리", "4~5주차 Navigation",
                                          "6주차 어댑터뷰와 RecyclerView", "7주차 SQLite", "8주차 중간고사"]]),
        ("3.1.이벤트 처리.pdf", "PROFESSOR_SLIDE", pages("3주차 이벤트 처리", [
            ("클릭 리스너", ["setOnClickListener { }", "토스트 메시지"]),
            ("뷰 바인딩", ["binding.button.setOnClickListener"])])),
        ("5.Navigation.pdf", "PROFESSOR_SLIDE", pages("5주차 Navigation", [
            ("NavHostFragment", ["nav_graph.xml", "app:navGraph 속성"]),
            ("화면 이동", ["findNavController().navigate(R.id.action_a_to_b)", "Safe Args"])])),
        ("6.어댑터뷰.pdf", "PROFESSOR_SLIDE", pages("6주차 어댑터뷰와 RecyclerView", [
            ("RecyclerView 구성", ["Adapter, ViewHolder, LayoutManager"]),
            ("어댑터 메서드", ["onCreateViewHolder, onBindViewHolder, getItemCount"])])),
    ],
    "영어회화": [
        ("영어회화교양.pdf", "SYLLABUS", [["영어회화 강의계획서(합성)", "교재 NEW English Conversation Arts 1",
                                     "1~7주차 교재 Unit 학습", "8주차 중간고사(교재 범위)"]]),
    ],
}

# 사용자가 실제로 보낸 요청(초안 4453의 대화 원문). 시험 안내를 포함한 그대로다.
REQUEST = """오늘 공부계획짜보자 나는 영어회화랑 빅데이터분석은 끝내고 가능하면 네트워크프로그래밍 더 가능하면 스마트앱프로젝트까지 끝내고 싶어
중간고사 안내입니다. 다음을 참고하여 준비해 주시기 바랍니다.
1. 일시 : 10월 23일 오후 02:00 ~
2. 범위 : Navigation, RecyclerView, SQLite, 이벤트 처리
3. 시험 방법 : 주어진 실습파일을 가지고 하나의 앱을 작성하여 제출. 오픈북방식(시험당일 참고할 pdf 파일을 배포하므로 배포한 pdf 파일만 참고해서 작성. 기타 다른 것을 참고하는 경우 컨닝으로 간주)
4. 시험 유형 : xml 속성, 속성값 채우기, 메소드 이름 혹은 메소드 파라미터(매개변수) 완성하기, 코드완성 및 코드작성하기, 시험지에 수기로 작성하는 문제도 있음(필기도구 지참 필요)
중간고사 (PA)• 일시 : 2026년 10월 23일 금요일 10:00 ~ 11:30 (90분)• 장소 : 3호관 215호 / 총 30점 / 필기시험- 문제 유형• 객관식 / 단답·서술형 / 코드 빈칸·실행 해석 / 상황 분석- 시험 범위• 2주차 : 2.리눅스_개요와_실습환경_구축• 3주차 : 3.AWS_구성하기_SSH실습• 6주차 : 06.네트워크와_소켓_프로그래밍• 7주차 : 07.소켓_시작하기와_주소_구조체_코드로_이해하기56번 장표까지※ AWS 서비스 암기와 환경 설치·설정 절차는 제외합니다.
 영어회화는 책에 시험범위 표시해놨어
빅데이터분석은 붓꽃타이타닉까지야
그리고 스마트앱프로젝트는 오픈북이라 pdf에 나오는거 보고 코드 채울 수 있으면 돼"""


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


def turn(token, conversation_id, message=None, action="AUTO", period_plan=None, source_message_id=None):
    body = {"requestedAction": action, "idempotencyKey": str(uuid.uuid4())}
    if message is not None:
        body["message"] = message
    if period_plan is not None:
        body["periodPlan"] = period_plan
    if source_message_id is not None:
        body["sourceMessageId"] = source_message_id
    result = {"action": action, "message": message, "responseType": None, "reply": "", "offer": None, "error": None,
              "draft": None, "userMessageId": None, "question": None}
    started = time.time()
    req = urllib.request.Request(BASE + "/api/ai/conversations/%s/messages" % conversation_id,
                                 data=json.dumps(body, ensure_ascii=False).encode("utf-8"), method="POST",
                                 headers={"Accept": "text/event-stream", "Content-Type": "application/json; charset=utf-8",
                                          "Authorization": "Bearer " + token})
    try:
        resp = urllib.request.urlopen(req, timeout=420)
    except urllib.error.HTTPError as e:
        result["error"] = "HTTP %s %s" % (e.code, e.read().decode("utf-8", errors="replace")[:300])
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
                name, payload = parse_frame(frame)
                if name is None:
                    continue
                if name == "message.completed":
                    result["responseType"] = payload.get("responseType")
                    result["reply"] = payload.get("reply") or result["reply"]
                    result["offer"] = payload.get("offerAction") or result["offer"]
                    result["userMessageId"] = payload.get("userMessageId")
                    result["question"] = (payload.get("consult") or {}).get("question")
                    if payload.get("periodPlanDraft"):
                        result["draft"] = payload["periodPlanDraft"]
                elif name == "message.delta":
                    result["reply"] += payload.get("text") or ""
                elif name == "offer.ready":
                    result["offer"] = payload.get("offerAction")
                elif name == "period_plan.ready":
                    result["draft"] = payload
                elif name == "message.error":
                    result["error"] = "%s %s" % (payload.get("code"), payload.get("message"))
    result["seconds"] = round(time.time() - started, 1)
    log("--- [%s] %ss type=%s offer=%s error=%s" % (action, result["seconds"], result["responseType"],
                                                  (result["offer"] or {}).get("type"), result["error"]))
    log("    AI: " + (result["reply"] or "")[:500].replace("\n", " / "))
    return result


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


def main():
    global BASE
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default=BASE)
    ap.add_argument("--out", default="build/verify-intent.json")
    args = ap.parse_args()
    BASE = args.base.rstrip("/")
    today = datetime.date.today()

    email = "intent-%s@memo-test.invalid" % uuid.uuid4().hex[:8]
    password = secrets.token_urlsafe(16)
    call("POST", "/auth/signup", body={"email": email, "password": password, "nickname": "의도검증"})
    status, body = call("POST", "/auth/login", body={"email": email, "password": password})
    token = body["token"]
    record("합성 계정", status == 200, email=email)

    courses, material_ids = {}, []
    for title, files in MATERIALS.items():
        status, course = call("POST", "/courses", token, {"title": title})
        courses[title] = course["courseId"]
        for filename, mtype, content in files:
            raw, ctype = multipart([("file", filename, "application/pdf", pdf(content))])
            status, res = call("POST", "/courses/%s/materials?materialType=%s" % (course["courseId"], mtype), token,
                               raw=raw, content_type=ctype)
            material_ids.append((res or {}).get("materialId"))
            record("업로드 " + filename, status in (200, 201), materialId=(res or {}).get("materialId"))

    def statuses():
        return [call("GET", "/materials/%s/analysis-status" % m, token)[1] or {} for m in material_ids]
    states, took = wait_for(statuses, lambda ss: all(s.get("state") in ("DONE", "PARTIAL", "FAILED", "NO_TEXT", "UNAVAILABLE")
                                                     for s in ss), 1500, "자료 분석")
    record("자동 분석 종료", all(s.get("state") in ("DONE", "PARTIAL") for s in states), seconds=round(took),
           states=sorted({s.get("state") for s in states}))

    status, conv = call("POST", "/ai/conversations", token, {"scope": "TODAY"})
    cid = conv["conversationId"]
    t1 = turn(token, cid, REQUEST)
    REPORT["turns"] = [t1]
    last = t1
    # 상담이 되묻기만 하면 짧게 그대로 진행하자고 답한다(사용자가 새 정보를 더하지 않는다).
    for _ in range(2):
        if (last.get("offer") or {}).get("type") == "CREATE_PERIOD_PLAN" or last.get("error"):
            break
        last = turn(token, cid, "그대로 오늘 계획 만들어줘")
        REPORT["turns"].append(last)
    offer = last.get("offer") or {}
    period = {"periodStartDate": offer.get("periodStartDate") or today.isoformat(),
              "periodEndDate": offer.get("periodEndDate") or today.isoformat(),
              "intensity": offer.get("intensity") or "NORMAL", "courseIds": offer.get("courseIds") or []}
    made = turn(token, cid, None, "CREATE_PERIOD_PLAN", period, last.get("userMessageId"))
    draft = made.get("draft") or {}
    record("초안 생성", bool(draft.get("proposalId")), proposalId=draft.get("proposalId"), error=made.get("error"))
    if not draft.get("proposalId"):
        finish(args)
        return
    status, full = call("GET", "/plans/proposals/%s/draft" % draft["proposalId"], token)
    draft = full or draft
    REPORT["draft"] = {"items": [(i.get("title"), i.get("expectedMinutes"), i.get("priority"))
                                 for i in ((draft.get("proposal") or {}).get("items") or [])],
                       "scopeReport": draft.get("scopeReport"), "strategy": draft.get("strategy")}

    # ===== 기준 1: 합의 항목 =====
    rows = mysql("SELECT items FROM ai_plan_briefs WHERE conversation_id=%d" % cid)
    brief = json.loads(rows[0][0]) if rows else []
    pending = [b for b in brief if b.get("speaker") == "ASSISTANT" and not b.get("accepted") and not b.get("basisMessageId")
               and not b.get("rejected") and not b.get("removed")]
    grounded = [b for b in brief if b.get("basisMessageId")]
    report = draft.get("scopeReport") or {}
    scopes = report.get("scopes") or []
    REPORT["criteria"]["1_user_intent"] = {
        "briefItems": [(b.get("kind"), b.get("speaker"), bool(b.get("accepted")), bool(b.get("basisMessageId")), b.get("text"))
                       for b in brief],
        "pendingAiProposals": len(pending), "grounded": len(grounded),
        "scopes": [(s.get("courseTitle"), s.get("role"), s.get("verified"), s.get("boundary"), [p.get("label") for p in s.get("parts") or []])
                   for s in scopes]}
    record("기준 1 사용자 요청 보존", len(scopes) > 0 and all(s.get("verified") for s in scopes),
           scopes=len(scopes), verified=sum(1 for s in scopes if s.get("verified")), pendingBrief=len(pending))

    # ===== 기준 2: 부분별 =====
    states = {p["partId"]: p for p in report.get("parts") or []}
    table = []
    for s in scopes:
        for p in s.get("parts") or []:
            st = states.get(p["partId"]) or {}
            table.append((s.get("courseTitle"), p.get("label"), st.get("reflection"), st.get("acquisition"),
                          st.get("note") or st.get("acquisitionNote")))
    REPORT["criteria"]["2_parts"] = table
    silent = [r for r in table if r[2] == "UNHANDLED" and not r[4]]
    record("기준 2 범위 반영·이유", len(silent) == 0 or report.get("notice") is not None, parts=len(table),
           unhandledWithoutNote=len(silent))
    for r in table:
        log("    · %s / %s — %s · %s %s" % r)

    # ===== 기준 3: 스마트앱 =====
    items = (draft.get("proposal") or {}).get("items") or []
    app_items = [i for i in items if i.get("courseId") == courses["스마트앱프로젝트"]]
    downgraded = [i for i in app_items if "범위 확인" in (i.get("title") or "")]
    REPORT["criteria"]["3_smartapp"] = [(i.get("title"), i.get("description")) for i in app_items]
    record("기준 3 자료 미확보가 목표 축소가 아님", not downgraded, items=[i.get("title") for i in app_items])

    # ===== 기준 4·5 =====
    REPORT["criteria"]["4_5"] = {"requiredUnmet": report.get("requiredUnmet"), "issues": report.get("issues"),
                                 "completion": report.get("completion"), "notice": report.get("notice")}
    record("기준 4 추가 과목 판단 설명", not report.get("requiredUnmet") or not report.get("issues")
           or any("서버 검증" in (c.get("what") or "") for c in ((draft.get("strategy") or {}).get("changes") or [])),
           requiredUnmet=report.get("requiredUnmet"), issues=report.get("issues"))
    record("기준 5 완료처럼 보이지 않음", report.get("completion") == "ALL" or bool(report.get("notice")),
           completion=report.get("completion"), notice=report.get("notice"))
    log("\n항목:")
    for i in items:
        log("  - [%s] %s (%s분)" % (i.get("priority"), i.get("title"), i.get("expectedMinutes")))
    finish(args)


def finish(args):
    path = ROOT / args.out
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(REPORT, ensure_ascii=False, indent=2, default=str), encoding="utf-8")
    log("\n결과: %s" % path)


if __name__ == "__main__":
    main()
