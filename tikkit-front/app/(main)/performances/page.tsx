import Link from "next/link";
import { EmptyState } from "@/components/layout/empty-state";
import { CategoryFilter } from "@/components/performance/category-filter";
import { Pagination } from "@/components/performance/pagination";
import { PerformanceCard } from "@/components/performance/performance-card";
import { SearchForm } from "@/components/performance/search-form";
import { StatusFilter } from "@/components/performance/status-filter";
import { buttonVariants } from "@/components/ui/button";
import { getPerformances } from "@/lib/api/performances";
import { CATEGORY_OPTIONS, STATUS_OPTIONS } from "@/lib/performance-labels";
import type { PerformanceCategory, PerformanceStatus } from "@/types/api";

const PAGE_SIZE = 12;

type SearchParams = Record<string, string | string[] | undefined>;

function firstOf(value: string | string[] | undefined): string | undefined {
  return Array.isArray(value) ? value[0] : value;
}

// URL의 category/status가 유효한 값이 아니면(수동으로 잘못 고친 경우 등) 조용히 "필터 없음"으로 취급한다.
function parseCategory(value?: string): PerformanceCategory | undefined {
  return CATEGORY_OPTIONS.find((category) => category === value);
}

function parseStatus(value?: string): PerformanceStatus | undefined {
  return STATUS_OPTIONS.find((status) => status === value);
}

export default async function PerformancesPage({
  searchParams,
}: {
  searchParams: Promise<SearchParams>;
}) {
  const params = await searchParams;
  const category = parseCategory(firstOf(params.category));
  const keyword = firstOf(params.keyword);
  const status = parseStatus(firstOf(params.status));
  const page = Math.max(0, Number(firstOf(params.page)) || 0);
  const hasFilter = Boolean(category || keyword || status);

  const {
    content,
    totalPages,
    page: currentPage,
  } = await getPerformances({ category, keyword, status, page, size: PAGE_SIZE });

  return (
    <main className="mx-auto flex w-full max-w-5xl flex-1 flex-col gap-6 px-4 py-8">
      <h1 className="text-2xl font-bold">공연 목록</h1>

      <div className="flex flex-col gap-3">
        <SearchForm defaultKeyword={keyword} category={category} status={status} />
        <CategoryFilter currentSearchParams={params} activeCategory={category} />
        <StatusFilter currentSearchParams={params} activeStatus={status} />
      </div>

      {content.length === 0 ? (
        <EmptyState
          message={hasFilter ? "조건에 맞는 공연이 없습니다." : "등록된 공연이 없습니다."}
          action={
            hasFilter && (
              <Link href="/performances" className={buttonVariants({ variant: "outline" })}>
                필터 초기화
              </Link>
            )
          }
        />
      ) : (
        <>
          {/* 카드 제목이 h3라서 h1 바로 아래에 h2가 없으면 제목 단계가 건너뛴다. 화면에는 보이지 않는 h2를 둔다 */}
          <h2 className="sr-only">공연 검색 결과</h2>
          <div className="grid grid-cols-2 gap-4 sm:grid-cols-3 md:grid-cols-4">
            {content.map((performance) => (
              <PerformanceCard key={performance.id} performance={performance} />
            ))}
          </div>
        </>
      )}

      <Pagination currentSearchParams={params} page={currentPage} totalPages={totalPages} />
    </main>
  );
}
