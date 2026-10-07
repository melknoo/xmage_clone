import { useEffect, useState, type ButtonHTMLAttributes, type CSSProperties, type MouseEvent } from 'react'
import { Icon, type IconName } from '../../lib/icons'

export type ButtonVariant = 'primary' | 'secondary' | 'ghost' | 'danger' | 'dangerConfirm' | 'icon' | 'wait'
export type ButtonSize = 'lg' | 'md' | 'sm' | 'xs' | 'xxs'

export interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  /** Standard 'secondary'. Pro Ansicht hoechstens ein 'primary'. */
  variant?: ButtonVariant
  /** lg h44 (Standard fuer primary/wait), md h40 (Standard sonst), sm h34, xs h30, xxs h28. 'icon' ist immer 32x32. */
  size?: ButtonSize
  /**
   * Spielbrett-Masse (btn() im Spielbrett-Prototyp): Laufweite immer .06em, md mit 12 px Innenabstand,
   * Tastenhinweis in Sekundaer-/Ghost-Buttons #2a2824 auf fg-3.
   */
  game?: boolean
  /** Icon vor dem Text (bei variant 'icon' das einzige Icon) */
  icon?: IconName
  /** Tastenhinweis im Button, z. B. "Space", "F5", "Esc" */
  kbd?: string
  /**
   * Zwei-Klick-Bestaetigung: der erste Klick schaltet scharf und zeigt diesen Text in dangerConfirm-Optik,
   * erst der zweite Klick ruft onClick. Zurueck bei Fokusverlust oder wenn sich confirm/disabled aendert.
   */
  confirm?: string
  testId?: string
}

const VARIANT_CLASS: Record<ButtonVariant, string> = {
  primary: 'btn-primary',
  secondary: 'btn-secondary',
  ghost: 'btn-ghost',
  danger: 'btn-danger',
  dangerConfirm: 'btn-danger-confirm',
  icon: 'btn-icon',
  wait: 'btn-wait',
}

const KBD_CLASS: Record<ButtonVariant, string> = {
  primary: 'kbd-primary',
  dangerConfirm: 'kbd-primary',
  secondary: 'kbd-dim',
  danger: 'kbd-dim',
  ghost: 'kbd-soft',
  icon: 'kbd-dim',
  wait: 'kbd-dim',
}

const SIZE_STYLE: Record<ButtonSize, CSSProperties> = {
  lg: { height: 44, padding: '0 18px', fontSize: 18, letterSpacing: '.08em' },
  md: { height: 40, padding: '0 14px', fontSize: 15, letterSpacing: '.06em' },
  sm: { height: 34, padding: '0 12px', fontSize: 14, letterSpacing: '.06em' },
  xs: { height: 30, padding: '0 10px', fontSize: 13, letterSpacing: '.06em' },
  xxs: { height: 28, padding: '0 8px', fontSize: 13, letterSpacing: '.06em' },
}
const GAME_SIZE_STYLE: Partial<Record<ButtonSize, CSSProperties>> = {
  lg: { letterSpacing: '.06em' },
  md: { padding: '0 12px' },
}

const ICON_SIZE: Record<ButtonSize, number> = { lg: 18, md: 16, sm: 14, xs: 14, xxs: 13 }

/** Button nach Designsystem. Text normal schreiben, Versalien kommen aus CSS. */
export function Button({
  variant = 'secondary',
  size,
  icon,
  kbd,
  confirm,
  testId,
  game = false,
  className = '',
  style,
  children,
  onClick,
  onBlur,
  disabled,
  type = 'button',
  ...rest
}: ButtonProps) {
  const [armed, setArmed] = useState(false)
  useEffect(() => setArmed(false), [confirm, disabled])

  const v: ButtonVariant = armed ? 'dangerConfirm' : variant
  const sz: ButtonSize = size ?? (variant === 'primary' || variant === 'wait' ? 'lg' : 'md')
  const sizeStyle = v === 'icon' ? undefined : game ? { ...SIZE_STYLE[sz], ...GAME_SIZE_STYLE[sz] } : SIZE_STYLE[sz]
  const kbdClass = game && v === 'ghost' ? 'kbd-dim' : KBD_CLASS[v]

  const handleClick = (e: MouseEvent<HTMLButtonElement>) => {
    if (variant === 'wait') return
    if (confirm && !armed) {
      e.preventDefault()
      setArmed(true)
      return
    }
    setArmed(false)
    onClick?.(e)
  }

  return (
    <button
      type={type}
      className={`${VARIANT_CLASS[v]} ${className}`}
      style={sizeStyle ? { ...sizeStyle, ...style } : style}
      data-testid={testId}
      data-armed={armed ? 'true' : undefined}
      aria-busy={variant === 'wait' ? true : undefined}
      disabled={disabled}
      onClick={handleClick}
      onBlur={(e) => {
        setArmed(false)
        onBlur?.(e)
      }}
      {...rest}
    >
      {icon && (
        <Icon
          name={icon}
          size={v === 'icon' ? 16 : variant === 'wait' ? 15 : ICON_SIZE[sz]}
          className={variant === 'wait' && icon === 'thinking' ? 'animate-think' : undefined}
        />
      )}
      {armed ? confirm : children}
      {kbd && <kbd className={kbdClass}>{kbd}</kbd>}
    </button>
  )
}
