import { Skeleton } from "@/components/ui/skeleton";

export default function Loading() {
  return (
    <main className="mx-auto flex w-full max-w-6xl flex-1 flex-col gap-6 px-4 py-8">
      <div className="flex flex-col gap-2">
        <Skeleton className="h-4 w-32" />
        <Skeleton className="h-7 w-28" />
        <Skeleton className="h-4 w-2/3" />
      </div>
      {/* 좌석맵은 수천 석이라 응답이 크다. 로딩 자리를 넓게 잡아 레이아웃이 튀지 않게 한다 */}
      <Skeleton className="h-[28rem] w-full rounded-xl" />
    </main>
  );
}
