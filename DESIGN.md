# Bridge Game — Basic Design (draft 0.1)

Status: early draft. Items marked **[OPEN]** are not decided yet.

## 1. Vision
A "sport" bridge game built on top of the existing Preferans game. The Preferans code, UI, accounts and
online/async infrastructure are reused as much as possible. Solo developer, so the first version must be
fast and cheap to build.

## 2. Product pillars (long term)
1. Offline play against strong bots (bots play as opponents AND as partner).
2. Sport mode with seasons (same deals for everyone, like in Preferans).
3. Online play with other players, asynchronous with confirmation / auto-confirmation.
4. Several contracts/rule sets and several bidding systems (called "negotiation" in early notes).
5. Later: simple builder for a player's own bidding system.

## 3. Scope of version 1 (MVP)
- Single-player seasons only (human + bot partner vs. two bots).
- Season length: one month. Fixed set of deals per season, identical for all players.
- Most widespread rules: standard contract bridge, **duplicate** format (see 5).
- One or two basic bidding systems (recommended: Standard American / SAYC). More added later.
- No custom bidding builder. No complex anti-cheat.

Out of scope for v1: online play, bidding-system builder, rubber/Chicago formats, tournaments with live tables.

## 4. Core rules summary (what the engine must implement)
- 4 players, 2 fixed partnerships (North–South, East–West), 52 cards, 13 each, no talon.
- Auction: pass / bid (level 1–7 + suit or No Trump) / double / redouble. Ends after 3 passes following a bid.
  Suit rank: clubs < diamonds < hearts < spades < no trump.
- Contract = last bid. Declarer = first player of the winning side to name that suit. Partner becomes dummy
  (hand shown face up after the opening lead; declarer plays both hands). Left-hand opponent leads.
- Trick play: must follow suit; trump = contract suit (none in No Trump).
- Scoring: trick points, game / partscore / slam bonuses, overtricks, undertrick penalties, doubled/redoubled
  values, vulnerability. A board is dealt with fixed dealer + vulnerability from a standard 16-board cycle.

## 5. Sport format
- **Duplicate bridge**: every deal is played at many tables and results are compared. This removes luck of the
  deal and matches the "same deals for everyone" season idea.
- Scoring for comparison: **Matchpoints** (recommended for v1; simpler). IMPs possible later.
- **[OPEN] Single-player comparison baseline**: with no other humans in v1, the player's result on each board
  must be compared to something. Options: (a) results of other real players who played the same board
  (needs a server), (b) a "field" of bot results precomputed per board, (c) double-dummy par score.
  Recommendation: start with (b), keep (a) possible.

## 6. Bots
- Card play: strong play is realistic with a double-dummy / Monte-Carlo approach (open-source solvers exist).
- Bidding: the hardest part. Bots must follow the chosen bidding system consistently, because the human
  partner must be able to understand their bids. Start with one simple system.
- **[OPEN]** Write own bot, or integrate an existing open-source engine (license check needed).

## 7. Reuse from Preferans
Sources found in `C:\Old` (inspected briefly, not yet in depth):
- `PrefAndroid` — Kotlin + Jetpack Compose, package `com.an0obIs.pref` (GitHub: SmaginStepan/PrefAndroid).
  Folders: `model` (Game, Deal, Season, SeasonRandom, scores), `ai` (bots), `mp` (host session, protocol),
  `net` (LobbyClient, ScoreClient), `monetization` (ads, billing), `ui`.
- `PrefiOS` — Swift/SwiftUI port; `PrefEngine` is a pure-Swift engine translated verbatim from Kotlin.
- `Pref60` — original 2011 Windows Phone C# app (historical).
- `PrefServer` — Node/TypeScript WebSocket relay. The server knows nothing about the game: the **host
  device runs the engine**, the server only seats players and relays opaque JSON.
- `ScoreServer` (+ `ScoreClientUnity`) — multi-game leaderboard/seasons service. Already designed for
  bridge: a season has a JSON payload that can carry duplicate deals, deals can be private (fetched one
  by one, access logged = anti-peek audit trail). New game = one CLI command, no redeploy.

