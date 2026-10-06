"""
AI 자료 선택 → 원문 조회 → 계획 호출을 실제 서버·실제 모델로 돌리고, 선택 결과·읽은 범위·최종 근거·입력 토큰(추정 vs 실측)을
표준 출력과 JSON으로 남긴다(2026-09-15, 15번 문서 §7.4).

합성 계정·합성 자료만 쓴다. 사용자 실제 계정·파일·원문은 쓰지 않는다. 비밀번호는 코드에 두지 않는다(환경 변수).

시나리오:
  small   — 기존 합성 계정의 한 과목(합성 PDF 3종). 지시를 바꿔 선택이 달라지는지(개념 먼저 / 문제부터),
            지정 자료, 이번만 빼기 → 같은 조건 다시 만들기 → 되돌리기(기간·지시·범위 유지).
  large   — --seed-large로 만든 합성 계정(과목 6 · 학습 항목 288 · 구간 540). 전체 범위 초안에서 묶음 접기·펼치기와
            입력 토큰을 잰다.

사용 예(로컬 DB 계정 정보는 application-local.properties에서 읽는다):
  VERIFY_EMAIL=... VERIFY_PASSWORD=... python scripts/ai-baseline/verify-ai-material-selection-2026-09-15.py small --course-id 672
  VERIFY_PASSWORD=... python scripts/ai-baseline/verify-ai-material-selection-2026-09-15.py seed-large
  VERIFY_EMAIL=... VERIFY_PASSWORD=... python scripts/ai-baseline/verify-ai-material-selection-2026-09-15.py large

표준 라이브러리 + mysql CLI만 쓴다. 한글 본문은 UTF-8로 보낸다.
"""
import argparse
import io
import json
import os
import pathlib
import subprocess
import sys
import time
import urllib.error
import urllib.request
from datetime import date, timedelta

OUT = io.open(sys.stdout.fileno(), "w", encoding="utf-8", closefd=False)
ROOT = pathlib.Path(__file__).resolve().parents[2]
BASE = os.environ.get("VERIFY_BASE", "http://localhost:8081")


def log(msg=""):
    OUT.write(msg + "\n")
    OUT.flush()


def call(method, path, token=None, body=None, timeout=300):
    headers = {}
    data = None
    if body is not None:
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
        headers["Content-Type"] = "application/json; charset=utf-8"
    if token:
        headers["Authorization"] = "Bearer " + token
    req = urllib.request.Request(BASE + path, data=data, method=method, headers=headers)
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
    """비어 있으면 안 되는 조회. 로컬 mysql CLI가 드물게 빈 결과를 돌려준 적이 있어(2026-09-15, 원인 미확인) 몇 번 다시 묻는다."""
    for i in range(attempts):
        rows = mysql(sql)
        if rows:
            return rows
        log(f"   (mysql 빈 결과 — 다시 조회 {i + 1}/{attempts})")
        time.sleep(1)
    raise RuntimeError("조회 결과가 계속 비어 있다: " + sql[:120])


def usage_by_workflow(workflow_id):
    rows = mysql(f"SELECT feature, input_tokens, output_tokens, result_status FROM ai_usage_logs "
                 f"WHERE workflow_id = '{workflow_id}' ORDER BY usage_log_id;")
    return [{"feature": r[0], "input": int(r[1]) if r[1] != "NULL" else None,
             "output": int(r[2]) if r[2] != "NULL" else None, "status": r[3]} for r in rows]


# ===== 공통 =====

def login():
    email = os.environ["VERIFY_EMAIL"]
    status, body, _ = call("POST", "/api/auth/login", body={"email": email, "password": os.environ["VERIFY_PASSWORD"]})
    assert status == 200, (status, body)
    return body["token"], email


