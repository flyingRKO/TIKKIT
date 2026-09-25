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
  | "INVALID_STATUS_TRANSITION";

// 회원 (Task 008에서 실제 인증 연결)

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

export interface LoginResponse {
  accessToken: string;
  tokenType: string;
  expiresIn: number;
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
  remainingQuantity: number;
}

// 예매·결제 (Task 012~013에서 실제 로직 연결)

export type ReservationStatus = "PENDING" | "CONFIRMED" | "CANCELLED" | "EXPIRED";

export interface ReservationCreateRequest {
  scheduleId: number;
  ticketGradeId: number;
  quantity: number;
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
  payment: PaymentResponse | null;
}

export interface PaymentRequest {
  method: PaymentMethod;
}