Reuse plan:
- **Seasons**: deterministic shared deals from a server seed (`SeasonRandom`: SHA-256 + SplitMix64, identical
  on all platforms) and seeded bots. Fits duplicate bridge directly. Reuse as is.
- **Score boards, accounts, purchases, ads**: reuse ScoreServer and the monetization code.
- **Online**: reuse PrefServer relay and host-authoritative model (needs 4 seats instead of 3-4; partnership).
- **UI**: Compose table, card sprites, animations, settings, tutorials, localization (en/es/ru).
- **Platforms**: Android first (Kotlin). iOS port later, following the same verbatim-port approach.
- **New work**: partnerships, dummy, bridge auction and scoring, matchpoint comparison, bridge bots.
- **Already live in Preferans 2.2.0** (Android + iOS, Nov 2026): monthly seasons, interstitial ads after
  single-player deals, one-time "remove ads" purchase, Google UMP consent, privacy page with data deletion,
  ScoreServer at scores.preferansmaster.com, relay at preferansmaster.com. Bridge can reuse all of it as
  another game on the same servers (new game id on ScoreServer). Store listing must not claim "no ads".
- **Known Preferans multiplayer issues to fix before reusing online code for bridge**: iOS reconnect loop
  ("replaced") after Back; Android guest-left keeps the seat ("Reconnecting"); Back never leaves a room;
  4-player table and "host leaves" were never tested (bridge needs 4 seats).
- **Not reusable**: Preferans AI (misere, raspasy, etc.) — bridge needs its own bidding and play logic.

## 8. Online play (built 2026-11-20, see section 19)
- Like Preferans: the host's phone runs the game, the relay only passes messages; Next board with an optional auto-continue.
- Anti-cheat for v1/v2: "impossible by game design" (hidden hands never sent to other clients; no in-game
  chat that can reveal cards). **[OPEN]** Whether to allow free chat.

## 8a. Training mode with a coach (idea from the author, 2026-11-07)
Goal: the game teaches bridge while you play (the author is a beginner himself; most players of a new
bridge app will be too). A coach comments on every bid and every card play, for the human AND for the bots.
- **Coach levels** (setting): off / hint before my move / feedback after my move / full commentary.
- **Bids**: each call has a meaning ("2♥ = 6-9 points, 3+ hearts"). After my call the coach says: matches the
  system / better was X because ... Partner's and opponents' bids are explained on tap or automatically.
- **Card play**: simple rules of thumb at first ("lead the 4th best of your longest suit", "second hand low",
  "cover an honor with an honor", count winners/losers); later a double-dummy solver can flag a play that
  costs a trick and show the better card.
- **Puzzles**: "bid this hand" and "find the lead" quizzes with the same coach (like the quizzes in the chat
  where the idea came up), possibly daily puzzles tied to seasons.
- **Retry/undo** in training games, never in sport (season) games.
- **Architecture consequence (important)**: the bidding system must be **data-driven**: a table of rules
  (conditions on points/shape/auction -> call + explanation text in en/es/ru). The same table drives the bot's
  choice, the coach's comments, the "what does this bid mean" popup, and later the player's own system
  builder (section 9). So the SAYC bot should be written as rules with explanations, not as hard-coded `if`s.
  The placeholder `BasicBot` will be replaced by that rule engine.
- Training games do not count for the season leaderboard; sport games hide the coach.

## 9. Bidding system builder (later)
- Must be as simple as possible. Idea to refine later: a convention card with a few selectable options
  (e.g. 5-card majors, strong or weak NT range) before any free-form rule editor.

