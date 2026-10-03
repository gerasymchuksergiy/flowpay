# FlowPay — handoff

Everything the next session needs to work on this app without relearning it the
expensive way. Written 16 September 2026, at `v3.12.0` / 1002 tests; brought up
to date 3 October 2026 at `v3.13.0` / 1096 tests (see §15 for what changed).

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
He cannot read the code to check what you tell him — so what you say about the
app has to be true, and "it should work" is not a report.

**Stack:** Kotlin, Jetpack Compose, Material 3 `1.4.0`, Compose BOM `2026.05.00`,
AGP `8.9.1`, Kotlin `2.1.0`, `compileSdk`/`targetSdk` 36, `minSdk` 26, JDK 17.
No database — everything is JSON in `SharedPreferences`.

**Repo:** `github.com/gerasymchuksergiy/flowpay`, branch `fix/production-readiness`.
**The repository is public.** That is load-bearing for anything secret.

---

## 2. The code, file by file

33 Kotlin files, ~20,000 lines. The shape is deliberate and worth understanding
before adding to it:

> **Pure logic lives in the small files. Compose lives in `MainActivity.kt` and
> `Components.kt`.** Everything that can be decided without a screen is a plain
> function over plain data, and it has a unit test. This is why 1096 tests can
> cover an app with no instrumented tests at all.

When you add a feature, the arithmetic goes in a small file with tests, and only
the drawing goes in the Compose files. An agent that put its logic inside a
composable would have shipped it untested.

### The two big Compose files

| File | Lines | What is in it |
|---|---|---|
| `MainActivity.kt` | 7982 | Data models, the `Store`, all seven screens, all eight sheets |
| `Components.kt` | 1827 | Every shared composable |

`MainActivity.kt` is large because it holds four things that would each be small:

1. **The data models** — `Wish` (~149), `WishSource` (~110), `Pay` (~257),
   `Order` (~309). Read these first; almost every question starts here.
2. **The JSON mappings** — `wishJson`/`wishOf`, `payJson`/`payOf`,
   `orderJson`/`orderOf`. See §7.2; these are the single most dangerous place to
   be careless.
3. **The `Store`** — one `SharedPreferences` file called `flowpay`. Keys in use:
   `w` (wishes), `pay`, `orders`, `bin`, `paid` (paid marks), `income`,
   `wish_sort`, `fx_*` (rate, its day, source, buy/sell), `fxh` (rate history),
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
  `currencyMoveNote`, `appendRate`, `RateTarget`.

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
   and that is his decision. The mitigation is a spend cap on the provider's side.

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

- **NBU** `https://bank.gov.ua/NBUStatService/v1/statdirectory/exchangenew?valcode=USD&json`
  — no key, no rate limit. The primary source.
- **Monobank** `/bank/currency` — rate-limited to about one call a minute.
- Monobank's **personal API** (`client-info`, `statement`, `currency`) is read-only
  by construction and cannot move money; statement is 1 request per 60 s, window
  31 days + 1 hour. Not integrated — the owner declined for now.

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
- **He cannot check the code.** Do not say a thing is done unless it is verified,
  and name what you could not verify. "Nothing here was seen on a screen" is a
  sentence that has to be said often.
- **Report the failures too.** He has been told about every mistake in this
  project — the three lost hours, the 403 files, the wrong status code — and the
  work went better for it.
- **Explain the reasoning, briefly.** He is not a programmer but he is making
  product decisions, and he has repeatedly improved on the plan when he could see
  the trade-off.
- When he asks for something that will not work, say so once with the reason,
  offer the nearest thing that will, and then do what he decides.

---

## 14. Open items

(Updated 3 October 2026 — the Search Suggestions item below is now done.)

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

The owner called the «Лайм 2.0» screens monotonous. Ten styles were tried on his
own screens in a private page (claude.ai artifact «Примірочна FlowPay»); he chose
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
his PC from the Figma community pack he downloaded — `Downloads\Emoji Mega Pack (3,900+ iOS
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
  and tiles to *appear* like the code-made motion videos he had shown (kinetic
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

