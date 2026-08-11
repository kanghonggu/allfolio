import { NextResponse } from 'next/server'

/** 백엔드(AuthController.REFRESH_COOKIE)가 굽는 이름·경로와 반드시 일치해야 삭제된다. */
const REFRESH_COOKIE = 'allfolio_rt'
const REFRESH_COOKIE_PATH = '/api/auth'

/**
 * refresh 쿠키를 브라우저에서 지운다 (AF-96).
 *
 * 로그아웃은 원래 백엔드가 revoke + 쿠키 삭제를 함께 처리한다. 그런데 백엔드가
 * 응답하지 못하면(라이브에서 Render 콜드스타트 중 503 관측) 쿠키를 지울 주체가
 * 사라진다 — allfolio_rt는 HttpOnly라 스크립트로 손댈 수 없고, 굽는 쪽이 백엔드다.
 * 그 결과 "로그아웃 → /login 이동 → refresh 성공 → 대시보드 복귀"가 되어
 * 사용자가 로그아웃했다고 믿는데 로그인 상태로 돌아온다(로컬에서 재현 확인).
 *
 * 이 핸들러는 Vercel에서 실행되므로 백엔드 가용성과 무관하게 쿠키를 지운다.
 * 서버 측 토큰은 만료까지 남지만, 최소한 이 브라우저에서 세션이 되살아나지 않는다.
 */
export async function POST() {
  const res = new NextResponse(null, { status: 204 })
  res.cookies.set({
    name: REFRESH_COOKIE,
    value: '',
    maxAge: 0,
    path: REFRESH_COOKIE_PATH,
    httpOnly: true,
    sameSite: 'lax',
    // 로컬 http에서는 Secure 쿠키가 거부돼 삭제가 되지 않는다
    secure: process.env.NODE_ENV === 'production',
  })
  return res
}
