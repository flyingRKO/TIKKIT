// Server Action이 돌려준 에러 메시지를 폼 안에 보여준다. role="alert"라서 스크린리더가 바로 읽는다.
export function FormError({ message }: { message?: string | null }) {
  if (!message) return null;

  return (
    <p role="alert" className="text-sm text-destructive">
      {message}
    </p>
  );
}
