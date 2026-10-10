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
        this.content = page.getContent();
        this.page = page.getNumber();
        this.size = page.getSize();
        this.totalElements = page.getTotalElements();
        this.totalPages = page.getTotalPages();
        this.first = page.isFirst();
        this.last = page.isLast();
    }
}