#!/usr/bin/env python3
"""
프로젝트 단위 자료 정리를 실제 서버·실제 모델로 한 번 돌리고 각 단계의 응답을 남긴다(2026-09-21).

보려는 것(고정 응답으로는 증명할 수 없는 것):
  1. 같은 개념을 서로 다른 이름으로 다루는 자료 셋을 주면 <항목을 하나로 모으는가>, 아니면
     자료마다 새 항목을 하나씩 만드는가. 이것이 이 작업 전체의 존재 이유다.
  2. 근거가 여러 자료에 걸친 변경이 실제로 나오는가(한 LINK의 sectionIds에 두 자료의 구간).
  3. 시작 전 예상 시간과 실제 소요가 자릿수라도 맞는가.

합성 계정과 스크립트가 만든 합성 자료(.sh 평문)만 쓴다 — 사용자의 실제 파일·계정을 쓰지 않는다.

사용 예:
  python scripts/ai-baseline/verify-project-tidy-2026-09-21.py --base http://localhost:8081

표준 라이브러리만 쓴다. 한글 본문은 UTF-8 JSON으로 보낸다(이 머신의 셸은 CP949를 넘긴다).
"""
import argparse
import io
import json
import sys
import time
import urllib.error
import urllib.request
import uuid

OUT = io.open(sys.stdout.fileno(), "w", encoding="utf-8", closefd=False)


def log(msg):
    OUT.write(msg + "\n")
    OUT.flush()


def call(base, method, path, token=None, body=None, raw=None, content_type=None):
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
        with urllib.request.urlopen(req, timeout=300) as resp:
            text = resp.read().decode("utf-8")
            return resp.status, (json.loads(text) if text else None)
    except urllib.error.HTTPError as e:
        text = e.read().decode("utf-8", errors="replace")
        try:
            return e.code, json.loads(text)
        except Exception:
            return e.code, {"raw": text}


def multipart(fields, files):
    """files: [(name, filename, content_type, bytes)]"""
    boundary = "----tidy" + uuid.uuid4().hex
    out = io.BytesIO()
    for key, value in fields.items():
        out.write(("--%s\r\n" % boundary).encode())
        out.write(('Content-Disposition: form-data; name="%s"\r\n\r\n' % key).encode())
        out.write(str(value).encode("utf-8") + b"\r\n")
    for name, filename, ctype, content in files:
        out.write(("--%s\r\n" % boundary).encode())
        out.write(('Content-Disposition: form-data; name="%s"; filename="%s"\r\n'
                   % (name, filename)).encode("utf-8"))
        out.write(("Content-Type: %s\r\n\r\n" % ctype).encode())
        out.write(content + b"\r\n")
    out.write(("--%s--\r\n" % boundary).encode())
    return out.getvalue(), "multipart/form-data; boundary=" + boundary


# 같은 개념(스택·큐)을 서로 다른 이름과 말투로 다루는 자료 셋. 모델이 이것을 하나로 모으는지가 관건이다.
LECTURE = """\
자료구조 3주차 강의 노트

1. 스택 (Stack)
LIFO 구조다. 가장 나중에 넣은 것이 가장 먼저 나온다.
push는 맨 위에 넣고 pop은 맨 위에서 뺀다. peek은 빼지 않고 본다.
배열로 구현하면 top 인덱스 하나로 관리할 수 있다.

2. 큐 (Queue)
FIFO 구조다. 먼저 넣은 것이 먼저 나온다.
enqueue는 뒤에 넣고 dequeue는 앞에서 뺀다.
배열로 구현할 때 front가 계속 밀리는 문제가 있다.
"""

TEXTBOOK = """\
교재 3장 목차와 요약

3.1 스택 자료구조
  후입선출(LIFO) 원리와 추상 자료형 정의.
  연산: push, pop, top. 배열 기반 구현과 연결 리스트 기반 구현을 비교한다.
3.2 스택의 응용
  괄호 검사, 후위 표기법 계산.
3.3 큐 자료구조
  선입선출(FIFO) 원리.
  연산: enqueue, dequeue, front.
3.4 원형 큐
  배열 기반 큐의 공간 낭비를 해결한다. 모듈러 연산으로 인덱스를 돌린다.
"""

