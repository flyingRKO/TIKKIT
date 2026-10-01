import Link from "next/link";
import { Suspense } from "react";
import { EmptyState } from "@/components/layout/empty-state";
import { PerformanceCard } from "@/components/performance/performance-card";
import { PerformanceGridSkeleton } from "@/components/performance/performance-grid-skeleton";
import { Skeleton } from "@/components/ui/skeleton";
import { cn, focusRing } from "@/lib/utils";
import { getPerformances } from "@/lib/api/performances";
import type { PerformanceStatus, PerformanceSummaryResponse } from "@/types/api";

// 이 페이지는 dynamic 세그먼트도, searchParams도 안 써서 Next가 빌드 시점에 정적 생성하려고 시도한다.
// "예매중"/"오픈 예정" 목록은 요청마다 최신이어야 하므로 매 요청 렌더링을 강제한다.
export const dynamic = "force-dynamic";

function PerformanceSection({
  title,
  status,
  performances,
}: {
  title: string;
  status: PerformanceStatus;
  performances: PerformanceSummaryResponse[];
}) {
  if (performances.length === 0) return null;

  return (
    <section className="flex w-full max-w-5xl flex-col gap-4 px-4">
      <div className="flex items-center justify-between">
        <h2 className="text-xl font-bold">{title}</h2>
        <Link
          href={`/performances?status=${status}`}
          className={cn("text-sm text-muted-foreground hover:text-foreground", focusRing)}
        >
          더보기
        </Link>
      </div>
      <div className="grid grid-cols-2 gap-4 sm:grid-cols-3 md:grid-cols-4">
        {performances.map((performance) => (
          <PerformanceCard key={performance.id} performance={performance} />
        ))}
      </div>
    </section>
  );
}

// 두 섹션이 모두 비었을 때 안내 문구를 보여주려면 둘의 결과를 같이 알아야 해서 조회를 한 곳에 모았다.
async function PerformanceSections() {
  const [onSale, upcoming] = await Promise.all([
    getPerformances({ status: "ON_SALE", size: 4 }),
    getPerformances({ status: "UPCOMING", size: 4 }),
  ]);

  if (onSale.content.length === 0 && upcoming.content.length === 0) {
    return <EmptyState message="아직 등록된 공연이 없습니다." />;
  }

  return (
    <>
      <PerformanceSection title="예매중" status="ON_SALE" performances={onSale.content} />
      <PerformanceSection title="오픈 예정" status="UPCOMING" performances={upcoming.content} />
    </>
  );
}

function SectionSkeleton() {
  return (
    <section className="flex w-full max-w-5xl flex-col gap-4 px-4">
      <Skeleton className="h-7 w-24" />
      <PerformanceGridSkeleton count={4} />
    </section>
  );
}

export default function Home() {
  return (
    <main className="flex flex-1 flex-col items-center gap-12 py-12">
      <div className="flex flex-col items-center gap-4 px-6 text-center">
        <h1 className="bg-linear-to-r from-primary to-highlight bg-clip-text text-4xl font-bold text-transparent">
          TIKKIT
        </h1>
        <p className="max-w-md text-muted-foreground">
          공연 탐색부터 예매까지, 티켓 예매/관리 서비스
        </p>
      </div>

      {/* Suspense로 감싸서 히어로는 바로 보이고, 목록은 도착하면 채운다 */}
      <Suspense
        fallback={
          <>
            <SectionSkeleton />
            <SectionSkeleton />
          </>
        }
      >
        <PerformanceSections />
      </Suspense>
    </main>
  );
}
