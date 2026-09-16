# FlowPay — handoff

Everything the next session needs to work on this app without relearning it the
expensive way. Written 16 September 2026, at `v3.12.0` / 1002 tests.

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

## 2. Building

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

---

## 3. Shipping

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
- **A malformed secret pasted into a generated Java string literal.** See §7.

---

## 4. The hard rules

### 4.1 Never format a number without a locale

`"%.1f".format(x)` reads the **phone's** default locale. CI is en-US, the phone is
uk-UA, so it passes locally and fails in CI — or worse, ships a screen mixing
`3.2%` with `2 199,50 ₴`. This cost a release; twelve sites had to be fixed at
once.

- Display figures: `figure(value, decimals)` — `Payments.kt`
- Percentages: `signedPercent(value, decimals)` — `History.kt`
- Money: `money()`, `approxMoney()` — `Payments.kt`
- Storage keys, API dates, clock faces: `Locale.ROOT`, explicitly

### 4.2 A new persisted field goes into **both** sides of its JSON mapping

`wishJson`/`wishOf`, `payJson`/`payOf`, `orderJson`/`orderOf` in `MainActivity.kt`.
These are also how the 30-day bin restores a deleted item and how backup/restore
works. **A field added to the data class but not the mapping is silently lost —
this happened three separate times.** The test that catches it round-trips a fully
populated object and asserts equality, not a spot check.

A **view** preference is not app data: follow `sectionOpen` / `rateTarget` /
`recapSeen` and keep it out of `exportJson`.

### 4.3 Numbers on screen come from the data layer

Never from anything generated. See §8.

---

## 5. Working with agents

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

## 6. Design system — `Theme.kt`

Read its comments before drawing anything; they explain why each value exists.

- **One accent.** `Accent` lime `#d7ff63` on near-black `#0a0b09`. One lime thing
  per screen. Do not introduce a second accent for any reason — not for AI, not
  for a new section.
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

## 7. Secrets

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

## 8. Domain knowledge worth not rediscovering

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

## 9. Judgement calls already made — and why

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
- **The app does not nag about what you have dealt with.** Paid marks silence the
  pill and the morning digest; archived parcels and held wishes are excluded.

---

## 10. How to talk to the owner

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

## 11. Open items

- **Nothing in this app has been verified visually.** Grain strength, the lit
  edge, the shape morph on a target-hit card, the appraisal card's states — all
  built and tested, none of them seen.
- **The Glance widget receiver is `android:exported="false"`.** If the widget ever
  stops redrawing, that attribute is the first thing to try.
- **Google's terms require displaying Search Suggestions** (`searchEntryPoint.renderedContent`)
  whenever grounding returns them. It is an HTML blob and the app has no WebView,
  so this is **not currently met**. Sources are shown; suggestions are not.
- **The stored appraisal has no expiry.** Google's caching allowance is capped at
  two years.
- **Five Gemini API keys were pasted into a chat transcript** on 16 September 2026
  and should be rotated.
- Retrieval of **independent lab results** (RTINGS, Which?, CHOICE) was proposed
  and not built: their failure mode is absence rather than error, which would let
  the appraisal make a real evaluative claim instead of restricting itself to what
  kind of thing something is.
