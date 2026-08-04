import type { Config } from 'tailwindcss'

/**
 * 디자인 토큰은 app/globals.css의 CSS 변수가 원본이다.
 * 여기서는 시맨틱 이름 ↔ 변수 매핑만 한다. 컴포넌트에 hex 하드코딩 금지.
 */
const config: Config = {
  content: [
    './app/**/*.{ts,tsx}',
    './components/**/*.{ts,tsx}',
  ],
  theme: {
    extend: {
      colors: {
        canvas: 'var(--c-canvas)',
        surface: {
          DEFAULT: 'var(--c-surface)',
          muted: 'var(--c-surface-muted)',
        },
        ink: 'var(--c-ink)',
        fg: {
          DEFAULT: 'var(--c-ink)',
          2: 'var(--c-fg-2)',
          3: 'var(--c-fg-3)',
          muted: 'var(--c-fg-muted)',
          faint: 'var(--c-fg-faint)',
          ghost: 'var(--c-fg-ghost)',
        },
        line: {
          DEFAULT: 'var(--c-line)',
          card: 'var(--c-line-card)',
          soft: 'var(--c-line-soft)',
          hair: 'var(--c-line-hair)',
        },
        // 손익 — 한국 시장 관례: 상승 빨강 / 하락 파랑
        gain: 'var(--c-gain)',
        loss: 'var(--c-loss)',
        danger: 'var(--c-danger)',
        ok: 'var(--c-ok)',
        warn: {
          DEFAULT: 'var(--c-warn)',
          bg: 'var(--c-warn-bg)',
          line: 'var(--c-warn-line)',
        },
        link: {
          DEFAULT: 'var(--c-link)',
          hover: 'var(--c-link-hover)',
        },
      },
      fontFamily: {
        sans: ['var(--font-sans)', 'IBM Plex Sans KR', 'system-ui', 'sans-serif'],
        mono: ['var(--font-mono)', 'IBM Plex Mono', 'ui-monospace', 'monospace'],
        serif: ['var(--font-serif)', 'var(--font-serif-kr)', 'IBM Plex Serif', 'Noto Serif KR', 'serif'],
      },
      letterSpacing: {
        label: '0.14em',
        wideLabel: '0.18em',
        brand: '0.24em',
      },
    },
  },
  plugins: [],
}

export default config
