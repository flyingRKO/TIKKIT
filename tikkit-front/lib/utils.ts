export { cn } from "cn"

// 커스텀 링크·버튼의 키보드 포커스 표시. shadcn Button/Input은 자체 focus 스타일이 있어서 쓰지 않는다.
export const focusRing =
  "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
