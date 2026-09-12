#!/usr/bin/env bash
#
# 푸시하려는 내용에 비공개 이름이 섞였는지 본다.
#
# 이 저장소는 공개다. 2026-09-07에 근무표를 다루면서 동료 실명이 두 곳에 들어갔다 —
# 테스트 픽스처의 이름으로, 그리고 측정 기록 문서에 "이름이 흔들렸다"를 적으면서 그 이름
# 자체로. 둘 다 푸시 전에 잡았지만, 잡은 것은 사람이 한 번 더 훑어봤기 때문이지 무엇이
# 막아준 게 아니었다.
#
# ★ 이름 목록은 저장소 안에 두지 않는다. 목록을 커밋하면 막으려던 그것을 커밋하는 셈이다.
#   기본 위치는 저장소 부모의 .private-names이고 PRIVATE_NAMES_FILE로 바꿀 수 있다.
#   한 줄에 하나, #으로 시작하는 줄과 빈 줄은 무시한다.
#
# 목록이 없으면 통과시키되 매번 눈에 띄게 알린다. 없다고 막으면 새로 받은 곳에서 아예
# 푸시가 안 되고, 그러면 --no-verify가 습관이 된다. 습관이 된 훅은 없는 것과 같다.

set -uo pipefail

NAMES_FILE="${PRIVATE_NAMES_FILE:-$(git rev-parse --show-toplevel)/../.private-names}"

if [ ! -f "$NAMES_FILE" ]; then
  echo "check-private-names: 이름 목록이 없어 검사를 건너뜁니다 ($NAMES_FILE)" >&2
  echo "  만들려면: 한 줄에 이름 하나씩 적은 파일을 그 경로에 두세요(저장소 밖입니다)." >&2
  exit 0
fi

# 검사할 범위. 인자로 받거나(훅이 넘긴다) 없으면 원격에 없는 커밋 전부를 본다.
RANGE="${1:-}"

# 변경분을 파일로 받는다. 셸 변수에 담아 `printf | grep`으로 넘기면 큰 푸시에서 조용히
# 아무것도 못 찾는다 — 실제로 그렇게 통과했고, 검사가 도는 것처럼 보이는 쪽이 더 나쁘다.
DIFF_FILE="$(mktemp)"
trap 'rm -f "$DIFF_FILE"' EXIT

# 추가된 줄만 본다. 삭제한 줄까지 걸면 이름을 지우는 커밋 자체가 막힌다.
if [ -z "$RANGE" ]; then
  git log --all --not --remotes -p --unified=0 --format='%H %s' > "$DIFF_FILE" 2>/dev/null
else
  git log "$RANGE" -p --unified=0 --format='%H %s' > "$DIFF_FILE" 2>/dev/null
fi

if [ ! -s "$DIFF_FILE" ]; then
  exit 0
fi

FOUND=0
CHECKED=0
while IFS= read -r NAME || [ -n "$NAME" ]; do
  # 윈도우에서 편집한 목록은 CRLF다. 떼지 않으면 "이름\r"을 찾게 되어 아무것도
  # 걸리지 않고 검사는 통과한 것처럼 보인다. 실제로 그렇게 통과했다.
  NAME="${NAME%$'\r'}"
  case "$NAME" in
    ''|\#*) continue ;;
  esac
  CHECKED=$((CHECKED + 1))
  HITS="$(grep -cF -- "$NAME" "$DIFF_FILE" || true)"
  if [ "${HITS:-0}" -gt 0 ]; then
    if [ "$FOUND" -eq 0 ]; then
      echo "" >&2
      echo "푸시를 멈췄습니다. 올라가려는 내용에 비공개 이름이 있습니다." >&2
      echo "" >&2
    fi
    FOUND=1
    # 이름 자체는 출력하지 않는다. 터미널 기록과 CI 로그에 남는다.
    echo "  목록 ${NAMES_FILE##*/}의 항목 하나가 ${HITS}곳에서 걸렸습니다." >&2
    echo "    찾으려면: git log $RANGE -p | grep -n <그 이름>" >&2
  fi
done < "$NAMES_FILE"

# 목록은 있는데 읽어낸 이름이 0개면 검사한 것이 없다. 통과로 보이면 안 된다.
if [ "$CHECKED" -eq 0 ]; then
  echo "check-private-names: 목록에서 이름을 하나도 읽지 못했습니다 ($NAMES_FILE)" >&2
  echo "  파일이 비었거나 형식이 다릅니다. 한 줄에 이름 하나입니다." >&2
  exit 1
fi

if [ "$FOUND" -eq 1 ]; then
  echo "" >&2
  echo "  고친 뒤 다시 푸시하세요. 이미 커밋했다면 히스토리도 손봐야 합니다" >&2
  echo "  (푸시 전이면 로컬 처리로 끝납니다)." >&2
  echo "  이 검사를 건너뛰려면: git push --no-verify" >&2
  echo "" >&2
  exit 1
fi

exit 0
