# An artist's sideways rows: arrows and "Show all" (20-10-2026)

## The problem

On a desktop with a plain mouse wheel, the artist page's sideways rows (Albums, Singles, Featured on, Similar artists)
could only be moved with the thin scrollbar or shift+wheel, and there was no way to see all of an artist's singles: the
row held YouTube's artist-page shelf of ten.

## The decision

- Arrows and "Show all" are web-app work (naviseerr-client `Shelf.tsx`, `ArtistShelfPage.tsx`). The arrows follow
  YouTube Music's desktop web: a ‹ › pair at the right of the row's title, greyed at each end, hidden when the row fits
  and on touch screens. "Show all" (Spotify's wording) opens a grid page of the whole row, in the row's own order, on
  the rows YouTube Music gives one: Albums, Singles, and (below) the artist's own playlists. "Featured on" and "Similar
  artists" get none, as on YouTube Music: YouTube has no full list for them.
- Albums already arrive in full (up to 100, by popularity, since 12-10-2026).
- Singles: the adapter now reads every single and EP (`get_artist_albums` on the singles shelf, newest first, in
  parallel with the rest; its first ten are exactly the old shelf), and `ArtistView` caps singles at 100 like albums
  (`MAX_RELEASES`). An older adapter still sends ten, which the row shows as before.
- No new route: the "Show all" page reads the same `GET /artists/{id}` answer, which the web app keeps for five minutes,
  so it opens instantly from the artist page and still works from a link.

## How to flip

- Fewer singles: `MAX_RELEASES` here, or the adapter's `ALL_ALBUMS_LIMIT`.
- Arrows on touch screens too, other wording, other rows with Show all: see the web app's `Shelf.tsx` / `ArtistPage.tsx`
  (the client README lists each).
