import { forwardRef } from 'react'
import { cx } from '@/lib/cx'

type Variant = 'primary' | 'outline' | 'ghost'
type Size = 'sm' | 'md'

const variantCls: Record<Variant, string> = {
  primary: 'border border-ink bg-ink text-white hover:bg-fg-2 hover:border-fg-2',
  outline: 'border border-line bg-surface text-fg-2 hover:border-ink hover:text-ink',
  ghost: 'border border-transparent bg-transparent text-fg-3 hover:text-ink',
}

const sizeCls: Record<Size, string> = {
  sm: 'px-3 py-1.5 text-xs',
  md: 'px-3.5 py-2 text-[12.5px]',
}

interface ButtonProps extends React.ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: Variant
  size?: Size
}

/** radius 0 버튼. primary=잉크 채움, outline=보더, ghost=텍스트 */
const Button = forwardRef<HTMLButtonElement, ButtonProps>(function Button(
  { variant = 'outline', size = 'md', className, type = 'button', ...rest },
  ref,
) {
  return (
    <button
      ref={ref}
      type={type}
      className={cx(
        'whitespace-nowrap transition-colors disabled:cursor-not-allowed disabled:opacity-50',
        variantCls[variant],
        sizeCls[size],
        className,
      )}
      {...rest}
    />
  )
})

export default Button
