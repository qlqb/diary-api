#!/usr/bin/env bash
#
# 통합 테스트용 격리 DB(memo_test)를 만든다.
#
# 왜 필요한가: @SpringBootTest DB 테스트가 사용자의 memo DB에 직접 붙어 있었다. 2026-09-21에
# 두 가지가 드러났다.
#   1. 테스트가 만든 합성 자료를 IDE에 떠 있는 개발 서버가 devtools로 새 클래스를 올려 처리했고,
#      그 소요 시간 표본이 사용자의 "예상 분석 시간" 계산에 섞일 뻔했다.
#   2. 계획·교재 쪽 DB 테스트 아홉 개는 "users 표의 첫 번째 사용자"를 빌려 쓴다. memo에서
#      그것은 실제 계정(user_id=1)이다 — 테스트 행이 실제 사용자 이름으로 쓰이고 지워졌다.
# 붙는 DB 자체를 가르는 것 말고는 둘 다 막을 방법이 없다.
#
# 하는 일:
#   - memo에서 <스키마만> 떠 온다(--no-data). 사용자 데이터는 한 줄도 읽지 않는다.
#   - memo_test를 지우고 다시 만든 뒤 그 스키마를 싣는다.
#   - 합성 사용자 하나를 넣는다. "첫 번째 사용자"를 빌리는 테스트가 이 계정을 쓴다.
#
# 쓰는 법(diary-api 루트에서):
#   bash scripts/test-db/prepare-memo-test.sh
#
# 비밀번호는 src/main/resources/application-local.properties에서 읽어 MYSQL_PWD로만 넘긴다.
# 화면이나 파일에 찍지 않는다. MYSQL_BIN으로 mysql 클라이언트 위치를 바꿀 수 있다.
#
# 스키마가 바뀌면(새 마이그레이션) 다시 돌린다. build.gradle의 test 태스크가 이 DB를 쓰고,
# TestDatabaseIsolationTest가 "memo가 아니다"와 "테이블이 갖춰져 있다"를 매번 확인한다.

set -euo pipefail

SOURCE_DB="${SOURCE_DB:-memo}"
TARGET_DB="${TARGET_DB:-memo_test}"
MYSQL_BIN="${MYSQL_BIN:-/c/Program Files/MySQL/MySQL Server 8.0/bin}"
PROPS="${PROPS:-src/main/resources/application-local.properties}"

if [ "$TARGET_DB" = "$SOURCE_DB" ] || [ "$TARGET_DB" = "memo" ]; then
    echo "대상 DB가 사용자 DB와 같다: $TARGET_DB — 멈춘다." >&2
    exit 1
fi
if [ ! -f "$PROPS" ]; then
    echo "$PROPS 가 없다. 워크트리라면 메인 체크아웃의 파일을 복사하되 URL은 memo_test로 바꿔라." >&2
    exit 1
fi

MYSQL_PWD="$(grep -m1 '^spring.datasource.password=' "$PROPS" | cut -d= -f2-)"
export MYSQL_PWD
USER_NAME="$(grep -m1 '^spring.datasource.username=' "$PROPS" | cut -d= -f2-)"
USER_NAME="${USER_NAME:-root}"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

"$MYSQL_BIN/mysqldump" -u "$USER_NAME" --no-data --routines --triggers --events \
    --skip-add-drop-table "$SOURCE_DB" > "$WORK/schema.sql" 2>"$WORK/dump.err" \
    || { cat "$WORK/dump.err" >&2; exit 1; }

"$MYSQL_BIN/mysql" -u "$USER_NAME" -e \
    "DROP DATABASE IF EXISTS \`$TARGET_DB\`; CREATE DATABASE \`$TARGET_DB\` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
"$MYSQL_BIN/mysql" -u "$USER_NAME" "$TARGET_DB" < "$WORK/schema.sql"

# 합성 사용자. 이메일은 .invalid 도메인이라 어디로도 가지 않는다. 비밀번호 해시는 로그인이
# 되지 않는 값이다 — 이 계정으로 들어갈 일은 없고, 테스트가 user_id만 빌린다.
"$MYSQL_BIN/mysql" -u "$USER_NAME" "$TARGET_DB" -e \
    "INSERT INTO users (user_id, email, password_hash, nickname, role, status)
     VALUES (900000001, 'test-fixture@memo-test.invalid', '!no-login', '테스트 고정 사용자', 'USER', 'ACTIVE');"

TABLES="$("$MYSQL_BIN/mysql" -u "$USER_NAME" -N -B -e \
    "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$TARGET_DB'")"
echo "$TARGET_DB 준비 완료: 테이블 $TABLES개, 합성 사용자 1명(user_id=900000001)"
