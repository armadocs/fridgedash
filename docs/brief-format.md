# Daily brief format (brief.json)

FridgeDash reads one JSON file, by default
`https://api.github.com/repos/armadocs/daily-brief/contents/brief.json`
(a **private** repo, read with a fine-grained token that has *Contents: Read-only*).
It refreshes every 5 minutes. Check-offs are stored on the fridge, per `date`.

```json
{
  "date": "2026-10-29",
  "title": "Armada daily brief",
  "generated_at": "2026-10-29T08:25:00-07:00",
  "sections": [
    {
      "id": "needs", "title": "Needs you", "kind": "tasks",
      "items": [
        {"id": "example-task", "title": "Short action title", "detail": "One line of context"}
      ]
    },
    {
      "id": "moving", "title": "Moving", "kind": "info",
      "items": [{"id": "example-info", "title": "FYI headline", "detail": "Status"}]
    },
    {
      "id": "next", "title": "Coming up", "kind": "events",
      "items": [{"id": "example-event", "title": "Weekly meeting", "detail": "", "when": "2026-10-30T09:00"}]
    },
    {
      "id": "parking", "title": "Parking lot", "kind": "later",
      "items": [{"id": "example-later", "title": "Only if there's time", "detail": ""}]
    }
  ]
}
```

- `kind`: `tasks` (checkboxes), `info` (no checkbox), `events` (date chip from `when`),
  `later` (folded away). Missing `kind` is guessed from the title.
- `id`: short and stable for the same item across days (so a check-off isn't lost if
  the brief is re-published the same day). Missing ids are derived from the title.
- `when`: `YYYY-MM-DD`, `YYYY-MM-DDTHH:MM`, or full ISO 8601 with offset.
