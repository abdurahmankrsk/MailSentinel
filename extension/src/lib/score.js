// Score banding, kept deliberately identical to the web app's ScoreDisplay.jsx.
//
// The thresholds are duplicated here rather than fetched, because the extension has to
// render a verdict before it can reach the server and a band that disagrees with the
// web app for the same score would be worse than no band at all. If ScoreDisplay.jsx
// ever moves its thresholds, this file has to move with it -- extension/tests covers
// the boundaries so the drift shows up as a failure rather than a surprise.
globalThis.MailSentinel = globalThis.MailSentinel || {};

(function (ns) {
  'use strict';

  // 60 is the red threshold the scoring tiers are built around, so it belongs in the
  // red band rather than at the top of the yellow one.
  function bandFor(score) {
    if (score < 30) {
      return {
        key: 'low',
        label: 'Low risk',
        note: 'Nothing here crosses the threshold for concern.',
      };
    }
    if (score < 60) {
      return {
        key: 'medium',
        label: 'Medium risk',
        note: 'Weak signals are stacking up. Worth a second look before you act on this.',
      };
    }
    return {
      key: 'high',
      label: 'High risk',
      note: 'Treat this as hostile. Do not click anything in the message or reply to it.',
    };
  }

  ns.score = { bandFor };
})(globalThis.MailSentinel);
