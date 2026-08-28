import { CheckIcon, FlagIcon } from './Icons.jsx'

export default function ChecksList({ checks }) {
  const flagged = checks.filter((c) => !c.passed).length

  return (
    <div className="ledger">
      <div className="ledger-head">
        <h2>Signals</h2>
        <p className="ledger-count">
          <b>{flagged}</b> of {checks.length} flagged
        </p>
      </div>
      <ul className="checks-list">
        {checks.map((check) => (
          <li key={check.name} className={check.passed ? 'check-pass' : 'check-fail'}>
            <span className="check-icon">
              {check.passed ? <CheckIcon size={16} /> : <FlagIcon size={16} />}
            </span>
            <div className="check-body">
              <div className="check-header">
                <span className="check-name">{check.name}</span>
                <span className="check-weight">{check.passed ? 'pass' : `+${check.weight}`}</span>
              </div>
              <p className="check-detail">{check.detail}</p>
            </div>
          </li>
        ))}
      </ul>
    </div>
  )
}
