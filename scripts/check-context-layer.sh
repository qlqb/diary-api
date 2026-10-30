#!/usr/bin/env bash
#
# docs/context/의 기계 추출 문서가 코드와 어긋났는지 본다.
#
# docs/context/는 웹 채팅 프로젝트 지식에 이 디렉터리 하나만 올려도 설계를 논의할 수 있게 만든
# 압축 요약이다. 요약은 코드와 어긋나는 순간 틀린 답을 자신 있게 내놓게 만든다 — 없는 것보다
# 나쁘다. 그래서 사람이 기억해서 고치는 데 기대지 않고, 셀 수 있는 것은 여기서 센다.
#
#   1. SCHEMA.md의 `### 표` 집합
#        ⊇ docs/sql/*.sql의 CREATE TABLE 대상(주석 줄 제외)
#        == 매퍼 XML·Java 문자열의 SQL이 읽고 쓰는 표(FROM/JOIN/INTO/UPDATE 뒤)
#      DDL이 저장소에 없는 핵심 표(users·execution_items 등)는 두 번째 집합으로 잡힌다.
#   2. API.md의 엔드포인트 행 수 == 메서드 레벨 매핑 애너테이션 수,
#      API.md의 prefix 표기 수 == 클래스 레벨 @RequestMapping 수
#   3. docs/context/ 파일마다 분량 상한
#
# 기대값을 숫자로 박지 않는다. 숫자를 박으면 표를 하나 추가할 때마다 이 스크립트도 고쳐야 하고,
# 그러면 사람들은 문서 대신 숫자를 고친다.
#
# 연결(CI·.githooks)은 아직 하지 않았다. 손으로 돌린다:
#   bash scripts/check-context-layer.sh

set -uo pipefail

ROOT="$(git rev-parse --show-toplevel)"
cd "$ROOT" || exit 2
CTX=docs/context
STATUS=0
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

fail() { echo "check-context-layer: $*" >&2; STATUS=1; }

# --- 1. SCHEMA ------------------------------------------------------------

grep -h '^### ' "$CTX/SCHEMA.md" | cut -c5- | tr -d '\r' | sort -u > "$TMP/schema"

# 주석 줄의 "SHOW CREATE TABLE x"나 "(CREATE TABLE IF NOT EXISTS, ...)"를 세지 않으려고 줄 맨 앞만 본다.
grep -rhiE '^\s*create\s+table' docs/sql --include='*.sql' \
  | grep -oiE 'create\s+table\s+(if\s+not\s+exists\s+)?`?[a-z_0-9]+' \
  | awk '{print tolower($NF)}' | tr -d '`' | sort -u > "$TMP/created"

# SQL 키워드는 대문자로 쓰는 것이 이 저장소의 관례다. 그 덕에 한글·영문 문장 속 "from"을 잡지 않는다.
# ON DUPLICATE KEY UPDATE 뒤에는 표가 아니라 열이 온다.
{
  find src/main/resources -name '*.xml' -print0 | xargs -0 cat
  find src/main/java -name '*.java' -print0 | xargs -0 grep -h '"'
} | sed -E 's/KEY UPDATE//g' \
  | grep -oE '\b(FROM|JOIN|INTO|UPDATE)\s+`?[a-z][a-z0-9_]+' \
  | awk '{print $2}' | tr -d '`' | sort -u > "$TMP/used"

MISSING_CREATED="$(comm -23 "$TMP/created" "$TMP/schema")"
[ -n "$MISSING_CREATED" ] && fail "SCHEMA.md에 없는 CREATE TABLE 대상: $(echo $MISSING_CREATED)"

MISSING_USED="$(comm -23 "$TMP/used" "$TMP/schema")"
[ -n "$MISSING_USED" ] && fail "코드가 쓰지만 SCHEMA.md에 없는 표: $(echo $MISSING_USED)"

EXTRA="$(comm -23 "$TMP/schema" <(sort -u "$TMP/created" "$TMP/used"))"
[ -n "$EXTRA" ] && fail "SCHEMA.md에 있지만 DDL도 코드 참조도 없는 표: $(echo $EXTRA)"

echo "SCHEMA: 문서 $(wc -l < "$TMP/schema") · CREATE $(wc -l < "$TMP/created") · 코드 참조 $(wc -l < "$TMP/used")"

# --- 2. API ---------------------------------------------------------------

grep -rhE '@(Get|Post|Put|Patch|Delete|Request)Mapping\b' src/main/java --include='*.java' \
  | grep -vE '^\s*(//|\*|/\*)' > "$TMP/ann"
ANN="$(grep -oE '@(Get|Post|Put|Patch|Delete|Request)Mapping\b' "$TMP/ann" | wc -l)"
# 클래스 레벨 애너테이션은 들여쓰기 없이 줄 맨 앞에 온다(메서드 레벨은 항상 들여쓴다).
CLASS="$(grep -cE '^@RequestMapping\b' "$TMP/ann")"
ROWS="$(grep -cE '^- (GET|POST|PUT|PATCH|DELETE) /' "$CTX/API.md")"
PREFIXES="$(grep -cE '· prefix `' "$CTX/API.md")"

[ "$ROWS" -eq $((ANN - CLASS)) ] || fail "API.md 엔드포인트 $ROWS행 ≠ 메서드 매핑 $((ANN - CLASS))개"
[ "$PREFIXES" -eq "$CLASS" ] || fail "API.md prefix $PREFIXES개 ≠ 클래스 @RequestMapping $CLASS개"

echo "API: 애너테이션 $ANN = 엔드포인트 $ROWS + 클래스 prefix $PREFIXES"

# --- 3. 분량 상한 (바이트, KB = 1000) -------------------------------------

check_size() {
  local f="$CTX/$1" cap=$(($2 * 1000))
  [ -f "$f" ] || return 0
  local n
  n="$(wc -c < "$f")"
  [ "$n" -le "$cap" ] || fail "$1 ${n}B > 상한 ${cap}B"
}
check_size MAP.md 6
check_size ARCHITECTURE.md 12
check_size DOMAIN.md 20
check_size SCHEMA.md 30
check_size API.md 25
check_size CONVENTIONS.md 10
check_size DECISIONS.md 15

TOTAL="$(cat "$CTX"/*.md | wc -c)"
[ "$TOTAL" -le 120000 ] || fail "docs/context 합계 ${TOTAL}B > 120000B"

[ "$STATUS" -eq 0 ] && echo "check-context-layer: 통과"
exit "$STATUS"
