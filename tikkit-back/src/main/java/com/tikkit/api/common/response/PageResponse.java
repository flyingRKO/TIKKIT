package com.tikkit.api.common.response;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import lombok.Getter;
import org.springframework.data.domain.Page;

import java.util.List;

/**
 * Spring Data의 PageImpl을 직접 직렬화하면 Boot 3.3+에서 경고가 발생하므로,
 * 응답 전용 DTO로 감싸서 반환한다.
 *
 * <p>순서를 명시한 이유는 {@link ApiResponse}와 같다. 이 클래스는 생성자 파라미터명이
 * {@code page}라서 Jackson 3가 그걸 creator 속성으로 보고 맨 앞으로 끌어올렸다
 * (선언 순서는 content가 먼저다).
 */
@Getter
@JsonPropertyOrder({"content", "page", "size", "totalElements", "totalPages", "first", "last"})
public class PageResponse<T> {

    private final List<T> content;
    private final int page;
    private final int size;
    private final long totalElements;
    private final int totalPages;
    private final boolean first;
    private final boolean last;

    public PageResponse(Page<T> page) {
        this(page.getContent(), page.getNumber(), page.getSize(), page.getTotalElements());
    }

    /**
     * {@code Page} 없이 응답을 조립한다 (Task 029).
     *
     * <p>캐시에 {@code PageImpl}을 넣을 수 없어서(기본 생성자가 없고 {@code Pageable}을 품는다)
     * 공연 목록 서비스가 {@code PerformanceListPage} record를 돌려주게 됐다. 그 경로에서 쓰는
     * 생성자다. 파생값({@code totalPages}/{@code first}/{@code last})은 {@code PageImpl}이 하던
     * 계산을 그대로 옮긴 것이다.
     *
     * <p>{@code size}가 0 이하로 올 수 없다 — 컨트롤러 기본값이 20이고
     * {@code PageRequest.of}가 0 이하를 거부한다. 그래도 나눗셈이라 방어한다.
     */
    public PageResponse(List<T> content, int page, int size, long totalElements) {
        this.content = content;
        this.page = page;
        this.size = size;
        this.totalElements = totalElements;
        this.totalPages = size <= 0 ? 1 : (int) Math.ceil((double) totalElements / (double) size);
        this.first = page == 0;
        this.last = page + 1 >= this.totalPages;
    }
}