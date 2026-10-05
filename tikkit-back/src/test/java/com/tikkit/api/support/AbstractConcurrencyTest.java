package com.tikkit.api.support;

import com.tikkit.api.domain.member.entity.Member;
import com.tikkit.api.domain.member.entity.MemberRole;
import com.tikkit.api.domain.member.repository.MemberRepository;
import com.tikkit.api.domain.performance.entity.Grade;
import com.tikkit.api.domain.performance.entity.Performance;
import com.tikkit.api.domain.performance.entity.PerformanceCategory;
import com.tikkit.api.domain.performance.entity.PerformanceStatus;
import com.tikkit.api.domain.performance.entity.Schedule;
import com.tikkit.api.domain.performance.entity.TicketGrade;
import com.tikkit.api.domain.performance.repository.PerformanceRepository;
import com.tikkit.api.domain.performance.repository.ScheduleRepository;
import com.tikkit.api.domain.performance.repository.TicketGradeRepository;
import com.tikkit.api.domain.venue.entity.Venue;
import com.tikkit.api.domain.venue.repository.VenueRepository;
import com.tikkit.api.security.MemberDetails;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 동시성 재현 테스트 공통 베이스 (Task 018).
 * <p>
 * 일반 통합 테스트({@code ReservationApiIntegrationTest} 등)와 달리 <b>{@code @Transactional}을 붙이지 않는다.</b>
 * 테스트 스레드가 열어둔 트랜잭션은 커밋 전까지 워커 스레드에서 보이지 않으므로, 트랜잭션 롤백 방식으로는
 * 여러 스레드가 같은 재고 행을 두고 경쟁하는 상황 자체를 만들 수 없다. 그래서 데이터를 실제로 커밋하고
 * {@link #truncateAll()}로 직접 정리한다.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        // HikariCP 기본 풀은 10이라 100개 스레드가 커넥션 대기로 줄을 서면서 사실상 직렬 실행이 되어
        // 경쟁이 약해진다. Testcontainers PostgreSQL의 max_connections(기본 100)보다는 낮게 둔다
        // (캐시된 다른 컨텍스트의 풀 10개가 살아있어도 여유가 있다).
        "spring.datasource.hikari.maximum-pool-size=30",
        // 커넥션을 미리 다 만들어 둔다. 요청 시점에 커넥션을 새로 맺으면 스레드들이 그 시간만큼
        // 어긋나서 같은 재고 값을 읽는 스레드 수가 줄어든다.
        "spring.datasource.hikari.minimum-idle=30",
        // 100개 스레드가 각자 쿼리 로그를 남기면 출력이 묻혀서 재현 수치를 읽을 수 없다.
        "spring.jpa.show-sql=false"
})
public abstract class AbstractConcurrencyTest extends AbstractContainerTest {

    /** 회원 100명을 만들 때 BCrypt를 100번 돌리지 않도록 한 번만 인코딩해 재사용한다. */
    private static final String RAW_PASSWORD = "password1234";

    /**
     * 한 테스트 메서드가 픽스처 헬퍼를 여러 번 부를 수 있어(예: 워밍업용 등급 + 본 시나리오 등급)
     * 공연장 이름({@code uk_venues_name})과 회원 이메일({@code uk_members_email})이 겹치지 않게 번호를 붙인다.
     */
    private final AtomicInteger fixtureSequence = new AtomicInteger();

    /** 커밋된 DB 상태를 영속성 컨텍스트 거치지 않고 직접 읽기 위해 서브클래스에도 열어둔다. */
    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private MemberRepository memberRepository;
    @Autowired
    private VenueRepository venueRepository;
    @Autowired
    private PerformanceRepository performanceRepository;
    @Autowired
    private ScheduleRepository scheduleRepository;
    @Autowired
    private TicketGradeRepository ticketGradeRepository;

    /**
     * 커밋된 데이터를 지운다.
     * <p>
     * Testcontainers 컨테이너는 JVM 싱글턴이고 스프링 컨텍스트도 캐시되므로, 정리하지 않으면 커밋된 데이터가
     * 다른 테스트 클래스까지 흘러간다. Flyway 이력 테이블({@code flyway_schema_history})은 목록에서 제외한다
     * (지우면 다음 컨텍스트에서 마이그레이션을 다시 돌리려 한다).
     * 예약번호 시퀀스({@code reservation_no_seq})는 {@code CYCLE} 옵션이라 리셋하지 않아도 된다.
     * <p>
     * 앞뒤로 모두 도는 이유: 이전 실행이 중간에 죽어 남은 데이터가 있으면 재고 계산이 어긋나므로
     * 시작 전에도 한 번 비운다.
     */
    @BeforeEach
    @AfterEach
    void truncateAll() {
        jdbcTemplate.execute("""
                TRUNCATE payments, reservations, ticket_grades, schedules, performances, venues, members
                    RESTART IDENTITY CASCADE
                """);
    }

