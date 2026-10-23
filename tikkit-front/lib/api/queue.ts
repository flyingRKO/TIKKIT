import { apiFetch } from "@/lib/api/client";
import type { QueueStatusResponse } from "@/types/api";

// 세 함수 모두 apiFetch(세션 O)다 — /api/v1/queue/**는 BE SecurityConfig의
// anyRequest().authenticated()로 떨어져서 비로그인은 401이다. 공개 조회가 아니다.
//
// 세 엔드포인트의 응답 스키마가 전부 QueueStatusResponse로 같다(이탈만 void). BE가 일부러
// 하나로 묶어둔 것이고, 그래서 화면이 응답 파서를 하나만 두고 state로 분기한다.

// 대기열 진입. 멱등이라 200이고 재호출이 기본 동작이다 — 더블클릭·새로고침이 순번을 밀지
// 않고(ZADD NX), 재진입으로 앞자리를 살 수도 없다. 요청 body가 없어서 JSON.stringify를 하지 않는다.
export function enterQueue(scheduleId: number): Promise<QueueStatusResponse> {
  return apiFetch<QueueStatusResponse>(`/api/v1/queue/schedules/${scheduleId}/entries`, {
    method: "POST",
  });
}

// 상태 폴링. 큐에 없는 회원도 200 + EXPIRED로 답한다 — "줄 서 있지 않다"는 오류가 아니다.
export function getQueueStatus(scheduleId: number): Promise<QueueStatusResponse> {
  return apiFetch<QueueStatusResponse>(`/api/v1/queue/schedules/${scheduleId}/status`);
}

// 대기열 이탈. 없는 항목을 지워도 200이다(멱등).
// 사용자가 "대기열에서 나가기"를 확인했을 때만 부른다 — 탭 전환·새로고침에서 부르면 안 된다.
export function leaveQueue(scheduleId: number): Promise<void> {
  return apiFetch<void>(`/api/v1/queue/schedules/${scheduleId}/entries`, {
    method: "DELETE",
  });
}
