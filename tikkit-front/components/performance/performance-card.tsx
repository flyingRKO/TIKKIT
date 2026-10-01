import { Ticket } from "lucide-react";
import Link from "next/link";
import { Badge } from "@/components/ui/badge";
import { CATEGORY_LABELS, STATUS_LABELS } from "@/lib/performance-labels";
import { cn, focusRing } from "@/lib/utils";
import type { PerformanceStatus, PerformanceSummaryResponse } from "@/types/api";

const STATUS_BADGE_VARIANT: Record<PerformanceStatus, "default" | "secondary" | "outline"> = {
  ON_SALE: "default",
  UPCOMING: "secondary",
  CLOSED: "outline",
};

function formatDateRange(startDate: string | null, endDate: string | null): string | null {
  if (!startDate) return null;
  if (!endDate || endDate === startDate) return startDate;
  return `${startDate} ~ ${endDate}`;
}

export function PerformanceCard({ performance }: { performance: PerformanceSummaryResponse }) {
  const dateRange = formatDateRange(performance.startDate, performance.endDate);

  return (
    <Link
      href={`/performances/${performance.id}`}
      className={cn(
        "group flex flex-col overflow-hidden rounded-xl border bg-card transition-colors hover:border-primary/40",
        focusRing
      )}
    >
      {/* posterUrl이 아직 없어서(시드 데이터 기준) 이미지 대신 중립적인 placeholder를 쓴다.
          브랜드 그라디언트(primary→highlight)는 카드마다 반복되는 배경에는 쓰지 않는다(ROADMAP 디자인 토큰 규칙 참조). */}
      <div className="flex aspect-[3/4] items-center justify-center bg-muted text-muted-foreground">
        <Ticket className="size-10 opacity-40" aria-hidden />
      </div>
      <div className="flex flex-1 flex-col gap-2 p-3">
        {/* 360px에서 카드 폭이 150px대라 배지 둘이 한 줄에 안 들어갈 수 있어 줄바꿈을 허용한다 */}
        <div className="flex flex-wrap items-center gap-1.5">
          <Badge variant="outline">{CATEGORY_LABELS[performance.category]}</Badge>
          <Badge variant={STATUS_BADGE_VARIANT[performance.status]}>
            {STATUS_LABELS[performance.status]}
          </Badge>
        </div>
        <h3 className="line-clamp-2 font-semibold group-hover:text-primary">
          {performance.title}
        </h3>
        <p className="text-sm text-muted-foreground">{performance.venueName}</p>
        {dateRange && <p className="text-xs text-muted-foreground">{dateRange}</p>}
      </div>
    </Link>
  );
}
