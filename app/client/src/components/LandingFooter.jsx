import { ShieldScanIcon } from './Icons.jsx'

export default function LandingFooter() {
  return (
    <footer className="landing-footer">
      <p className="landing-footer-mark"><ShieldScanIcon size={16} /> MailSentinel</p>
      <p className="landing-footer-note">
        Anonymous scans are never logged, stored, or shown to anyone. Everything
        happens in memory for the lifetime of a single request.
      </p>
    </footer>
  )
}