## 10. Code review of the Preferans copy (2026-11-07, subagent review, partly verified)
Summary: the **infrastructure** is reusable; the **game core, AI and table UI** must be rewritten.
- Engine (`model/Game.kt`): synchronous state machine (`GamePhase`, `next()`), bots plug in via `AI.makeMove`.
  `turnController()` already means "someone else plays the cards of the seat in turn" (whister plays the
  passer's cards in open games) — this is almost exactly declarer-plays-dummy.
- "3 players" is hard-coded almost everywhere (Game, Deal, Calculation, RemoteViews, TableLayout, ~20 AI files).
  The existing 4-player mode is a 3-player engine with a sitting-out dealer, so the engine never ran with
  4 real seats and the 4-seat room code is partly untested.
- Reusable as is: Card/Hand, seeding (`SeasonRandom` — SHA-256 + SplitMix64), Seasons/ScoreClient/SeasonScreen,
  LobbyClient + protocol (rules are opaque JSON), relay server (already allows 3 or 4 seats; no teams —
  partnership = seat parity), storage, settings, localization infrastructure, ads/billing, animation machinery,
  HostGameSession skeleton (pump, confirm stops, broadcast).
- Rewrite: phases (Bidding, opening lead, dummy reveal, play, score), Bid (level + denomination + pass/X/XX),
  contract/declarer, scoring (replaces Calculation/GlobalScores/HighScores), matchpoint comparison, all AI,
  TableLayout for 4 seats with 13 cards per hand, RemoteViews/GameMsg for 4 seats and dummy.
- Delete: all of `ai/` (~4900 lines), `ui/calc/*`, Agreements, raspasy/miser/prikup/vist code, Pref tutorials and
  dictionary, Pref rule texts, the 3-to-4-player remapping.
- Dummy: `RemoteViews` already shows a hand face up when `isVisible` is set; set it on the dummy hand only after
  the opening lead. Declarer's client receives the "ask" for dummy's cards (`turnController`). Never send the
  partner's hand in snapshots. Card taps on the dummy must be accepted for declarer (currently rejected).
- Suit order: Preferans codes are 0=S 1=C 2=D 3=H 4=NT; bridge order is C<D<H<S<NT — define a separate rank.
- Missing card art: 2–6 of each suit (20 images). Table UI is a fixed 480x716 canvas; full rewrite needed.
- **Security**: `net/ScoreClient.kt` has the ScoreServer API key and HMAC secret hard-coded (also in git
  history of the clone). Bridge needs its **own game id and keys** on ScoreServer, and secrets should not be
  committed. New applicationId also needs new AdMob / Play / App Store setup.
- Build: AGP 8.13, Kotlin 2.2.20, Compose BOM 2025.09, minSdk 26, target 36; 11 unit tests (JUnit4) as a template.

## 11. Roadmap (revised)
1. **DONE (2026-11-07)** Fork cleanup: package/applicationId = `com.an0obis.bridge`, fresh git repo (no Pref
   history, nothing committed yet), Preferans AI/calc/game/mp/season code deleted, ScoreServer key and secret
   moved to `scoreApiKey` / `scoreSecret` in `~/.gradle/gradle.properties` (BuildConfig), game id `bridge`.
   Skeleton builds; menu has placeholders (Solo, Season, Online, Remove ads, Rate, Privacy, About).
   New `model/Cards.kt` (Suit, Card, Hand, Seat, Deck.deal) with unit tests.
   Leftovers for later: ~300 unused Preferans strings in `res/values*`, launcher icon, `assets/img` and
   `assets/cards` (needs 2–6), Play/AdMob/IAP setup for the new app id, ScoreServer registration of game `bridge`,
   `LobbyClient`/`Protocol` still mirror the 3-4 seat Preferans relay.
2. **DONE (2026-11-07)** Pure-Kotlin bridge engine in `model/` (no Android): `Cards.kt` (deck, seeded deal),
   `Auction.kt` (calls, legality, end, contract, declarer), `Play.kt` (tricks, follow suit, dummy control and
   visibility), `Scoring.kt` (duplicate scoring, 16-board cycle, matchpoint percentage). 17 unit tests pass.
   Not yet covered: bot players, game loop (`Game` state machine), saving a game, claims/concessions, revoke rules.
3. **Bot + game loop DONE (2026-11-07)**: `BoardGame` (auction -> play -> result, `runBots` stops when a human
   must act), `BotView` (only what a player may legally see; declarer chooses for dummy), `Bot` interface and a
   placeholder `BasicBot` (opens longest suit with 12+, raises partner, random card play). 300 boards simulated
   in tests, deterministic from the season seed.
   **Solo table screen DONE (2026-11-07)**: `ui/solo/` (SoloViewModel, SoloScreen, CardViews). Human = South,
   bots = N/E/W; bidding box, auction grid, dummy face-up (text lines for W/E, tappable row for N), trick
   compass with the last trick lingering, result dialog, next board. Cards use the Preferans art (`assets/cards`, 296x392 png); the missing 2-6 were generated in the same style (Times New Roman digit over the 7 card of each suit; redo by an artist later). Hands are shown as separate cards in 1-2 rows with wider gaps between suits, on the Preferans green cloth.
   **Bidding box = one-tap grid (concept A, chosen 2026-11-07)**: appears only on the human turn, shows a row per level that still has a legal bid, Pass/X/XX underneath; the middle of the screen is a scrollable list of the calls made so far, the human hand stays visible. Concept B (coach suggestions + "All bids") planned on top later.
   Checked by playing a full board on an emulator (screenshots), not yet on a phone. Not yet: coach, undo,
   animations, landscape layout, tablets, accessibility labels.
4. Season layer: new game id, seeded deals, matchpoint comparison, leaderboard.
5. Real bots: **rule-table SAYC bidding with explanations** (see 8a) + double-dummy / Monte Carlo player
   (check solver licenses). Then the coach / training mode on top of the same rule table.
6. Online async play: 4-seat host session, partnership seats, dummy visibility; fix known lobby issues.
7. Strings (en/es/ru), tutorial, card art, store assets; second bidding system.
8. Bidding-system builder (later).

## 12. Open questions list
- Decided: new project is a clone of PrefAndroid (C:\Old\BridgeAndroid), history kept, remote removed.
- Reviewed: 4-seat partnership is a large change (see section 10). Confirm the roadmap order.
- Package name / applicationId / app name for the bridge app.
- Baseline for single-player matchpoints (section 5).
- Which bidding system(s) first (SAYC recommended).
- Own bot vs. existing open-source engine.
- Boards per day / per season, and play-time limits.
- Should the app teach beginners (tutorial, hints)? The author is a beginner in bridge himself.

## 13. SAYC-lite bidding (built 2026-11-07)
Code: `model/sayc/` (`Bidding.kt` framework, `SaycRules.kt` rule table, `Sayc.kt` fallback + `SaycBot` + `explain`).
- **Rule table** (data-like): each `Rule` = id, call, HCP range, suit-length needs, balanced flag, situation
  (a function of the auction), extra choosing conditions. Order = priority. The same table chooses the bot's call
  AND explains any call (`Sayc.explain`), so bot and coach can never disagree.
- **Covered**: opening bids (1-suit 5-card majors/better minor, 1NT 15-17, 2NT 20-21, strong 2C with waiting/positive
  responses, weak twos, 3-level preempts), responses to 1-suit (raises, new suits, 2/1, 1NT/2NT/3NT), responses to
  1NT (Jacoby transfers, Stayman, invites, 3NT, simplified slam) with opener answers, opener rebids, overcalls
  (suit, 1NT), takeout doubles and answers, raising partner's overcall.
- **Fallback** (`Fallback.decide`) for everything else: combined-points + fit logic using what partner's calls
  promise (derived from the rule table): game at ~25 points (4M / 3NT / 5m), invite at 23-24, compete with a fit,
  game-forcing after 2C.
- **UI**: tap any call in the auction list to see what it means (text in en/ru/es: `sayc_<ruleId>` strings kept by
  `res/raw/keep.xml`, ranges like "12-21 points . 5+ H").
- **Checked**: 48 unit tests incl. 2000 simulated boards (about 3% passed out, 60% part-scores, 37% games, no slams
  worth mentioning, never an illegal call). 25 sample auctions reviewed by eye: plausible.
- **Known gaps (next steps)**: Blackwood/Gerber and slam bidding, negative doubles, penalty doubles, 2/1 game
  force follow-ups, Michaels/Unusual NT, responses when opponents interfere beyond the basics, 4th-seat/balancing
  rules, preempt responses, texas transfers, minor-suit Stayman, Jacoby 2NT, splinters, game-try bids.
  Games are a little too frequent (37%); a real SAYC bot is much more careful about stopping in part-scores.
- **Card play is still random** (`SaycBot.chooseCard`). The next big step is a real card-play engine.

## 14. Card play (built 2026-11-08)

Code in `model/play/`. The bots now play the cards with a sampling player instead of at random.

How a bot picks a card (`CardPlayer.choose`):
1. It reads only its `BotView` (own hand, the dummy once exposed, the tricks so far), so it never sees a hidden hand.
2. It deals the unseen cards to the hidden hands at random, respecting voids (a player who did not follow suit has none of that suit) and the number of cards each hand still holds. Up to 100 such "worlds" per decision (600 ms limit).
3. For each legal card (equal cards, e.g. K and Q together, counted once) it plays the card in every world and finishes the deal:
   - with 5 or fewer cards left in each hand, exactly (alpha-beta double-dummy search);
   - earlier, with a quick rule-of-thumb policy (`Heuristic`): lead winners or the long suit, draw trumps as declarer, second hand low, third hand high, cover an honor, ruff when the opponents win, keep winners when discarding.
4. It plays the card with the best average number of tricks for its side. When leading it plays the top of a sequence.

Measured on the desktop JVM with 100 worlds: about 1 ms on average and 162 ms at worst (first calls, cold JIT). A phone is several times slower, so check there before adding more worlds or a deeper exact search. Against random card play, with the same 58 boards played from both sides, the sampling side took 67% of the tricks (1007 against 501).

Known limits:
- Rollouts use the heuristic policy, so early decisions are only as good as that policy.
- No signals or leads by convention (no attitude/count signals, no "fourth best" reading), and no inference from the opponents' bidding (hands are sampled without using the auction).
- No bluffing or deceptive plays; the declarer plays the best average line, not the best line against a clever defender.

Next ideas: use the auction to bias the sampled hands (the SAYC rule table can say what a bid shows), standard defensive signals, a difficulty setting (worlds and exact depth).

### 14a. The bidding as information (2026-11-13)

`BidInference` (model/sayc) tells the sampler which hands are plausible for a seat: a hand is plausible if the bidding system, given that hand, would have made exactly the calls the seat made. It replays the rule table (and the fallback) for each call the seat made, so one mechanism covers every opening, response and also passes ("East passed" excludes strong hands). A call made in a position the rule table does not cover gives no information.

The sampler deals the hidden hands (respecting voids), tests them with `BidInference`, and re-deals up to 150 times per world until every hand fits. If no deal fits (for example a human bid off-system) it falls back to a deal that only respects the voids, and after 3 failed worlds in a row without a success it stops trying for that decision.

Measured (SaycBot, 40 worlds, 116-680 boards played from both sides):
- The true hands always fit their own bidding (400 boards, all four seats), so the replay is exact.
- About 94% of the sampled worlds fit the bidding; the rest are fallbacks.
- Playing strength: no measurable difference in tricks (+12 tricks over 116 boards; exactly equal over 680 boards; score edge within +/-15 points per board). The bidding in SAYC-lite is mostly wide-range, and the rollout policy is simple, so extra knowledge about the hands rarely changes the chosen card.
- Speed: average decision 4 ms, worst 608 ms (the 600 ms limit) on the desktop JVM.

So the mechanism is correct and cheap, but it is not yet a visible gain. It should matter more with narrow bids (1NT, preempts, strong 2C), a better rollout policy, and in the coach mode (explaining "West must have the king for his bid").

## 15. What is next (2026-11-13, replaces roadmap items 4-8 above)

Done so far: engine and scoring, solo table screen, one-tap bidding box, SAYC-lite bidding with "what does this call mean", card-play bots (sampling, exact endgame, reads the bidding). Commits up to 6e3c6b7.

Proposed order (S = hours, M = a day or two, L = several days):

1. **Play it for real (S)**: install on the emulator and a phone, play 10+ boards with the new bots. Check bot thinking time on a phone (the 600 ms limit), add a small "thinking" indicator, fix whatever the screens show. Nothing below should start before this.
2. **Solo session with matchpoints (M)**: 16 boards in a row, a score table, and a matchpoint percentage without any server. Baseline: the same board is played by bots at all four seats a number of times (say 30 tables, different seeds) and the human's score is ranked against those results. This is also the base for seasons.
3. **Speed of play (S-M)**: claim/concede ("I take the rest"), undo of a misclick before the next bot moves, an "auto-play last card" option. Needed for "fast and effortless".
4. **Coach / training mode (M)**: before each bid a hint button (rule-table suggestion plus the reason), after each bid a comment if it differs from the system; in the play "this card was best in N% of sampled worlds". All data already exists (rule table, `BidInference`, `CardPlayer` averages).
5. **Bidding upgrades (M-L)**: Blackwood and slam bidding, negative doubles, 2/1 follow-ups, responses after interference; tune how often games are bid (37% in the simulation, a bit high). Each new convention is a data row plus a text in en/es/ru.
6. **Season layer (M)**: ScoreServer game `bridge` registered (needs the VPS), the monthly seed, seeded deals/bots, the season menu, the leaderboard of matchpoint percentage. Reuses SeasonRandom, ScoreClient and the Preferans season screen.
7. **Menu, strings, tutorial, store (M)**: strings for all new screens (en/es/ru), delete the ~300 unused Preferans strings, icon, Play/AdMob/IAP ids, privacy policy, a short "how to play" for beginners.
8. **Online asynchronous play (L)**: 4 seats, partnerships, dummy visibility, confirmation and auto-confirmation; fix the 3-4 seat assumptions in LobbyClient/Protocol.
9. **Later**: second bidding system, bidding-system builder, better card art, landscape/tablet layout.

Recommended first release (single-player seasons): items 1, 2, 3, 6, 7, with 4 and 5 as the next updates.

### 15a. Items 2 and 3 built (2026-11-13)

**Solo session with matchpoints** (`model/session/Baseline.kt`, `ui/solo/SoloViewModel.kt`, `SoloScreen.kt`):
- A session is 16 boards from one random seed; the screen shows "Board n / 16", the running average, and at the end a summary (board, contract, result, score, percent) with "New session". The session is not saved yet: leaving the screen loses it.
- Each board is ranked against a field of 20 bot tables playing the same deal, computed in the background while the human plays (about 0.5 s per board on a PC; the result dialog shows "calculating" until it is ready). The matchpoint percentage is `Scoring.matchpointPercent(score, field)`.
- The bots bid by the same system, so to get different contracts each field bot gets a random boldness: a shift of -2..+2 (triangular, mostly 0) added to its point count (`HandInfo.hcpShift`), and its card play is sampled with 8 worlds. The same seed and board always give the same field. The human always sits South (north-south), as the field scores are north-south scores.
- Limit: the field is made of the same kind of bot, so it is a rough stand-in for human opponents. When seasons arrive the baseline can become the real results of other players.

**Speed of play**:
- *Claims* (`model/play/Claims.kt`): at the start of a trick with 6 or fewer cards left in each hand, an exact solver checks whether one side wins every remaining trick. To avoid giving away a lucky finesse (the solver knows all four hands), the claim must also hold in 16 other layouts of the opponents' cards (same hand sizes, no suit they showed out of). If it does, the board ends at once with "The rest of the tricks was sure". Average check 0.8 ms, worst 135 ms on a PC.
- *Concede* button (with a confirmation) gives all remaining tricks to the opponents.
- *Undo*: the last human move can be taken back as long as no bot has answered it (the bots wait 0.9 s after a human move, and the Undo button shows during that time). Undo replays the recorded calls and cards. It must be switched off for seasons.
- *Forced cards*: when the human has one legal card it is played automatically after a short pause (also for the dummy).

Tested: 64 unit tests (field is repeatable and varied, claims are really sure when the claiming side plays correctly, the solver matches a brute-force search). Smoke-tested on the tablet emulator: new counter, undo button, concede dialog, result with matchpoints. Not tested on a phone, and not tested: a full 16-board session to the summary, the claim message on screen.

## 16. Release 0.2.0: ads, Remove ads, Top players (Nov 2026)
- Interstitial after boards (not the first 10, at most one per 45 s), UMP consent, "Remove ads" (`remove_ads`, one-time product), rate banner every 10 boards until rated: the code was already copied from Preferans; this release connects it to the solo table.
- Top players: ScoreServer game `bridge`, board `alltime`, score = average matchpoint percentage of a finished 16-board session x10 (best per player). Strictly opt-in: asked once after the first finished session; the "Top players" screen can switch it off. Results queue in `pending_scores.json` and are sent when online; nothing is queued unless the choice is "post".
- Needs before upload: game `bridge` registered on the VPS (`register-game bridge "Bridge Master" --max-score 1000 --min-interval 30`) and `scoreApiKey` / `scoreSecret` in the root's gitignored `score-secrets.properties` (or `~/.gradle/gradle.properties` / CI `-P` properties); AdMob ids for Bridge in `~/.gradle/gradle.properties` under `bridgeAdmobAppId` / `bridgeAdmobInterstitialId` (that file is shared with Preferans, whose `admobAppId` / `admobInterstitialId` releases up to 0.3.2 wrongly used; the build now refuses the Preferans values) and the Play product `remove_ads`. Never put credentials in the tracked root `gradle.properties`.
- Privacy policy and Data safety CSV (`store/data-safety-ads-leaderboard.csv`) describe exactly this.

## 17. Several bidding systems and the system builder (2026-11-19)
- **A system is a convention card** (`model/sayc/SystemConfig.kt`): 1♣ style (natural / strong Precision / Polish), 4- or 5-card majors, lightest opening, 1NT and 2NT ranges, 2♦ (weak / Multi / short diamonds), 2♥/2♠ (weak / Polish 5+4), Stayman, transfers, 2/1 game forcing with the forcing 1NT, Jacoby 2NT, notrump answers (standard / Acol limit). `normalized()` makes any combination consistent (for example a strong club caps 1NT at 15). Serializable.
- **`RuleBuilder`** builds the rule table from the card (the old `SaycRules` table, now with ranges derived from the options plus branches for the strong club, the Polish club, the Multi, 2/1 and Acol). `BiddingSystem.of(config)` is cached; `Seating(ns, ew)` gives each side its own system, so every call is chosen, explained and read for card play (`BidInference`) in the system of the side that made it. Rules may be `gameForcing`; the fallback keeps bidding to game after one (replaces the hard-coded "after 2♣").
- **Presets**: Standard American (the SAYC preset reproduces the old table exactly: same calls and explanations on 1000 boards, checked with a fingerprint before the refactor), 2/1 Game Forcing, Acol, Precision Club, Polish Club. Depth: openings and the first round or two of answers per system; later rounds go to the points-and-fit fallback as before. Not built yet: relays after Precision's positive answers, the full Polish 1♣ follow-ups, defence against strong clubs (the opponents' artificial 1♣ is overcalled as if natural).
- **Player's systems**: the builder screen (`ui/system/SystemBuilderScreen.kt`, from Settings) starts from a preset, changes options, previews the openings from the real rule table and saves to `systems.json` (`SystemCatalog`). Settings choose "your system" and "the opponents' system" (`AppSettings.biddingSystem` / `opponentSystem`); a session reads them when it starts, and the bot field for matchpoints uses the same seating.
- **Texts**: call meanings (`sayc_<id>`) no longer contain numbers; the popup and the guide show each system's own range under the text. The bidding guide shows only the sections of the player's system (strong club, Polish club, special two-level openings, 2/1, Acol) and lists the system's calls under each section (`BiddingGuide.table`).
- **Tests**: every preset bids 1500 boards legally with sensible results, mixed systems at one table, every hand has an opening in every preset, 30 random convention cards bid legally, typical hands per system, every rule id has a guide section and a meaning in en/es/ru.
- **Seasons (decided 2026-11-19)**: a season never makes players learn a system. The player and partner always play the player's own system from the settings; the opponent bots play a system drawn at random for the whole season (`SystemCatalog.seasonSeating(seed)`, purpose "opponent-system" of the season generator, picked from the five presets), the same for every player of that season. In normal play both systems stay as chosen in the settings.
- **Later**: a free rule editor for advanced players (agreed: convention card first, editor later); deeper Precision/Polish follow-ups.

