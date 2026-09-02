# DB 백업 덤프

이 디렉터리의 `*.sql`은 **저장소에 커밋하지 않는다**(`.gitignore` 처리). 실제 사용자
데이터 — 이메일, bcrypt 비밀번호 해시, 일기 본문, AI 상담 대화 원문 — 가 그대로 들어가기
때문이다. README의 "실제 사용자 데이터가 포함된 SQL 덤프는 저장소에 커밋하지 않습니다"를
파일로 강제하는 자리다.

덤프는 로컬 디스크에만 둔다. 무엇을 언제 왜 떴는지는 그 작업을 한
`docs/sql/<날짜>-*.sql`의 주석에 남긴다.

## 뜨는 법

서버가 MariaDB이므로 같은 버전의 클라이언트를 쓴다(MySQL 8.0의 mysqldump는
MariaDB 상대로 `COLUMN_STATISTICS` 문제를 낸다).

```bash
"C:/xampp/mysql/bin/mysqldump.exe" -h127.0.0.1 -P3306 -uroot \
  --databases memo --default-character-set=utf8mb4 --single-transaction \
  --routines --events --triggers --add-drop-database \
  --result-file=docs/sql/backup/<날짜>-<이름>.sql
```

## ★ 다른 DB에 복원해 검증할 때

`--add-drop-database`로 뜬 덤프는 **버전 주석 형태**의 DROP을 포함한다.

```sql
/*!40000 DROP DATABASE IF EXISTS `memo`*/;
```

평문 `grep -v "^DROP DATABASE"`로는 걸러지지 않는다. 2026-08-25에 이걸 놓쳐
검증 중 실 DB가 드롭됐다(같은 백업으로 전량 복구). 검증용 사본을 만들 때는
`CREATE DATABASE` / `USE` 와 함께 이 줄도 반드시 제거하고, 제거됐는지 눈으로 확인한다.
