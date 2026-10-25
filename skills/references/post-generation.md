# Post-generation Enhancements

Treat speaker support and motion as **optional post-generation enhancements**, not as default blockers for the first deck build. The normal path is: clarify the presentation itself once when needed → plan → build → QA → deliver the usable deck → offer relevant enhancements. Do **not** add another pre-generation question such as "Do you want notes / a script / animations?" unless the user explicitly asks for one of these before generation.

After the core deck has passed QA, you may briefly surface up to three relevant follow-up enhancements:

1. **Speaker notes / presenter cues** — add support inside each slide's Notes area while keeping the visible slide concise.
2. **Full presentation script + transitions** — create a complete spoken narrative with opening, per-slide narration, logical bridges between slides, closing, and optional timing guidance.
3. **Transitions / emphasis animations** — add restrained motion that clarifies sequence, causality, comparison, route progression, or emphasis. Motion is never decoration-only by default.

Do not imply these enhancements were already added unless they actually were. If the user does not request them, the deck is complete without them.

## Speaker-support levels

Distinguish these outputs instead of treating all "notes" as the same artifact:

- **Presenter cues** — 2–4 short prompts per slide: what to emphasize, what not to over-explain, and the intended takeaway. Best for experienced speakers.
- **Speaker notes** — concise paragraph-level talking points for each slide, normally enough to explain the visual without reading verbatim. Keep slide content and notes complementary rather than duplicative.
- **Full script** — complete spoken copy, including opening, slide-to-slide transitions, closing, and optional timing. A full script may be much longer than PowerPoint notes and should not automatically be stuffed into the Notes pane unless the user explicitly wants that.

When a user requests notes from the start, still stabilize the slide narrative and visible layout first, then generate notes against the final slide order. If the user specifies a live talk duration (for example a 20-minute briefing), use that duration during planning because it affects page count, pacing, and content density; the detailed notes/script can still be authored after the slide content is stable.

## Slide-to-slide transitions in speech

When writing notes or a script, make transitions **logical bridges**, not generic filler such as "next, let's look at…". The bridge should explain why the next slide follows from the current one. Prefer a question, implication, contrast, or unresolved point that the next slide answers.

A good bridge should make the logic explicit: state the implication, contrast, question, or unresolved point from the current slide that the next slide answers.

## Motion and transition enhancements

Add animations/transitions only **after content, slide order, object hierarchy, and layout are stable**. Editing the deck after animation work can invalidate shape references or create inconsistent timing.

Use motion to communicate structure:

- **Progressive mechanism / process** → reveal stages in causal order.
- **Route / itinerary / map** → reveal locations or segments in travel order.
- **Comparison** → reveal comparable elements consistently, not randomly.
- **Evidence build** → reveal claim, then the evidence that supports it when the narrative benefits from staged disclosure.
- **Section change** → use restrained slide transition only when it helps signal a real narrative boundary.

Avoid applying the same entrance animation to every object. Avoid motion that competes with reading, changes layout meaning, or exists only to make the deck feel "dynamic". Prefer no animation over purposeless animation.

For existing decks or template-based decks, preserve native transitions/animations where practical. If their OOXML relationships cannot be preserved unambiguously, fail closed rather than silently inventing replacement behavior.

## Delivery behavior

The first delivery should remain the finished core presentation. If the runtime supports a compact follow-up UI, offer relevant enhancement actions after delivery; otherwise use one short sentence. Do not ask for approval before delivering the core `.pptx` unless the user explicitly requested an approval-first workflow.