## 18. Seasons (built 2026-11-19)
- **Format (decided)**: one continuous run per monthly season through a deal sequence shared by every player (`Deck.deal(SeasonRandom(seed, "deal", n))`), played in 16-board sessions as often as the player likes; each board once, no restarts. The bots are seeded per deal (`"bots"`), the opponents' system per season (`"opponent-system"`), the player keeps their own system.
- **Scoring (decided)**: each board is ranked in matchpoints against the bot field of that deal (built with the same seating, so it is the same for everyone with the same system); it scores percentage − 50. The season total is the sum, posted to the running-total board `total:<season id>` (negatives allowed, latest replaces) after every board, only if the player agreed to appear on leaderboards (same opt-in as Top players; the Season screen offers it).
- **Help (decided)**: no advice and no undo; tapping a call still explains it.
- **Code**: `model/session/Season.kt` (`SeasonState` in `season.json`, `Seasons`: refresh from the server, scoring once per board, posting, the board cache, moves as text and replay), the season mode of `SoloViewModel` (`PlayMode.Season`: season seed and deal numbers, every move saved so a board continues where it was left), `ui/season/SeasonScreen.kt`. The screen's own writes (board, posted total, dates) re-read the file so they never put back an older copy of the game's progress.
- **Needs before release (VPS)**: `npm run admin -- set-rotation bridge monthly` (seasons with a `{"seed": …}` payload every UTC month) and `npm run admin -- set-limits bridge --max-score 100000 --min-interval 30` (a season total in tenths soon passes 1000). Check a season → board round trip on production.
- **Later**: the rank of the player beyond the top 10 (`around/{player}`), a season history, comparing against real players' results on the same board instead of the bot field.

