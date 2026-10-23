// 백엔드 API 계약과 1:1로 맞춘 타입 정의 (Task 006).
// tikkit-back의 common/response, domain/*/dto 패키지와 대응된다.

export interface ApiResponse<T> {
  success: boolean;
  data?: T;
  code?: string;
  message?: string;
  errors?: string[];
}

export interface PageResponse<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  first: boolean;
  last: boolean;
}

export type ErrorCode =
  | "VALIDATION_ERROR"
  | "NOT_FOUND"
  | "UNAUTHORIZED"
  | "FORBIDDEN"
  | "INTERNAL_SERVER_ERROR"
  | "BOOKING_NOT_OPEN"
  | "SOLD_OUT"
  | "RESERVATION_EXPIRED"
  | "INVALID_STATUS_TRANSITION"
  | "DUPLICATE_PENDING_RESERVATION"
  | "CANCEL_DEADLINE_PASSED"
  | "DUPLICATE_EMAIL"
  | "INVALID_CREDENTIALS"
  // 대기열 403 두 개 (Task 031). 일반 FORBIDDEN도 403이므로 HTTP 상태가 아니라 이 code로 분기한다.
  | "QUEUE_PASS_REQUIRED"
  | "QUEUE_PASS_EXPIRED";

// 회원 — 세션 기반 인증(Task 008). 로그인 성공 응답은 토큰이 아니라 회원 정보이며,
// 실제 인증 상태는 BE가 내려주는 JSESSIONID 쿠키로 유지된다 (Task 011에서 Next 서버가 중계)

export type MemberRole = "USER" | "ADMIN";

export interface SignupRequest {
  email: string;
  password: string;
  name: string;
  phone: string;
}

export interface SignupResponse {
  id: number;
  email: string;
  name: string;
}

export interface LoginRequest {
  email: string;
  password: string;
}

export interface MemberResponse {
  id: number;
  email: string;
  name: string;
  phone: string;
  role: MemberRole;
}

// 공연·회차 (Task 009에서 실제 조회 연결)

export type PerformanceCategory = "CONCERT" | "MUSICAL" | "THEATER" | "CLASSIC" | "SPORTS";
export type PerformanceStatus = "UPCOMING" | "ON_SALE" | "CLOSED";

export interface PerformanceSummaryResponse {
  id: number;
  title: string;
  category: PerformanceCategory;
  posterUrl: string | null;
  venueName: string;
  status: PerformanceStatus;
  startDate: string | null; // yyyy-MM-dd
  endDate: string | null;
}

export interface ScheduleSummaryResponse {
  id: number;
  showAt: string; // ISO instant
  bookingOpenAt: string;
  bookingCloseAt: string;
}

export interface PerformanceDetailResponse {
  id: number;
  title: string;
  category: PerformanceCategory;
  description: string | null;
  posterUrl: string | null;
  venueName: string;
  venueAddress: string;
  runningMinutes: number;
  ageRating: string;
  status: PerformanceStatus;
  schedules: ScheduleSummaryResponse[];
}

export type Grade = "VIP" | "R" | "S" | "A";

export interface TicketGradeResponse {
  id: number;
  grade: Grade;
  price: number;
  // 지정석 전환 후 BE가 schedule_seats의 AVAILABLE 건수로 계산해 내려준다 (Task 022).
  // 응답 형식은 그대로라 화면은 바뀌지 않았다.
  remainingQuantity: number;
}

// 좌석 (Task 022)

export type SeatStatus = "AVAILABLE" | "HELD" | "SOLD";

export interface ScheduleSeatResponse {
  // schedule_seats.id다 — 물리 좌석(seats.id)이 아니다. 선점 요청의 seatIds가 이 값이다.
  // 좌석은 공연장 단위로 공유되지만 선점은 회차 단위라서, 물리 좌석 id로는 어느 회차인지 알 수 없다.
  id: number;
  section: string;
  rowLabel: string;
  seatNumber: number;
  // 배치도 좌표. posY가 작을수록 무대에 가깝고, posX는 구역 사이 통로만큼 값이 비어 있다.
  posX: number;
  posY: number;
  status: SeatStatus;
  ticketGradeId: number;
}

