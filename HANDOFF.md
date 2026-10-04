# FlowPay — handoff

Everything the next session needs to work on this app without relearning it the
expensive way. Written 16 September 2026, at `v3.12.0` / 1002 tests; brought up
to date 3 October 2026 at `v3.13.0` / 1096 tests (see §15 for what changed), and
again at the end of 4 October 2026 at `v3.16.1` / 1180 tests. **Start with §19**:
where things stand and what to do first.

Nearly every rule below exists because breaking it cost something real — a failed
release, a silent data loss, three hours of the owner waiting. Where that is so,
the cost is written down next to the rule. Read those parts especially.

---

## 1. What this is

A personal Android app for one person. Not a product, no other users, no store
listing. It does five things:

| Tab | What it holds |
|---|---|
| Бажання | A wishlist with scraped prices, price history, multi-shop tracking |
| Курс | The USD rate, its 30-day history, a threshold alert |
| Платежі | Subscriptions and recurring bills, paid marks, trials, annual items |
| Покупки | Parcels tracked through Nova Poshta |
| Огляд | Budget, savings plans, monthly recap, bin, backups, settings |

**Owner:** Ukrainian, not a programmer. Phone: Redmi Note 14, HyperOS, Android 16.
They cannot read the code to check what you tell them — so what you say about the
app has to be true, and "it should work" is not a report.

**Stack:** Kotlin, Jetpack Compose, Material 3 `1.4.0`, Compose BOM `2026.05.00`,
AGP `8.9.1`, Kotlin `2.1.0`, `compileSdk`/`targetSdk` 36, `minSdk` 26, JDK 17.
No database — everything is JSON in `SharedPreferences`.

**Repo:** `github.com/gerasymchuksergiy/flowpay`, branch `fix/production-readiness`.
**The repository is public.** That is load-bearing for anything secret.

---

## 2. The code, file by file

46 Kotlin files, ~28,500 lines. The shape is deliberate and worth understanding
before adding to it:

> **Pure logic lives in the small files. Compose lives in `MainActivity.kt` and
> `Components.kt`.** Everything that can be decided without a screen is a plain
> function over plain data, and it has a unit test. This is why 1180 tests can
> cover an app with no instrumented tests at all.

When you add a feature, the arithmetic goes in a small file with tests, and only
the drawing goes in the Compose files. An agent that put its logic inside a
composable would have shipped it untested.

### The two big Compose files

| File | Lines | What is in it |
|---|---|---|
| `MainActivity.kt` | 9333 | Data models, the `Store`, all seven screens, all eight sheets |
| `Components.kt` | 2090 | Every shared composable |

`MainActivity.kt` is large because it holds four things that would each be small:

1. **The data models** — `Wish` (~149), `WishSource` (~110), `Pay` (~257),
   `Order` (~309). Read these first; almost every question starts here.
2. **The JSON mappings** — `wishJson`/`wishOf`, `payJson`/`payOf`,
   `orderJson`/`orderOf`. See §7.2; these are the single most dangerous place to
   be careless.
3. **The `Store`** — one `SharedPreferences` file called `flowpay`. Keys in use:
   `w` (wishes), `pay`, `orders`, `bin`, `paid` (paid marks), `income`,
   `wish_sort`, `fx_*` (rate, its day, source, buy/sell), `fxh` (rate history,
   Monobank readings only — §11),
   `fxt*` (rate threshold), `hol` (holidays), `digest_h` (digest hour),
   `reminded`, `recap`, `pill`/`pill_day` (dismissed status note), `bk_dir`/`bk_at`
   (backup folder and time), `tile`.
4. **The screens** — `WishlistScreen`, `WishDetailScreen`, `CalculatorScreen`,
   `PaymentsScreen`, `OrdersScreen`, `OrderDetailScreen`, `SettingsScreen`; and the
   sheets `AddWishSheet`, `EditWishSheet`, `AddSourceSheet`, `AddPaymentSheet`,
   `EditPaymentSheet`, `AddOrderSheet`, `BoughtSheet`, `CloseOrderSheet`.

`Components.kt` holds what more than one screen needs: `PriceChart`,
`PriceRangeBar`, `RebasedPriceAndRate`, `StageRail`, `HeroPanel`, `LeaderRow`,
`DottedLeader`, `DaysStrip`, `CollapsingTitle`, `SegmentedControl`, `FormSheet`,
`StatusPill`, `VerdictChip`, `PhotoHeader`, `BusyMark`, `CollapsibleSection`,
`CardFold`, `ClampedText`.

### The wishlist and prices

| File | Lines | Holds |
|---|---|---|
| `Parsing.kt` | 1334 | Everything that reads a shop page |
| `Wishes.kt` | 1194 | What a wish *is* and what its state means |
| `History.kt` | 611 | Price history and what it implies |
| `Browse.kt` | 288 | Filtering, searching, sorting, category totals |
| `About.kt` | 62 | Folding the product description |
| `Compare.kt` | 249 | The Hotline search query |
| `Appraisal.kt` | 1383 | The AI review (see §11) |

- **`Parsing.kt`** — `extractOffers` and its four strategies, `Offer`,
  `Availability`, `matchOffer`/`OfferMatch` (the remembered edition on a
  multi-price page), `extractAbout`/`ProductAbout`, `decodeEntities`,
  `cleanProductTitle`, `priceNumber`, and the NBU/Monobank rate readers.
- **`Wishes.kt`** — `Freshness` (OK / UNREADABLE / OUT_OF_STOCK / GONE / MANUAL)
  and `isStale`; `Reading` and `SourceReading`, the three-outcome results that keep
  a dropped connection from being mistaken for a dead page; `wishSources`,
  `bestSource`, `sourceFreshness`, `mergeSources`; `targetHit`, `purchaseReview`,
  `lowestTracked`, `onHold`, `wishGoal`, `firstPrice`.
- **`History.kt`** — `PricePoint` (price, day, **and the exchange rate that day**),
  `appendPrice` (writes **only on a change**, which is why charts must be stepped —
  see §11), `priceInsight`/`PriceInsight` (the 30-day reference window, the
  all-time low, `position` behind the range bar), `priorLow`, `inDollars`,
  `currencyMoveNote`, `appendRate`, `rateToKeep`, `RateTarget`.

### Money and time

| File | Lines | Holds |
|---|---|---|
| `Payments.kt` | 1412 | Subscriptions, the month, all number formatting |
| `Savings.kt` | 137 | Savings plans |
| `Overview.kt` | 129 | The one place that answers "how am I doing" |
| `Holidays.kt` | 141 | Ukrainian holidays, so a charge shifts **backwards** |
| `Recap.kt` + `RecapDeck.kt` | 814 | The monthly recap |
| `Csv.kt` | 202 | The spreadsheet export |

`Payments.kt` is the most-used file in the project — 79 functions. It owns
`money`, `figure`, `approxMoney`, `monthKey`, and therefore every number the app
prints. It also owns `nextPayment`, `nextCharge`, `monthlyTotal`, `yearlyCost`,
`yearlyCommitment`, `stillOwing`, `PaidMark`/`isPaid`, `onTrial`, `billingMonth`,
`budget`, and the warn-days machinery.

### Parcels

`Purchases.kt` decides what *kind* of purchase an order is — a download
(`Order.digital`, guessed from the shop by `isDigitalStore`, correctable on the
add and edit forms), a Nova Poshta parcel (`isAutoTracked`), or a parcel another
post carries. The card and the parcel page ask it whether to draw the rail
(not for a download), the Nova Poshta blocks (`carrierSectionsApply`), a 17TRACK
link (`trackingPageUrl`), and what the close button says. **Every open purchase
can be closed at every stage** — it used to be possible only once the rail
reached «Отримано», which a Steam game or a Temu parcel under RL…EE never did.

`Tracking.kt` (885) is the Nova Poshta reader: `parseNovaPoshtaStatus`,
`stageForStatusCode`, `isProblemCode`, `problemNote`, `stageLabel`, `applyStatus`,
`Sighting` (the app's own observed history), `addressBeyond`/`alreadySaid` (the
functions that stop the detail page printing an address twice), and the three
date parsers.

### Background, notifications, the system

| File | Holds |
|---|---|
| `PriceWorker.kt` | Every 12 h: re-read prices and parcels |
| `ReminderWorker.kt` | The 09:00 digest |
| `BackupWorker.kt` | Weekly backup to the chosen folder |
| `Background.kt` | Whether background work is actually alive; `stripLine` — the strip shows only on trouble |
| `Digest.kt` | What the one morning message says |
| `Chrome.kt` | The status pill's single most pressing thing |
| `Backup.kt` | Export/import format |
| `Bin.kt` | The 30-day bin |
| `Intents.kt` | Share-into-app, open-in-shop |
| `Widget.kt`, `Tile.kt` | Home screen widget, quick settings tile |
| `HealthPanel.kt` | "Is anything broken?" on the Огляд tab |

### Look and feel

`Theme.kt` (588) is the design system — read §9. `Shapes.kt` (190) is the
`graphics-shapes` morph for a wish that reached its target price. `Haptics.kt`
(108) is the five-strong semantic vibration vocabulary.

---

## 3. How the data flows

### Adding a wish

```
link → pageHtml() → extractOffers()      four strategies, in order
                  → matchOffer()          if the page lists several editions
                  → wishFromOffer()       → Wish
                  → extractAbout()        → ProductAbout
     → store.saveWishes()  →  wishJson()  →  SharedPreferences "w"
```

If no price is found: one retry, then the add still succeeds on the title and
photo with `Freshness.MANUAL` and a price you type. **The flow never fails on a
missing price** — that was the Temu bug.

### A price refresh (every 12 h, or on demand)

```
PriceWorker → for each wish → for each source → pageHtml()
                                              → readSource() → SourceReading
            → mergeSources()   cheapest source that answers wins
            → appendPrice()    only if the price actually changed
            → priceAlertFor()  target hit / back in stock / new low
```

Three things that look like details and are not:

- A source that **times out** keeps its previous price. Otherwise a bad connection
  looks like a discount.
- A **sold-out** page's price never enters history, never fires an alert, never
  counts as a low, and never wins `bestSource`.
- `appendPrice` writes **only on a change**, so history is a list of events, not a
  time series. Everything downstream must treat it that way.

### A parcel refresh

```
PriceWorker → getStatusDocuments → parseNovaPoshtaStatus → ParcelStatus
            → stageForStatusCode → one of PARCEL_STAGES (or "" for a problem)
            → applyStatus        → appends a Sighting only on a real change
```

### The morning message

```
ReminderWorker → digest(wishes, pays, orders, paid, holidays, rateTarget, …)
               → paymentLines  (stillOwing → remindersDue → nextCharge)
               → parcelLines   (skips archived)
               → priceLines    (skips held and stale)
               → amountLines   (a subscription that changed price)
               → rateTargetLine
```

Everything non-urgent is batched here. Only three things interrupt immediately: a
target price hit, something back in stock, and storage expiring tomorrow.

### The appraisal

```
appraisalGate()  ← pure, before any network call, on data only
      ↓ READY
Gemini generateContent, tools: [googleSearch]      price and rating NOT sent
      ↓
readAppraisal()  ← rejects price/rating claims; rejects hearsay when ungrounded
      ↓
Appraisal (text, sources, query count, price when written) → wishJson "ap"
```

---

## 4. Where to look for a given thing

