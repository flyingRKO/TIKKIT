import { Skeleton } from "@/components/ui/skeleton";

// PerformanceCard 그리드와 같은 열 구성을 써야 로딩이 끝났을 때 레이아웃이 튀지 않는다.
export function PerformanceGridSkeleton({ count = 8 }: { count?: number }) {
  return (
    <div className="grid grid-cols-2 gap-4 sm:grid-cols-3 md:grid-cols-4">
      {Array.from({ length: count }).map((_, index) => (
        <div key={index} className="flex flex-col gap-2">
          <Skeleton className="aspect-[3/4] rounded-xl" />
          <Skeleton className="h-4 w-3/4" />
          <Skeleton className="h-3 w-1/2" />
        </div>
      ))}
    </div>
  );
}
