package com.tikkit.api.domain.performance.dto;

import java.util.List;

/**
 * 공연 목록 한 페이지. {@code Page<PerformanceSummaryResponse>}를 대신한다 (Task 029).
 *
 * <h2>왜 Page를 그대로 쓰지 않나</h2>
 * {@code PageImpl}은 기본 생성자가 없고 {@code Pageable}을 품고 있어서 Jackson으로 역직렬화할 수
 * 없다. 캐시에 넣을 때는 직렬화되지만 꺼낼 때 깨진다.
 * <p>
 * 우회 방법이 둘 있었다. mixin이나 커스텀 역직렬화기를 붙이는 쪽은 Spring Data의 내부 구조에
 * 의존하게 되고 버전이 올라갈 때 조용히 깨진다. 반대로 서비스가 캐시 친화적인 타입을 돌려주고
 * 컨트롤러가 응답으로 조립하면, 캐시는 평범한 record 하나만 알면 된다. 후자를 골랐다.
 * <p>
 * 덤으로 self-invocation 함정을 피한다 — 캐시 전용 메서드를 서비스 안에 따로 두고 같은 클래스에서
 * 호출하면 프록시를 거치지 않아 <b>캐시가 조용히 동작하지 않는다</b>. 반환형을 바꾸면
 * {@code list()} 자체에 {@code @Cacheable}을 붙일 수 있어서 그 구조가 아예 필요 없다.
 *
 * <p>{@code page}/{@code size}는 담지 않는다. 호출 측이 이미 알고 있는 값이고, 캐시 키에도
 * 들어가므로 값에 또 넣으면 같은 사실이 두 곳에 생긴다.
 *
 * @param content       이 페이지의 공연 요약
 * @param totalElements 필터에 걸린 전체 공연 수
 */
public record PerformanceListPage(List<PerformanceSummaryResponse> content, long totalElements) {
}
