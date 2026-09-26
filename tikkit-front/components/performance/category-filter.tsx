import Link from "next/link";
import { cn } from "cn";
import { CATEGORY_LABELS, CATEGORY_OPTIONS } from "@/lib/performance-labels";
import { withSearchParams } from "@/lib/url";
import type { PerformanceCategory } from "@/types/api";

interface CategoryFilterProps {
  currentSearchParams: Record<string, string | string[] | undefined>;
  activeCategory?: PerformanceCategory;
}

export function CategoryFilter({ currentSearchParams, activeCategory }: CategoryFilterProps) {
  const tabs: { label: string; category?: PerformanceCategory }[] = [
    { label: "전체", category: undefined },
    ...CATEGORY_OPTIONS.map((category) => ({ label: CATEGORY_LABELS[category], category })),
  ];

  return (
    <nav className="flex flex-wrap gap-1.5" aria-label="카테고리 필터">
      {tabs.map((tab) => {
        const isActive = tab.category === activeCategory;
        return (
          <Link
            key={tab.label}
            href={withSearchParams(currentSearchParams, { category: tab.category, page: undefined })}
            className={cn(
              "rounded-full px-3 py-1.5 text-sm font-medium transition-colors",
              isActive
                ? "bg-primary text-primary-foreground"
                : "bg-muted text-muted-foreground hover:text-foreground"
            )}
            aria-current={isActive ? "true" : undefined}
          >
            {tab.label}
          </Link>
        );
      })}
    </nav>
  );
}