| If you need to change… | Start at |
|---|---|
| What a price means, or a verdict | `History.kt` → `priceInsight` |
| How a page is read | `Parsing.kt` → `extractOffers` |
| Whether a wish is trustworthy | `Wishes.kt` → `Freshness`, `isStale` |
| Anything a number looks like | `Payments.kt` → `money`, `figure` |
| The month, a bill, a trial | `Payments.kt` → `nextCharge`, `stillOwing` |
| A parcel's stage or wording | `Tracking.kt` → `stageForStatusCode`, `stageLabel` |
| What interrupts the owner | `Chrome.kt` (pill), `Digest.kt` (morning) |
| A colour, a size, a spring | `Theme.kt` — and nowhere else |
| A shared piece of UI | `Components.kt` |
| A screen's layout | `MainActivity.kt`, the `*Screen` functions |
| What survives a restore | `wishJson`/`wishOf` and friends — §7.2 |
| Whether background work runs | `Background.kt`, `HealthPanel.kt` |

**Tests are named after the behaviour, not the file.** Looking for how something
is meant to work, grep the test names first — `StatusLadderTest`,
`WishSourcesTest`, `SubscriptionTest`, `RecapTest`, `AppraisalTest`,
`AvailabilityTest`, `CurrencyTest`, `BillingPeriodTest` are the ones that encode
the most decisions.

---

## 5. Building

Nothing is on PATH. Every build sets its own paths.

```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-17.0.20.101-hotspot"
export ANDROID_HOME="/c/Users/GS PC/AppData/Local/Android/Sdk"
export JAVA_TOOL_OPTIONS="-Djdk.net.unixdomain.tmpdir=C:\Temp"
export GRADLE_OPTS="-Djdk.net.unixdomain.tmpdir=C:\Temp"
echo "sdk.dir=C:/Users/GS PC/AppData/Local/Android/Sdk" > local.properties
./gradlew testDebugUnitTest lintDebug assembleDebug \
  -Duser.language=en -Duser.country=US -Duser.timezone=UTC
```

Three things in there are not optional:

- **`local.properties` is gitignored**, so a fresh worktree has none. Write it.
- **The `unixdomain.tmpdir` variables.** A forked JVM on this machine cannot open
  its NIO pipe under `AppData\Local\Temp`; Gradle reports it as
  `Unable to establish loopback connection`. `C:\Temp` works.
- **The sandbox must be disabled** (`dangerouslyDisableSandbox: true`). The Gradle
  daemon needs loopback sockets the sandbox blocks.

**Run all three tasks, always.** `testDebugUnitTest lintDebug` alone once passed
locally while CI failed: only `assembleDebug` packages the app, and only it
catches Compose compilation errors. Tests and lint do not build an APK.

**Read Gradle's own exit code.** A `| grep` in the pipeline reports grep's status,
not Gradle's. That is how "tests and lint passed" was once said about a failed
build.

The 44 test files under `app/src/test/java/com/flowpay/app/` are plain JVM unit
tests over the pure functions. There are no instrumented tests and no device in
the loop — **nothing in this app has ever been verified by looking at it.** Say so
when it matters.

**Screenshots without a phone (added 3 October 2026).** `ScreenShots.kt` in
`app/src/test/java/com/flowpay/app/screens/` renders every tab of the real app,
seeded with sample data, through Robolectric's native renderer and saves PNGs to
`app/build/outputs/roborazzi/`:

```bash
./gradlew :app:testDebugUnitTest -Pshots
```

Same environment as above. Without `-Pshots` the screens are excluded, so CI never
renders anything. Pinned on purpose: Roborazzi **1.60.0** (1.61+ is built with
Kotlin 2.3, unreadable by this project's 2.1) and Robolectric `sdk = 34` (36 needs
Java 21; 35+ also breaks on the space in the user folder path, which is why
`maven.repo.local` points at `C:/Temp/robolectric-m2`). It is not a phone: no
status bar, no HyperOS, blur and shaders unproven — but the composables, fonts
and data are real, and the first renders matched the owner's own screenshots.

---

## 6. Shipping

```bash
git push origin fix/production-readiness
git tag -a vX.Y.Z -m "…"
git push origin vX.Y.Z
```

CI builds, signs with the phone's key, and publishes one asset named
`FlowPay-vX.Y.Z.apk`. The workflow derives `versionCode` from the tag as
`major*10000 + minor*100 + patch`, and fails rather than publishing unsigned.

### Waiting for it — do not poll the API

```bash
until curl -sI -o /dev/null -w "%{http_code}" -L \
  "https://github.com/gerasymchuksergiy/flowpay/releases/download/vX.Y.Z/FlowPay-vX.Y.Z.apk" \
  | grep -q "^200$"; do sleep 45; done
```

Polling `api.github.com` every 20s exhausts the unauthenticated limit (60/hour).
The API then answers 403, an `until … grep -q '"tag_name"'` loop can never match,
and it spins forever. **That happened. The owner waited three hours for a release
that had failed in its second minute, because "the tag is pushed" was reported as
"it shipped".**

When the API is genuinely needed — reading why a run failed — authenticate with
the credential already stored for pushes, and never print it:

```bash
TOKEN=$(printf "protocol=https\nhost=github.com\n\n" | git credential fill \
  | sed -n 's/^password=//p')
curl -s -H "Authorization: Bearer $TOKEN" https://api.github.com/repos/.../actions/runs
```

### Before saying it shipped

Download the APK and check three things:

```bash
apksigner verify --print-certs FlowPay-vX.Y.Z.apk   # SHA-256 must be 00e2a967…bca6e
aapt2 dump badging FlowPay-vX.Y.Z.apk | head -1     # versionCode and name
```

The signature must be `00e2a9675a5cce774a5485ed2f0bf998eff83005a9a54d81d6aef2ea261bca6e`
— the phone's existing install was signed with the local **debug** keystore, so
CI signs with the same key (alias `androiddebugkey`, type `jks`, from repository
secrets). A different signature means the phone refuses the update and the only
way forward is uninstall-and-lose-everything.

Grepping the DEX for a new string is a cheap way to prove the build really
contains the change. Two APKs once had identical byte sizes by coincidence; the
DEX sizes differed by 4,316 bytes and the new strings were present.

### If CI fails

Two failures have happened and both were infrastructure, not code:

- **`setup-android` asking for the removed `tools` package.** Fixed by
  `packages: ""` — the platform and build-tools this project needs are installed
  explicitly on the next line anyway.
- **A malformed secret pasted into a generated Java string literal.** See §10.

---

## 7. The hard rules

### 7.1 Never format a number without a locale

`"%.1f".format(x)` reads the **phone's** default locale. CI is en-US, the phone is
uk-UA, so it passes locally and fails in CI — or worse, ships a screen mixing
`3.2%` with `2 199,50 ₴`. This cost a release; twelve sites had to be fixed at
once.

- Display figures: `figure(value, decimals)` — `Payments.kt`
- Percentages: `signedPercent(value, decimals)` — `History.kt`
- Money: `money()`, `approxMoney()` — `Payments.kt`
- Storage keys, API dates, clock faces: `Locale.ROOT`, explicitly

### 7.2 A new persisted field goes into **both** sides of its JSON mapping

`wishJson`/`wishOf`, `payJson`/`payOf`, `orderJson`/`orderOf` in `MainActivity.kt`.
These are also how the 30-day bin restores a deleted item and how backup/restore
works. **A field added to the data class but not the mapping is silently lost —
this happened three separate times.** The test that catches it round-trips a fully
populated object and asserts equality, not a spot check.

A **view** preference is not app data: follow `sectionOpen` / `rateTarget` /
`recapSeen` and keep it out of `exportJson`.

### 7.3 Numbers on screen come from the data layer

Never from anything generated. See §11.

---

## 8. Working with agents

Parallel worktree agents built most of this. What was learned:

- **A worktree agent is branched from `main`, which is far behind.** Every agent
  must be told the base commit explicitly and told to `git reset --hard` to it.
  They all hit this; they all recovered, because the brief named the commit.
- **Merge one at a time, and have each remaining agent rebase its own branch.**
  Never resolve a conflict in `MainActivity.kt` yourself with `--theirs`/`--ours`
  — that shortcut once silently discarded a whole agent's work, caught only
  because a symbol ended up declared twice.
- **Tell an agent to commit each green piece rather than holding everything.** A
  machine reboot once lost an hour of work because nothing was committed.
- **Ask for the reasoning, not just the result.** The best findings in this
  project came from agents that pushed back: that Material 3 Expressive's motion
  APIs were `internal`; that a status code meant the opposite of what the brief
  said; that `remindersDue` had the same fault one layer down.
- **Never `git add -A`.** Finished worktrees under `.claude/` once went into a
  commit — 403 files, 136,000 lines, alongside a 23-line change. `.claude/` is now
  in `.gitignore`; add files by name anyway.
- The scratchpad is **shared between agents**. Name files distinctly.
- **Another Claude session may be working in the same checkout.** On 4 October a
  second session fixed the rate bug while this one held uncommitted monobank
  work. Run `git status` before the first edit; if files you did not touch have
  changed, work in `git worktree add --detach .claude/worktrees/<name>` (it needs
  its own `local.properties`), commit there and hand over the hash to
  cherry-pick. Never a bare `git stash` — the stash stack is shared.
- **Do not start Edge or Chrome from a script on the owner's PC.** A headless
  Edge render on 4 October popped an error dialog («Не вдалося створити каталог
  даних») on the owner's screen, and they asked what had broken. Use the built-in
  browser pane, the JVM screenshots, or skip the look.

---

## 9. Design system — `Theme.kt`

Read its comments before drawing anything; they explain why each value exists.

- **One accent.** `Accent` lime `#d7ff63` on near-black `#0a0b09`. One lime thing
  per screen. Do not introduce a second accent for any reason — not for AI, not
  for a new section.
