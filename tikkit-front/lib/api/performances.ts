import { apiFetchPublic } from "@/lib/api/client";
import type {
  PageResponse,
  PerformanceCategory,
  PerformanceDetailResponse,
  PerformanceStatus,
  PerformanceSummaryResponse,
  ScheduleSeatResponse,
  TicketGradeResponse,
} from "@/types/api";

// 이 파일의 네 함수는 전부 apiFetchPublic을 쓴다 — 로그인 여부와 무관하게 같은 응답을 주는
// 공개 조회이고, BE SecurityConfig도 GET /performances/**, /schedules/*/ticket-grades,
// /schedules/*/seats를 permitAll로 열어 뒀다. 세션을 실어 보낼 이유가 없고, 안 실으면
// cookies()를 부르지 않아서 fetch 단위 캐시를 켤 수 있는 조건이 된다 (Task 030_2).
//
// 캐시를 실제로 켜지 않은 이유는 client.ts의 apiFetchPublicRaw 주석에 적었다.

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
  return apiFetchPublic<PageResponse<PerformanceSummaryResponse>>(`/api/v1/performances${query}`);
}

// 존재하지 않는 id면 apiFetchPublic이 ApiError(code: "NOT_FOUND")를 던진다 — 호출부에서 notFound()로 처리한다.
export function getPerformanceDetail(id: number | string): Promise<PerformanceDetailResponse> {
  return apiFetchPublic<PerformanceDetailResponse>(`/api/v1/performances/${id}`);
}

export function getTicketGrades(scheduleId: number): Promise<TicketGradeResponse[]> {
  return apiFetchPublic<TicketGradeResponse[]>(`/api/v1/schedules/${scheduleId}/ticket-grades`);
}

// 회차의 모든 좌석을 배치도 순서(앞열 → 왼쪽)로 받는다. 큰 공연장이면 수천 건이라 응답이 크다 —
// 공연 상세에서 회차마다 미리 불러오면 안 되고, 좌석이 실제로 필요한 시점에만 호출한다.
export function getScheduleSeats(scheduleId: number): Promise<ScheduleSeatResponse[]> {
  return apiFetchPublic<ScheduleSeatResponse[]>(`/api/v1/schedules/${scheduleId}/seats`);
}
