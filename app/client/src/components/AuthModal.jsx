import { useState } from 'react'
import Modal from './Modal.jsx'
import AuthPanel from './AuthPanel.jsx'

const TITLES = {
  register: 'Create your account',
  login: 'Welcome back',
}

/**
 * The title used to derive from `initialMode` while AuthPanel kept the live mode in
 * its own state, so opening on "Sign up" and then clicking the "Log in" tab left the
 * heading reading "Create your account" above a Log in form -- and since that heading
 * is the dialog's accessible name via aria-labelledby, a screen reader was told the
 * wrong thing too. `mode` now lives one level above both, so they cannot disagree.
 *
 * The `key` remount idiom moves up here with it: it is what reseeds `mode` when the
 * dialog is reopened from the other button, without an effect syncing prop to state.
 */
export default function AuthModal({ open, initialMode, onClose, onAuthenticated }) {
  return (
    <AuthModalContent
      key={open ? initialMode : 'closed'}
      open={open}
      initialMode={initialMode}
      onClose={onClose}
      onAuthenticated={onAuthenticated}
    />
  )
}

function AuthModalContent({ open, initialMode, onClose, onAuthenticated }) {
  const [mode, setMode] = useState(initialMode)

  return (
    <Modal open={open} onClose={onClose} titleId="auth-modal-title" title={TITLES[mode] ?? TITLES.login}>
      <AuthPanel mode={mode} onModeChange={setMode} onAuthenticated={onAuthenticated} />
    </Modal>
  )
}
