import Link from "next/link";
import { SignupForm } from "@/components/auth/signup-form";

export default async function SignupPage({
  searchParams,
}: {
  searchParams: Promise<{ redirect?: string }>;
}) {
  const { redirect } = await searchParams;
  const redirectTo = redirect ?? "/";

  return (
    <div className="flex flex-col gap-6 rounded-xl border p-6">
      <h1 className="text-xl font-bold">회원가입</h1>
      <SignupForm redirectTo={redirectTo} />
      <p className="text-center text-sm text-muted-foreground">
        이미 계정이 있으신가요?{" "}
        <Link
          href={`/login?redirect=${encodeURIComponent(redirectTo)}`}
          className="font-medium text-primary hover:underline"
        >
          로그인
        </Link>
      </p>
    </div>
  );
}
