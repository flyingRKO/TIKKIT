import { Skeleton } from "@/components/ui/skeleton";

/**
 * 좌석 영역 자리표시자 (Task 030_2).
 *
 * 쓰이는 곳이 둘이다 —
 *  1) seats/loading.tsx: 페이지 진입 직후 (세그먼트 전체가 아직 안 풀렸을 때)
 *  2) seats/page.tsx의 Suspense fallback: 공연·회차·등급은 그렸고 좌석만 기다릴 때
 *
 * 둘이 같은 마크업이어야 한다. 셸 -> 본문 -> 좌석으로 두 번 교체되는 동안 자리 크기가
 * 달라지면 레이아웃이 두 번 튄다. 그래서 한 컴포넌트로 묶었다.
 *
 * 높이 28rem은 loading.tsx가 쓰던 값 그대로다. 좌석맵은 수천 석이라 응답이 크고,
 * 자리를 넓게 잡아야 좌석이 들어올 때 아래 내용이 밀리지 않는다.
 */
export function SeatMapSkeleton() {
  return <Skeleton className="h-[28rem] w-full rounded-xl" />;
}
