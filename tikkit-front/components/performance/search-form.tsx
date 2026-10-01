import { buttonVariants } from "@/components/ui/button";
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
    <form method="get" role="search" className="flex gap-2">
      {category && <input type="hidden" name="category" value={category} />}
      {status && <input type="hidden" name="status" value={status} />}
      <Input
        type="search"
        name="keyword"
        defaultValue={defaultKeyword}
        placeholder="공연명으로 검색"
        aria-label="공연명 검색"
        className="flex-1"
      />
      {/* Server Component라 Button 대신 buttonVariants를 써서 focus 스타일을 그대로 가져온다 */}
      <button type="submit" className={buttonVariants({ size: "lg" })}>
        검색
      </button>
    </form>
  );
}
