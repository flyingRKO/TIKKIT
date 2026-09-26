import Link from "next/link";
import { PerformanceCard } from "@/components/performance/performance-card";
import { getPerformances } from "@/lib/api/performances";
import type { PerformanceStatus } from "@/types/api";

// 이 페이지는 dynamic 세그먼트도, searchParams도 안 써서 Next가 빌드 시점에 정적 생성하려고 시도한다.
// "예매중"/"오픈 예정" 목록은 요청마다 최신이어야 하므로 매 요청 렌더링을 강제한다.
export const dynamic = "force-dynamic";

async function PerformanceSection({
  title,
  status,
}: {
  title: string;
  status: PerformanceStatus;
}) {
  const { content } = await getPerformances({ status, size: 4 });

  if (content.length === 0) return null;

  return (
    <section className="flex w-full max-w-5xl flex-col gap-4 px-4">
      <div className="flex items-center justify-between">
        <h2 className="text-xl font-bold">{title}</h2>
        <Link
          href={`/performances?status=${status}`}
          className="text-sm text-muted-foreground hover:text-foreground"
        >
          더보기
        </Link>
      </div>
      <div className="grid grid-cols-2 gap-4 sm:grid-cols-3 md:grid-cols-4">
        {content.map((performance) => (
          <PerformanceCard key={performance.id} performance={performance} />
        ))}
      </div>
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

      <PerformanceSection title="예매중" status="ON_SALE" />
      <PerformanceSection title="오픈 예정" status="UPCOMING" />
    </main>
  );
}
