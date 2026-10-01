import Link from "next/link";
import { redirect } from "next/navigation";
import { EmptyState } from "@/components/layout/empty-state";
import { Pagination } from "@/components/performance/pagination";
import { ReservationListItem } from "@/components/reservation/reservation-list-item";
import { ReservationStatusFilter } from "@/components/reservation/reservation-status-filter";
import { buttonVariants } from "@/components/ui/button";
import { ApiError } from "@/lib/api/client";
import { getMyReservations } from "@/lib/api/reservations";
import { RESERVATION_STATUS_OPTIONS } from "@/lib/reservation-labels";
import { withSearchParams } from "@/lib/url";
import { cn } from "cn";
import type { ReservationStatus } from "@/types/api";

const PAGE_SIZE = 10;

type SearchParams = Record<string, string | string[] | undefined>;

function firstOf(value: string | string[] | undefined): string | undefined {
  return Array.isArray(value) ? value[0] : value;
}

// URL의 status가 유효한 값이 아니면 조용히 "전체"로 취급한다. BE는 잘못된 status를 400으로 거절하기 때문에
// 검증하지 않고 그대로 넘기면 URL을 잘못 고친 것만으로 에러 화면이 뜬다.
function parseStatus(value?: string): ReservationStatus | undefined {
  return RESERVATION_STATUS_OPTIONS.find((status) => status === value);
}

export default async function MyReservationsPage({
  searchParams,
}: {
  searchParams: Promise<SearchParams>;
}) {
  const params = await searchParams;
  const status = parseStatus(firstOf(params.status));
  const requestedPage = Math.max(0, Number(firstOf(params.page)) || 0);

  let result;
  try {
    result = await getMyReservations({ status, page: requestedPage, size: PAGE_SIZE });
  } catch (error) {
    // proxy.ts는 쿠키 "존재"만 보기 때문에, 쿠키는 있는데 BE 세션이 만료된 경우는 여기서 걸러낸다.
    if (error instanceof ApiError && error.code === "UNAUTHORIZED") {
      redirect(
        `/login?redirect=${encodeURIComponent(`/my/reservations${withSearchParams(params, {})}`)}`
      );
    }
    throw error;
  }

  const { content, totalPages, page } = result;

  return (
    <main className="mx-auto flex w-full max-w-2xl flex-1 flex-col gap-6 px-4 py-8">
      <h1 className="text-2xl font-bold">내 예매 내역</h1>

      <ReservationStatusFilter currentSearchParams={params} activeStatus={status} />

      {content.length === 0 ? (
        <EmptyState
          message={status ? "해당 상태의 예매 내역이 없습니다." : "아직 예매 내역이 없습니다."}
          action={
            !status && (
              <Link href="/performances" className={cn(buttonVariants({ size: "lg" }))}>
                공연 둘러보기
              </Link>
            )
          }
        />
      ) : (
        <ul className="flex flex-col gap-3">
          {content.map((reservation) => (
            <ReservationListItem key={reservation.id} reservation={reservation} />
          ))}
        </ul>
      )}

      <Pagination currentSearchParams={params} page={page} totalPages={totalPages} />
    </main>
  );
}
