#!/usr/bin/env python3
"""
프로젝트 정리 검토 후속(2026-09-21)을 실제 서버·실제 모델로 돌리고 각 단계의 응답을 남긴다.

모킹 테스트로는 증명할 수 없는 것만 본다:
  A. 큰 입력의 <뒤쪽> 자료. 예산을 일부러 작게 둔 서버(--project.tidy.section-char-budget=2500,
     --project.tidy.list-char-budget=4000)에서, 이미 있는 「스택」 항목과 같은 개념을 가장 마지막에
     올린 자료가 다룬다. 예전 방식은 입력 순서대로 잘라 이 자료를 매번 뺐다. 이제는
       - 모든 구간이 목록 수준에 올랐는가(scope.sectionsListed == sectionsTotal)
       - 고르기 호출이 실제로 일어났는가(scope.modelCalls >= 2)
       - 마지막 자료의 구간이 기존 「스택」에 LINK됐는가(새 「스택」을 또 만들지 않았는가)
  B. 여러 자료의 통합 판단. 같은 개념을 서로 다른 이름으로 다루는 자료들이 항목 하나로 모이는가.
  C. 압축 가져오기가 같은 분석 흐름에 들어가는가. 실제 ZIP(이름이 같은 파일 둘) → 확정 → 묶음
     (분모 고정, 경로 구분) → 실제 내용 분석 → 정리 준비 수에 들어가는가.

합성 계정과 스크립트가 만든 합성 자료(.sh 평문)만 쓴다. 격리 DB(memo_test)에 붙은 서버에만 돌린다.

사용 예:
  python scripts/ai-baseline/verify-project-tidy-review-2026-09-21.py --base http://localhost:8081/api
"""
import argparse
import io
import json
import sys
import time
import urllib.error
import urllib.request
import uuid
import zipfile

OUT = io.open(sys.stdout.fileno(), "w", encoding="utf-8", closefd=False)


def log(msg=""):
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
    boundary = "----review" + uuid.uuid4().hex
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
# 합성 자료
# ---------------------------------------------------------------------

FIRST = """\
자료구조 3주차 강의 노트

1. 스택 (Stack)
LIFO 구조다. push는 맨 위에 넣고 pop은 맨 위에서 뺀다. peek은 빼지 않고 본다.

2. 큐 (Queue)
FIFO 구조다. enqueue는 뒤에 넣고 dequeue는 앞에서 뺀다.
"""


def filler(topic, parts):
    lines = ["%s 정리 노트" % topic, ""]
    for i, (title, body) in enumerate(parts, 1):
        lines += ["%d. %s" % (i, title), body, ""]
    return "\n".join(lines)


FILLERS = [
    ("sorting.sh", filler("정렬 알고리즘", [
        ("버블 정렬", "인접한 두 원소를 비교해 자리를 바꾼다. 시간 복잡도 O(n^2)."),
        ("삽입 정렬", "정렬된 앞부분에 새 원소를 끼워 넣는다. 거의 정렬된 입력에 빠르다."),
        ("병합 정렬", "반으로 나눠 정렬한 뒤 합친다. O(n log n), 추가 공간이 든다."),
        ("퀵 정렬", "피벗을 기준으로 나눈다. 평균 O(n log n), 최악 O(n^2)."),
    ])),
    ("hashing.sh", filler("해시 테이블", [
        ("해시 함수", "키를 배열 인덱스로 바꾼다. 고르게 퍼지는 것이 좋다."),
        ("충돌 처리: 체이닝", "같은 칸에 연결 리스트로 이어 붙인다."),
        ("충돌 처리: 개방 주소법", "빈 칸을 찾아 옆으로 간다. 선형 탐사, 이차 탐사."),
        ("적재율", "원소 수 / 칸 수. 높아지면 다시 해싱한다."),
    ])),
    ("graphs.sh", filler("그래프", [
        ("표현", "인접 행렬과 인접 리스트. 간선 수에 따라 고른다."),
        ("너비 우선 탐색", "큐를 써서 가까운 정점부터 방문한다."),
        ("깊이 우선 탐색", "재귀 또는 명시적 저장소로 깊이 들어간다."),
        ("최단 경로", "다익스트라는 음수 간선이 없을 때 쓴다."),
    ])),
    ("trees.sh", filler("트리", [
        ("이진 트리", "각 노드가 자식을 최대 둘 가진다."),
        ("순회", "전위·중위·후위 순회. 중위 순회는 BST에서 정렬된 순서다."),
        ("이진 탐색 트리", "왼쪽은 작고 오른쪽은 크다. 균형이 깨지면 느려진다."),
        ("힙", "완전 이진 트리. 최댓값/최솟값을 빨리 꺼낸다."),
    ])),
    ("complexity.sh", filler("알고리즘 분석", [
        ("점근 표기법", "빅오·빅오메가·빅세타."),
        ("최선·평균·최악", "입력에 따라 달라지는 실행 시간."),
        ("공간 복잡도", "추가로 쓰는 메모리."),
        ("분할 상환 분석", "여러 연산의 평균 비용. 동적 배열의 확장."),
    ])),
]

