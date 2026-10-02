# Custom catalog format

Any game can be fed from a JSON file you import in **Settings** or from an `https://` URL set in **Settings → Card databases**. The app also understands the native formats of Pokémon TCG API, Scryfall, YGOPRODeck, lorcana-api, DigimonCard.io, vegapull-records (One Piece) and Card Game Simulator game files, and falls back to a tolerant extractor for other card-shaped JSON (including Tabletop Simulator mod data). The format below is the explicit one, the only one that can carry graded prices.

```json
{
  "cards": [
    {
      "id": "gd01-001",
      "setCode": "GD01",
      "setName": "Newtype Rising",
      "releaseDate": "2025-07-25",
      "setTotal": 100,
      "number": "GD01-001",
      "name": "Gundam",
      "rarity": "R",
      "category": "creature",
      "imageUrl": "https://example.com/gd01-001.jpg",
      "prices": { "normal": 1.5, "foil": 4.0 },
      "graded": [ { "company": "PSA", "grade": 10, "usd": 120.0 } ]
    }
  ]
}
```

* A bare top-level array of card objects is also accepted.
* Required: `name`, `number`. Everything else is optional.
* `category`: `creature`, `spell`, `trainer`, `resource`, `other`.
* `prices` keys: `normal`, `holo`, `reverse_holo`, `1st_edition`, `1st_ed_holo`, `foil`, `etched`, `parallel`, `promo`, `limited`.
* `graded.company`: `PSA`, `BGS`, `CGC`, `SGC` or `ANY`; `grade` accepts halves (`9.5`).
* `number` should be what is printed on the card (`OP01-120`, `025`) so the scanner can match it.

## Identity

Cards are keyed internally by `game:setCode:number[:printTag]` (see `CardKeys`). Keep `setCode` and `number` stable across versions of your file so users' collections stay attached; use distinct `id`s for alternate prints that share a number (the app adds a `dup2` suffix if you do not).