def summarize_draft(name, status, draft, elapsed, token):
    record = {"name": name, "status": status, "seconds": round(elapsed, 1)}
    if status != 200:
        record["error"] = draft
        log(f"[{name}] HTTP {status} {json.dumps(draft, ensure_ascii=False)[:300]}")
        return record
    sel = draft.get("materialSelection") or {}
    ctx = draft.get("requestContext") or {}
    items = (draft.get("proposal") or {}).get("items") or []
    record.update({
        "proposalId": draft.get("proposalId"), "period": [draft.get("startDate"), draft.get("endDate")],
        "selection": {k: sel.get(k) for k in ("status", "mode", "selectionCalls", "expanded", "candidateTotal",
                                             "candidateShown", "insufficientEvidence", "note", "unknownIds",
                                             "selectionInputTokens", "planInputTokens", "perSectionChars")},
        "picked": [{k: s.get(k) for k in ("sectionId", "title", "locator", "filename", "reason", "outcome",
                                           "retrievedRange", "refId")} for s in sel.get("sections", [])],
        "topics": [t.get("title") for t in sel.get("topics", [])],
        "unreviewed": sel.get("unreviewed", [])[:10],
        "requested": sel.get("requestedMaterials", []),
        "ambiguities": sel.get("ambiguities", []),
        "requestContext": ctx,
        "items": [{"title": i.get("title"), "minutes": i.get("expectedMinutes"),
                   "description": (i.get("description") or "")[:160]} for i in items],
    })
    # 최종 근거: 항목 refIds가 계획 호출에 실제로 준 줄만 가리키는가(선택 호출의 핸들이 아닌가)
    s2, prov, _ = call("GET", f"/api/plans/drafts/{draft['proposalId']}/provenance", token)
    if s2 == 200:
        sources = {p["refId"]: p for p in prov.get("providedSources", [])}
        cited = []
        for ev in prov.get("items", []):
            for ref in ev.get("refIds") or []:
                src = sources.get(ref)
                cited.append({"ref": ref, "type": src and src.get("sourceType"), "sourceId": src and src.get("sourceId"),
                              "retrieval": src and (src.get("providedValue") or {}).get("retrieval")})
        record["citations"] = cited
        record["unknownRefCount"] = sum((ev.get("unknownRefCount") or 0) for ev in prov.get("items", []))
        record["generationId"] = prov.get("generationId")
        if prov.get("generationId"):
            record["usage"] = usage_by_workflow(prov["generationId"])
    log(f"[{name}] {elapsed:.1f}s proposal={record['proposalId']} selection={record['selection']}")
    for p in record["picked"]:
        log(f"   picked: {p['title']} ({p['locator']}) · {p['filename']} · {p['outcome']} · {p['retrievedRange']} · 이유: {p['reason']}")
    for t in record["topics"]:
        log(f"   topic: {t}")
    for it in record["items"]:
        log(f"   item: {it['title']} · {it['minutes']}분 · {it['description']}")
    log(f"   citations: {record.get('citations')}  unknownRefs={record.get('unknownRefCount')}")
    log(f"   usage(workflowId): {record.get('usage')}")
    return record


