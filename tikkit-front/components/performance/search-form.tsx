import { Input } from "@/components/ui/input";

interface SearchFormProps {
  defaultKeyword?: string;
  category?: string;
  status?: string;
}

// JS 없이 동작하는 GET 폼. <form method="get">은 자기 안의 input들로만 쿼리스트링을 새로 만들기 때문에,
// 지금 켜져 있는 category/status 필터를 hidden input으로 같이 보내야 검색해도 필터가 풀리지 않는다.
export function SearchForm({ defaultKeyword, category, status }: SearchFormProps) {
  return (
    <form method="get" className="flex gap-2">
      {category && <input type="hidden" name="category" value={category} />}
      {status && <input type="hidden" name="status" value={status} />}
      <Input
        type="search"
        name="keyword"
        defaultValue={defaultKeyword}
        placeholder="공연명으로 검색"
        className="flex-1"
      />
      <button
        type="submit"
        className="h-9 rounded-md bg-primary px-3 text-sm font-medium text-primary-foreground hover:bg-primary/80"
      >
        검색
      </button>
    </form>
  );
}
