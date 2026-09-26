// "use server" 파일(lib/actions/auth.ts)은 export가 전부 async 함수여야 해서, 상수/타입은 따로 둔다.
export interface AuthActionState {
  error: string | null;
}

export const initialAuthActionState: AuthActionState = { error: null };
