import Link from "next/link";
import { notFound, redirect } from "next/navigation";
import { Suspense } from "react";
import { SeatMapSkeleton } from "@/components/performance/seat-map-skeleton";
import { SeatSelector } from "@/components/performance/seat-selector";
import { ApiError } from "@/lib/api/client";
import { getPerformanceDetail, getScheduleSeats, getTicketGrades } from "@/lib/api/performances";
import { formatDateTime } from "@/lib/format-date";
import { GRADE_LABELS } from "@/lib/performance-labels";
import { getSaleStateNow, MAX_QUANTITY } from "@/lib/schedule-rules";
import { buildSeatMapLayout } from "@/lib/seat-map";
import type { ScheduleSeatResponse, TicketGradeResponse } from "@/types/api";

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

interface SeatAreaProps {
  seatsPromise: Promise<ScheduleSeatResponse[]>;
  performanceId: number;
  performanceTitle: string;
  scheduleId: number;
  showAt: string;
  grades: TicketGradeResponse[];
  selectedGrade: TicketGradeResponse;
  quantity: number;
  bookable: boolean;
}

/**
 * 좌석 조회만 기다리는 영역 (Task 030_2).
 *
 * page가 공연·회차·등급까지 그려서 내보낸 뒤, 좌석은 이 경계 안에서 따로 풀린다.
 * toNotFound도 여기로 같이 옮겨야 한다 — page에 두면 좌석 404가 page 레벨에서 던져져서
 * 이 분리의 의미가 없어진다.
 *
 * 404는 soft다(HTTP 200에 404 화면). 이건 이번 변경으로 생긴 게 아니라 원래 그랬다 —
 * 같은 세그먼트에 loading.tsx가 있으면 그 자체가 Suspense 경계라서 notFound()를 부르는
 * 시점에 이미 헤더가 나가 있다. performances/[id]/page.tsx의 주석이 같은 것을 적어뒀다.
 */
async function SeatArea({
  seatsPromise,
  performanceId,
  performanceTitle,
  scheduleId,
  showAt,
  grades,
  selectedGrade,
  quantity,
  bookable,
}: SeatAreaProps) {
  const seats = await seatsPromise.catch(toNotFound);
  const layout = buildSeatMapLayout(seats);

  return (
    <SeatSelector
      performanceId={performanceId}
      performanceTitle={performanceTitle}
      scheduleId={scheduleId}
      showAt={showAt}
      seats={seats}
      layout={layout}
      grades={grades}
      selectedGrade={selectedGrade}
      quantity={quantity}
      bookable={bookable}
    />
  );
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

  // 좌석 조회를 먼저 띄우고 await는 하지 않는다. 세 응답 중 좌석만 수천 건이라 가장 늦게
  // 오는데, 아래 redirect/notFound 판단에는 쓰이지 않는다. 그래서 공연·등급만 기다려
  // 제목·회차·등급을 먼저 내보내고 좌석은 Suspense 안에서 따로 받는다.
  //
  // scheduleId가 이 공연의 회차가 아니면 좌석 조회가 헛돌지만, 검증 뒤로 미루면 정상
  // 경로(대부분)가 왕복 두 번을 더 기다린다 — 분리 전과 같은 판단이다.
  const seatsPromise = getScheduleSeats(scheduleId);
  // 아래 redirect()가 throw되면 이 promise를 아무도 await하지 않아 unhandled rejection이
  // 된다. 거부를 흡수하는 핸들러를 먼저 붙여두고, 원본은 그대로 자식에게 넘긴다.
  seatsPromise.catch(() => {});

  const [performance, grades] = await Promise.all([
    getPerformanceDetail(performanceId).catch(toNotFound),
    getTicketGrades(scheduleId).catch(toNotFound),
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

      <Suspense fallback={<SeatMapSkeleton />}>
        <SeatArea
          seatsPromise={seatsPromise}
          performanceId={performanceId}
          performanceTitle={performance.title}
          scheduleId={scheduleId}
          showAt={schedule.showAt}
          grades={grades}
          selectedGrade={grade}
          quantity={quantity}
          bookable={saleState === "OPEN"}
        />
      </Suspense>
    </main>
  );
}
