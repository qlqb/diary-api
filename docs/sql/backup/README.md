# DB 백업 덤프

**덤프는 이 디렉터리에 두지 않는다. 저장소 밖 `<저장소 부모>/db-backup/`에 둔다.**

실제 사용자 데이터 — 이메일, bcrypt 비밀번호 해시, 일기 본문, AI 상담 대화 원문 — 가
그대로 들어가고, 이 저장소는 공개다.

`.gitignore`의 `docs/sql/backup/*.sql`은 그대로 두지만 거기에 기대지 않는다. 규칙은
`git add -f` 한 번으로 뚫리고, 나중에 `.gitignore`를 정리하다 그 줄이 지워져도 같은 결과가
난다. 파일이 저장소 트리 안에 없으면 두 사고 모두 일어나지 않는다.

이 디렉터리는 이제 이 문서만 있는 자리다 — 덤프를 여기 두려던 사람이 여기서 멈추게 하는
것이 목적이다.

## 왜 이렇게 바꿨나

2026-08-25 덤프가 `backup-before-rewrite` 브랜치에 커밋된 채 남아 있었다. 어느 원격에도
올라가지 않았지만, 공개 저장소의 로컬 브랜치는 `git push --all` 한 번이면 끝이다.
2026-09-07에 그 브랜치를 번들로 떠서 저장소 밖에 옮기고 삭제했다. 같은 논리가 파일에도
적용되므로 덤프 파일도 함께 옮겼다.

## 뜨는 법

서버가 MariaDB이므로 같은 버전의 클라이언트를 쓴다(MySQL 8.0의 mysqldump는
MariaDB 상대로 `COLUMN_STATISTICS` 문제를 낸다). `--result-file`이 **저장소 밖**을
가리키는 것에 주의한다.

```bash
"C:/xampp/mysql/bin/mysqldump.exe" -h127.0.0.1 -P3306 -uroot \
  --databases memo --default-character-set=utf8mb4 --single-transaction \
  --routines --events --triggers --add-drop-database \
  --result-file=../db-backup/<날짜>-<이름>.sql
```

무엇을 언제 왜 떴는지는 그 작업을 한 `docs/sql/<날짜>-*.sql`의 주석에 남긴다.

## ★ 다른 DB에 복원해 검증할 때

`--add-drop-database`로 뜬 덤프는 **버전 주석 형태**의 DROP을 포함한다.

```sql
/*!40000 DROP DATABASE IF EXISTS `memo`*/;
```

평문 `grep -v "^DROP DATABASE"`로는 걸러지지 않는다. 2026-08-25에 이걸 놓쳐
검증 중 실 DB가 드롭됐다(같은 백업으로 전량 복구). 검증용 사본을 만들 때는
`CREATE DATABASE` / `USE` 와 함께 이 줄도 반드시 제거하고, 제거됐는지 눈으로 확인한다.