# 가장 마지막에 올린다. 기존 「스택」과 같은 개념을 <다른 이름>으로 다룬다.
LATE = ("zz-last-lifo-lab.sh", filler("LIFO 저장소 실습", [
    ("후입선출 저장소 만들기", "배열 하나와 꼭대기 인덱스로 넣기(push)·꺼내기(pop)·들여다보기(peek)를 구현한다."),
    ("넘침과 모자람", "가득 찼을 때 넣으면 넘침(overflow), 비었을 때 꺼내면 모자람(underflow)을 알린다."),
    ("괄호 짝 검사", "여는 괄호는 넣고 닫는 괄호가 오면 꺼내 짝을 맞춘다. 끝에 남으면 짝이 안 맞는다."),
]))

# C. 압축: 폴더가 다른 같은 이름 파일 둘.
ZIP_FILES = [
    ("과제1/run.sh", "echo 과제1 — 큐로 프린터 대기열을 흉내 낸다. enqueue/dequeue를 써서 먼저 온 인쇄 요청부터 처리한다."),
    ("과제2/run.sh", "echo 과제2 — 원형 큐로 버퍼를 만든다. front와 rear를 모듈러 연산으로 돌린다."),
]


def upload(base, token, course_id, name, content):
    raw, ctype = multipart({"materialType": "PROFESSOR_SLIDE"},
                           [("file", name, "text/plain", content.encode("utf-8"))])
    return call(base, "POST", "/courses/%s/materials?materialType=PROFESSOR_SLIDE" % course_id,
                token, raw=raw, content_type=ctype)


def tidy_until_done(base, token, course_id, timeout_s):
    status, view = call(base, "POST", "/courses/%s/tidy?refresh=true" % course_id, token)
    log("정리 요청 %s job=%s" % (status, (view or {}).get("job")))
    view, took = wait_for(lambda: call(base, "GET", "/courses/%s/tidy" % course_id, token)[1],
                          lambda v: v and (v.get("proposalId") or (v.get("job") or {}).get("status")
                                           in ("FAILED", "UNAVAILABLE") or v.get("status") == "EMPTY"),
                          timeout_s, "정리안")
    log("정리안까지 %.0f초" % took)
    return view


def topics_flat(base, token, course_id):
    status, topics = call(base, "GET", "/courses/%s/topics" % course_id, token)
    out = []

    def walk(ns, depth=0):
        for n in ns or []:
            out.append((depth, n.get("topicId"), n.get("title"),
                        sorted({m.get("filename") for m in (n.get("linkedMaterials") or []) if m.get("filename")})))
            walk(n.get("children"), depth + 1)
    walk(topics)
    return out


def apply_all(base, token, view):
    return call(base, "POST", "/project-tidy/%s/apply" % view["proposalId"], token,
                {"revision": view["revision"], "editRevision": view["editRevision"],
                 "baseTreeVersion": view["baseTreeVersion"],
                 "selectedChangeIds": [c["changeId"] for c in view.get("changes", [])],
                 "titleOverrides": {}})


