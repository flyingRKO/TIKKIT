import { apiFetch } from "@/lib/api/client";
import type {
  PageResponse,
  PerformanceCategory,
  PerformanceDetailResponse,
  PerformanceStatus,
  PerformanceSummaryResponse,
  ScheduleSeatResponse,
  TicketGradeResponse,
} from "@/types/api";

export interface GetPerformancesParams {
  category?: PerformanceCategory;
  keyword?: string;
  status?: PerformanceStatus;
  page?: number;
  size?: number;
}

// 값이 없는 파라미터는 쿼리스트링에서 그냥 뺀다 — BE도 없으면 필터 없음으로 취급한다.
function buildQuery(params: Record<string, string | number | undefined>): string {
  const query = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined && value !== "") {
      query.set(key, String(value));
    }
  }
  const search = query.toString();
  return search ? `?${search}` : "";
}

export function getPerformances(
  params: GetPerformancesParams = {}
): Promise<PageResponse<PerformanceSummaryResponse>> {
  const query = buildQuery({
    category: params.category,
    keyword: params.keyword,
    status: params.status,
    page: params.page,
    size: params.size,
  });
  return apiFetch<PageResponse<PerformanceSummaryResponse>>(`/api/v1/performances${query}`);
}

// 존재하지 않는 id면 apiFetch가 ApiError(code: "NOT_FOUND")를 던진다 — 호출부에서 notFound()로 처리한다.
export function getPerformanceDetail(id: number | string): Promise<PerformanceDetailResponse> {
  return apiFetch<PerformanceDetailResponse>(`/api/v1/performances/${id}`);
}

export function getTicketGrades(scheduleId: number): Promise<TicketGradeResponse[]> {
  return apiFetch<TicketGradeResponse[]>(`/api/v1/schedules/${scheduleId}/ticket-grades`);
}

// 회차의 모든 좌석을 배치도 순서(앞열 → 왼쪽)로 받는다. 큰 공연장이면 수천 건이라 응답이 크다 —
// 공연 상세에서 회차마다 미리 불러오면 안 되고, 좌석이 실제로 필요한 시점에만 호출한다.
export function getScheduleSeats(scheduleId: number): Promise<ScheduleSeatResponse[]> {
  return apiFetch<ScheduleSeatResponse[]>(`/api/v1/schedules/${scheduleId}/seats`);
}
