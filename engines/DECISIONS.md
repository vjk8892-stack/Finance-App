
## Post-install feedback pass (2026-08-14)

- **2026-08-14 · Keyword merchant→category rules bootstrap categorization** ·
  Uncategorized until the G7 3-of-4 rule has history · On a real install every
  captured rupee sat in Uncategorized, because the learned rule cannot
  bootstrap. That is not just cosmetic: Flow, Shape, What-if, Opportunity cost
  and the radar all key off category MIX, so with one category they each
  degenerate to a single 100% slice and the Insights tab says nothing. Keyword
  rules now provide a first guess, ranked strictly BELOW the learned rule, so
  a recategorization still wins permanently. Keywords match on token
  boundaries — substring matching filed LICIOUS under Insurance via "lic".
- **2026-08-14 · "from X" is only a counterparty on a CREDIT** · treating it as
  the payee on any direction · On a debit, "debited from HDFC Bank XX0773"
  names the user's own account. Capturing it produced ledger rows titled after
  the user's own bank, and a leak report recommending they watch their spending
  at "A C NO". The noise filter also only rejected candidates that were
  ENTIRELY one noise word, so "a/c no" passed; it now rejects any candidate
  containing account or bank vocabulary or a masked identifier.
- **2026-08-14 · Charts must not fill with the card's own background colour** ·
  `CharcoalRaised` as a treemap/heatmap tone · `CharcoalRaised` IS the card
  surface, so every even-index treemap slice was invisible and a
  single-category treemap rendered as an empty box — which is exactly what a
  fresh install produces. Zero-spend heatmap cells had the same problem, so the
  month grid had holes and stopped reading as a calendar. Both ramps now start
  above the card surface, the treemap outlines each slice, and the heatmap has
  a weekday strip.
- **2026-08-14 · Tapping a ledger row shows the message behind it** · swipe-only
  row actions · A row that reads "a/c no" is untrustworthy AND unfixable
  without the source text — you cannot tell whether Kosha misread the message
  or the bank phrased it oddly. The sheet shows the parsed fields plus the
  verbatim SMS. When raw retention was off at capture time it says so and
  offers the toggle, while being explicit that it only affects future scans:
  a discarded message cannot be recovered.
- **2026-08-14 · Anomaly rows name what they are about** · a fixed "Bigger than
  usual" label on every row · Three identical rows cannot be acted on. The
  flag now carries the merchant, or the category name for category-scope
  flags.
