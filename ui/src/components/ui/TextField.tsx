import { forwardRef, useId, type InputHTMLAttributes, type ReactNode } from 'react'
import { Icon, type IconName } from '../../lib/icons'

export interface TextFieldProps extends InputHTMLAttributes<HTMLInputElement> {
  /** Label darueber (Barlow 600 13 .12em fg-3, Versalien per CSS) */
  label?: ReactNode
  /** Fehlerzeile darunter (Karmin + Warn-Icon); setzt die Karmin-Kontur */
  error?: ReactNode
  /** Icon links im Feld (fg-4) */
  icon?: IconName
  /** Tastenhinweis rechts im Feld, z. B. "/" fuer die Suche */
  kbd?: string
  /** Codes: IBM Plex Mono 500 14 .1em */
  mono?: boolean
  /** Element rechts im Feld (z. B. Button) */
  trailing?: ReactNode
  /** Feldhoehe; 40 Standard, 42 Konto/Admin, 44 im Login */
  fieldHeight?: 40 | 42 | 44
  /** Klassen fuer den aeusseren Container */
  className?: string
}

/** Eingabefeld: bg-0, Kontur line-3, Fokus Ember, Fehler Karmin. ref zeigt auf das <input>. */
export const TextField = forwardRef<HTMLInputElement, TextFieldProps>(function TextField(
  { label, error, icon, kbd, mono, trailing, fieldHeight, className = '', id, disabled, ...rest },
  ref,
) {
  const autoId = useId()
  const inputId = id ?? autoId
  const hasError = !!error
  return (
    <div className={`flex min-w-0 flex-col gap-[7px] ${className}`}>
      {label && (
        <label htmlFor={inputId} className="label">
          {label}
        </label>
      )}
      <div
        className={`field ${mono ? 'field-mono' : ''}`}
        style={fieldHeight ? { height: fieldHeight } : undefined}
        data-error={hasError ? 'true' : undefined}
        data-disabled={disabled ? 'true' : undefined}
      >
        {icon && <Icon name={icon} size={16} className="text-fg-4" />}
        <input ref={ref} id={inputId} disabled={disabled} aria-invalid={hasError || undefined} {...rest} />
        {trailing}
        {kbd && <kbd className="kbd-dim">{kbd}</kbd>}
      </div>
      {hasError && (
        <span className="field-error" role="alert">
          <Icon name="error" size={13} />
          {error}
        </span>
      )}
    </div>
  )
})
