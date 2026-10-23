package com.tikkit.api.domain.performance.repository;

import com.tikkit.api.domain.performance.entity.Schedule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface ScheduleRepository extends JpaRepository<Schedule, Long>, ScheduleRepositoryCustom {

    /**
     * 대기열이 켜진 회차 id 전부 (Task 031).
     * <p>
     * {@code QueueFlagRegistry}가 10초마다 한 번 호출해 스냅샷을 만든다. 조건이 컬럼 하나에 대한
     * 동등 비교라 QueryDSL을 쓸 이유가 없다.
     * <p>
     * 파생 쿼리({@code findIdsByQueueEnabledTrue})로 쓰지 않는 이유: Spring Data는 {@code find}와
     * {@code By} 사이의 텍스트를 주제어로 보고 버리므로 "Ids"가 프로젝션으로 해석되지 않는다.
     * 엔티티를 select한 뒤 {@code Long}으로 변환하려다 실패한다. JPQL로 id만 뽑는다고 명시한다.
     * <p>
     * id만 뽑는 이유: 플래그 확인은 "이 회차가 켜진 집합에 있나"뿐이라 엔티티가 필요 없고,
     * 회차가 수천 개여도 Long 집합은 작다.
     */
    @Query("select s.id from Schedule s where s.queueEnabled = true")
    List<Long> findQueueEnabledScheduleIds();
}