LAB = """\
실습 안내 3주차

실습 1. 스택 구현 연습
  배열을 써서 push, pop, peek를 직접 구현하세요.
  크기를 넘으면 오류를 내야 합니다.

실습 2. 괄호 검사기
  스택을 써서 괄호가 짝이 맞는지 검사하는 프로그램을 작성하세요.

실습 3. 원형 큐 구현
  모듈러 연산으로 front와 rear를 돌리는 원형 큐를 구현하세요.
"""

MATERIALS = [
    ("lecture-week3.sh", LECTURE),
    ("textbook-ch3.sh", TEXTBOOK),
    ("lab-week3.sh", LAB),
]

# 2단계: 트리가 <이미 있는> 상태에서 같은 개념을 다시 다루는 자료가 들어온다.
# 예전 방식이 무너지던 자리다 — 여기서 「스택」을 또 만들면 중복 항목이 쌓인다.
QUIZ = """\
4주차 퀴즈 대비 자료

스택 복습
  push/pop/peek의 시간 복잡도는 모두 O(1)이다.
  스택 오버플로와 언더플로를 구분할 것.

큐 복습
  원형 큐에서 가득 찬 상태와 빈 상태를 구분하는 방법(크기 변수 또는 한 칸 비우기).
"""


def wait_for(fn, predicate, timeout_s, label):
    started = time.time()
    last = None
    while time.time() - started < timeout_s:
        last = fn()
        if predicate(last):
            return last, time.time() - started
        time.sleep(3)
    log("!! %s: %.0f초 안에 끝나지 않았다" % (label, timeout_s))
    return last, time.time() - started


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="http://localhost:8081/api")
    ap.add_argument("--analysis-timeout", type=int, default=600)
    ap.add_argument("--tidy-timeout", type=int, default=300)
    args = ap.parse_args()
    base = args.base.rstrip("/")

    stamp = int(time.time())
    email = "project-tidy-%d@example.com" % stamp
    password = "tidy-1234"

    log("=== 0. 합성 계정 ===")
    status, body = call(base, "POST", "/auth/signup",
                        body={"email": email, "password": password, "nickname": "정리검증"})
    log("signup %s" % status)
    status, body = call(base, "POST", "/auth/login", body={"email": email, "password": password})
    token = (body or {}).get("token") or (body or {}).get("accessToken")
    if not token:
        log("로그인 실패: %s %s" % (status, body))
        return 1
    log("login OK")

    status, course = call(base, "POST", "/courses", token, {"title": "자료구조(정리 검증)"})
    course_id = course["courseId"]
    log("course %s" % course_id)

    log("\n=== 1. 시작 전 예상 시간 ===")
    files = [{"filename": n, "sizeBytes": len(c.encode("utf-8"))} for n, c in MATERIALS]
    status, estimate = call(base, "POST", "/materials/analysis/estimate", token, {"files": files})
    log("estimate %s: min=%ss max=%ss basis=%s uploadExcluded=%s"
        % (status, estimate.get("minSeconds"), estimate.get("maxSeconds"),
           estimate.get("basis"), estimate.get("uploadTimeExcluded")))
    for f in estimate.get("files", []):
        log("  %s supported=%s estimable=%s %s~%ss %s"
            % (f["filename"], f["supported"], f["estimable"], f.get("minSeconds"),
               f.get("maxSeconds"), f.get("reason") or ""))

    log("\n=== 2. 묶음 만들고 올리기 ===")
    status, batch = call(base, "POST", "/materials/analysis/batches", token,
                         {"courseId": course_id, "files": files})
    log("batch %s id=%s items=%s" % (status, batch.get("batchId"), batch.get("itemCount")))
    upload_started = time.time()
    for item, (name, content) in zip(batch["items"], MATERIALS):
        raw, ctype = multipart({"materialType": "PROFESSOR_SLIDE"},
                               [("file", name, "text/plain", content.encode("utf-8"))])
        status, res = call(base, "POST",
                           "/courses/%s/materials?materialType=PROFESSOR_SLIDE&batchItemId=%s"
                           % (course_id, item["itemId"]),
                           token, raw=raw, content_type=ctype)
        log("  upload %s %s -> material %s (%s)"
            % (status, name, (res or {}).get("materialId"), (res or {}).get("extractionStatus")))
    log("업로드 전송 %.1f초 (예상에 포함되지 않는 시간)" % (time.time() - upload_started))

    log("\n=== 3. 분석 진행 ===")
    def read_batch():
        return call(base, "GET", "/materials/analysis/batches/%s" % batch["batchId"], token)[1]

    final, elapsed = wait_for(read_batch, lambda b: b and b.get("status") == "FINISHED",
                              args.analysis_timeout, "자료 분석")
    log("처리 %.0f초 (예상 %s~%ss) · %s%% · 성공 %s / 실패 %s / 제외 %s"
        % (elapsed, estimate.get("minSeconds"), estimate.get("maxSeconds"),
           final.get("processedPercent"), final.get("doneCount"),
           final.get("failedCount"), final.get("skippedCount")))
    for item in final.get("items", []):
        log("  %s %s" % (item["filename"], item["stage"]))

    log("\n=== 4. 정리 요청 전 범위 ===")
    status, view = call(base, "GET", "/courses/%s/tidy" % course_id, token)
    log("ready=%s analyzing=%s firstTime=%s"
        % (view.get("readyMaterialCount"), view.get("analyzingMaterialCount"), view.get("firstTime")))

    log("\n=== 5. 이 프로젝트 자료 정리 ===")
    tidy_started = time.time()
    status, view = call(base, "POST", "/courses/%s/tidy" % course_id, token)
    log("request %s job=%s" % (status, (view.get("job") or {}).get("status")))

    def read_tidy():
        return call(base, "GET", "/courses/%s/tidy" % course_id, token)[1]

    view, tidy_elapsed = wait_for(
        read_tidy,
        lambda v: v and (v.get("proposalId") or (v.get("job") or {}).get("status") in ("FAILED", "UNAVAILABLE")),
        args.tidy_timeout, "정리안 생성")
    log("정리안 생성 %.0f초" % tidy_elapsed)
    if not view.get("proposalId"):
        log("!! 정리안을 만들지 못했다: %s" % json.dumps(view.get("job"), ensure_ascii=False))
        return 1

    log("\n=== 6. 정리안 ===")
    log("요약: %s" % (view.get("summary") or {}).get("headline"))
    log("범위: 검토 %s · 제외 %s · 부분=%s"
        % (len((view.get("scope") or {}).get("reviewed") or []),
           len((view.get("scope") or {}).get("excluded") or []),
           (view.get("scope") or {}).get("truncated")))
    material_names = {m["materialId"]: m["filename"]
                      for m in ((view.get("scope") or {}).get("reviewed") or [])}
    log("\n[묶음 — 영향을 받는 항목]")
    for g in view.get("groups", []):
        log("  %s %s (변경 %s)" % (g["kind"], g.get("title"), len(g.get("changeIds") or [])))
    log("\n[변경]")
    cross = 0
    for c in view.get("changes", []):
        mats = sorted({s["materialId"] for s in (c.get("sections") or [])})
        if len(mats) > 1:
            cross += 1
        log("  [%s] %s" % (c["op"], c["text"]))
        if c.get("reason"):
            log("        근거: %s" % c["reason"])
        for s in c.get("sections") or []:
            log("        - %s %s %s"
                % (material_names.get(s["materialId"], s["materialId"]), s.get("locator"), s.get("title")))

    log("\n=== 7. 판정에 쓸 수 있는 관찰 ===")
    adds = [c for c in view.get("changes", []) if c["op"] == "ADD"]
    links = [c for c in view.get("changes", []) if c["op"] == "LINK"]
    log("ADD %d · LINK %d · 여러 자료를 한 변경에 묶은 것 %d" % (len(adds), len(links), cross))
    titles = [c.get("title") or "" for c in adds]
    log("새 항목 제목: %s" % ", ".join(titles))
    log("※ '스택'류 제목이 자료 수만큼(3개) 생겼으면 통합 판단이 안 된 것이다.")

    log("\n=== 8. 일부만 골라 적용 ===")
    selected = [c["changeId"] for c in view.get("changes", [])][:2]
    status, applied = call(base, "POST", "/project-tidy/%s/apply" % view["proposalId"], token,
                           {"revision": view["revision"], "editRevision": view["editRevision"],
                            "baseTreeVersion": view["baseTreeVersion"],
                            "selectedChangeIds": selected, "titleOverrides": {}})
    log("apply %s status=%s" % (status, (applied or {}).get("status")))
    status, topics = call(base, "GET", "/courses/%s/topics" % course_id, token)
    def walk(ns, depth=0):
        for n in ns or []:
            mats = {m.get("filename") for m in (n.get("linkedMaterials") or [])}
            log("  %s%s  %s" % ("  " * depth, n.get("title"), sorted(m for m in mats if m)))
            walk(n.get("children"), depth + 1)
    log("[적용 뒤 학습 구조]")
    walk(topics)

    log("\n=== 9. 두 번째 적용은 거절 ===")
    status, again = call(base, "POST", "/project-tidy/%s/apply" % view["proposalId"], token,
                         {"revision": view["revision"], "editRevision": view["editRevision"],
                          "baseTreeVersion": view["baseTreeVersion"],
                          "selectedChangeIds": selected, "titleOverrides": {}})
    log("두 번째 apply %s %s" % (status, (again or {}).get("message") or (again or {}).get("details")))

    log("\n=== 10. 트리가 있는 상태에서 같은 개념을 다시 다루는 자료 ===")
    log("(예전 방식이 무너지던 자리다 — 기존 항목에 연결하는지, 같은 개념을 또 만드는지)")
    raw, ctype = multipart({"materialType": "PROFESSOR_SLIDE"},
                           [("file", "quiz-week4.sh", "text/plain", QUIZ.encode("utf-8"))])
    status, res = call(base, "POST",
                       "/courses/%s/materials?materialType=PROFESSOR_SLIDE" % course_id,
                       token, raw=raw, content_type=ctype)
    log("upload %s quiz-week4.sh -> material %s" % (status, (res or {}).get("materialId")))

    view2, _ = wait_for(read_tidy, lambda v: v and v.get("readyMaterialCount", 0) >= 4,
                        args.analysis_timeout, "4번째 자료 분석")
    log("ready=%s" % view2.get("readyMaterialCount"))

    status, _ignored = call(base, "POST", "/courses/%s/tidy" % course_id, token)
    log("request %s" % status)
    view2, second_elapsed = wait_for(
        read_tidy,
        lambda v: v and (v.get("proposalId")
                         or (v.get("job") or {}).get("status") in ("FAILED", "UNAVAILABLE")),
        args.tidy_timeout, "두 번째 정리안")
    if not view2.get("proposalId"):
        log("!! 두 번째 정리안 실패: %s" % json.dumps(view2.get("job"), ensure_ascii=False))
        return 1
    log("두 번째 정리안 생성 %.0f초 · 요약: %s"
        % (second_elapsed, (view2.get("summary") or {}).get("headline")))
    names2 = {m["materialId"]: m["filename"]
              for m in ((view2.get("scope") or {}).get("reviewed") or [])}
    for c in view2.get("changes", []):
        log("  [%s] %s" % (c["op"], c["text"]))
        if c.get("reason"):
            log("        근거: %s" % c["reason"])
        for sct in c.get("sections") or []:
            log("        - %s %s" % (names2.get(sct["materialId"], sct["materialId"]),
                                     sct.get("locator")))
    adds2 = [c for c in view2.get("changes", []) if c["op"] == "ADD"]
    links2 = [c for c in view2.get("changes", []) if c["op"] == "LINK"]
    log("ADD %d · LINK %d" % (len(adds2), len(links2)))
    log("※ 기존 항목과 같은 개념의 ADD가 또 나왔으면 통합 판단이 무너진 것이다.")

    log("\n합성 계정: %s (비밀번호 %s), courseId=%s" % (email, password, course_id))
    return 0


if __name__ == "__main__":
    sys.exit(main())
