import { SeatMapSkeleton } from "@/components/performance/seat-map-skeleton";
import { Skeleton } from "@/components/ui/skeleton";

export default function Loading() {
  return (
    <main className="mx-auto flex w-full max-w-6xl flex-1 flex-col gap-6 px-4 py-8">
      <div className="flex flex-col gap-2">
        <Skeleton className="h-4 w-32" />
        <Skeleton className="h-7 w-28" />
        <Skeleton className="h-4 w-2/3" />
      </div>
      {/* 좌석맵은 수천 석이라 응답이 크다. 로딩 자리를 넓게 잡아 레이아웃이 튀지 않게 한다.
          page.tsx의 Suspense fallback과 같은 컴포넌트를 쓴다 — 자리 크기가 다르면
          셸 -> 본문 -> 좌석으로 교체되는 동안 레이아웃이 두 번 튄다 (Task 030_2) */}
      <SeatMapSkeleton />
    </main>
  );
}
