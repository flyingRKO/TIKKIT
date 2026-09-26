import { apiFetch } from "@/lib/api/client";
import type {
  PageResponse,
  PerformanceCategory,
  PerformanceDetailResponse,
  PerformanceStatus,
  PerformanceSummaryResponse,
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