def small(args):
    token, _ = login()
    today = date.today()
    base = {"startDate": today.isoformat(), "endDate": (today + timedelta(days=6)).isoformat(),
            "courseIds": [args.course_id]}
    records = []

    s, d, t = call("POST", "/api/plans/draft", token, {**base, "instruction": "개념부터 이해하고 싶어. 개념 설명을 먼저 보고 싶다"})
    first = summarize_draft("concept-first", s, d, t, token)
    records.append(first)
    s, d, t = call("POST", "/api/plans/draft", token, {**base, "instruction": "개념 설명은 건너뛰고 실습 문제부터 풀고 싶어"})
    second = summarize_draft("problems-first", s, d, t, token)
    records.append(second)

    materials = mysql_rows(f"SELECT cm.material_id, cm.original_filename FROM course_materials cm JOIN material_links l "
                           f"ON l.material_id = cm.material_id WHERE l.course_id = {args.course_id} AND cm.status = 'ACTIVE';")
    target = next((m for m in materials if "long-late" in m[1]), materials[0])
    s, d, t = call("POST", "/api/plans/draft", token, {**base, "requestedMaterialIds": [int(target[0])]})
    requested = summarize_draft("requested-material", s, d, t, token)
    records.append(requested)

    s, d, t = call("POST", "/api/plans/draft", token, {**base, "instruction": f"{target[1].rsplit('.', 1)[0]} 중심으로 봐줘"})
    records.append(summarize_draft("instruction-filename", s, d, t, token))

    # 이번만 빼기 → 다시 만들기 → 되돌리기. 기간·지시·범위가 유지되는지 저장된 요청으로 확인한다.
    # 문제 먼저 초안(연습 구간이 학습 항목에 연결돼 있다)에서 인용한 학습 항목을 뺀다. 그 초안이 실패했으면 개념 먼저 초안으로.
    first = second if second.get("status") == 200 else first
    if first.get("status") == 200:
        topic_ids = [c["sourceId"] for c in first.get("citations", []) if c["type"] == "TOPIC" and c["sourceId"]]
        if not topic_ids:
            rows = mysql(f"SELECT topic_id FROM course_topics WHERE course_id = {args.course_id} AND status='ACTIVE' LIMIT 1;")
            topic_ids = [int(rows[0][0])]
        exclude = topic_ids[:1]
        s, d, t = call("POST", f"/api/plans/proposals/{first['proposalId']}/redraft", token, {"excludeTopicIds": exclude})
        r = summarize_draft("redraft-exclude", s, d, t, token)
        if s == 200:
            stored = mysql_rows(f"SELECT plan_request_json FROM ai_proposals WHERE proposal_id = {r['proposalId']};")[0][0]
            old = mysql_rows(f"SELECT status FROM ai_proposals WHERE proposal_id = {first['proposalId']};")[0][0]
            r["storedRequest"] = json.loads(stored)
            r["oldProposalStatus"] = old
            log(f"   stored request: {stored}")
            log(f"   old proposal status: {old}")
            s, d, t = call("POST", f"/api/plans/proposals/{r['proposalId']}/redraft", token, {"excludeTopicIds": []})
            back = summarize_draft("redraft-restore", s, d, t, token)
            records.append(r)
            records.append(back)
        else:
            records.append(r)
    # 지정 자료 초안을 본문 없이 다시 만들면 지정 자료가 그대로 유지돼야 한다.
    if requested.get("status") == 200:
        s, d, t = call("POST", f"/api/plans/proposals/{requested['proposalId']}/redraft", token, {})
        kept = summarize_draft("redraft-keeps-requested", s, d, t, token)
        if s == 200:
            stored = json.loads(mysql_rows(f"SELECT plan_request_json FROM ai_proposals WHERE proposal_id = {kept['proposalId']};")[0][0])
            kept["storedRequest"] = stored
            log(f"   stored requestedMaterialIds={stored.get('requestedMaterialIds')} period={stored.get('startDate')}~{stored.get('endDate')}")
        records.append(kept)
    return records


# ===== 큰 합성 계정 =====

COURSES = ["자료구조", "네트워크프로그래밍", "웹서버프로그래밍", "빅데이터분석", "센서활용프로그래밍", "스마트앱프로젝트"]
SUBJECTS = ["기초 개념", "자료 표현", "핵심 알고리즘", "구현 실습", "성능 분석", "응용 사례", "오류 처리", "정리와 확장"]
DETAILS = ["정의와 용어", "동작 원리", "예제 따라가기", "연습 문제", "심화 과제 준비"]


def sql_str(s):
    return "'" + s.replace("\\", "\\\\").replace("'", "''") + "'"