def wait_analysis(base, token, material_ids, timeout_s):
    def statuses():
        return [call(base, "GET", "/materials/%s/analysis-status" % m, token)[1] or {} for m in material_ids]
    result, took = wait_for(statuses, lambda ss: all(s.get("state") in ("DONE", "PARTIAL", "FAILED", "NO_TEXT")
                                                     for s in ss), timeout_s, "내용 분석")
    log("내용 분석 %.0f초: %s" % (took, [s.get("state") for s in result]))
    return result


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="http://localhost:8081/api")
    ap.add_argument("--timeout", type=int, default=900)
    args = ap.parse_args()
    base = args.base.rstrip("/")

    stamp = int(time.time())
    email = "tidy-review-%d@example.com" % stamp
    password = "review-1234"
    log("=== 0. 합성 계정 ===")
    call(base, "POST", "/auth/signup", body={"email": email, "password": password, "nickname": "검토후속"})
    status, body = call(base, "POST", "/auth/login", body={"email": email, "password": password})
    token = (body or {}).get("token") or (body or {}).get("accessToken")
    if not token:
        log("로그인 실패 %s %s" % (status, body))
        return 1
    status, course = call(base, "POST", "/courses", token, {"title": "자료구조(검토 후속)"})
    course_id = course["courseId"]
    log("course %s" % course_id)

    # ---- 1. 기존 트리 만들기 ----
    log("\n=== 1. 기존 트리: 스택·큐 ===")
    status, res = upload(base, token, course_id, "lecture-week3.sh", FIRST)
    first_id = res["materialId"]
    wait_analysis(base, token, [first_id], args.timeout)
    view = tidy_until_done(base, token, course_id, args.timeout)
    scope = view.get("scope") or {}
    log("1차 범위: 구간 %s · 목록 %s · 상세 %s · 모델 호출 %s"
        % (scope.get("sectionsTotal"), scope.get("sectionsListed"), scope.get("sectionsReviewed"),
           scope.get("modelCalls")))
    status, applied = apply_all(base, token, view)
    log("1차 적용 %s" % status)
    tree = topics_flat(base, token, course_id)
    for d, tid, title, mats in tree:
        log("  %s#%s %s %s" % ("  " * d, tid, title, mats))
    stack_ids = [tid for _, tid, title, _ in tree if title and "스택" in title]
    log("기존 스택 항목: %s" % stack_ids)

    # ---- 2. 큰 입력: 채움 자료 다섯 + 마지막에 LIFO 실습 ----
    log("\n=== 2. 큰 입력 — 핵심 자료는 맨 뒤 ===")
    ids = []
    for name, content in FILLERS + [LATE]:
        status, res = upload(base, token, course_id, name, content)
        ids.append(res["materialId"])
        log("  upload %s -> %s" % (name, res.get("materialId")))
    late_id = ids[-1]
    wait_analysis(base, token, ids, args.timeout)

    view = tidy_until_done(base, token, course_id, args.timeout)
    if (view.get("job") or {}).get("status") in ("FAILED", "UNAVAILABLE"):
        log("!! 정리 실패: %s" % view.get("job"))
        return 2
    scope = view.get("scope") or {}
    log("범위: 자료 %s · 구간 %s · 목록 %s · 상세 %s · 모델 호출 %s · 부분=%s"
        % (len(scope.get("reviewed") or []), scope.get("sectionsTotal"), scope.get("sectionsListed"),
           scope.get("sectionsReviewed"), scope.get("modelCalls"), scope.get("truncated")))
    for m in scope.get("reviewed") or []:
        log("  M%s %s: 구간 %s · 목록 %s · 상세 %s" % (m["materialId"], m.get("filename"), m.get("sectionCount"),
                                                  m.get("listedCount"), m.get("reviewedCount")))
    for e in scope.get("excluded") or []:
        log("  제외 M%s %s: %s" % (e.get("materialId"), e.get("filename"), e.get("reasonLabel")))

    late_links = []
    new_stack = []
    for c in view.get("changes", []):
        mats = {s.get("materialId") for s in (c.get("sections") or [])}
        log("  [%s] %s" % (c.get("op"), c.get("text")))
        if late_id in mats:
            late_links.append(c)
        if c.get("op") == "ADD" and c.get("title") and ("스택" in c["title"] or "LIFO" in c["title"].upper()):
            new_stack.append(c["title"])

    log("\n--- A 판정 ---")
    log("모든 구간 목록 수준: %s" % (scope.get("sectionsListed") == scope.get("sectionsTotal")))
    log("고르기 호출 발생(모델 호출 >= 2): %s" % ((scope.get("modelCalls") or 0) >= 2))
    log("마지막 자료가 판단에 들어감(근거로 인용됨): %s (%d건)" % (bool(late_links), len(late_links)))
    # 변경 응답에는 대상 topicId가 없다(묶음에 있다). 기존 스택 항목의 제목이 문장에 있는지로 본다.
    stack_titles = [title for _, tid, title, _ in tree if tid in stack_ids]
    linked_to_existing = [c for c in late_links if c.get("op") == "LINK"
                          and any("「%s」" % t in (c.get("text") or "") for t in stack_titles)]
    log("마지막 자료 → 기존 스택에 LINK: %s" % bool(linked_to_existing))
    log("스택류 새 항목(중복 의심): %s" % new_stack)

    # ---- 3. 압축 ----
    log("\n=== 3. 압축 가져오기 → 분석 묶음 ===")
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as z:
        for path, text in ZIP_FILES:
            z.writestr(path, text.encode("utf-8"))
    raw, ctype = multipart({"courseId": course_id, "materialType": "PROFESSOR_SLIDE"},
                           [("file", "과제모음.zip", "application/zip", buf.getvalue())])
    status, imp = call(base, "POST", "/materials/zip-imports", token, raw=raw, content_type=ctype)
    log("zip upload %s id=%s status=%s" % (status, imp.get("importId"), imp.get("status")))
    status, open_before = call(base, "GET", "/materials/analysis/batches/open?courseId=%s" % course_id, token)
    log("확정 전 열린 묶음(압축): %s" % [b["batchId"] for b in open_before.get("batches", []) if b.get("zipImportId")])
    imp, took = wait_for(lambda: call(base, "GET", "/materials/zip-imports/%s" % imp["importId"], token)[1],
                         lambda i: i and i.get("status") == "READY", 120, "압축 탐색")
    entries = [e for e in imp.get("entries", []) if e.get("supported")]
    log("탐색 %.0f초: 항목 %s" % (took, [e["entryPath"] for e in entries]))
    status, imp = call(base, "POST", "/materials/zip-imports/%s/confirm" % imp["importId"], token,
                       {"entryIds": [e["entryId"] for e in entries]})
    log("confirm %s status=%s" % (status, imp.get("status")))
    status, page = call(base, "GET", "/materials/analysis/batches/open?courseId=%s" % course_id, token)
    zb = [b for b in page.get("batches", []) if b.get("zipImportId") == imp["importId"]]
    log("압축 묶음: %s" % [(b["batchId"], b["itemCount"], b.get("sourceArchiveName")) for b in zb])
    if zb:
        for it in zb[0]["items"]:
            log("  자리 %s path=%s state=%s" % (it["itemId"], it.get("sourcePath"), it.get("uploadState")))
        batch_id = zb[0]["batchId"]
        final, took = wait_for(lambda: call(base, "GET", "/materials/analysis/batches/%s" % batch_id, token)[1],
                               lambda b: b and b.get("status") == "FINISHED", args.timeout, "압축 묶음 분석")
        log("압축 묶음 %.0f초: %s (성공 %s / 실패 %s / 제외 %s)"
            % (took, final.get("status"), final.get("doneCount"), final.get("failedCount"),
               final.get("skippedCount")))
        for it in final.get("items", []):
            log("  %s -> material %s %s" % (it.get("sourcePath"), it.get("materialId"), it.get("stageLabel")))
    status, tv = call(base, "GET", "/courses/%s/tidy" % course_id, token)
    log("정리 준비 자료 수(압축 포함): %s" % tv.get("readyMaterialCount"))
    log("\n완료. 합성 계정: %s" % email)
    return 0


if __name__ == "__main__":
    sys.exit(main())
