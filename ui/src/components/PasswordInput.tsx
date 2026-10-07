import { forwardRef, useState } from 'react'
import { Icon } from '../lib/icons'
import { TextField, type TextFieldProps } from './ui/TextField'

export type PasswordInputProps = Omit<TextFieldProps, 'type' | 'trailing' | 'kbd' | 'mono'>

/**
 * Passwortfeld im TextField-Look (bg-0, Kontur line-3, Fokus Ember, Fehlerzeile) mit Auge zum Ein-/Ausblenden.
 * Props wie TextField (label, error, icon, fieldHeight, className = aeusserer Container); alle uebrigen gehen an
 * das <input> (autoComplete, value, onChange, required, minLength …). ref zeigt auf das <input>.
 */
export const PasswordInput = forwardRef<HTMLInputElement, PasswordInputProps>(function PasswordInput(props, ref) {
  const [visible, setVisible] = useState(false)
  const label = visible ? 'Passwort verbergen' : 'Passwort anzeigen'
  return (
    <TextField
      {...props}
      ref={ref}
      type={visible ? 'text' : 'password'}
      trailing={
        <button
          type="button"
          className="-mr-1.5 flex h-7 w-7 shrink-0 items-center justify-center rounded-xs text-fg-4 transition-colors duration-1 hover:text-fg-1 focus-visible:text-fg-1 focus-visible:outline-2 focus-visible:outline-fg-1"
          aria-label={label}
          aria-pressed={visible}
          title={label}
          disabled={props.disabled}
          onClick={() => setVisible((v) => !v)}
        >
          <Icon name={visible ? 'invisible' : 'spectate'} size={16} />
        </button>
      }
    />
  )
})