def seed_large(args):
    stamp = str(int(time.time()))
    email = f"ai-selection-{stamp}@example.com"
    password = os.environ["VERIFY_PASSWORD"]
    s, body, _ = call("POST", "/api/auth/signup", body={"email": email, "password": password, "nickname": f"sel{stamp}"})
    assert s in (200, 201), (s, body)
    user_id = int(mysql(f"SELECT user_id FROM users WHERE email = {sql_str(email)};")[0][0])
    lines = ["SET NAMES utf8mb4;", "START TRANSACTION;"]
    for ci, course in enumerate(COURSES):
        lines.append(f"INSERT INTO courses (user_id, title, status) VALUES ({user_id}, {sql_str(course + '(선택 검증)')}, 'ACTIVE');")
        lines.append("SET @course = LAST_INSERT_ID();")
        for mi, (name, pages) in enumerate([(f"{course}_강의자료.pdf", 80), (f"{course}_개념정리.pdf", 10)]):
            h = f"sel{stamp}c{ci}m{mi}".ljust(64, "0")[:64]
            lines.append(
                "INSERT INTO course_materials (user_id, original_filename, stored_filename, storage_path, size_bytes, page_count, "
                f"file_hash, extraction_status, extracted_text, status) VALUES ({user_id}, {sql_str(name)}, 'none.pdf', "
                f"{sql_str(f'{user_id}/synthetic-{ci}-{mi}.pdf')}, 1, {pages}, '{h}', 'SUCCESS', '합성', 'ACTIVE');")
            lines.append(f"SET @m{mi} = LAST_INSERT_ID();")
            lines.append(f"INSERT INTO material_links (user_id, material_id, course_id, material_type) VALUES ({user_id}, @m{mi}, @course, 'PROFESSOR_SLIDE');")
            lines.append("INSERT INTO material_analysis_jobs (user_id, material_id, course_id, job_kind, file_hash, analysis_version, "
                         f"priority, status, attempt, max_attempts, next_run_at, finished_at) VALUES ({user_id}, @m{mi}, 0, 'CONTENT', "
                         f"'{h}', 1, 10, 'DONE', 1, 3, NOW(), NOW());")
            lines.append(f"SET @h{mi} = '{h}';")
        page = 0
        for wi, subject in enumerate(SUBJECTS):
            week = wi + 1
            lines.append("INSERT INTO course_topics (user_id, course_id, title, order_index, source_type, source_locator, status) "
                         f"VALUES ({user_id}, @course, {sql_str(f'{week}주차 {subject}')}, {wi}, 'SOURCE', '{week}주차', 'ACTIVE');")
            lines.append("SET @root = LAST_INSERT_ID();")
            for di, detail in enumerate(DETAILS):
                title = f"{course} {subject}: {detail}"
                lines.append("INSERT INTO course_topics (user_id, course_id, parent_topic_id, title, order_index, source_type, "
                             f"source_locator, status) VALUES ({user_id}, @course, @root, {sql_str(title)}, {di}, 'SOURCE', '{week}주차', 'ACTIVE');")
                lines.append("SET @topic = LAST_INSERT_ID();")
                if wi == 1 and di == 1:
                    lines.append(f"INSERT INTO topic_progress (user_id, topic_id, status, last_studied_at) VALUES ({user_id}, @topic, 'IN_PROGRESS', NOW() - INTERVAL 2 DAY);")
                if wi == 0:
                    lines.append(f"INSERT INTO topic_progress (user_id, topic_id, status, last_studied_at) VALUES ({user_id}, @topic, 'LEARNED', NOW() - INTERVAL 7 DAY);")
                for role, kind in [("CONCEPT", "개념 설명"), ("EXERCISE", "연습 문제")]:
                    page += 1
                    marker = f"[{course}-{week}-{di}-{role}]"
                    body = (f"{marker} {title}의 {kind}. " + (
                        f"{detail}을 이해하기 위한 설명이 이어진다. 핵심 용어를 정의하고 그림으로 흐름을 보여 준다. " * 4
                        if role == "CONCEPT" else
                        f"문제 {di + 1}-1: {detail}을 직접 구현하고 결과를 확인하라. 문제 {di + 1}-2: 입력 크기를 바꿔 수행 시간을 비교하라. " * 3))
                    lines.append("INSERT INTO material_text_units (user_id, material_id, file_hash, unit_index, unit_type, unit_no, char_count, text) "
                                 f"VALUES ({user_id}, @m0, @h0, {page}, 'PDF_PAGE', {page}, {len(body)}, {sql_str(body)});")
                    lines.append("INSERT INTO material_sections (user_id, material_id, file_hash, analysis_version, chunk_index, unit_type, "
                                 "unit_start, unit_end, display_title, roles_json, task_text, excerpt, assignment_cue, dedupe_key, status) "
                                 f"VALUES ({user_id}, @m0, @h0, 1, 0, 'PDF_PAGE', {page}, {page}, {sql_str(f'{detail} {kind}')}, "
                                 f"'[\"{role}\"]', {sql_str(f'{detail} 문제 풀이') if role == 'EXERCISE' else 'NULL'}, "
                                 f"{sql_str(body[:160])}, 0, {sql_str(f'k{page}')}, 'ACTIVE');")
                    lines.append("INSERT INTO topic_material_links (user_id, course_id, topic_id, material_id, section_id, role, locator, origin, status) "
                                 f"VALUES ({user_id}, @course, @topic, @m0, LAST_INSERT_ID(), '{role}', 'p.{page}', 'PROPOSAL_APPLIED', 'ACTIVE');")
                if wi == 3 and di == 3:
                    lines.append("INSERT INTO course_assignments (user_id, course_id, material_id, topic_id, title, confirm_status, due_kind, "
                                 f"due_date, due_source, dedupe_key, version) VALUES ({user_id}, @course, @m0, @topic, "
                                 f"{sql_str(f'{course} 과제 · {subject} 구현 보고서')}, 'CONFIRMED', 'DATE', DATE_ADD(CURDATE(), INTERVAL 4 DAY), "
                                 f"'SOURCE', {sql_str(f'sel{stamp}-{ci}-open')}, 1);")
                if wi == 2 and di == 3:
                    lines.append("INSERT INTO course_assignments (user_id, course_id, material_id, topic_id, title, confirm_status, due_kind, "
                                 f"due_date, due_source, completed_at, dedupe_key, version) VALUES ({user_id}, @course, @m0, @topic, "
                                 f"{sql_str(f'{course} 과제 · {subject} 연습 제출')}, 'CONFIRMED', 'DATE', DATE_SUB(CURDATE(), INTERVAL 3 DAY), "
                                 f"'SOURCE', NOW() - INTERVAL 4 DAY, {sql_str(f'sel{stamp}-{ci}-done')}, 1);")
        for pi in range(10):
            body = f"[{course}-concept-{pi}] {course} 개념 정리 {pi + 1}쪽. 앞 주차 내용을 한 장으로 요약하고 자주 틀리는 부분을 짚는다. " * 3
            lines.append("INSERT INTO material_text_units (user_id, material_id, file_hash, unit_index, unit_type, unit_no, char_count, text) "
                         f"VALUES ({user_id}, @m1, @h1, {pi + 1}, 'PDF_PAGE', {pi + 1}, {len(body)}, {sql_str(body)});")
            lines.append("INSERT INTO material_sections (user_id, material_id, file_hash, analysis_version, chunk_index, unit_type, unit_start, "
                         "unit_end, display_title, roles_json, excerpt, assignment_cue, dedupe_key, status) "
                         f"VALUES ({user_id}, @m1, @h1, 1, 0, 'PDF_PAGE', {pi + 1}, {pi + 1}, {sql_str(f'개념 정리 {pi + 1}')}, "
                         f"'[\"CONCEPT\",\"SUMMARY\"]', {sql_str(body[:160])}, 0, {sql_str(f'c{pi}')}, 'ACTIVE');")
    lines.append("COMMIT;")
    mysql("\n".join(lines))
    counts = mysql(f"SELECT (SELECT COUNT(*) FROM courses WHERE user_id={user_id}), (SELECT COUNT(*) FROM course_topics WHERE user_id={user_id}), "
                   f"(SELECT COUNT(*) FROM material_sections WHERE user_id={user_id}), (SELECT COUNT(*) FROM material_text_units WHERE user_id={user_id});")
    log(f"seeded large synthetic account: {email} user_id={user_id} courses/topics/sections/units={counts[0]}")
    log("(비밀번호는 VERIFY_PASSWORD로 준 값. 정리는 사용자 판단)")


