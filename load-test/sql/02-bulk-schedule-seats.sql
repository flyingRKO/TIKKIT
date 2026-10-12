-- schedule_seats 약 600만 행 생성 (Task 027)
--
-- 한 INSERT로 600만 행을 넣지 않고 프로시저로 회차 단위 청크를 돌린다. 이유 셋 —
--  1) 한 트랜잭션이 10분 넘게 열려 있다가 실패하면 전부 롤백된다. 청크마다 COMMIT하면
--     중단되어도 거기까지는 남고, 재실행이 "좌석이 아직 없는 회차"부터 이어서 채운다.
--  2) 청크별 경과 시간이 NOTICE로 찍혀서 "10만 행에 몇 초"를 측정 없이 알 수 있다.
--     기본 청크 50회차 = 10만 행이라 첫 NOTICE가 곧 전체 소요 시간의 1/60 표본이다.
--  3) 중간에 Ctrl+C로 끊고 작은 규모로 먼저 측정할 수 있다.
--
-- 인덱스를 떼고 넣지 않는다. schedule_seats는 fillfactor 90 + "인덱스 없음"이 HOT 업데이트를
-- 노린 설계(V4 주석)라서, 운영과 같은 모양으로 넣었을 때의 쓰기 시간 자체가 기록할 값이다.
-- 너무 느리면 그때 uk_schedule_seats_schedule_seat를 떼는 쪽으로 바꾸고 문서에 남긴다.
--
-- 실행:
--   docker compose -f docker-compose.prod.yml exec -T postgres \
--     psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -f - < load-test/sql/02-bulk-schedule-seats.sql
--
-- 주의: 이 프로시저는 Flyway 밖에서 만드는 DB 객체다. ddl-auto가 validate라 엔티티 매핑만
-- 검증하므로 애플리케이션 기동에는 영향이 없고, 99-teardown.sql이 같이 지운다.

CREATE OR REPLACE PROCEDURE load_test_fill_schedule_seats(p_chunk int DEFAULT 50)
    LANGUAGE plpgsql
AS
$$
DECLARE
    v_venue_id  bigint;
    v_schedules bigint[];
    v_inserted  bigint;
    v_total     bigint      := 0;
    v_started   timestamptz := clock_timestamp();
    v_chunk_at  timestamptz;
BEGIN
    SELECT id INTO v_venue_id FROM venues WHERE name = '[LOAD] 부하테스트 아레나';
    IF v_venue_id IS NULL THEN
        RAISE EXCEPTION '부하 테스트 공연장이 없다. 01-bulk-catalog.sql을 먼저 실행한다.';
    END IF;

    LOOP
        v_chunk_at := clock_timestamp();

        -- 아직 좌석이 하나도 없는 회차를 p_chunk개 집는다.
        -- NOT EXISTS는 uk_schedule_seats_schedule_seat(schedule_id가 선두 컬럼)를 탄다.
        SELECT array_agg(sub.id)
        INTO v_schedules
        FROM (SELECT s.id
              FROM schedules s
                       JOIN performances p ON p.id = s.performance_id
              WHERE p.venue_id = v_venue_id
                AND NOT EXISTS (SELECT 1 FROM schedule_seats ss WHERE ss.schedule_id = s.id)
              ORDER BY s.id
              LIMIT p_chunk) sub;

        -- array_agg는 빈 결과에 NULL을 준다. 남은 회차가 없다는 뜻이다.
        EXIT WHEN v_schedules IS NULL;

        -- 좌석의 section이 등급명 그대로라(01-bulk-catalog [2/5]) 등급 조인이 한 줄이다.
        -- 이 조인이 fk_schedule_seats_ticket_grade_schedule(복합 FK)을 자동으로 만족시킨다 —
        -- ticket_grade_id를 그 회차의 등급에서만 가져오기 때문이다.
        INSERT INTO schedule_seats (schedule_id, seat_id, ticket_grade_id, status)
        SELECT s.id, st.id, tg.id, 'AVAILABLE'
        FROM schedules s
                 JOIN seats st ON st.venue_id = v_venue_id
                 JOIN ticket_grades tg ON tg.schedule_id = s.id AND tg.grade = st.section
        WHERE s.id = ANY (v_schedules);

        GET DIAGNOSTICS v_inserted = ROW_COUNT;
        v_total := v_total + v_inserted;

        -- 프로시저(CALL)라서 COMMIT을 쓸 수 있다. DO 블록에서는 불가능하다.
        COMMIT;

        RAISE NOTICE '회차 % 개 처리 (이번 % 행 / 누적 % 행) — 청크 %, 전체 %',
            array_length(v_schedules, 1), v_inserted, v_total,
            clock_timestamp() - v_chunk_at, clock_timestamp() - v_started;
    END LOOP;

    RAISE NOTICE '완료 — 총 % 행, 소요 %', v_total, clock_timestamp() - v_started;
END;
$$;

-- 청크 50회차 = 10만 행. 첫 NOTICE의 경과 시간이 전체 추정에 쓰인다.
-- 작게 먼저 재보려면 psql에서 CALL load_test_fill_schedule_seats(5); 로 1만 행만 넣어 본다.
CALL load_test_fill_schedule_seats(50);

-- 결과 확인 — 목표는 약 600만 행
SELECT count(*) AS schedule_seats_total,
       count(*) FILTER (WHERE status = 'AVAILABLE') AS available
FROM schedule_seats;
