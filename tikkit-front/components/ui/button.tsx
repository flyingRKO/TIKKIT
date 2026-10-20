import type { ComponentProps } from "react"
import { cva, type VariantProps } from "class-variance-authority"
import { cn } from "cn"

const buttonVariants = cva(
  "group/button inline-flex shrink-0 items-center justify-center rounded-lg border border-transparent bg-clip-padding text-sm font-medium whitespace-nowrap transition-all outline-none select-none focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50 active:not-aria-[haspopup]:translate-y-px disabled:pointer-events-none disabled:opacity-50 aria-invalid:border-destructive aria-invalid:ring-3 aria-invalid:ring-destructive/20 dark:aria-invalid:border-destructive/50 dark:aria-invalid:ring-destructive/40 [&_svg]:pointer-events-none [&_svg]:shrink-0 [&_svg:not([class*='size-'])]:size-4",
  {
    variants: {
      variant: {
        default: "bg-primary text-primary-foreground hover:bg-primary/80",
        outline:
          "border-border bg-background hover:bg-muted hover:text-foreground aria-expanded:bg-muted aria-expanded:text-foreground dark:border-input dark:bg-input/30 dark:hover:bg-input/50",
        secondary:
          "bg-secondary text-secondary-foreground hover:bg-[color-mix(in_oklch,var(--secondary),var(--foreground)_5%)] aria-expanded:bg-secondary aria-expanded:text-secondary-foreground",
        ghost:
          "hover:bg-muted hover:text-foreground aria-expanded:bg-muted aria-expanded:text-foreground dark:hover:bg-muted/50",
        destructive:
          "bg-destructive/10 text-destructive hover:bg-destructive/20 focus-visible:border-destructive/40 focus-visible:ring-destructive/20 dark:bg-destructive/20 dark:hover:bg-destructive/30 dark:focus-visible:ring-destructive/40",
        link: "text-primary underline-offset-4 hover:underline",
      },
      size: {
        default:
          "h-8 gap-1.5 px-2.5 has-data-[icon=inline-end]:pr-2 has-data-[icon=inline-start]:pl-2",
        xs: "h-6 gap-1 rounded-[min(var(--radius-md),10px)] px-2 text-xs in-data-[slot=button-group]:rounded-lg has-data-[icon=inline-end]:pr-1.5 has-data-[icon=inline-start]:pl-1.5 [&_svg:not([class*='size-'])]:size-3",
        sm: "h-7 gap-1 rounded-[min(var(--radius-md),12px)] px-2.5 text-[0.8rem] in-data-[slot=button-group]:rounded-lg has-data-[icon=inline-end]:pr-1.5 has-data-[icon=inline-start]:pl-1.5 [&_svg:not([class*='size-'])]:size-3.5",
        lg: "h-9 gap-1.5 px-2.5 has-data-[icon=inline-end]:pr-2 has-data-[icon=inline-start]:pl-2",
        icon: "size-8",
        "icon-xs":
          "size-6 rounded-[min(var(--radius-md),10px)] in-data-[slot=button-group]:rounded-lg [&_svg:not([class*='size-'])]:size-3",
        "icon-sm":
          "size-7 rounded-[min(var(--radius-md),12px)] in-data-[slot=button-group]:rounded-lg",
        "icon-lg": "size-9",
      },
    },
    defaultVariants: {
      variant: "default",
      size: "default",
    },
  }
)

/**
 * 네이티브 <button>으로 그린다 (Task 030_2).
 *
 * 원래 @base-ui/react/button을 썼다. 030_1 번들 분석에서 홈 스크립트 191,785B 중
 * @base-ui/react가 34,562B(18%)였고 전 페이지가 내는 비용이었다. 서브패스로 import해도
 * @base-ui/utils 공용 런타임(render prop 병합, ref 조합)이 버튼 하나에 같이 딸려온다.
 *
 * 스타일은 이미 cva가 전담했고, base-ui가 주던 동작 중 이 프로젝트가 실제로 쓰는 것은
 * "네이티브 버튼이면 type을 button으로 둔다" 하나뿐이었다(useButton.js의 isNativeButton
 * 분기). 그것만 기본값으로 옮겼다.
 *
 * type 기본값이 핵심이다. HTML <button>의 기본 type은 submit이라, 안 박아두면 폼 안에서
 * type을 안 적은 버튼의 클릭이 폼 제출로 바뀐다. 호출부가 적은 type이 이기도록 구조분해로
 * 받는다(base-ui도 외부 props를 마지막에 머지해 같은 우선순위였다).
 *
 * alert-dialog는 base-ui를 계속 쓴다 — 포커스 트랩·스크롤 락 같은 접근성 로직이 실제로
 * 필요하다. 그래서 의존성이 사라지는 것이 아니라 그 다이얼로그를 쓰는 화면
 * (/my/reservations/[id])에만 남는다. 홈·상세·좌석맵 청크에서는 빠진다.
 *
 * base-ui 프리미티브가 render={<Button/>}로 이 컴포넌트를 받는 자리가 둘 있다
 * (alert-dialog.tsx의 Close, cancel-reservation-button.tsx의 Trigger). 프리미티브가
 * props와 ref를 머지해 넘기고 React 19는 함수 컴포넌트에 ref를 prop으로 주므로,
 * {...props}를 그대로 펼치면 동작이 유지된다.
 */
function Button({
  className,
  variant = "default",
  size = "default",
  type = "button",
  ...props
}: ComponentProps<"button"> & VariantProps<typeof buttonVariants>) {
  return (
    <button
      data-slot="button"
      type={type}
      className={cn(buttonVariants({ variant, size, className }))}
      {...props}
    />
  )
}

export { Button, buttonVariants }