## 19. Online game (built 2026-11-20)
- **Model (as in Preferans)**: host-authoritative. The host's phone runs the board (`ui/online/OnlineHost.kt`: engine, bots, claims, bot field); the PrefServer relay only seats players and passes JSON. Room seats go clockwise from the host: 0 South, 1 West, 2 North (partner), 3 East. Empty seats are bots; the host moves players with the arrows before the start.
- **Decided with the author**: scoring = matchpoints against the bot field, 16-board sessions (like solo, nothing posted to Top players); systems = the host picks one for N-S and one for E-W when creating the game (defaults from the settings, custom systems travel in the room rules); helpers = call meanings on tap only (no advice, no guide, no undo, no concession). No ads in online games.
- **Views**: every player sees the table from South (`turnedFor`): seats, dealer, vulnerability, scores and matchpoints are turned, and the two sides swap for East-West (their score has the sign changed, their percentage is 100 minus North-South's). Seat labels carry the player names. Guests get only what they may see (own hand, dummy after the lead).
- **Messages** (`ui/online/OnlineWire.kt`, discriminator `t`): host → guest `state` with the whole turned table; guest → host `act` with a call/card (season move texts), `next` or `sync`. Every state carries a `step` (moves of the session); a move made on another step is ignored, so a double tap or a late resend can never become a later move.
- **Next board**: everybody online presses it (offline players are not waited for); the room's auto-continue (off/5/10/30 s) presses it for all. After board 16 the session result is shown; only the host starts the next session.
- **Leaving / reconnecting**: Back in a room leaves it; in a game it asks first (the host's leaving closes the game). A guest whose app was closed gets the seat back on reopening the Online screen (server grace) and the host resends the table; meanwhile the others see "Reconnecting: name".
- **Server**: `hello` carries `game: "bridge"`; PrefServer keeps rooms of each game apart (missing = `"pref"`, so old Preferans apps are unchanged). Must be deployed before a Bridge release with online play, or Preferans players would see Bridge rooms. Debug builds can use a local relay with `-PbridgeRelayUrl=ws://10.0.2.2:8091/ws`.
- **Tested**: unit tests (wire round trip, turning, explanations in the turned view, a whole board with a remote guest); two emulators against a local relay (lobby, seat swap, bidding and play, waiting texts, board result mirrored for E-W, host leave, guest reconnect).
- **Later**: save and resume a hosted game; a bot taking over the seat of a player who left; chat.
