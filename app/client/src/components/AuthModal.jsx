import Modal from './Modal.jsx'
import AuthPanel from './AuthPanel.jsx'

export default function AuthModal({ open, initialMode, onClose, onAuthenticated }) {
  return (
    <Modal
      open={open}
      onClose={onClose}
      titleId="auth-modal-title"
      title={initialMode === 'register' ? 'Create your account' : 'Welcome back'}
    >
      <AuthPanel key={initialMode} initialMode={initialMode} onAuthenticated={onAuthenticated} />
    </Modal>
  )
}