// 예매·결제 (Task 012~013에서 실제 로직 연결)

export type ReservationStatus = "PENDING" | "CONFIRMED" | "CANCELLED" | "EXPIRED";

export interface ReservationCreateRequest {
  scheduleId: number;
  ticketGradeId: number;
  quantity: number;
  // 고른 좌석의 schedule_seats.id 목록. quantity와 개수가 같아야 하고, 다르면 BE가 400으로 끊는다.
  // "한 예약 = 한 등급" 정책이라 ticketGradeId/quantity도 그대로 보낸다 (Task 022).
  seatIds: number[];
}

export interface ReservationResponse {
  id: number;
  reservationNo: string;
  status: ReservationStatus;
  expiresAt: string | null;
  confirmedAt: string | null;
  cancelledAt: string | null;
}

export interface ReservationSummaryResponse {
  id: number;
  reservationNo: string;
  performanceTitle: string;
  scheduleShowAt: string;
  grade: Grade;
  quantity: number;
  totalAmount: number;
  status: ReservationStatus;
}

// 예약이 받은 좌석 (Task 023). 상세 응답에만 있고 목록 응답에는 없다 —
// 목록 N건마다 좌석 조인이 돌면 N+1이 되고, 목록에서는 매수만 보여주면 된다.
export interface ReservationSeatResponse {
  section: string;
  rowLabel: string;
  seatNumber: number;
}

export type PaymentMethod = "CARD" | "KAKAO_PAY" | "BANK_TRANSFER";
export type PaymentStatus = "PAID" | "REFUNDED";

export interface PaymentResponse {
  id: number;
  method: PaymentMethod;
  status: PaymentStatus;
  transactionKey: string;
  paidAt: string | null;
}

export interface ReservationDetailResponse {
  id: number;
  reservationNo: string;
  performanceTitle: string;
  scheduleShowAt: string;
  grade: Grade;
  quantity: number;
  unitPrice: number;
  totalAmount: number;
  status: ReservationStatus;
  expiresAt: string | null;
  confirmedAt: string | null;
  cancelledAt: string | null;
  seats: ReservationSeatResponse[];
  payment: PaymentResponse | null;
}

export interface PaymentRequest {
  method: PaymentMethod;
}

// 대기열 (Task 031 BE / Task 032 FE) — tikkit-back의 domain/queue/dto와 대응된다.
// 진입(POST .../entries)·폴링(GET .../status)·이탈이 전부 같은 응답 스키마를 쓴다.

export type QueueState =
  // 이 회차는 대기열을 쓰지 않는다(schedules.queue_enabled=false 또는 앱 레벨 off). 좌석으로 직행.
  | "DISABLED"
  // 대기 중. position / estimatedWaitSeconds / pollAfterSeconds가 채워진다.
  | "WAITING"
  // 입장 완료. passExpiresAt까지 좌석 조회·예매 선점이 허용된다.
  | "ADMITTED"
  // 큐에 없거나 입장권이 만료됐다. BE가 둘을 한 값으로 묶었다 — 양쪽 다 할 일이 "다시 줄 서기"다.
  | "EXPIRED";

export interface QueueStatusResponse {
  scheduleId: number;
  state: QueueState;
  // 내 순번(1-based). WAITING이 아니면 null이다.
  position: number | null;
  totalWaiting: number;
  // rank / 초당 입장 인원. 공칭 속도 기준이라 실제보다 낙관적이다(표시보다 늦게 입장한다).
  estimatedWaitSeconds: number;
  // 다음 폴링까지 기다릴 초. 백엔드가 간격을 지시한다 — 프론트에 숫자를 박으면
  // Task 033이 측정해서 값을 고칠 때 프론트도 같이 고쳐야 한다. 0이면 폴링 중단.
  pollAfterSeconds: number;
  // 입장권 만료 시각(ISO instant). ADMITTED가 아니면 null이다.
  passExpiresAt: string | null;
}
