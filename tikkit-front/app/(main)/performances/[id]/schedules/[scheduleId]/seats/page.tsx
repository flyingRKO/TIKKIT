import Link from "next/link";
import { notFound, redirect } from "next/navigation";
import { SeatSelector } from "@/components/performance/seat-selector";
import { ApiError } from "@/lib/api/client";
import { getPerformanceDetail, getScheduleSeats, getTicketGrades } from "@/lib/api/performances";
import { formatDateTime } from "@/lib/format-date";
import { GRADE_LABELS } from "@/lib/performance-labels";
import { getSaleStateNow, MAX_QUANTITY } from "@/lib/schedule-rules";
import { buildSeatMapLayout } from "@/lib/seat-map";

type SearchParams = Record<string, string | string[] | undefined>;

function firstOf(value: string | string[] | undefined): string | undefined {
  return Array.isArray(value) ? value[0] : value;
}

/** BE가 404를 주면 화면도 404로 맞춘다. 그 외 에러는 error.tsx가 받게 그대로 올린다. */
function toNotFound(error: unknown): never {
  if (error instanceof ApiError && error.code === "NOT_FOUND") {
    notFound();
  }
  throw error;
}

export default async function SeatSelectionPage({
  params,
  searchParams,
}: {
  params: Promise<{ id: string; scheduleId: string }>;
  searchParams: Promise<SearchParams>;
}) {
  const { id, scheduleId: scheduleIdParam } = await params;
  const query = await searchParams;

  const performanceId = Number(id);
  const scheduleId = Number(scheduleIdParam);
  if (!Number.isInteger(performanceId) || !Number.isInteger(scheduleId)) {
    notFound();
  }

  const detailPath = `/performances/${performanceId}`;

  // 셋을 병렬로 받는다. scheduleId가 이 공연의 회차가 아니면 좌석 조회가 헛돌지만,
  // 순차로 바꾸면 정상 경로(대부분)가 왕복 두 번을 더 기다린다.
  const [performance, grades, seats] = await Promise.all([
    getPerformanceDetail(performanceId).catch(toNotFound),
    getTicketGrades(scheduleId).catch(toNotFound),
    getScheduleSeats(scheduleId).catch(toNotFound),
  ]);

  // 다른 공연의 회차 id를 URL에 끼워 넣어도 좌석맵을 보여주지 않는다.
  const schedule = performance.schedules.find((item) => item.id === scheduleId);
  if (schedule === undefined) {
    notFound();
  }

  const gradeId = Number(firstOf(query.gradeId));
  const quantity = Number(firstOf(query.quantity));
  const grade = grades.find((item) => item.id === gradeId);

  // 등급·매수는 공연 상세에서 고르고 넘어오는 값이다. 어긋나면 404가 아니라 상세로 되돌린다 —
  // 회차 자체는 존재하므로 "없는 페이지"가 아니고, 다시 고르면 되는 상황이다.
  if (grade === undefined || !Number.isInteger(quantity) || quantity < 1 || quantity > MAX_QUANTITY) {
    redirect(detailPath);
  }

  const saleState = getSaleStateNow(schedule);
  const layout = buildSeatMapLayout(seats);

  return (
    <main className="mx-auto flex w-full max-w-6xl flex-1 flex-col gap-6 px-4 pt-8 pb-56 lg:pb-8">
      <div className="flex flex-col gap-2">
        <Link
          href={detailPath}
          className="w-fit text-sm text-muted-foreground underline-offset-4 hover:underline"
        >
          ← 공연 정보로 돌아가기
        </Link>
        <h1 className="break-words text-xl font-bold md:text-2xl">좌석 선택</h1>
        <dl className="grid grid-cols-[auto_1fr] gap-x-3 gap-y-1 text-sm text-muted-foreground">
          <dt>공연</dt>
          <dd className="min-w-0 break-words">{performance.title}</dd>
          <dt>회차</dt>
          <dd>{formatDateTime(schedule.showAt)}</dd>
          <dt>등급</dt>
          <dd>
            {GRADE_LABELS[grade.grade]} · {grade.price.toLocaleString()}원 · {quantity}매
          </dd>
        </dl>
      </div>

      {saleState !== "OPEN" && (
        <p className="rounded-md border border-destructive/40 bg-destructive/5 p-3 text-sm">
          {saleState === "BEFORE_OPEN"
            ? `${formatDateTime(schedule.bookingOpenAt)}에 예매가 열립니다.`
            : "예매가 종료된 회차입니다."}
        </p>
      )}

      <SeatSelector
        performanceId={performanceId}
        performanceTitle={performance.title}
        scheduleId={scheduleId}
        showAt={schedule.showAt}
        seats={seats}
        layout={layout}
        grades={grades}
        selectedGrade={grade}
        quantity={quantity}
        bookable={saleState === "OPEN"}
      />
    </main>
  );
}
