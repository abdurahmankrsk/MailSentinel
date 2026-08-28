// Authored icon set. One geometry, one stroke weight (1.75 at a 24 viewBox), round
// caps and joins throughout, so the marks read as a family rather than as glyphs
// borrowed from wherever. Everything inherits currentColor.

function Svg({ children, size = 18, ...rest }) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.75"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      focusable="false"
      {...rest}
    >
      {children}
    </svg>
  )
}

export function CheckIcon(props) {
  return (
    <Svg {...props}>
      <path d="M20 6 9 17l-5-5" />
    </Svg>
  )
}

export function FlagIcon(props) {
  return (
    <Svg {...props}>
      <path d="M12 8v5" />
      <path d="M12 16.5v.01" />
      <path d="M10.3 3.9 1.8 18a2 2 0 0 0 1.7 3h17a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0Z" />
    </Svg>
  )
}

// Wordmark glyph: a shield whose lower half is cut by a scan line, which is the
// product in one mark -- something protected, and something being read.
export function ShieldScanIcon(props) {
  return (
    <Svg {...props}>
      <path d="M12 22s8-3.6 8-10V5.4l-8-3-8 3V12c0 6.4 8 10 8 10Z" />
      <path d="M4.6 13.5h14.8" />
    </Svg>
  )
}

// The overflow menu people are told to open in their mail client. Drawn as three
// zero-length round-capped strokes rather than filled circles, so it keeps the same
// weight as the rest of the set (the same way FlagIcon draws its dot).
export function MoreVerticalIcon(props) {
  return (
    <Svg {...props}>
      <path d="M12 5v.01" />
      <path d="M12 12v.01" />
      <path d="M12 19v.01" />
    </Svg>
  )
}

export function CloseIcon(props) {
  return (
    <Svg {...props}>
      <path d="M18 6 6 18" />
      <path d="m6 6 12 12" />
    </Svg>
  )
}
