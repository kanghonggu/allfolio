// 로컬 달력 기준 YYYY-MM-DD.
//
// **`new Date().toISOString().slice(0, 10)`을 쓰지 말 것 — 그게 이 파일이 생긴 이유다.**
//
// 화면의 날짜는 전부 로컬 `new Date()`로 만들어지는데 `toISOString()`은 UTC로 포맷한다.
// **로컬로 만들고 UTC로 읽는 불일치**라 UTC보다 앞선 존에서는 자정부터 오프셋만큼 하루가 밀린다 —
// KST(UTC+9)면 00:00~09:00, 한국의 출근 전 시간대 전체다. 그 시간에 화면을 연 사용자는
// 어제 날짜를 보고, 폼 기본값으로 쓰이는 자리에서는 **어제 날짜가 그대로 저장된다**
// (`cashflow`의 `flowDate`, `trades`의 `tradedAt`).
//
// 존을 KST로 박는 것과는 다른 이야기다. 만드는 쪽이 이미 로컬이므로 읽는 쪽만 로컬로 맞추면
// 되고, 그래야 해외 사용자도 자기 달력 기준으로 맞는다. KST를 하드코딩하면 이 사용자들이
// 반대 방향으로 틀린다.
//
// 백엔드 쪽 같은 결함은 #170(`generatedAt` 오프셋)·#172(조회 구간 KST)가 따로 고쳤다.
// 서버는 "KST 달력으로 자른다"가 맞고 여기는 "로컬 달력으로 읽는다"가 맞다 — 뿌리는 같지만
// 고치는 방향이 다르다.
export function isoDate(d: Date): string {
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${y}-${m}-${day}`
}

/** 로컬 달력의 오늘 (YYYY-MM-DD) */
export function todayIso(): string {
  return isoDate(new Date())
}
