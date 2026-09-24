# 레포지토리 레이어 테스트

실제 PostgreSQL(Testcontainers)로 JPA/QueryDSL 쿼리와 제약 조건을 검증한다. **H2는 쓰지 않는다** — `db-design` 스킬과 같은 이유로, 조건부 UPDATE의 영향받은 행 수, `CHECK` 제약, `FOR UPDATE` 락 같은 PostgreSQL 고유 동작은 H2로 재현할 수 없다.

## 기본 구조

`com.tikkit.api.support.AbstractContainerTest`(Task 002에서 만든 공통 Testcontainers 베이스 클래스)를 상속한다(새로 만들지 않는다).

```java
class TicketGradeRepositoryTest extends AbstractContainerTest {

    @Autowired
    private TicketGradeRepository ticketGradeRepository;

    @Test
    @DisplayName("잔여 수량이 요청 수량 이상일 때만 조건부 UPDATE가 성공한다")
    void 조건부_UPDATE_성공_조건() {
        // given
        TicketGrade grade = ticketGradeRepository.save(fixture(remaining: 5));

        // when
        int updated = ticketGradeRepository.decreaseRemainingQuantity(grade.getId(), 3);

        // then
        assertThat(updated).isEqualTo(1);
        assertThat(ticketGradeRepository.findById(grade.getId()).get().getRemainingQuantity()).isEqualTo(2);
    }
}
```

## 규칙

- 검증 대상은 QueryDSL 동적 쿼리의 결과, `UNIQUE`/`CHECK` 제약 위반 시 예외, 조건부 UPDATE의 영향받은 행 수처럼 **DB 엔진이 실제로 관여하는 동작**이다. 단순 CRUD(`save`, `findById`)는 굳이 테스트하지 않는다 — Spring Data가 이미 검증한 코드다.
- 데이터 셋업은 `@BeforeEach` 또는 fixture 헬퍼 메서드로 최소화하고, 테스트마다 필요한 최소한의 데이터만 만든다.
- 각 테스트는 독립적으로 실행 가능해야 한다. 트랜잭션 롤백(`@Transactional` 테스트) 또는 `@AfterEach` 정리 중 하나를 베이스 클래스에서 통일한다.
- 제약 조건 테스트는 "성공"뿐 아니라 "위반 시 예외가 나는지"도 함께 검증한다(예: `UNIQUE(schedule_id, grade)` 중복 삽입 시 `DataIntegrityViolationException`).
- Phase 6 이후 `schedule_seats`처럼 복합 FK(`FOREIGN KEY (ticket_grade_id, schedule_id) REFERENCES ...`)가 있는 테이블은, 두 경로가 어긋나는 데이터를 넣었을 때 실제로 막히는지 테스트한다.
