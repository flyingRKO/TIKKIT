// 서버(어떤 타임존이든)와 브라우저(사용자 로컬 타임존)에서 항상 같은 문자열이 나오도록
// 타임존을 명시적으로 고정한다 — 지정하지 않으면 하이드레이션 시 서버/클라이언트 렌더 결과가 달라질 수 있다.
export function formatDateTime(iso: string): string {
  return new Intl.DateTimeFormat("ko-KR", {
    timeZone: "Asia/Seoul",
    year: "numeric",
    month: "long",
    day: "numeric",
    weekday: "short",
    hour: "2-digit",
    minute: "2-digit",
  }).format(new Date(iso));
}
