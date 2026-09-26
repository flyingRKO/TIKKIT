import { notFound } from "next/navigation";
import { TicketSelector } from "@/components/performance/ticket-selector";
import { Badge } from "@/components/ui/badge";
import { ApiError } from "@/lib/api/client";
import { getPerformanceDetail, getTicketGrades } from "@/lib/api/performances";
import { CATEGORY_LABELS, STATUS_LABELS } from "@/lib/performance-labels";
import type { TicketGradeResponse } from "@/types/api";

export default async function PerformanceDetailPage({
  params,
}: {
  params: Promise<{ id: string }>;
}) {
  const { id } = await params;

  // 알려진 제약: 같은 세그먼트에 loading.tsx가 있어 스트리밍이 이미 시작된 뒤라, 여기서 notFound()를
  // 불러도 HTTP 상태 코드는 200으로 남는다(Next.js 스트리밍 문서에 나오는 "soft 404"). 화면 자체는
  // not-found.tsx가 정확히 뜨고 <meta name="robots" content="noindex">도 자동으로 붙어 검색엔진 색인은
  // 막힌다. 상태 코드까지 정확히 404로 만들려면 proxy(미들웨어)에서 BE를 먼저 조회해야 하는데, 지금 단계에서는
  // 과한 비용이라 보류한다.
  const performance = await getPerformanceDetail(id).catch((error) => {
    if (error instanceof ApiError && error.code === "NOT_FOUND") {
      notFound();
    }
    throw error;
  });

  // 회차별 등급은 회차 수만큼 병렬로 조회해 TicketSelector에 한 번에 넘긴다(회차 수가 몇 개뿐이라 부담 없다).
  const ticketGradesBySchedule: Record<number, TicketGradeResponse[]> = {};
  await Promise.all(
    performance.schedules.map(async (schedule) => {
      ticketGradesBySchedule[schedule.id] = await getTicketGrades(schedule.id);
    })
  );

  return (
    <main className="mx-auto flex w-full max-w-5xl flex-1 flex-col gap-8 px-4 py-8 md:flex-row">
      <div className="flex flex-1 flex-col gap-4">
        <div className="flex items-center gap-1.5">
          <Badge variant="outline">{CATEGORY_LABELS[performance.category]}</Badge>
          <Badge variant={performance.status === "ON_SALE" ? "default" : "secondary"}>
            {STATUS_LABELS[performance.status]}
          </Badge>
        </div>
        <h1 className="text-2xl font-bold">{performance.title}</h1>
        <dl className="grid grid-cols-[auto_1fr] gap-x-3 gap-y-1 text-sm text-muted-foreground">
          <dt>공연장</dt>
          <dd>
            {performance.venueName} · {performance.venueAddress}
          </dd>
          <dt>러닝타임</dt>
          <dd>{performance.runningMinutes}분</dd>
          <dt>관람 등급</dt>
          <dd>{performance.ageRating}</dd>
        </dl>
        {performance.description && (
          <p className="whitespace-pre-line text-sm leading-relaxed">{performance.description}</p>
        )}
      </div>

      <div className="w-full md:w-80">
        <TicketSelector
          schedules={performance.schedules}
          ticketGradesBySchedule={ticketGradesBySchedule}
        />
      </div>
    </main>
  );
}
