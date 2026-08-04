/** 조건부 클래스 결합 (clsx 최소 대체) */
export const cx = (...xs: Array<string | false | null | undefined>) => xs.filter(Boolean).join(' ')
