#!/usr/bin/env python3
"""
자료 자동 분석 → 과제 확인 → 변경안 적용 → 계획 초안(자료 구간 반영) → 「자세히」까지를 실제 서버와
실제 모델로 한 번 돌리고, 각 단계의 실제 응답을 표준 출력에 남긴다(2026-09-13).

합성 계정과 합성 PDF만 쓴다(사용자 실제 파일·계정을 쓰지 않는다). PDF는 먼저
  SYNTHETIC_MATERIALS=1 ./gradlew.bat test --tests "*SyntheticMaterialGenerator*"
로 build/synthetic 에 만든다.

사용 예:
  python scripts/ai-baseline/verify-auto-analysis-2026-09-13.py --seed
  python scripts/ai-baseline/verify-auto-analysis-2026-09-13.py --token "$JWT" --course-id 31

표준 라이브러리만 쓴다. 한글 본문은 UTF-8 JSON으로 보낸다(이 머신의 셸은 CP949를 넘긴다).
"""
import argparse
import io
import json
import mimetypes
import os
import sys
import time
import urllib.error
import urllib.request
import uuid
from datetime import date, timedelta

OUT = io.open(sys.stdout.fileno(), "w", encoding="utf-8", closefd=False)


def log(msg):
    OUT.write(msg + "\n")
    OUT.flush()


def http(base, method, path, token=None, body=None, raw=None, content_type=None):
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
    return urllib.request.urlopen(req, timeout=180)


def call(base, method, path, token=None, body=None, raw=None, content_type=None):
    try:
        with http(base, method, path, token, body, raw, content_type) as resp:
            text = resp.read().decode("utf-8")
            return resp.status, (json.loads(text) if text else None)
    except urllib.error.HTTPError as e:
        text = e.read().decode("utf-8", errors="replace")
        try:
            return e.code, json.loads(text)
        except Exception:
            return e.code, text


def multipart(fields, files):
    boundary = "----verify" + uuid.uuid4().hex
    body = io.BytesIO()
    for name, value in fields.items():
        body.write(f"--{boundary}\r\nContent-Disposition: form-data; name=\"{name}\"\r\n\r\n{value}\r\n".encode("utf-8"))
    for name, (filename, path) in files.items():
        ctype = mimetypes.guess_type(filename)[0] or "application/octet-stream"
        body.write(f"--{boundary}\r\nContent-Disposition: form-data; name=\"{name}\"; filename=\"{filename}\"\r\n"
                   f"Content-Type: {ctype}\r\n\r\n".encode("utf-8"))
        with open(path, "rb") as f:
            body.write(f.read())
        body.write(b"\r\n")
    body.write(f"--{boundary}--\r\n".encode("utf-8"))
    return body.getvalue(), f"multipart/form-data; boundary={boundary}"


def seed(base):
    stamp = int(time.time())
    email = f"auto-analysis-{stamp}@example.com"
    password = "verify-1234"
    call(base, "POST", "/api/auth/signup", None, {"email": email, "password": password, "nickname": f"auto{stamp}"})
    status, login = call(base, "POST", "/api/auth/login", None, {"email": email, "password": password})
    token = login["token"]
    today = date.today()
    _, course = call(base, "POST", "/api/courses", token, {"title": "자료구조(자동분석 검증)"})
    course_id = course["courseId"]
    # 수업 루틴(화 14:00). "다음 수업까지" 추정과 계획의 마감 계산 근거가 된다.
    call(base, "POST", "/api/routines", token, {
        "courseId": course_id, "title": "자료구조", "daysOfWeek": ["TUESDAY"],
        "startTime": "14:00", "endTime": "17:00",
        "effectiveFrom": (today - timedelta(days=14)).isoformat(), "effectiveUntil": f"{today.year}-12-11",
    })
    log(f"seeded user={email} (user 정리는 사용자 판단) courseId={course_id}")
    return token, course_id


def upload(base, token, course_id, path, material_type="PROFESSOR_SLIDE"):
    raw, ctype = multipart({}, {"file": (os.path.basename(path), path)})
    status, res = call(base, "POST", f"/api/courses/{course_id}/materials?materialType={material_type}", token,
                       raw=raw, content_type=ctype)
    log(f"upload {os.path.basename(path)} -> {status} materialId={res.get('materialId') if isinstance(res, dict) else res}")
    return res["materialId"]


