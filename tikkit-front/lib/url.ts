// 현재 페이지의 searchParams를 유지하면서 일부 값만 바꾼 쿼리스트링을 만든다.
// value가 undefined면 그 파라미터를 제거한다(예: "전체" 옵션 선택 시).
export function withSearchParams(
  current: Record<string, string | string[] | undefined>,
  overrides: Record<string, string | undefined>
): string {
  const params = new URLSearchParams();

  for (const [key, value] of Object.entries(current)) {
    if (typeof value === "string") {
      params.set(key, value);
    }
  }

  for (const [key, value] of Object.entries(overrides)) {
    if (value === undefined) {
      params.delete(key);
    } else {
      params.set(key, value);
    }
  }

  const search = params.toString();
  return search ? `?${search}` : "";
}