- **Bento tiles (owner's decision, 3 October 2026 — see §16).** Six pastels
  (`TILE_COLOURS`) are *containers*, not accents: a payment, a parcel, an
  overview figure sits on one. Dark ink on them (`TileInk`, `TileInkSoft` at 72%,
  `TileAlarm`), never `TextSecondary`/`Accent`/`Negative`. `inkOn(colour)` picks.
- **Display face:** Unbounded (`Display`), two static weights, for screen titles
  and figures only. It is wide: size figures with `displayFigureSize`.
- **Five surfaces**, five type levels, three radii, a 4dp rhythm. Pick from the
  scales; do not invent a value.
- **Typeface:** Inter, three static weights, subset, in `app/src/main/res/font/`.
  Variable axes work at API 26 but were declined: an axis the file lacks is
  ignored silently and every title quietly stops being bold.
- **Tabular figures** (`Tabular`) on every figure that sits in a column or gets
  rewritten — money in a running sentence keeps proportional digits.
- **Grain**: a static, seeded 128×128 noise tile at 10% over a tile capped at
  level 64. It is anti-banding, not decoration: near-black on OLED bands visibly.
  Never animate it.
- **`litEdge`** — a 1px light top border — is how a raised card separates itself.
  Near-black costs the shadow channel, and tinting surfaces with the one accent
  would wash the UI green.
- **Motion:** `Motion.spatial()` / `fastSpatial()` / `effects()`. These are the
  Material 3 Expressive spring values written out by hand, because `MotionScheme`
  is `internal` in material3 1.4.0 and public only in the 1.5 alphas, which are
  still churning. This decision is settled; do not revisit it.
- **Reduced motion** is honoured by snapping, not shortening. Compose applies the
  system setting to its own animations; what leaks is custom canvas work and
  `infiniteRepeatable`. A half-implemented reduced-motion mode is worse than none.
- **Haptics** (`Haptics.kt`): five semantic uses, none on ordinary taps. Google's
  own guidance: "given the choice of buzzy haptics or no haptics, choose no
  haptics."
- **Sections fold.** `CollapsibleSection` for screen sections, `CardFold` inside a
  card. A shut heading must say what is inside it, or people open all of them to
  find anything.

---

## 10. Secrets

The repository is public. Two separate exposures, do not confuse them:

1. **A key in the source** is found by GitHub's secret scanning, reported to the
   provider, and revoked. It stops working. Never commit one.
2. **A key in the APK** is extractable by anyone who downloads a release. The
   owner asked for this explicitly so that nothing has to be typed into the app,
   and that is their decision. The mitigation is a spend cap on the provider's side.

So: secrets live in **GitHub repository secrets**, injected at build time. The
signing keystore already worked this way; `GEMINI_API_KEY` joined it.

**Sanitise anything pasted into generated code.** `v3.10.0` failed to compile
because the secret held something multi-line with backslashes and it went straight
into a Java string literal. The guard now keeps a value only if it is one line of
characters that cannot break the literal. It deliberately does **not** check the
key's shape: the first attempt required `AIza…` and would have silently discarded
the `AQ.…` format Google also issues, leaving a feature dead with nothing saying
why. Guessing a provider's format is not the build's job.

---

## 11. Domain knowledge worth not rediscovering

### Scraping prices

Four strategies, in this order, and the order is the point — structured data is a
shop *declaring* a price; the last one is us *inferring* it.

1. JSON-LD (`offers.price`, inherited through `AggregateOffer`)
2. schema.org microdata (`itemprop="price"`)
3. Open Graph / `product:price:amount`
4. `data-*price*` attributes (minor units: `if (raw >= 10_000 && raw % 100.0 == 0.0) raw / 100`)
5. **Last resort, only when the page declared nothing:** a quoted string inside an
   inline `<script>` whose whole content is a figure next to a currency. Temu ships
   `"1 458.21₴"` this way and renames its keys between responses, so matching the
   value rather than the key is what survives.

Plus **one retry** on the no-price path: the same URL seconds apart often returns
a usable page.

**Availability is read too** (`Availability` in `Parsing.kt`). A sold-out page
often shows a lower placeholder price; before this was read, the app reported a
price *drop* on something nobody could buy. `PreOrder`/`BackOrder` count as in
stock — the shop will take money at that figure. `Discontinued` is its own state
because the advice inverts: stop waiting rather than keep waiting.

**A shop that merely times out keeps its previous price.** Otherwise a bad tunnel
looks like a discount, since dropping the dearest shop makes the wish look cheaper.

**What cannot be scraped:** Temu behind its captcha, hotline.ua (renders in JS,
serves 40 bytes to a plain client), Ukrposhta (no public JSON tracking at all).
For hotline we build a **search URL** — `https://hotline.ua/ua/sr/?q=…`, and note
`/ua/search/` is dead and returns "Legacy home controller has been disabled".

### Nova Poshta

`POST https://api.novaposhta.ua/v2.0/json/`, `TrackingDocument.getStatusDocuments`,
**empty `apiKey`**. Returns ~128 fields and **no movement history** — the scan-by-
scan list in their own app comes from an authenticated internal API. Do not invent
intermediate stops; the detail page says so on purpose.

Three different date formats in one response: `DateCreated` is `dd-MM-yyyy HH:mm:ss`,
`DateScan` is `HH:mm dd.MM.yyyy`, `TrackingUpdateDate` is `yyyy-MM-dd HH:mm:ss`.
Parse each explicitly; a parser lenient enough for all three eventually reads
`09-10-2026` as the wrong month.

All 21 status codes are mapped in `stageForStatusCode` and each is pinned by its
own test. Traps found the hard way: **11 is «переказ виплачено»**, i.e. received —
it was mapped to `ORDERED` and sent finished parcels back to the first dot. **12 is
«комплектує ваше відправлення»** — ordinary progress, and it was being announced in
red as a problem. **41** (local delivery) was missing entirely. `Common.getDocumentStatuses`
looks like the authoritative table and is **not**: it is the internal document-state
dictionary and its numbers collide with different meanings.

Two timestamps that look alike and are not: `DateScan` is when the carrier last
scanned the parcel; the app's own check time is when *it* asked. Showing only the
second made a parcel that had not moved in a day look freshly updated.

### Money APIs

- **Monobank** `/bank/currency` — asked first (`usdRate`): the bank's own buy and
  sell, the figures money changes hands at. Rate-limited to about one call a
  minute; a second call inside it answers 429.
- **NBU** `https://bank.gov.ua/NBUStatService/v1/statdirectory/exchangenew?valcode=USD&json`
  — no key, no rate limit. The **fallback** when Monobank does not answer, not the
  primary source: one official figure (buy = sell), shown labelled «НБУ» and used
  for conversions. It usually sits below the bank's sell rate, so it **never**
  enters the rate history (`appendRate`), never crosses, arms or promises the rate
  threshold (`rateTargetLine`, `armRateTarget`, `rateTargetNote`), and does not
  replace a Monobank reading under six hours old (`rateToKeep`). Both callers —
  the Курс screen and `PriceWorker` — go through `refreshUsdRate`. Before this a
  second refresh inside a minute swapped the bank's figure for the NBU's in the
  chart and the threshold; chart points from before it may still hold an NBU day
  until they age out (30 days).
- Monobank's **personal API** (`client-info`, `statement`, `currency`) is read-only
  by construction and cannot move money; statement is 1 request per 60 s, window
  31 days + 1 hour. Integrated on 4 October 2026 at the owner's request — §18.

### The appraisal (`Appraisal.kt`)

Gemini `gemini-3.1-flash-lite` with **Google Search grounding** (`googleSearch`
tool, `generateContent`). Billing is dominated by grounded queries — about $0.015
per appraisal — and how many searches one prompt fires is the model's choice, so
the query count is shown on screen next to the sources.

The design is shaped by measured evidence, not caution:

- Every shipped review-summary feature summarises a corpus its company **holds**.
  This app holds no review text, so a sentence in buyers' voice is invented.
- CHOICE (Australia) asked LLMs about specific products and checked against lab
  results: a recommended vacuum scored 25%, an air fryer was third-worst of 37,
  and two recommended baby products had **failed safety testing**.
- RAGTruth measured response-level hallucination at 29% for QA and **68.6% for
  data-to-text** — structured attributes plus thin text, generating prose. That is
  exactly this feature's input shape.
- Joren et al. (ICLR 2025): giving a model thin context makes it **less** likely to
  abstain, not more — 84.1% → 52%.
- Prompt wording recovers about a fifth of the gap. Scale buys nothing.

So the mitigations are structural, not instructional:

- **A sufficiency gate before any call.** Two of four grounds — description ≥120
  chars, ≥3 specs, a brand, a rating with ≥10 people behind it. Below that the
  section is *absent*, not hedged. This is what Amazon, Tripadvisor and Google all
  do, and the only thing that worked in the study.
- **The model is never sent the price or the rating.** That is the structural
  reason it cannot contradict the figures printed above it.
- **Answers are rejected in code**, after they arrive: any claim about price or
  rating, and hearsay about buyers when no sources were returned.
- **Sources are shown and openable.** An ungrounded answer is drawn visibly
  weaker, in the alarm colour, saying it is a guess.
- **"I could not find this model" is a valid answer** and is stored, not
  discarded. The whole failure mode is a model describing the *class* and letting
  the reader believe it describes the *item*.

---

## 12. Judgement calls already made — and why

Do not silently reverse these. Reopen them with the owner if you think they are
wrong.

- **No free-storage progress bar.** The app learns when paid storage starts, never
  the window's length. Any bar would invent its denominator.
- **No Sankey diagram.** Five subscriptions of similar size is a stacked bar with
  extra curves; width differentiation is the chart's whole mechanism.
- **No month-grid calendar.** Five dots in 35 cells reads as a bug. The horizontal
  day strip is the form that survives at this scale.
- **No confetti, no streaks, no celebration on a purchase.** Robinhood shipped
  confetti on trades, was cited by regulators for gamification, and paid $7.5M.
  Spending money is not unambiguously good news.
- **No "you'd have saved X" marker.** No shipped app does it; it would also be a
  reproach every time the screen opens.
- **No percentiles in the recap.** At n=1 any percentile is fabricated.
- **No "wasted money" label inferred from data.** You know money left; you do not
  know whether the thing was used.
- **No Liquid Glass / translucent panels.** `Modifier.blur` renders only on API
  31+ and is silently **ignored** below, so text would land on product photography
  with nothing behind it. The `PhotoHeader` scrim is the technique that works
  everywhere.
- **Amortised annual costs never masquerade as cash.** A ₴1 200/year subscription
  shows ₴1 200 in its renewal month, not ₴100 every month — a competitor publicly
  reverted to this, because the averaged figure shows a shortfall that is not
  there in the month the real charge lands.
- **The background-health strip on Огляд appears only when something is wrong.**
  It used to be permanent («Фонове оновлення працює» on every visit); the owner
  asked for it gone on 3 October 2026. The calm state is the «Фонове оновлення»
  row under Налаштування, which opens the same sheet (and the digest hour).
- **The paid-months history lives on Платежі** («Розклад | По місяцях»), not on
  Огляд. The owner looked for "how much did September cost" where the marks are
  made. The month just ended is always listed (`monthRecords(atLeast = 2)`).
- **The app does not nag about what you have dealt with.** Paid marks silence the
  pill and the morning digest; archived parcels and held wishes are excluded.

---

## 13. How to talk to the owner

- **Ukrainian**, always.
- **They cannot check the code.** Do not say a thing is done unless it is verified,
  and name what you could not verify. "Nothing here was seen on a screen" is a
  sentence that has to be said often.
- **Report the failures too.** They have been told about every mistake in this
  project — the three lost hours, the 403 files, the wrong status code — and the
  work went better for it.
- **Explain the reasoning, briefly.** They are not a programmer but they are
  making product decisions, and they have repeatedly improved on the plan when
  they could see the trade-off.
- When they ask for something that will not work, say so once with the reason,
  offer the nearest thing that will, and then do what they decide.
- **Pronouns.** The owner has not said which to use; write «they» in English and,
  in Ukrainian, prefer forms without grammatical gender («у тебе позначено»,
  «натисни») over a guessed «зробив/зробила».

---

## 14. Open items

(Updated 4 October 2026, end of day. The newest are first.)

- **monobank has met real data only at the account check.** On 4 October the
  owner connected a real token: name, four cards (two hryvnia, a dollar and a
  euro one — all four ticked) and balances came through. The first statement
  load then hit the ten-minute worker limit (fixed in 3.16.1, §18). Still unseen
  on real data: the questions «monobank: схоже, це оплати» on Платежі, found
  subscriptions, the drift tile, the jar link, the balance line under «Фінансова
  погода». Ask for screenshots before changing any of the matching thresholds.
- **Bugs the ten-app research found in the code** (read in the code and on live
  pages by the researchers; no test written yet — start each with a failing one):
  1. Rozetka: the parser offers three same-named prices (regular, strikethrough,
     Rozetka-card) in «Яка ціна ваша?»; choosing other than the first, the next
     check jumps back to it and the history gets an invented step
     (`offersInNode`/`extractOffers` in `Parsing.kt`, `matchOffer` in `Wishes.kt`).
  2. «Поділитися» of an SMS or Viber text with both a link and a 14-digit TTN
     becomes a wish, because the link is checked before the TTN.
  3. A held wish's plan still counts in «Плани не сходяться» and in the treat's
     budget (`plannedMonthly` ignores `holdUntil`) — a few lines, but the owner's
     call.
  4. hotline.ua product pages do return full HTML to the app's User-Agent
     (checked from a PC); only the `/sr/` search is disallowed by robots.txt. The
     «40 bytes» note in §11 is about the dead search URL.
- **Next features are the owner's pick.** The artifact «Що взяти в інших» ranks
  twelve ideas (№1 «На життя» — an honest «Вільно»; №2 «Сплачено» from the widget
  and the morning digest; №3 cash on delivery in the weather; …) and lists quick
  wins. The owner was asked for numbers on 4 October; no answer yet.
- **Left out of the shipped instalments and monobank** (in the ideas, not built):
  a cancelled subscription «діє до», «Погасив достроково» / «Повернув», the
  digest's «на картці не вистачає», a notification when a jar covers the price.

(Older, from 3 October:)

- **Nothing in this app has been verified visually.** Grain strength, the lit
  edge, the shape morph on a target-hit card, the appraisal card's states — all
  built and tested, none of them seen.
- **The Glance widget receiver is `android:exported="false"`.** If the widget ever
  stops redrawing, that attribute is the first thing to try.
- **Search Suggestions are now shown** under a fresh grounded review
  (`SearchSuggestions.kt`, platform WebView, JS off, taps open the browser). They
  are kept in memory only, as the terms require. **Never seen on a device.** Still
  open with the owner: the terms forbid "modifying" grounded results, and the app
  splits the answer into headings and rejects parts in code — arguably that.
- **Stored reviews older than 730 days are no longer shown** (`appraisalKept`).
- **`gemini-3.1-flash-lite` shuts down on 7 May 2027.** The named replacement is
  `gemini-3.5-flash-lite` (dearer: $0.30/$2.50 per 1M). Not switched — the name
  was not verified against a live call. Change `APPRAISAL_MODEL` before May.
- **Android developer verification** reaches more countries from 2027. Once it
  reaches Ukraine, an unregistered package cannot be installed or updated on a
  certified phone, which would break self-update. The free limited-distribution
  account (≤20 devices) with the current debug-key SHA-256 is the fix. Owner action.
- **Holidays under martial law** are ordinary working days (Labour Code art. 73
  is suspended), and the NBU payment system runs 24/7. The «святковий день —
  платіж до …» shift is therefore stricter than necessary. Left as is; the
  weekend rule is unaffected.
- **Five Gemini API keys were pasted into a chat transcript** on 16 September 2026
  and should be rotated.
- Retrieval of **independent lab results** (RTINGS, Which?, CHOICE) was proposed
  and not built: their failure mode is absence rather than error, which would let
  the appraisal make a real evaluative claim instead of restricting itself to what
  kind of thing something is.

---

## 15. What changed on 3 October 2026 (v3.13.0)

A day of owner bug reports, a five-agent code audit, and two research passes.
Everything below has unit tests; **nothing was seen on a screen** (no emulator,
no device attached).

### New files
| File | Holds |
|---|---|
| `Purchases.kt` | Order kinds (download / Nova Poshta / other post), close button wording, carrier sites (Ukrposhta for 13-digit and `…UA` S10, 17TRACK otherwise), tracking numbers in free text, return windows |
| `Merge.kt` | `mergeById`, `withoutRepeatedIds`, `binEntryId` — the rule for slow work landing on a list that changed |
| `Discounts.kt` | The shop's crossed-out price vs the lowest of the 30 days *before* the current price; Black Friday date and note |
| `Notifications.kt` | `openTabIntent` — every notification opens its tab |
| `SearchSuggestions.kt` | Gemini Search Suggestions in a WebView |

### Rules added — do not undo
- **Never save the list a slow operation started from.** Every network-bound
  path goes through `update { now -> … }` or `mergeById`. Saving the starting
  list resurrected deleted wishes, dropped added ones, reverted typing, and made
  duplicate ids that crashed the grid on every launch.
- **The app re-reads the store on every return** (`LifecycleEventEffect(ON_START)`),
  and `today`, the rate and the income are keyed on it. The activity lives for
  days on this phone.
- **Lists are read through `withoutRepeatedIds`.** A duplicate id must never be
  able to lock the owner out again.
- **Bin entries have their own ids** (`binEntryId`); a bought wish's parcel has
  its own id too.
- **The paid tick follows the reminder** (`tickMonth`): inside the notice period
  it marks the coming charge's month, otherwise this month's. Next month's marks
  are kept by `prunePaidMarks`.
- **The digest remembers what it said** (`digest_p`) and compares against that,
  not against a calendar day; it is pinned to its hour with
  `setNextScheduleTimeOverride` and re-pinned after each run.
- **`priceNumber(grouping = false)` for ratings.** "1,299" is a thousand; "4,667"
  stars is not.
- **`BACK_IN_STOCK` only from `OUT_OF_STOCK`.**

### Owner decisions taken today (reversals of §12-style calls)
- The background-health strip shows **only on trouble** (`stripLine`).
- «По місяцях» lives on Платежі, not Огляд.
- The navigation bar is nearly opaque (0.96).

### New Store keys
`alerted` (alerts already sent), `digest_p` (prices the last digest saw),
`hol_<year>` (holidays per year; the old `hol` is still read), `sec_orderarchive`.
None of them is app data, so none is in the backup. New JSON fields, all in both
halves of their mapping and round-trip tested: `Order.dg` (digital), `Order.rb`
(return by), `Wish.why`, `WishSource.lp` (crossed-out price).

### Researched and deliberately not built
- **Live Updates for parcels** — Google's own page says they are not for package
  tracking.
- **Ukrposhta / Meest / Nova Post Global tracking APIs** — all need a contract
  token. The web pages are linked instead.
- **Temu via a crawler User-Agent** — it does return JSON-LD with price and photo,
  but that relies on Temu serving crawlers differently, against its terms.
- **Nova Poshta with the recipient's phone** unlocks more fields; it would need
  the owner's phone stored on the device. Not asked yet.

---

## 16. Bento and emoji (3 October 2026, evening)

The owner called the «Лайм 2.0» screens monotonous. Ten styles were tried on their
own screens in a private page (claude.ai artifact «Примірочна FlowPay»); they chose
**bento** and asked for an emoji per thing («інтернет — то браузер емодзі, якщо
телефон — то трубка»), then chose **Apple's emoji** over the free Fluent set after
being told about the licence. Rendered and checked on the JVM screenshots; **not
seen on the phone yet.**

### New files
| File | Holds |
|---|---|
| `Bento.kt` | `BentoTile`, `tileColours` (by name, never a neighbour's colour), `inkOn`, `SplitFigure`, `displayFigureSize`, `TileChip`, `EmojiSticker`, the emoji picker and `EmojiField` |
| `Emoji.kt` | `payEmoji` / `orderEmoji` / `wishEmoji` rules (most specific first, word-start matching), `shownEmoji`, `emojiKey`, `packEntryKey`, `typedEmoji`, `EmojiPack`, `EmojiGlyph` |
| `res/font/unbounded_*.ttf` | Unbounded SemiBold/Bold, subset like Inter; OFL in `app/licenses/` |

### Apple emoji are never in this repository
Apple's emoji are Apple's artwork and this repository and every APK are public.
The owner imports them once: **Огляд → Налаштування → «Емодзі Apple» → Вибрати**,
picking `FlowPay-emoji-Apple.zip` (3 370 PNGs, 72 px, named by `emojiKey`, made on
their PC from the Figma community pack they downloaded — `Downloads\Emoji Mega Pack (3,900+ iOS
Apple Emojis).zip`, a `fig-kiwi` file whose images are blobs in the message
chunk). They live in `filesDir/emoji`, are not in the backup, and until imported
— or on any other phone — `EmojiGlyph` draws the character in the phone's font.
**Do not commit the PNGs, not even as test resources.** `ScreenShots.kt` reads
them from `C:/Temp/flowpay-emoji` (or `-Dflowpay.emojiPack`) when present.

### What changed on screen
- **Платежі:** hero with the next payment's emoji as a sticker; «Лишається» and
  «Сплачено» as two tiles; the year rows in one dark card; the timeline is a
  two-column grid of pastel tiles (emoji, date chip, weekend note, name, yearly
  line, trial, raise line, amount, tick). Tap a tile to edit, tap the ring to mark.
- **Огляд:** sticker on the hero, pastel tiles with emoji, 🎬 on the recap invite,
  the emoji row in settings.
- **Покупки:** a summary tile (`parcelsAtAGlance`: at a branch → on the way →
  open), each purchase a tile with its emoji (or photo), the close action a dark
  pill inside it.
- **Бажання:** the total as a lavender tile; a wish without a photo shows a
  pastel square with `wishEmoji` instead of a grey one.
- **Titles** in Unbounded on every tab.

### New JSON fields
`Pay.em`, `Order.em` — the hand-picked emoji; empty means "guess from the name".
Both halves, round-trip tested (`EmojiTest`).

### Later the same evening (owner away, asked for "all the remaining pages")
- **Курс** (`Exchange.kt`): two tiles, «Купівля» (bank buys, what $1 brings) and
  «Продаж» (bank sells, what $1 costs), the spread under them; the NBU's single
  official figure gets one tile, never shown twice as two. The converter names
  the deal — «Купую $ | Продаю $» — and the deal picks the rate; the arrows switch
  the typed currency. Dollars are typed by default.
- **По місяцях:** each month a tile whose colour is its state (mint settled,
  pink ended unmarked, peach partial, sky running, sand nothing due), with the
  payments' emoji on the lines. `LeaderRow` and `DottedLeader` take ink colours.
- **Recap deck:** each card a pastel tile with its own emoji (`recapEmoji`),
  sliding in from the side it is read towards.
- **Wish and parcel pages:** an emoji band where there is no photo; the parcel
  page has the emoji picker under the name.
- **Widget:** a lime tile with the next payment's emoji, decoded from the pack.
- **A paid payment tile turns dark** (`SurfaceRaised`) instead of fading — a
  faded peach read as mud. Colour = still to pay, dark = done.
- **Motion, second wave** (all on `Motion` springs, all off under reduced motion):
  `popOnRise` (the emoji jumps when a tick goes on, ✅ when the paid count rises),
  the hero sticker swaps with a scale when the next payment changes,
  `revealOnEnter` + `rememberEntrance` (tiles rise in the first time a tab opens
  in a session, within 700 ms of opening — never on later visits or on scroll),
  stage segments fill by colour animation.
- **Motion, third wave — arrivals (4 October 2026).** The owner asked for tabs
  and tiles to *appear* like the code-made motion videos they had shown (kinetic
  type, staggered physics, things drawing on). Every time a tab opens
  (`LocalEntrance`, provided once around each tab in FlowPayApp; 700 ms window,
  so nothing replays while scrolling): the page drifts in from the side of the
  tapped tab; the title rises out of a mask and its tracking closes up; tiles fall
  in on an underdamped spring with a tilt, a scale and a blur clearing (blur on
  API 31+), 55 ms apart, capped at seven; figures roll their digits up into place
  (`rollIn`, the real number — still never a count from nought); bars and the
  days strip grow (`entranceFraction`); every emoji pops in last, top to bottom by
  its position on screen (`popInOnEnter`, inside `EmojiGlyph`). Reduced motion
  turns all of it off. This reverses the old "nothing slides sideways" note on
  the tab switch, at the owner's request.
- **Motion can now be seen without a phone:** `ScreenShots.clipPaid` /
  `clipOpen` write frames to `build/outputs/roborazzi/clips/` with the clock
  driven by hand; stitch them with PIL. The page renders (`6-by-months`,
  `7-wish-page`, `8-parcel-page`, `9-recap`, `2b-rate-whole`) tap into the page
  first.

---

## 17. New ideas, 4 October 2026 (v3.15.0)

The owner asked for features that are "absolutely new, not repeating" anything
usual, plus the recap as a picture. Logic in `Ideas.kt` (tested in `IdeasTest`),
tiles in `IdeasUi.kt`. Rendered on the JVM; **not seen on the phone.**

- **Фінансова погода** (Огляд, under the hero): the next seven days, each day's
  still-unpaid charges read as weather against the income — ☀️ nothing, 🌤️ ≤3%,
  🌧️ ≤20%, ⛈️ more, or any charge in a month that does not fit. Marked charges
  are gone from the sky (same rule as reminders); trials do not rain. Without an
  income, fixed amounts (500 / 5 000 ₴) stand in. One sentence names the worst day.
- **Подарунок собі / «Можна дозволити собі»** (Огляд, under the tiles): the one
  wish that fits into free money after the savings plans with a fifth to spare,
  preferring a reached target, then the furthest below its own highest price.
  Never held or stale wishes. A permission, not a warning; tapping opens the wish.
- **Дуель бажань** (Бажання, under the total; three priced wishes needed): five
  side-by-side picks per round; least-played first, closest rating second, never
  the pair just shown. Rating = (wins+1)/(duels+2). After the round: top three
  and, if one has lost ≥3/4 of at least four duels, an offer to hold it for a
  month. New sort «За бажанням». New JSON fields `Wish.dw` / `Wish.dp` (both
  halves, round-trip tested).
- **Підсумок місяця картинкою**: a share button on the recap deck records the
  card area (Compose `GraphicsLayer`) to `cache/shared/` and hands it to the
  share sheet through a `FileProvider` (`${applicationId}.files`, paths in
  `res/xml/shared_files.xml`, only `cache/shared/`). The share itself was not
  exercised anywhere — check it on the phone.

Kept in reserve, proposed to the owner and not built: price in hours of work, a
savings plant that grows with the savings.

---

## 18. «Частинами» and monobank (4 October 2026)

Both asked for by the owner («роби monobank і частинами») after the ten-app
research (artifact «Що взяти в інших»). The owner connected a real token on
4 October (3.16.0): `client-info` parsed fine (name, four cards — two hryvnia,
one dollar, one euro). The first statement load was what broke — see «Ten
minutes per worker» below.

### «Частинами» (Payments.kt)
- Two fields on `Pay`: `instalments` (count, JSON `ic`) and `instalmentStart`
  (epoch day of the first payment, `is`). Monthly only (`isInstalment` is false
  for an annual fee).
- **`chargesIn` is what starts and stops a plan** — every total, record, strip and
  the weather follow from it. `nextDateFor` returns the first payment before a
  plan starts. `isFinished` (last payment behind today) removes a plan from
  `stillOwing`, `nextPayment` and `paymentGroups`; finished plans are listed under
  «Розстрочки, які закінчились» until deleted.
- The form asks «Усього платежів» and «Уже сплачено» (`instalmentStartFor`), not a
  first date nobody remembers, and says back the last date. The tile shows
  «платіж 3 з 6 · останній 15 січня» and a bar; the year card says
  «Після … звільниться …» (`freedLine`, plans ending within six months).
- `yearlyCost` of a plan is the whole plan; `yearlyCharge` only its payments still
  to come (≤ 12).

### monobank (Mono.kt pure, MonoSync.kt Android, MonoUi.kt screens)
- **Token:** the owner's personal read-only token from api.monobank.ua, sealed
  with an AES-GCM key in the Android keystore (`MonoVault`); prefs file
  `flowpay-mono` holds token, accounts, jars and ~100 days of operations. Not in
  any export or backup (Android backup is off in the manifest). «Відключити»
  deletes the file, the key and every wish's jar link.
- **Calls:** `/personal/client-info`, `/personal/statement/{acc}/{from}/{to}`;
  one request a minute (limit 1/60 s), kept across passes: `call()` waits from
  the stored time of the last request, the token check on connecting included,
  and on a 429 waits once more and retries. Windows ≤ 31 days, paging at 500.
  `statementPlan` lists the requests: 93 days back for a card never read (three
  months for the subscription finder), otherwise from where it was read — minus
  two days at the recent edge only (a hold settles under the same id). Account
  information younger than 5 minutes is not asked again. `MonoWorker` every 6 h
  (network required), plus «Оновити зараз»; a pass that finds another running
  (`Mutex.tryLock`) leaves it to finish.
- **Ten minutes per worker (fixed in 3.16.1).** WorkManager stops a worker at
  10 minutes. With all four cards ticked a first load is 4 × 3 windows = 12
  requests, ≈ 12 minutes, and a «Оновити зараз» worker waiting on the old
  `Mutex.withLock` counted its wait too. A worker was stopped, and the
  coroutine's `CancellationException`, caught as `Exception`, was shown in red as
  «Немає зв'язку з monobank» (the owner's screenshot, 4 October). Now a pass
  stops itself at 8 minutes (`PASS_BUDGET_MS`) and queues the rest as
  `mono-more` (`APPEND_OR_REPLACE`); operations and the per-card `until` are
  saved after every request; cancellation is rethrown, never reported; the plan
  is recomputed after every request, so a card ticked mid-load joins it.
  Settings and the sheet show «Завантажую виписку — ще ≈N хв» (`loadingLeft`,
  stale after 15 minutes) instead of an error.
- **Ask before deciding** (the research's strongest rule): `monoMatches` finds,
  per unmarked month (this one and last), the charge on the due date −4…+6 days
  that fits: LEARNED (merchant the owner confirmed; ±40%), NAMED (description
  names the payment, incl. transliteration and aliases; ±25%), AMOUNT_ONLY (near
  exact, ±2 days). Only LEARNED ticks by itself (switchable); the rest are asked
  on Платежі — «Так, сплачено» marks the month at the charged amount and stores
  `Pay.monoMerchant` (JSON `mm`); «Ні» is remembered per operation and payment.
  Transfers and cash (`NOT_A_PAYMENT_MCC`) never match.
- **Also:** `findSubscriptions` (same merchant 2+ times 25–36 days apart, ±10%,
  not on the list) with «Додати» / «Не підписка»; `monoDrifts` («Ціна змінилась»
  → `withAmount`, so «було → стало» and the digest see it); the card's own money
  (balance − credit line) under «Фінансова погода» with «не вистачить … до …»;
  `Wish.jar` (JSON `jr`) — a wish's «Вже відкладено» follows a monobank jar.
- The JVM screenshots seed `flowpay-mono` with a fake token string and a sample
  statement (the keystore does not exist under Robolectric).

---

## 19. Where things stand — end of 4 October 2026

**Released and checked after CI** (signature `00e2a967…bca6e`, version code
from the tag, the new strings found in the DEX):

| Tag | What |
|---|---|
| `v3.15.0` | Arrivals motion, money weather, a treat of the month, the wish duel, the recap as a picture (§17) |
| `v3.16.0` | «Частинами», monobank, and the parallel session's NBU-rate fix `b02ba03` (§18, §11) |
| `v3.16.1` | monobank: a first load longer than ten minutes goes in parts (§18) |

Branch `fix/production-readiness`, everything pushed. No worktrees or side
branches left over (the peer's `fix/nbu-rate-leak` was cherry-picked, then
removed).

**On the owner's phone:** 3.16.0 with monobank connected and all four cards
ticked. It showed the false «Немає зв'язку з monobank» that 3.16.1 fixes; the
owner was told to update and press «Оновити зараз» in Налаштування → monobank.
What was read before the stop is kept, so the load goes on rather than restarts.

**First thing next session:**
1. Ask how monobank looks after 3.16.1: a screenshot of Налаштування → monobank
   (it should read «Завантажую виписку — ще ≈N хв», then «Оновлено …») and one
   of Платежі (the questions, found subscriptions). That is the first real test
   of the matching in §18 — judge the thresholds by it, not by the sample
   statement in the tests.
2. Ask which ideas to build next (§14 and the page below). The quick wins
   include the three real bugs listed in §14.

**Pages made for the owner** (claude.ai artifacts, private to them):
- «Що взяти в інших» — ten apps taken apart: 145 functions, 52 ideas kept and
  30 dropped with the reasons, twelve ranked, quick wins.
  https://claude.ai/artifact/8CLCEbrNbushrXhNjqBZuF
- «Примірочна FlowPay» — ten design styles tried on the app's own screens
  (3 October; bento was chosen, §16).

**Owner actions still open:** rotate the Gemini keys pasted on 16 September;
the Android developer verification account before it reaches Ukraine (both
§14). Optionally untick the dollar and euro cards if no payment is made from
them — each card adds about three minutes to a first load and a request to
every pass.

---

## 20. Parcels and purchases, second round (4 October 2026, v3.17.0)

The owner looked at «Що взяти в інших» and said «додай все»; five builders took a
share each in worktrees (§8). This is the parcels-and-purchases share: research
№3, №9, №10, the «Поділитися» and «Гарантія до» quick wins, Klarna's
«Автопідхоплення посилок» **step 1 only** (reading the phone's notifications needs
the owner's explicit consent and was not built), «Повернення до копійки», «Як тобі
покупка?», Nova Poshta's five, and «Відкрити в Новій пошті» / «Як на фото? Ні».
Logic in `ParcelsMore.kt` (tested in `ParcelsMoreTest`), Android bits in
`ParcelsNet.kt`, composables in `PurchasesUi.kt`, JVM renders in
`screens/ParcelScreens.kt`. **Not seen on the phone.**

### New files
| File | Holds |
|---|---|
| `ParcelsMore.kt` | `sharedParcelNumber`, `knownParcel`; `normalizedPhone`, `maskedPhone`, `phoneFor`; `codDues`, `codLine`, `weatherWithParcels`, `codChips`; `PickupPoint` with `pointHours`, `hoursChip`, `pickupChips`, `pickupUntilLine`, `familiarPoint`; `Refund` and its lifecycle (`startReturn`, `shopReceived`, `moneyBack`, `undoMoneyBack`, `cancelReturn`, `applyReturnStatus`, `refundStatusLine`, `owed`); warranty (`warrantyStart`, `warrantyEnd`, `onWarranty`); `Delight`, `categoryJoy`, `delightedIn`; `purchaseOnceLines`; `NOVA_POSHTA_APPS` |
| `ParcelsNet.kt` | `ParcelPrefs` (phone, point cache, said-once memory), `fetchPickupPoint`, `refreshPickupPoints`, `openNovaPoshta` |
| `PurchasesUi.kt` | `PointChipsRow`, `CodChipsRow`, `ParcelsToPayLine`, `ReturnRow`, `ReturnBlock`, `ReturnSheet`, `WarrantyPicker`, `DelightQuestion`, `DelightAnswerView`, `CategoryJoyLine`, `NovaPhoneDialog`, `RecipientPhoneField` |

### What changed on screen
- **Share:** a 14-digit waybill in a shared text goes to Покупки even beside a
  link (it used to become a wish). Digits inside a product link do not count. A
  waybill already on the list opens its parcel.
- **Phone:** Налаштування → «Мій номер для Нової пошти» (masked on the row);
  «Номер одержувача» on a parcel's edit dialog. Sent as `Phone`. «Оплата» shows
  «Післяплата за товар», «Вартість доставки», «Платне зберігання» when they arrive;
  «Сама посилка» shows «Відправник».
- **Cash on delivery (`AmountToPay`):** on the tile; on Огляд «📦 ще N посилки до
  оплати: …» under the hero; the forecast adds it to its day (today at a branch,
  else the promised day; with no day it is left out of the forecast) and judges
  it by the same `moneySky`, with a chip; the treat subtracts it; the digest's
  waiting line adds «, до сплати …».
- **Pickup point:** `Address.getWarehouses` by Ref, once a week. Chips for hours,
  generator, terminal, fitting room; peach «скоро зачиняється» in the last hour;
  «відкриється завтра о 08:00» after closing. At a familiar point only the warning
  shows. The digest adds «забрати можна до …» (only when every waiting parcel is
  at one point).
- **Returns:** «↩️ Повертаю» from the archive or the filing sheet («Не таке, як на
  фото? Повертаю») turns the purchase into a «Повернення» tile (Відправив → Магазин
  отримав → Гроші повернулись). «Магазин отримав» from the return waybill or a tap.
  Then «чекаю гроші: N з 30 днів», a weekly digest line once late, and «💸 Мені
  винні» on Огляд (shown from the start, pink when late). Undo: «Гроші ще не
  прийшли».
- **Warranty:** chips «немає · 12 · 24 · 36 міс · своя дата» on the filing and
  archive sheets, counted from `RecipientDateTime`, else the first «received»
  status seen, else the filing day; 🛡️ chip and «На гарантії» filter in the
  archive; one digest line 30 days before the end. No receipt photos.
- **«Як тобі …?»:** days 21–27 after filing, 😍🙂😐😞 and «Купити таке ще раз?»;
  `why` and `category` now come over from the wish at «Я купив це»; «Гаджети: 3 з 4
  — 😍» under a new wish's category; recap card «Що справді порадувало».

### Nova Poshta, checked live 4 October 2026
- An empty `Phone` gives the warning «Please enter a valid phone number from the
  express invoice to show full information»; a well-formed dummy number removes
  it and the same 128 fields come back. What the owner's real number fills in on a
  real parcel is **unverified**.
- `getWarehouses` needs no key; by Ref it returns one record (~3 KB).
- **The pickup hours are `Schedule`.** `Delivery` is "-" on all 200 Kyiv lockers
  asked and `Reception` on 162; on 279 of 300 branches `Delivery` ends an hour
  before `Schedule`/`Reception` on weekdays — a same-day dispatch cut-off, by
  reading, not by documentation (the NP developer docs answer 403 to scripts).
- `curl -d` on Windows mangles Cyrillic; send the body from a UTF-8 file.

### New JSON fields (both halves, round-trip tested)
- `Order`: `pkPh` (recipient's phone — **travels in the JSON backup and the bin**),
  `pkRet`, `pkWu`, `pkJoy`, `pkAgain`, `pkJoyDay`, `pkWhy`, `pkCat`. `pkRet` is an
  object: `pkS`, `pkA`, `pkT`, `pkR`, `pkD`, `pkG`, `pkB`, and `pkC`/`pkX`/`pkK`
  for the return waybill's own status.
- `ParcelDetails`: `pkWr`, `pkDc`, `pkGp`, `pkSc`, `pkSn`, `pkRa`.

### New preference keys (not in any backup)
`pk_np_phone`, `pk_points` (dropped after 30 days), `pk_said` (last 200 keys).

### Rules added — do not undo
- The waybill branch runs first in the share router.
- `refund != null` means not counted as bought (`countsAsBought`): out of the
  «Куплено вчасно» tally, the verdict, cost per use, the recap and «Снайпер». The
  spreadsheet records paid minus refunded.
- The return waybill's status lives in `Refund`, never in the delivery's
  `statusCode`/`sightings`; the worker follows it separately (`followsReturn`).
- Said-once digest lines use keys in `pk_said`, saved from `Digest.onceKeys`.
- The recap always ends on its label (`body.take(MAX - 1) + label`).
- No gendered verb next to a shop or product name.

### Open with the owner
- Enter «Мій номер для Нової пошти»? Without it Nova Poshta most likely does not
  give the cash-on-delivery sum. One real COD parcel is needed to confirm.
- The recipient's number is stored with the parcel and so is in the backup; the
  owner's own is not. Keep it so?
- «Мені винні» right after «Повертаю» (now) or only once late? 30 days default?

---

## 21. A payment's life: cancelled, paused, promo, paid off (4 October 2026, v3.18.0)

The payments share of «додай все»: ideas №6, №11, №12 remainder; «Як скасувати»;
Rocket Money «Кінець акції», «Мовчать», «Автозвірка»; Copilot «Пауза»; Monarch «не
списалось»; the Revolut jar alert; monobank «не вистачить на завтра». Pure logic in
`PaymentsLife.kt` (`PaymentsLifeTest`) and `Mono.kt` (`MonoLifeTest`); screens in
`PaymentsUi.kt`; the Android half in `PaymentsMemory.kt`. Rendered on the JVM
(`screens/PaymentsLifeShots.kt`, `-Pshots`). **Not seen on the phone; the monobank
parts have met only sample statements.**

### The one check and the one price — never bypass them
- **`runsOn(pay, date)`.** A charge due on a date happens unless the date is after
  `Pay.stopsAfter`, on or after `pausedFrom`, or inside a finished pause (`pauses`).
  `chargesIn()` asks it, so every total, record, strip, forecast, digest line, the
  CSV and the widget follow. `nextLiveCharge()` / `isLive()` replaced `isFinished`
  in `stillOwing`, `nextPayment`, `paymentGroups`, `annualElsewhere` and the CSV
  «Підписка» rows. One test runs a cancelled, a paused and a returned payment
  through every one of those places.
- **`priceOn(pay, day)`.** `promoPrice` before `trialEnd` (0 = a free trial, as
  before), `amount` after. Every former «trial → 0» goes through it: `monthCharge`,
  `yearlyCharge`, `chargedOn` (returns copies carrying that day's price — **anything
  that sums charges another way must use `priceOn`**), `remindersDue`
  (`DueReminder.amount`), `monthRecord` / `monthLines`, `togglePaid` / `markAmount`,
  `monoMatches` / `monoDrifts`. `nextCharge` looks past a free period only.
- **`isFinished`** uses `planLast` (the last plan payment that still happens), so a
  payoff or a return ends a plan.

### States (`lifeOf` → `PayLife`)
| State | Meaning | Where it is shown |
|---|---|---|
| RUNNING | charging as usual | the grid |
| CANCELLED | `stopsAfter ≥ today` | «Скасовані — до кінця оплаченого», when no charge is left |
| ENDED | `stopsAfter < today` | a question tile at the top |
| PAUSED | `pausedFrom` set | «На паузі» |
| FINISHED | a plan that is over | «Розстрочки, які закінчились» (`finishedPlanLine`) |

### New fields (both JSON halves, round-trip tested)
`Pay.stopsAfter` `plsa`; `Pay.stopReason` `plsr` (`cancelled` / `paidoff` /
`returned`); `Pay.pausedFrom` `plpf`; `Pay.pauses` `plps` (`[{f,u,c}]`, last 12);
`Pay.promoPrice` `plpp`; `Pay.cancelUrl` `plcu`; `Pay.order` `plo`;
`Order.planReturned` `plr`. Preferences, none in the backup: `flowpay` → `pl_said`;
`flowpay-mono` → `pl_gone`, `pl_wait`, `pl_dup`, `pl_after`, `pl_jar`.

### Cancelled
«Скасував ✓» opens a dialog with «діє до» = `paidUntil` (the day before the next
charge, or the one after when that month is ticked; editable; a past date bins the
payment at once). From the next day it is ENDED and its tile asks «Нового списання
не було?» — «Не було ✓» bins it, «Списали — повернути» runs `unstopped` and ticks
that month. The digest asks once (`ended|name|day`). Unanswered for 7 days
(`CANCEL_QUESTION_DAYS`), `sweepPayments` on ON_START bins it. A cancelled payment
always enters the bin un-cancelled (restoring brings it back running); the swept
entry's id is deterministic, so a stale list cannot bin it twice.

### «Як скасувати»
`CANCEL_PLACES`, each URL requested once on 4 October 2026: Google Play
subscriptions, YouTube `paid_memberships`, Netflix `cancelplan`, Spotify
`account/overview`, ChatGPT `chatgpt.com/#settings` (whether it opens the dialog is
not verified), Megogo `account?view_type=subscriptions`, Sweet.tv
`ua-uk/cabinet/personal`. `Pay.cancelUrl` overrides; an unknown service gets a
Google search «як скасувати <назва>». `cancelHelpFits`: always on trials; never on
plans; not on rent, utilities, loans, insurance, car, medicine, pets (by emoji).
A trial's last reminder adds up to 2 notification buttons (`DigestAction`).

### Promo
`PromoField` replaced `TrialField`: a date plus «Безкоштовно», «Пів ціни» or a
typed price. The digest says once, as many days ahead as the payment's notice,
«З 1 лютого Інтернет коштуватиме 300 ₴ замість 150 ₴ — …» (`promo|name|day`). When
the promo ends, `withPromoEnded` writes `[regular@0 if the history is empty] +
promo@0 + regular@trialEnd` once — «було 150 → стало 300», the digest's raise line
and the recap («Акція скінчилась») all see it. Run by `sweepLife` (ON_START) and
`recordPromoEnds` in ReminderWorker before the digest. A promo tile keeps the
regular price as its big figure, «150 ₴ до 1 лютого» under the name, like trials.

### Pause
`paused` / `resumed`; a finished pause is kept as a span; paused months stay out of
«По місяцях» even after resuming. `bankResumed` (MonoSync.apply, before matching)
ends a pause when the confirmed merchant charges for a date inside it, within 40 %
of the price, at that charge's due date, so the charge is ticked in the same pass.
The digest says once «Megogo знову списує 199 ₴ — паузу знято».

### Plans
`paidOff`: `stopsAfter` = this month's payment (or today when it is behind);
`payOffMarks` puts this month's payment and every later one into this month's mark.
`returned`: `stopsAfter` = yesterday; months already paid stay; refunds are not
tracked. `Pay.order` ties a plan to a purchase (picker on the plan's sheet);
«Повернув» then sets `Order.planReturned` and the archived purchase card says so.
«Відновити розстрочку» takes a stop back; the marks stay.

### monobank extras (Mono.kt, read-only)
- `silentPayments` («Мовчать»): 40 days for monthly, 10 days past the date for
  annual; skipped when the last due month was ticked by hand. «Ще чекаю» quiets it
  30 days; «Прибрати» opens the cancel dialog with `silentPaidUntil`.
- `doubleCharges`: same merchant, same kopecks, ≤ 3 days apart, last 30 days.
- `chargedAfterCancel`: cancelled payments still on the list, plus `pl_gone`
  entries for 92 days after the paid period.
- `missedCharges` («не списалось»): this month's date + 3 days, only once the
  statement has been read since.
- `shortTomorrowLine`: the digest's FIRST line; confirmed merchants only; balance
  no older than 24 h.
- `jarAlerts`: price read within 2 days, `Freshness.OK`, wish not held; once per
  price level; channel `price_changes`.

### The morning message — one say-once memory (merged with §20)
`digest(points, now, said, lead, once)` returns `Digest.said` (every say-once key
the message carried — payment life, monobank, purchases) and `Digest.actions`.
ReminderWorker builds `lifeLines` + `monoMorning`, filters them through
`LifeMemory` (`pl_said`, 300 kept) and passes them as `once`; the purchases' lines
(§20 `purchaseOnceLines`) are filtered inside `digest()` against the same `said`
set. After the message, `memory.markSaid(summary.said)`. The parcels branch's own
`pk_said` memory and `Digest.onceKeys` were folded into this at the merge;
`OnceLine` is declared once, in PaymentsLife.kt. **Android shows at most three
notification actions in total** — whoever adds more buttons must share them.

### Not verified
Nothing seen on the phone. Not exercised: the notification buttons, the date
pickers and confirm dialogs, «Відкрити monobank» (starts `com.ftband.mono`, falls
back to monobank.ua), the jar notification, and every monobank part on real data.

### Open with the owner
- «діє до» default (day before the next charge) — fine, or ask every time?
- An unanswered ended cancellation goes to the bin after 7 days — fine?
- ChatGPT paid through Google Play? Then its own link should be Google Play's page.
- «Мовчать» thresholds 40 days / 10 days — keep?
- «Завтра не вистачить» counts only payments already confirmed from monobank.
- During a promo the big figure is the regular price — or the promo one?

---

## 22. Acting without opening the app, and hiding sums (4 October 2026, v3.19.0)

Research idea №2, the «Без сум» quick win, YNAB's «Сховати суми», monobank's
«Інкогніто» and Rocket Money's «Поділись листом про підписку», built for «додай
все». The screens were rendered on the JVM (`screens/TouchShots`, `-Pshots`).
**The widget, the Quick Settings tile and the notification were not drawn
anywhere, and none of it was seen on the phone** — Glance and the shade have no
renderer here, and HyperOS is known to redraw widgets late.

### New files
| File | Holds |
|---|---|
| `QuickActions.kt` | `QuickMark`, `chargeMonth`, `markKept`, `markPaid`/`unmarkPaid`/`quickMarked`, `rebaseMarks`, `widgetTick`/`widgetUndo`, `digestOffers`, `DigestCard` (with `links`) and `digestTitle`/`digestButtons`/`digestLinks`/`digestPressed`/`digestUndone`/`digestGone`, its JSON |
| `QuickReceiver.kt` | `QuickMarkReceiver` (the digest's buttons; not exported, no filter), `QuickMarks.version`, `Store.updatePaidMarks` (one lock for every writer of the marks), `TouchPrefs` (every `tc_*` key) |
| `Privacy.kt` | `maskSums`/`maskFigure`/`onlyMasks`, `recapWithoutSums`, `widgetWithoutSums`, `hiddenFreeLine`, `SumsMask`, `personal()`/`personalFigure()` |
| `SubscriptionText.kt` | `parseSubscription`, `subscriptionLetter` → `SharedLetter.NewPayment`/`PriceChange`/`SamePrice`, `paymentNamedIn`, `draftLine`, `priceChangeLabel`, `oldPriceChargeBefore`, `shortDate` |
| `TouchUi.kt` | `SumsEye`, `HideSumsOutsideRow`, `PersonalNumberField`, `PasteLetterButton`, `PriceChangeDialog` |
| `res/drawable/widget_tick.xml` | the widget's ring with a tick |
| Tests | `QuickActionsTest`, `PrivacyTest`, `SubscriptionTextTest`; with `-Pshots` only `screens/TouchShots` (33 PNGs) and `screens/QuickButtonsCheck` (the digest's buttons through Robolectric: posted, sent, received, marked, redrawn, undone) |

### «Сплачено» from the widget and the morning message
- **Which month.** `chargeMonth` is the month of the charge being shown — what
  `stillOwing` asks about — so a mark takes the payment off the widget, the pill
  and the digest at once. Whenever the reminder is asking it equals `tickMonth`
  (pinned over 62 days and six rhythms); outside the notice period the widget
  marks what it shows (the charge ahead), while a tile's tick in the app answers
  for the one behind. A month the store would prune is never offered
  (`markKept`), so an annual fee months away has no tick. A tap from outside never
  takes a mark off.
- **Widget.** A ring beside the next payment (`WidgetMarkPaid`); with several
  payments that day it opens Платежі instead. Undo: the header line «✓ Інтернет ·
  Скасувати» (`WidgetUndoMark`) for the rest of the day while the mark stands
  (`tc_widget_undo`, `tc_widget_undo_day`).
- **Digest.** `ReminderWorker` keeps the morning's `DigestCard` (`tc_digest`) and
  posts it through `postDigest` (Notifications.kt). `digestOffers` uses the very
  `remindersDue` the payment lines come from. A press marks the payment; the
  message keeps its text, is retitled «Позначено: …», «Скасувати» comes first and
  the other payments keep their buttons; «Уже позначено» for one marked before; a
  deleted payment loses its button. A redraw never rings (`setOnlyAlertOnce`).
- **Three slots, shared (merge with §21).** §21's «Як скасувати» links ride on the
  card as `DigestCard.links` (`tcl`); `digestLinks` shows one link when there are
  payments to mark and two when there are none, and `digestButtons` leaves them the
  room. Payment buttons come first, so «Скасувати» stays first.
- **The stale list (§15) for the marks.** Every write goes through
  `Store.updatePaidMarks` (re-read, change, save, under one lock — Glance runs a
  callback on a background thread). The app's `setPaid` rebases what a screen did
  (`rebaseMarks(store, before, after)`) instead of saving the screen's list.
  `QuickMarks.version` makes an open app read the marks again. **Not changed:**
  `MonoSync.apply` still writes the marks without the lock.
- **Glance sessions.** A widget session lives ~45 s after each redraw, and during
  it `updateAll` recomposes only if the widget's own state changed. The widget
  reads the store inside its composition, keyed on `tc_stamp` (Glance state).
  **`refreshWidget(context)` is the way to redraw it.** `PriceWorker` still calls
  `updateAll` (rarely inside a live session).
- R8: the two callbacks' constructors are kept in `proguard-rules.pro`.

### Hiding sums
- **What a sum is** (`maskSums`): a figure followed by ₴, $, грн, UAH, USD or «тис»;
  «$12»; a bare figure right before «→». It becomes «•••» («••• ₴»). Percentages,
  counts, days, dates and multiples stay. The mask is applied at the screen with
  `personal(line)` / `personalFigure(figure)` — **never inside `money()` or
  `bareAmount()`**, which also write the digest, alerts and tests. **Any new
  screen that shows a personal sum must wrap it.**
- **Recap «Без сум»**: a checkbox on the deck (`tc_recap_nosums`, ticked by itself
  while the eye is shut); amounts «•••», a card whose headline is a sum left out,
  a detail that was only a sum dropped. What is shared is what is shown.
- **«Ховати суми поза застосунком»** (Налаштування, `tc_hide_out`): the widget
  says «Інтернет», «12 жовтня · завтра», «Вільно: є» / «Бракує до кінця місяця»;
  the tile «Вільно: є» with «на місяць · суми сховано». The rate stays. The
  morning notification is not covered.
- **The eye on the Огляд hero** (`tc_hide_in`, read in `onCreate` before the first
  frame; always visible). Hidden: Огляд, the pill's payment line, Платежі, По
  місяцях, a wish's savings plan (inputs as dots), purchases' tally/verdict/cost
  per use, monobank's row, sheet and jar link. Kept: shop prices, the rate,
  percentages and rings; sheets opened to edit a figure show it. Known gap by
  design: a wish's ring plus the shop price let someone estimate the savings.

### «Поділись листом про підписку»
- First in the share router — before the waybill (§20) and the link: such letters
  carry links, and a date-like order number can look like a waybill.
  `parseSubscription` needs a subscription's own word outside the links
  (підписк-, передплат-, пробн-, автопродовж-, абонплат-, абонентськ-, trial,
  subscri-, renew, membership) **and** a sign the charge recurs (a trial, a
  renewal or fee word, or a rhythm beside a price). «від 499 ₴/міс» instalments, a
  999 ₴ gift subscription and «підписка на новини» stay what they were.
- Price: the figure after «далі/then», not after «замість/was»; the second of «з
  200 на 250». Dates: dd.MM[.yyyy], ISO, «12 листопада», «12 лист.», «Nov 12,
  2026», «3 Nov». Name: ~70 known services, an e-mail's «From:», «підписка «…»», a
  Latin name after «підписка», «Your X subscription».
- A payment already on the list (`paymentNamedIn`, whole words or word starts;
  «Підписка» alone never matches): at another price `PriceChangeDialog` offers
  «Оновити ціну з 1 лист.: 200 → 250 ₴» via `withAmount` dated today (one price per
  payment, so the change is immediate; a charge still due at the old price before
  the new date is named in the dialog); at the same price a snackbar. Otherwise
  «Новий платіж» opens filled in, with «З листа: …» under «Вставити з буфера»,
  which reads the clipboard the same way.

### New keys
SharedPreferences (`flowpay`, none in the backup): `tc_digest`, `tc_widget_undo`,
`tc_widget_undo_day`, `tc_hide_out`, `tc_hide_in`, `tc_recap_nosums`. Glance state:
`tc_stamp`. No new JSON fields on Wish, Pay or Order.

### Open with the owner
- Hide sums in the morning notification too (it shows on the lock screen)?
- With the eye shut, hide a wish page's ring as well?
- The widget's undo lasts until the end of the day — fine?
- A price change from a letter applies at once — or wait for its date (needs a
  future-price field)?

---

## 23. Prices, second pass (4 October 2026, v3.20.0)

The research page's prices/rate part («додай все»), plus the owner's own request
for a «Hotline ↗» button on every wish page. Logic in `PricesMore.kt` and
`RateWatch.kt` (tested in `PricesMoreTest`, `RateWatchTest`); drawing in
`PricesUi.kt` and `ShopSheet.kt`; prefs in `PriceStore.kt` (same `flowpay` file,
own class, keys `wp_*`, none in the backup); `RateWorker.kt`. JVM renders in
`screens/PricesShots.kt`. **Nothing seen on the phone.**

### What changed
- **Rozetka prices by type.** Rozetka's JSON-LD lists regular 1 599,
  `StrikethroughPrice` 1 799 and a `validForMemberTier #rozetka-card` 1 519 under
  one name; they used to be three «editions» in «Яка ціна ваша?», and choosing
  other than the first made the next check jump back. `isSidePrice` skips
  reference types (Strikethrough/List/MSRP/SRP/MinimumAdvertised) and member tiers
  in `offersInNode`; the "was" stays `declaredListPrice`; the member price is
  `WishSource.memberPrice/memberTier` (`memberOfferIn`). `matchOffer` picks among
  same-named offers by the price followed so far. Setting «У мене є Картка
  Rozetka» (off): a dim «1 519 ₴ з Карткою Rozetka» under the price and the push
  «Досягнуто ціль … — … при оплаті Карткою Rozetka (звичайна …)»
  (`cardTargetReached`). `targetHit`, the pill, the card shape, chart and history
  stay on the ordinary price — on purpose. Wishes added earlier by picking 1 519
  take one real step to 1 599 at their next check. Fixture:
  `app/src/test/resources/rozetka-jbl-tune-520bt.html` (trimmed, no reviews).
- **A shared link already watched opens its page** (`wishToOpen`); `linkKey` drops
  utm_/gclid-style parameters and `www.`.
- **«Схоже на збій».** `glitchCandidates`: >35 % (`GLITCH_SHARE`, a guess) from
  both neighbours, same side, ≤ 1 day, never the last point. Hint with «Не
  враховувати» / «Справжня ціна»; a chart scrub ending on a point offers «Це був
  збій». Set-aside points are MOVED to `Wish.excluded` (with the following point as
  an anchor), so every reader of `history` stops seeing them; «Повернути» restores
  exactly. Per wish, not per shop (the history is one series per wish). **Do not
  change this to "mark and filter in each consumer".**
- **Sold-out gap.** `mergeSources` records `Wish.stockGaps`; the wish page's
  hryvnia chart is `WishPriceChart` (PricesUi.kt), which clips them out. The shared
  `PriceChart` is unchanged (rate screen, dollar view).
- **Hotline market.** `Wish.market` (product page URL, low, offer count, its own
  history). Bound only by sharing a hotline PRODUCT page (`hotlineProductUrl`, then
  `parseMarket` must find an AggregateOffer) — «Прив'язати як ринок до «X»?».
  Read every 12 h in PriceWorker (`withMarketRead`), the product page only, never
  `/sr/` (robots.txt) — ≈1.2 MB per bound wish per pass. Line «Ринок: від … · N
  магазинів · Hotline», chip «на … дешевше» above +5 %. Never the price, never a
  push; the digest says «… — на Hotline від …, у межах цілі …» once per crossing
  (`marketTargetLines`, memory `wp_digest_market`). No line on the chart yet; the
  history is kept for it.
- **«Hotline ↗» on every wish page** (the owner's request): opens the bound market
  page, else the Hotline search with the wish's own or owner-corrected query.
  «До магазину ↗» became «Магазин ↗»; `ActionsRow` keeps three buttons in one row
  at 360 dp and drops Hotline to a second row when the labels would not fit
  (checked at font scale 1.15).
- **Rate corridor + «Сплеск»** replace the single threshold: edges «нижче» /
  «вище» on Monobank's SELL rate; spike > 1 % against yesterday's bank point in the
  history. `RateWorker` runs hourly only while something is watched and asks
  `/bank/currency` only when the stored reading is > 45 min old; `checkRate`
  ignores the NBU figure (never arms, crosses, re-arms or fills) and readings > 3 h
  old; one notification per crossing, re-armed after 0.25 % back inside; «Стежити
  далі» / «Готово» (`RateActionReceiver`, not exported). The old `fxt*` threshold
  migrates once into an edge and is cleared, so `rateTargetLine` in the digest
  goes quiet. `RateTarget` & co. remain in History.kt (tested, unused by the UI).
- **Sheet over the shop.** `ShopSheetActivity` (translucent, excludeFromRecents,
  noHistory, `taskAffinity=""`) is the SEND target now; MainActivity no longer has
  the SEND filter. `shopSheetRoute` forwards the whole intent to MainActivity for:
  a subscription letter (`parseSubscription`, §22) or a Nova Poshta waybill
  (`sharedParcelNumber`, §20) — the same detectors the app's router asks first,
  added at the merge so the sheet never takes what the router would send
  elsewhere — a Hotline page, no link, or > 280 characters around the link. Known
  item: chart, range bar, verdict + «нижче/вище звичайного» (time-weighted 30-day
  `usualPrice`), target. New item: today's price, «історії ще нема», «Ціль
  −10%», «Стежити». It writes via `addFromSheet` (the store as it is now) and bumps
  `ShopSheetSignal`, which FlowPayApp watches to reload — ON_START alone was shown
  (JVM) to lose the wish to a later save from a live main screen.
  `FlowPayOverlayTheme` is the theme without the full-screen ground.
- **Black Friday card** in the November recap (`blackFridayCard`): wishes watched
  from 30 days before November; «справді подешевшали» = cut in November and below
  `lowBeforeCurrent`. The recap label is appended after the cut (§20 did the same
  thing; one version kept).

### New stored fields (both halves, round-trip tested)
Wish: `wpx` (set-aside points + anchor), `wpr` (points said real), `wpg` (stock
gaps), `wpmk` (market, only when bound). WishSource: `wpm` (member price), `wpt`
(tier). Prefs: `wp_rozetka_card`, `wp_digest_market`, `wp_fx_corridor`.

### Corrections to earlier sections
- §11 «What cannot be scraped»: hotline.ua's SEARCH (`/ua/sr/?q=`) is JS-rendered
  and disallowed for all robots — only ever opened in the browser; the dead
  `/ua/search/?q=` answers 200 with 40 bytes «Legacy home controller has been
  disabled». **Product pages are not blocked**: on 4 Oct 2026 JBL Tune 520BT
  answered the app's UA with 200, 1 193 356 bytes, AggregateOffer lowPrice 1316,
  offerCount 105.
- §3 «The morning message»: the rate is no longer announced there (the corridor
  notifies at once); `marketTargetLines` follows `priceLines`.
- §14: bug 1 (Rozetka three prices) and the share-a-known-link item are fixed;
  note 4 (hotline 40 bytes) corrected above.

### Not verified
On the phone: everything — the translucent sheet over another app on HyperOS, its
absence from recents and the return to the shop; RateWorker's timing under HyperOS
and the notification buttons; the market bind sheet (needs network); the
scrub-to-«Це був збій» gesture; Hotline from a mobile network.

### Open with the owner
- Has a Rozetka card? Then switch on «У мене є Картка Rozetka».
- The 35 % glitch threshold is a guess — a screenshot when the hint first shows.
- Hotline: ≈1.2 MB per bound wish twice a day on mobile data — acceptable?
- The rate is checked hourly while edges or «Сплеск» are set — fine?
- «До магазину ↗» → «Магазин ↗» to fit «Hotline ↗» — fine?
- «Поділитися» now opens a sheet over the shop instead of switching to FlowPay —
  keep, or go back to switching?

---

## 24. One «Вільно», funds and the payday (4 October 2026, v3.21.0)

The planning half of «додай все»: ideas №1 «На життя», №5 «Чи потягну?», №7
funds, №8 payday, the held-wish quick win, YNAB's true expenses, month ahead and
snooze, Revolut's and Rocket Money's payday views, Cleo's ritual, Copilot's
reserve and Monarch's funds — merged into one model. Logic: `MoneyPlan.kt`,
`Funds.kt`, `Afford.kt` (tests `MoneyPlanTest`, `FundsTest`, `AffordTest`);
preferences `PlanStore.kt`; tiles and sheets `MoneyPlanUi.kt`; small hooks in
MainActivity, Overview, Ideas, Digest, ReminderWorker, Widget, Tile, Bin, Savings.
Rendered on the JVM (`screens/PlanShots.kt`); **not seen on the phone**, and no
AlertDialog renders under Robolectric (it never goes idle — the old income dialog
too), so the income/life/skip/put dialogs were never drawn.

### The rule: one «Вільно»
`income − this month's payments (annual whole, in its own month — §12) + what
funds hold of this month's annual charges − «На життя»`, computed by
`honestMonth()`. `.asBudget()` is what «Лишається», the widget, the quick tile
(`honestBudget`) and the digest's closing line read; `overview(…, plan)` takes the
same `MoneyPlan`. Plans (wish and fund monthly sums) are **not** subtracted from
it — they are checked against it («Плани не сходяться») and taken off the treat
(`MoneyPlan.treatBudget`). With nothing set, every figure is as before (pinned).

### The pieces
- **«На життя»:** `LifeCost`, prefs `mp_life_on`/`mp_life`, off by default.
  Settings row on Огляд; the hero says «після платежів і життя … · змінити» (tap
  the hero); Платежі's bar has a lighter-lime 🛒 part. The widget's and tile's
  words still say «Вільно на місяць»; only their data changed.
- **Held and skipped plans:** `plannedMonthly` returns 0 for a held wish (§14 bug
  3, fixed) and for a skipped month.
- **«Пропустити»:** `Wish.skipMonth` (JSON `mpsk`) and `Fund.skipMonth`, on the
  plans card (candidate: lowest duel rating, else farthest date; wishes before
  funds), the wish page and a fund's sheet. The price is said first (`skipPrice`).
- **Payday:** `Payday(salary, advance)`, `LAST_WORKING_DAY = -1`, prefs
  `mp_payday`/`mp_advance`; a weekend/holiday payday moves back to the working
  day before. Set in the income dialog. The hero says «до зарплати ще N днів».
- **«Розкласти зарплату»:** 5 days from the salary day (not the advance; the
  1st–5th with no payday); proportional sums when plans don't fit; «Я відклав»
  adds to wishes and funds (jar wishes skipped), undo, ✕ hides it for that
  payday; record in `mp_ritual`. Money put aside this month stays this month's
  (`PlanAsk.put`), so a goal reached with it does not drop out of the check and
  feed the treat twice.
- **Фонди:** `Fund`, prefs `mp_funds`, backup key `mpFunds` (an old file without
  it keeps the phone's funds), bin kind `mpf`. A payment fund's goal is the
  payment at the sell rate, its date the charge in `dueMonth`, its monthly sum
  `deadlinePlan`. **The trap:** in the charge month the payment counts whole and
  `fundCoverage` adds the fund's part back once (capped at the month's charge) —
  in «Вільно», `moneyWeather(covered=…)`, the «Чи потягну?»/per-day sums and month
  ahead. **Settlement is derived (`settledFund`):** when the charge month is
  marked paid (any way, monobank too) or has ended, the fund pays min(saved,
  charged) into `coveredMonth`/`covered` and moves `dueMonth` a year on; unticking
  within the month returns it. A `LaunchedEffect` in FlowPayApp persists it; a
  tick says «Фонд покрив …». One offer per annual payment ≥ a month away; «Не
  треба» in `mp_fund_no`. Presets: Подушка ☂️ (the cushion, at most one), ТО авто,
  Подарунки, Ліки, Відпустка. The 🫙 tile replaces «Далі ніж за місяць»; annual
  rows and tiles with a fund show «зібрано X з Y». 🫙 and 🛟 are not in the
  owner's Apple pack (phone font draws 🫙; ☂️ used for Подушка).
- **Digest:** the eve of a salary «Завтра зарплата — за планом …»; with no payday
  the 1st says «Час відкласти у фонди: …» (`planDigestLines`, passed to
  `digest(planLines, month)`).
- **«Місяць наперед»:** only with a cushion fund. `monthCostAt` judges each charge
  at its own date's price through `priceOn` (§21) — a free trial still running
  costs nothing, a promo still running its promo price (fixed at the merge; the
  branch had counted promo months as free). Numerator = cushion + funds held for
  next month's annual charges.
- **«Чи потягну?» (`Afford.kt`):** by balance (monobank `ownUah`, or a typed «Зараз
  на картці» in `mp_cash`/`mp_cash_day`) before the next payday; otherwise by plan,
  labelled so. Checks: payments, life (if on), then plans give way (least wanted,
  farthest, funds last), then «Влазить». Levers: cancel a trial, buy the day after
  payday. «Купив частинами» creates an instalment `Pay` from the purchase day.
  `MoneyHost.treat` subtracts the parcels' cash on delivery like Огляд does
  (merged).
- **«Скільки можна сьогодні»:** monobank plus a payday only; assumes «Я відклав»
  money has left the card.

### Merged with §20–§23
- `markPaid` (fund settlement) now writes through `Store.updatePaidMarks` with
  `rebaseMarks` (§22) instead of saving the screen's list.
- The forecast is `weatherWithParcels(moneyWeather(…, covered = weatherCover(…)),
  cod, …)` — funds and cash on delivery both.
- The widget reads `honestBudget` inside its composition (§22's stamp), and its
  hidden-sums line uses the same month.
- New sums from this section are wrapped in `personal()` where they met a
  conflict; the rest are in the mask audit (§22 addendum).

### Keys
Prefs: `mp_life_on mp_life mp_payday mp_advance mp_cash mp_cash_day mp_funds
mp_ritual mp_fund_no`. JSON: `Wish.mpsk`; Fund `mpi mpn mpe mpg mps mpm mpd mpp
mpdm mpcm mpc mpq mpsk mppm mppa mpad`; ritual `mpa mpo mpd mpe(mpk mpi mps mpj)`;
backup `mpFunds`.

### Not built
Splitting payments into «з авансу / із зарплати» lists; cash or another bank as
one extra number; a "low per-day" digest line.

### Open with the owner
- Roughly how much «на життя» a month? (Off until set.)
- Which day is payday; an advance; or the last working day?
- Is money put aside kept on the same card, or in jars / another account? (If
  on the card, «Скільки можна сьогодні» reads too high after «Я відклав».)
- A «Подушка» fund (turns on «Місяць наперед»)? Which annual payments get funds?
- Should the widget/tile say «після платежів і життя» when life is on?
- Phone-font 🫙 for Фонди acceptable?
