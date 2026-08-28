export default function ChecksList({ checks }) {
  return (
    <ul className="checks-list">
      {checks.map((check) => (
        <li key={check.name} className={check.passed ? 'check-pass' : 'check-fail'}>
          <span className="check-icon" aria-hidden="true">{check.passed ? '✓' : '✕'}</span>
          <div className="check-body">
            <div className="check-header">
              <span className="check-name">{check.name}</span>
              {!check.passed && <span className="check-weight">+{check.weight}</span>}
            </div>
            <p className="check-detail">{check.detail}</p>
          </div>
        </li>
      ))}
    </ul>
  )
}
