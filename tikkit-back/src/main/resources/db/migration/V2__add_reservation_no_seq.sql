-- 예약번호(TK{yyMMdd}-{6자리}) 뒤 6자리 채번용 시퀀스 (docs/ERD.md 4절 참조)
-- 날짜 prefix와 조합해 쓰므로 하루 100만 건을 넘기지 않는 한 중복이 나지 않는다.
-- 날짜가 바뀌어도 시퀀스 값은 리셋되지 않고 계속 누적된다 — 매일 000001부터 시작하지는 않는다.

CREATE SEQUENCE reservation_no_seq MINVALUE 1 MAXVALUE 999999 CYCLE;
COMMENT ON SEQUENCE reservation_no_seq IS '예약번호 뒤 6자리 채번용. 날짜 prefix와 조합하므로 하루 100만 건 미만이면 중복 없음';