    /** 판매 중인 회차에 등급 하나를 만든다. 재고를 인자로 받아 재현 조건(예: 10석)을 테스트가 정한다. */
    protected TicketGrade createOnSaleGrade(int totalQuantity) {
        int seq = fixtureSequence.getAndIncrement();
        Venue venue = venueRepository.save(Venue.builder()
                .name("동시성 테스트 공연장%d".formatted(seq))
                .address("서울")
                .build());
        Performance performance = performanceRepository.save(Performance.builder()
                .title("동시성 테스트 공연%d".formatted(seq))
                .category(PerformanceCategory.CONCERT)
                .venue(venue)
                .runningMinutes(120)
                .ageRating("전체 관람가")
                .status(PerformanceStatus.ON_SALE)
                .build());
        Schedule schedule = scheduleRepository.save(Schedule.builder()
                .performance(performance)
                .showAt(Instant.now().plus(10, ChronoUnit.DAYS))
                .bookingOpenAt(Instant.now().minus(1, ChronoUnit.DAYS))
                .bookingCloseAt(Instant.now().plus(9, ChronoUnit.DAYS))
                .build());
        return ticketGradeRepository.save(TicketGrade.builder()
                .schedule(schedule)
                .grade(Grade.VIP)
                .price(new BigDecimal("150000"))
                .totalQuantity(totalQuantity)
                .remainingQuantity(totalQuantity)
                .build());
    }

    /**
     * 서로 다른 회원 {@code count}명을 만들고 각자의 로그인 세션을 돌려준다.
     * <p>
     * 회원을 여러 명 만드는 이유는 {@code ReservationService.create()}가
     * {@code (memberId, ticketGradeId, PENDING)} 단위로 중복 선점을 막기 때문이다. 같은 회원으로 동시에
     * 요청하면 1건만 성공하고 나머지는 {@code DUPLICATE_PENDING_RESERVATION}(409)으로 떨어져 초과 판매가
     * 재현되지 않는다.
     */
    protected List<MockHttpSession> createMembersWithSession(int count) {
        String encodedPassword = passwordEncoder.encode(RAW_PASSWORD);
        int seq = fixtureSequence.getAndIncrement();
        List<MockHttpSession> sessions = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            Member member = memberRepository.save(Member.builder()
                    .email("concurrency-%d-%d@tikkit.test".formatted(seq, i))
                    .password(encodedPassword)
                    .name("동시성테스터%d".formatted(i))
                    .phone("010-0000-0000")
                    .role(MemberRole.USER)
                    .build());
            sessions.add(sessionFor(member));
        }
        return sessions;
    }

    /**
     * 회원 <b>한 명</b>을 만들고, 그 회원의 독립된 로그인 세션 {@code count}개를 돌려준다.
     * <p>
     * {@code (회원, 등급, PENDING)} 중복 선점 가드를 겨누는 테스트용이다. 같은 회원이어야 가드가
     * 발동하므로 회원은 하나만 만들고, 세션은 스레드마다 따로 준다 — {@code MockHttpSession}의 속성
     * 맵이 {@code LinkedHashMap}이라 스레드 안전하지 않고, 스프링 시큐리티가 요청 처리 중에
     * 세션에 {@code SecurityContext}를 다시 써넣을 수 있다. 세션 하나를 여러 스레드가 공유하면
     * 검증 대상과 무관한 하네스 레벨 경쟁이 섞인다.
     */
    protected List<MockHttpSession> createOneMemberWithSessions(int count) {
        int seq = fixtureSequence.getAndIncrement();
        Member member = memberRepository.save(Member.builder()
                .email("concurrency-dup-%d@tikkit.test".formatted(seq))
                .password(passwordEncoder.encode(RAW_PASSWORD))
                .name("중복선점테스터")
                .phone("010-0000-0000")
                .role(MemberRole.USER)
                .build());

        List<MockHttpSession> sessions = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            sessions.add(sessionFor(member));
        }
        return sessions;
    }

    /**
     * 로그인 API를 타지 않고 세션에 SecurityContext를 직접 심는다.
     * <p>
     * {@code /auth/signup} + {@code /auth/login}을 100번 호출하면 BCrypt 해싱·검증이 200번 돌아
     * 재현과 무관한 시간이 수십 초 늘어난다. 인증 자체는 이 테스트의 검증 대상이 아니므로
     * (그건 {@code AuthIntegrationTest}가 다룬다) 세션만 같은 모양으로 만들어 쓴다.
     */
    private MockHttpSession sessionFor(Member member) {
        MemberDetails details = new MemberDetails(member);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(
                new UsernamePasswordAuthenticationToken(details, null, details.getAuthorities()));

        MockHttpSession session = new MockHttpSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
        return session;
    }
}