def large(args):
    token, _ = login()
    today = date.today()
    records = []
    s, d, t = call("POST", "/api/plans/draft", token, {"startDate": today.isoformat(),
                                                       "endDate": (today + timedelta(days=6)).isoformat(),
                                                       "instruction": "이번 주는 네트워크프로그래밍의 구현 실습과 과제 마감을 우선으로"})
    records.append(summarize_draft("large-all-courses", s, d, t, token))
    s, d, t = call("POST", "/api/plans/draft", token, {"startDate": today.isoformat(),
                                                       "endDate": (today + timedelta(days=6)).isoformat()})
    records.append(summarize_draft("large-no-instruction", s, d, t, token))

    # 큰 계정에서 이번만 빼기 → 다시 만들기 → 되돌리기. 뺀 학습 항목이 새 초안의 선택·인용에서 사라지는지 본다.
    base = next((r for r in reversed(records) if r.get("status") == 200), None)
    if base:
        cited = [c["sourceId"] for c in base.get("citations", []) if c["type"] == "TOPIC" and c["sourceId"]]
        if cited:
            exclude = cited[:1]
            s, d, t = call("POST", f"/api/plans/proposals/{base['proposalId']}/redraft", token, {"excludeTopicIds": exclude})
            r = summarize_draft("large-redraft-exclude", s, d, t, token)
            if s == 200:
                stored = json.loads(mysql_rows(f"SELECT plan_request_json FROM ai_proposals WHERE proposal_id = {r['proposalId']};")[0][0])
                old = mysql_rows(f"SELECT status FROM ai_proposals WHERE proposal_id = {base['proposalId']};")[0][0]
                still = [c for c in r.get("citations", []) if c["type"] == "TOPIC" and c["sourceId"] in exclude]
                r.update({"storedRequest": stored, "oldProposalStatus": old, "excludedStillCited": len(still)})
                log(f"   excluded={exclude} stored.exclude={stored.get('excludeTopicIds')} instruction={stored.get('instruction')!r} "
                    f"period={stored.get('startDate')}~{stored.get('endDate')} old={old} excludedStillCited={len(still)}")
                log(f"   requestContext.excludedTopics={(d.get('requestContext') or {}).get('excludedTopics')}")
                records.append(r)
                s, d, t = call("POST", f"/api/plans/proposals/{r['proposalId']}/redraft", token, {"excludeTopicIds": []})
                back = summarize_draft("large-redraft-restore", s, d, t, token)
                records.append(back)
            else:
                records.append(r)
    return records


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("mode", choices=["small", "seed-large", "large"])
    parser.add_argument("--course-id", type=int, default=672)
    parser.add_argument("--out", default=None)
    args = parser.parse_args()
    if args.mode == "seed-large":
        seed_large(args)
        return
    records = small(args) if args.mode == "small" else large(args)
    out = pathlib.Path(args.out or ROOT / "build" / "synthetic" / f"ai-selection-{args.mode}-{int(time.time())}.json")
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(records, ensure_ascii=False, indent=2), encoding="utf-8")
    log(f"saved {out}")


if __name__ == "__main__":
    main()
