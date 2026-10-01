// 서버(어떤 타임존이든)와 브라우저(사용자 로컬 타임존)에서 항상 같은 문자열이 나오도록
// 타임존을 명시적으로 고정한다 — 지정하지 않으면 하이드레이션 시 서버/클라이언트 렌더 결과가 달라질 수 있다.
const formatter = new Intl.DateTimeFormat("ko-KR", {
  timeZone: "Asia/Seoul",
  year: "numeric",
  month: "long",
  day: "numeric",
  weekday: "short",
  hour: "2-digit",
  minute: "2-digit",
});

// 같은 "ko-KR"이어도 서버 Node(24.x, CLDR 48)는 오전/오후를 "AM"/"PM"으로, 브라우저(Chrome)는 "오전"/"오후"로
// 내보낸다. 타임존만 고정해서는 하이드레이션 불일치를 못 막아서, 이 부분만 직접 정해둔 값으로 바꾼다.
const DAY_PERIOD_LABELS: Record<string, string> = { AM: "오전", PM: "오후" };

export function formatDateTime(iso: string): string {
  return formatter
    .formatToParts(new Date(iso))
    .map((part) =>
      part.type === "dayPeriod" ? (DAY_PERIOD_LABELS[part.value.toUpperCase()] ?? part.value) : part.value
    )
    .join("");
}