def wait_analysis(base, token, material_ids, timeout_s=600):
    started = time.time()
    last = {}
    while time.time() - started < timeout_s:
        _, overview = call(base, "GET", "/api/materials/analysis/overview", token)
        rows = {m["materialId"]: m for m in overview.get("materials", []) if m["materialId"] in material_ids}
        snapshot = {k: (v["state"], v.get("completedChunks"), v.get("totalChunks")) for k, v in rows.items()}
        if snapshot != last:
            log("  status " + json.dumps(snapshot, ensure_ascii=False))
            last = snapshot
        done = all(rows.get(mid, {}).get("state") in ("DONE", "PARTIAL", "FAILED", "UNAVAILABLE", "NO_TEXT")
                   for mid in material_ids) and len(rows) == len(material_ids)
        link_done = all(all(ls["state"] in ("DONE", "PARTIAL", "FAILED", "UNAVAILABLE", "CANCELLED")
                            for ls in rows[mid].get("linkStates", []))
                        and len(rows[mid].get("linkStates", [])) > 0 for mid in material_ids if mid in rows)
        if done and link_done:
            return rows
        time.sleep(5)
    return last


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base-url", default="http://localhost:8081")
    ap.add_argument("--token")
    ap.add_argument("--course-id", type=int)
    ap.add_argument("--seed", action="store_true")
    ap.add_argument("--synthetic-dir", default="build/synthetic")
    ap.add_argument("--out")
    args = ap.parse_args()
    base = args.base_url
    if args.seed:
        token, course_id = seed(base)
    else:
        token, course_id = args.token, args.course_id
    record = {"steps": []}

    files = ["problem-free.pdf", "long-late-exercise.pdf", "ambiguous-due.pdf"]
    material_ids = [upload(base, token, course_id, os.path.join(args.synthetic_dir, f)) for f in files]

    log("== 1. 자동 분석 대기(서버 worker) ==")
    rows = wait_analysis(base, token, material_ids)
    record["analysis"] = rows
    for mid in material_ids:
        st = rows.get(mid) or {}
        log(f"material {mid}: state={st.get('state')} chunks={st.get('completedChunks')}/{st.get('totalChunks')} "
            f"sections={st.get('sectionCount')} candidates={st.get('assignmentCandidateCount')} pages={st.get('pageCount')} "
            f"msg={st.get('message')}")
        _, sections = call(base, "GET", f"/api/materials/{mid}/sections", token)
        record.setdefault("sections", {})[mid] = sections
        for s in sections or []:
            log(f"   [{s['locator']}] {'/'.join(s.get('roleLabels') or [])} {s['title']}"
                + (f" — 수행: {s['taskText'][:60]}" if s.get('taskText') else "")
                + (" — 제출 단서" if s.get('assignmentCue') else "")
                + (f" — 날짜 {json.dumps(s.get('dates'), ensure_ascii=False)[:120]}" if s.get('dates') else ""))

    log("== 2. 변경안 ==")
    _, proposals = call(base, "GET", f"/api/courses/{course_id}/topic-change-proposals", token)
    record["proposals"] = proposals
    for p in proposals or []:
        log(f"proposal {p['proposalId']} material={p.get('materialFilename')} status={p['status']} summary={json.dumps(p['summary'], ensure_ascii=False)} stale={p['stale']}")
        for op in p["ops"][:12]:
            log("   op " + json.dumps(op, ensure_ascii=False)[:200])
    applied = []
    for p in proposals or []:
        status, res = call(base, "POST", f"/api/topic-change-proposals/{p['proposalId']}/apply", token, {})
        log(f"apply {p['proposalId']} -> {status} status={res.get('status') if isinstance(res, dict) else res}")
        applied.append((status, res))
        # 같은 변경안 재적용은 409여야 한다.
        status2, res2 = call(base, "POST", f"/api/topic-change-proposals/{p['proposalId']}/apply", token, {})
        log(f"re-apply {p['proposalId']} -> {status2} {res2.get('code') if isinstance(res2, dict) else res2}")
    _, tree = call(base, "GET", f"/api/courses/{course_id}/topics", token)
    record["tree"] = tree

    def walk(nodes, depth=0):
        for n in nodes:
            log("   " + "  " * depth + f"#{n['topicId']} {n['title']} ({n.get('sourceLocator')}) 구간 {len(n.get('linkedMaterials') or [])}개")
            walk(n.get("children") or [], depth + 1)
    walk(tree or [])

    log("== 3. 과제 후보 → 확인 → 마감 → 완료 ==")
    _, assignments = call(base, "GET", f"/api/courses/{course_id}/assignments", token)
    record["assignments_before"] = assignments
    for a in assignments or []:
        log(f"assignment {a['assignmentId']} [{a['confirmStatus']}] {a['title']} due={a['dueKind']} {a.get('dueDate')} src={a.get('dueSource')} "
            f"quote={a.get('dueQuote')} estimates={json.dumps(a.get('dueEstimates'), ensure_ascii=False)[:160]}")
    confirmed_id = None
    for a in assignments or []:
        if a["confirmStatus"] != "CANDIDATE":
            continue
        status, res = call(base, "PATCH", f"/api/assignments/{a['assignmentId']}/answer", token,
                           {"answer": "CONFIRMED", "version": a["version"]})
        log(f"answer CONFIRMED {a['assignmentId']} -> {status} due={res.get('dueKind') if isinstance(res, dict) else res}")
        if isinstance(res, dict) and res["dueKind"] == "UNKNOWN":
            est = next((e for e in (res.get("dueEstimates") or []) if e.get("isoDate")), None)
            if est:
                status, res = call(base, "PATCH", f"/api/assignments/{a['assignmentId']}/due", token,
                                   {"dueKind": "DATE", "dueDate": est["isoDate"], "version": res["version"]})
                log(f"  이 날짜 맞아요 {est['isoDate']} -> {status} due={res.get('dueDate') if isinstance(res, dict) else res}")
        if isinstance(res, dict) and confirmed_id is None:
            confirmed_id = (res["assignmentId"], res["version"])
        break

    log("== 4. 계획 초안(기본 AI 경로) ==")
    today = date.today()
    status, draft = call(base, "POST", "/api/plans/draft", token, {
        "startDate": today.isoformat(), "endDate": (today + timedelta(days=6)).isoformat(),
        "courseIds": [course_id], "instruction": "실습 문제가 있으면 먼저 풀어 보고 막히면 자료를 보는 식으로",
    })
    record["draft"] = draft
    log(f"draft -> {status} proposalId={draft.get('proposalId') if isinstance(draft, dict) else draft} "
        f"pending={json.dumps(draft.get('pendingMaterials'), ensure_ascii=False) if isinstance(draft, dict) else ''}")
    items = (draft.get("proposal") or {}).get("items") or [] if isinstance(draft, dict) else []
    for it in items[:12]:
        log(f"   item {it.get('proposalItemId')} {it.get('title')} · {it.get('expectedMinutes')}분 · {it.get('priority')} · {(it.get('description') or '')[:90]}")
    if isinstance(draft, dict) and draft.get("proposalId"):
        _, prov = call(base, "GET", f"/api/plans/drafts/{draft['proposalId']}/provenance", token)
        record["provenance"] = prov
        kinds = {}
        for s in (prov.get("sources") or prov.get("providedSources") or []):
            kinds[s.get("sourceType")] = kinds.get(s.get("sourceType"), 0) + 1
        log(f"provenance recorded={prov.get('recorded')} sourceTypes={json.dumps(kinds, ensure_ascii=False)}")
        cited = [i for i in (prov.get("items") or []) if i.get("refIds")]
        log(f"items with refIds: {len(cited)}/{len(prov.get('items') or [])}")

        log("== 5. 자세히 ==")
        for it in items[:6]:
            status, detail = call(base, "GET", f"/api/plans/drafts/items/{it['proposalItemId']}/detail", token)
            log(f"detail GET {it['proposalItemId']} -> available={detail.get('available')} canGenerate={detail.get('canGenerate')}")
            if isinstance(detail, dict) and detail.get("canGenerate") and not detail.get("available"):
                status, created = call(base, "POST", f"/api/plans/drafts/items/{it['proposalItemId']}/detail", token)
                record.setdefault("details", []).append(created)
                log(f"detail POST -> {status} steps={len(created.get('steps') or []) if isinstance(created, dict) else created}")
                for step in (created.get("steps") or []) if isinstance(created, dict) else []:
                    log(f"     - {step['text']} refs={step.get('refIds')}")
                status, again = call(base, "GET", f"/api/plans/drafts/items/{it['proposalItemId']}/detail", token)
                log(f"detail GET again -> available={again.get('available')} same={again.get('detailId') == created.get('detailId')}")
                break

    if confirmed_id:
        log("== 6. 과제 완료 체크 → 다음 초안에서 제외되는지 ==")
        aid, ver = confirmed_id
        _, fresh = call(base, "GET", f"/api/courses/{course_id}/assignments", token)
        row = next(a for a in fresh if a["assignmentId"] == aid)
        status, done = call(base, "PATCH", f"/api/assignments/{aid}/completed", token, {"completed": True, "version": row["version"]})
        log(f"complete {aid} -> {status} completed={done.get('completed') if isinstance(done, dict) else done}")
        _, opens = call(base, "GET", "/api/assignments", token)
        log(f"open assignments now: {[a['assignmentId'] for a in opens]}")

    if args.out:
        with io.open(args.out, "w", encoding="utf-8") as f:
            json.dump(record, f, ensure_ascii=False, indent=2, default=str)
    log("done")


if __name__ == "__main__":
    main()
