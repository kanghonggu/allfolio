import { queryClient } from './queryClient'

// QA AF-87: 계정 전환 시 이전 사용자의 화면·캐시가 남지 않도록 세션 경계
// (로그인·회원가입·로그아웃)에서는 클라이언트 상태를 통째로 버린다.
//
// queryClient는 모듈 싱글턴이라 소프트 내비게이션(router.push/replace)만으로는
// 비워지지 않고, Next Router Cache도 이전 세그먼트의 렌더 결과를 재사용한다.
// 라이브러리별 캐시를 하나씩 비우다 빠뜨리면 증상이 부분적으로 남으므로,
// 문서 자체를 다시 띄우는 하드 내비게이션으로 확실히 차단한다.
// 로그인·로그아웃은 빈번한 동작이 아니라 풀 리로드 비용이 문제되지 않는다.
export function navigateWithFreshSession(path: string) {
  queryClient.clear()
  // replace: 인증 전/후 화면이 히스토리에 남아 뒤로가기로 되살아나지 않게 한다
  window.location.replace(path)
}
