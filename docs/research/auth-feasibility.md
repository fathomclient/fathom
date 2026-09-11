# Authentication Feasibility Spike

Question: **can a new third-party Minecraft: Java Edition launcher obtain production
Microsoft authentication today?**

Date of investigation: **2026-08-24**. Everything below is a snapshot; the gate
this document describes is operated by Microsoft/Mojang and can change without
notice.

Status: **spike only.** No production authentication was built or extended as
part of this work.

---

## 0. Answer first

**UNCLEAR / APPROVAL-DEPENDENT** — leaning positive. See §11 for the full
reasoning.

There *is* a currently documented, currently live, first-party path. It is free.
It was demonstrably granted to at least one apparently-individual hobbyist
developer in June 2026. But it is a **manual, discretionary allow-list review**
with no published eligibility criteria and no published SLA, so nobody can
promise the outcome in advance — including us.

---

## 1. The authentication chain, and exactly where it breaks

```
1. Microsoft OAuth 2.0        login.microsoftonline.com/consumers      OK
       |                      scope: XboxLive.signin offline_access
       v
2. Xbox Live user auth        user.auth.xboxlive.com/user/authenticate OK
       v
3. XSTS authorization         xsts.auth.xboxlive.com/xsts/authorize    OK
       v
4. Minecraft Services         api.minecraftservices.com                <-- 403 HERE
                              /authentication/login_with_xbox
       v
5. Entitlement check          /entitlements/mcstore                    (never reached)
       v
6. Profile                    /minecraft/profile                       (never reached)
```

The reported failure is real and its shape is exactly as described in the brief.
Steps 1–3 succeed for any newly registered Entra application, because none of
them know or care about Minecraft. The wall is step 4:

```json
{
  "path" : "/authentication/login_with_xbox",
  "errorMessage" : "Invalid app registration, see https://aka.ms/AppRegInfo for more information"
}
```

**This is the single most important structural fact in this document:** the gate
is at the *Minecraft Services* layer, not the Entra layer and not the Xbox
layer. Any amount of Azure configuration correctness will not move it. A
launcher can therefore get all the way through a convincing-looking Microsoft
sign-in — the user types their password, consents, and comes back — and only
then fail.

---

## 2. Required Microsoft configuration

Confirmed by cross-referencing Microsoft Learn, Minecraft Wiki and a launcher
maintainer's 2026 write-up (§5). All three agree.

| Setting | Value | Why |
|---|---|---|
| Directory | **`consumers`** tenant | Minecraft accounts are consumer MSAs. `common` or an AAD tenant id fails. |
| Supported account types | **Personal Microsoft accounts only** | Same reason. |
| Client type | **Public client / native** | Desktop app; no secret can be kept. |
| **Allow public client flows** | **Yes** | Without it: `AADSTS70002: The provided client is not supported for this feature`. |
| Client secret | **None** | A public client must not have one. |
| Redirect URI | none needed for device code; `https://login.microsoftonline.com/common/oauth2/nativeclient` for the loopback/native flow | We chose device code, so this is moot for us. |
| Scope | `XboxLive.signin offline_access` | `offline_access` is what yields the refresh token. |
| Cost | **Free.** Entra ID free tier; a Microsoft account is enough — no Azure subscription, no credit card. | |

### 2.1 Supported OAuth flow for a native desktop app

Both work; we picked **device code**:

- **Device code flow** — no redirect URI, no local HTTP listener, works headless.
- **Authorization code + PKCE on a loopback redirect** — needs a temporary local
  HTTP server.

Device code avoids binding a local socket, which is one less attack surface and
one less firewall prompt. That decision predates this spike and this spike does
not change it.

---

## 3. Does `XboxLive.signin` itself require special approval?

**No — and this is a widely repeated misconception worth killing.**

Two separate things get conflated:

| Layer | Approval needed? | Evidence |
|---|---|---|
| Getting `XboxLive.signin` from Entra | **No.** Using the `consumers` authority grants it without pre-adding it to the app registration. | Microsoft Q&A, community-verified 2023-10-10, consistent with observed behaviour |
| Calling `api.minecraftservices.com` | **Yes.** The client ID must be allow-listed by Mojang. | The 403 above; `aka.ms/AppRegInfo` |

So "I can't find XboxLive.signin in the Azure portal permission picker" is not
the problem, and adding it is not the fix. The problem is downstream.

### 3.1 The ID@Xbox claim — contradicted

A February 2026 Microsoft Q&A thread asks precisely our question. The answer says
you must be registered in the **Xbox Developer Program**, and points at ID@Xbox.

**Treat this as unreliable.** Two reasons:

1. It was posted by a **Student Ambassador and Volunteer Moderator**, explicitly
   *not* a Microsoft employee answer. The page itself labels it as such.
2. It is contradicted by the June 2026 outcome in §5: a developer got an app
   approved through the Minecraft form, with no indication of ID@Xbox
   involvement.

ID@Xbox is also a poor fit on its own terms: it is a **game publishing** program,
and its onboarding expects a game to submit. A launcher is not a game.

I could not find any first-party statement making ID@Xbox a prerequisite for
Minecraft Services access. Flagged as an unresolved question (§9) rather than a
requirement.

---

## 4. The registration and approval path

### 4.1 Link resolution — verified directly

I resolved these myself on 2026-08-24 rather than trusting write-ups:

| Link | Result | Meaning |
|---|---|---|
| `aka.ms/AppRegInfo` | **HTTP 301** → `help.minecraft.net/hc/en-us/articles/16254801392141` | The error message points at a **first-party Minecraft help article**. This is an intentional, documented gate — not a bug or an outage. |
| `aka.ms/mce-reviewappid` | **HTTP 200** → `forms.cloud.microsoft/Pages/ResponsePage.aspx?id=…`, page title `Microsoft Forms` | The approval form is **live and responding today**. |
| `aka.ms/MinecraftAppReg` | falls through to Bing | Not a real link. Ignore any guide citing it. |

**Caveat, stated plainly:** `help.minecraft.net` now serves its articles from a
JavaScript single-page app. I could not extract the article body with any tool
available to me — plain fetch, the Zendesk content API, four Wayback snapshots
(2023-06 through 2026-05) and the in-app browser (its policy check was
unavailable) all returned the shell rather than the text. **I have confirmed the
article exists, is first-party, and is what the 403 points to. I have not read
its current wording.** Reading it is the first manual action in §10.

### 4.2 What the form asks for

From a launcher maintainer's June 2026 write-up (community source, §5):

- Application (client) ID
- Directory (tenant) ID
- A contact email matching the Azure account
- A short description of the launcher and why it needs access
- EULA acceptance

Notably **absent** from every account of the form: any company name, company
registration number, VAT id, or commercial-status question.

### 4.3 Is approval manual?

Yes. Every account describes a human review ending in an emailed decision.
One 2026 write-up states *"Submissions are reviewed weekly. Do not submit
multiple times."* Older write-ups said "about a week, possibly longer," and that
after approval you should allow up to 24 hours for it to take effect.

---

## 5. Evidence: is approval actually being granted in 2026?

This is the question that decides whether the project is blocked, so it deserves
dated, first-hand evidence rather than inference.

### 5.1 Approval granted — June 2026

[`swiftforyou/SwiftLauncher` issue #1](https://github.com/swiftforyou/SwiftLauncher/issues/1)

| Date | Event |
|---|---|
| 2026-05-31 | A user reports the exact 403: *"Invalid app registration, see https://aka.ms/AppRegInfo"* |
| 2026-06-04 | Maintainer: *"that specific error isn't caused by my launcher, it is because of my app hasn't been verified for Minecraft authentication, so we're waiting for an email from Minecraft itself."* |
| **2026-06-09** | Maintainer: *"last night i just got an email from Minecraft itself saying that our app has been successfully verified"* |
| 2026-06-09 | User confirms: *"i was able to log in and launcher works great!"* |

**This is the load-bearing data point.** A small, apparently individual, hobbyist
launcher project was reviewed and approved, and the approval demonstrably worked.
The path is not theoretically-open-but-actually-dead.

### 5.2 The failure mode is well understood — May/June 2026

[`Lighty-Launcher/LightyLauncherLib` issue #11](https://github.com/Lighty-Launcher/LightyLauncherLib/issues/11)

Maintainer, 2026-05-31: *"this is coming from Minecraft Services rejecting your
Azure AD client ID, not from the library. Since Mojang introduced stricter app
registration requirements, every new Azure AD app needs to be explicitly
approved before it can call Minecraft Services. Without that approval you'll
always hit this error regardless of how the library is configured."*

They then published a [step-by-step
guide](https://hamadi.gitbook.io/lightylauncher/crates/auth/docs/application-id-microsoft),
which is the source of the configuration table in §2 and the form fields in §4.2.

### 5.3 Cost — July 2026

[`MilkdromedaStudios/Dwine-Minecraft-Launcher` PR
#8](https://github.com/MilkdromedaStudios/Dwine-Minecraft-Launcher/pull/8)
(merged 2026-07-09) exists specifically to correct the misconception that Azure
costs money: the registration *"lives in Microsoft Entra ID's free tier and
needs a Microsoft account — **not** an Azure subscription, and no credit card"*,
and the Mojang form is a *"one-time free allow-list form"*.

Consistent with Microsoft Learn: app registration is included in the Entra ID
free tier.

### 5.4 Grandfathering

Multiple sources agree that applications registered **before** the requirement
was introduced continue to work unchanged. This explains why established
launchers were unaffected and why the problem looks invisible from the outside —
the people who would notice are precisely the people starting new projects.

### 5.5 Absence of evidence

I searched for reports of **denials**, indefinite waits, or the form being
closed, across GitHub issues, Microsoft Q&A and general web search. **I found
none.** That is weak evidence — absence of complaints is not proof of a healthy
process, and unsuccessful applicants have less reason to post than successful
ones — but it is worth recording that no pattern of rejection surfaced.

---

## 6. Confirmed facts vs community reports

Being explicit about this, because the difference matters for planning.

### Confirmed by my own direct observation

- `aka.ms/AppRegInfo` 301-redirects to a first-party `help.minecraft.net` article.
- `aka.ms/mce-reviewappid` returns HTTP 200 and is a live Microsoft Forms page.
- `aka.ms/MinecraftAppReg` is not a real link.
- **Minecraft version metadata and all game artifacts download with no
  authentication whatsoever** (§8) — verified by our own passing integration test.

### First-party documentation (exists, contents not read by me)

- The `help.minecraft.net` article behind `aka.ms/AppRegInfo` (§4.1 caveat).

### First-hand developer reports, dated (community, but specific and checkable)

- The June 2026 approval (§5.1).
- The failure-mode diagnosis and configuration guide (§5.2).
- The cost clarification (§5.3).

### Community claims I could not verify, or that are contradicted

- ID@Xbox / Xbox Developer Program enrolment being required (§3.1) — **contradicted**.
- "Reviewed weekly" / "about a week" timelines — plausible, single-sourced, no
  first-party confirmation.
- The exact form fields — consistent across sources, none of them first-party.

---

## 7. Is a legal entity or company required?

**No evidence that one is.** Weighing against it:

- The June 2026 approval went to what appears to be an individual hobbyist project.
- No account of the form mentions company details.
- The Entra registration needs only a personal Microsoft account.
- Even ID@Xbox — the stricter program that may not even apply — states there are
  no fees to apply and accommodates solo developers.

**Not proven, either.** The eligibility criteria are unpublished, and a
discretionary reviewer may weigh things they have never written down. Treat
"individuals can be approved" as *demonstrated at least once*, not as policy.

## 7.1 Are commercial applications treated differently?

**Unknown.** No source addresses it. Adjacent context that is *not* an answer but
bounds the question: the Minecraft Usage Guidelines permit developing tools and
services provided they do not appear official or endorsed, while prohibiting
commercial use of Mojang's own assets. Our project is non-commercial and
distinctly branded, so this is not on our critical path — but do not assume the
same answer would hold for a paid product.

---

## 8. Can the install pipeline be developed without authentication?

**Yes, completely — and this is already proven in our own repository.**

This is the finding that de-risks the whole schedule.

Mojang's metadata and content hosts are **unauthenticated public CDNs**. No
token, no header, no account:

| Host | Serves | Auth required |
|---|---|---|
| `piston-meta.mojang.com` | version manifest, version JSON | none |
| `launchermeta.mojang.com` | asset index (1.8.9 still points here) | none |
| `piston-data.mojang.com` | client jar, libraries | none |
| `launcher.mojang.com` | legacy libraries | none |
| `libraries.minecraft.net` | libraries | none |
| `resources.download.minecraft.net` | asset objects | none |

Our integration test `crates/launcher-core/tests/live_mojang.rs` exercises this
today and passes with no credentials of any kind:

```
verified 1.8.9: 37 libraries, 49 URLs allowlisted, sha1 d546f1707a3f
```

That test resolves the manifest, fetches and SHA-1-verifies the 1.8.9 version
document, and confirms every artifact URL in it resolves to an allow-listed host.

**Authentication is required only to *launch*, not to *install*.** So the
following can all be built, tested and finished while approval is pending:

- the entire download, verification and extraction pipeline
- version metadata parsing and platform rule evaluation
- classpath and natives handling
- profile isolation, settings, mod management
- JVM argument policy and launch-command construction
- the whole interface
- all 1.8.9 performance work — that runs inside the game process and is reachable
  through singleplayer

What genuinely cannot be done without an approved client ID: a real Microsoft
sign-in, a real entitlement check, and joining an online-mode server.

---

## 9. Unresolved questions

1. **What does the `aka.ms/AppRegInfo` article actually say today?** I confirmed
   it exists and is first-party but could not read it (§4.1). It may contain
   eligibility criteria that change this document's conclusions.
2. **Are the published eligibility criteria, if any, more restrictive than the
   evidence suggests?** Nothing found; nothing ruled out.
3. **Is ID@Xbox ever required?** One community claim says yes, one 2026 outcome
   suggests no (§3.1).
4. **Real approval turnaround for a project like ours.** One data point of
   roughly days-to-two-weeks. Not a distribution.
5. **What happens on rejection?** No reports found. Is there an appeal, or a
   reason given?
6. **Does approval survive?** Can it be revoked, does it need renewal, is it tied
   to the app name we submit?
7. **Commercial treatment** (§7.1).
8. **Is the Mojang entitlement signing key we compiled in still correct?**
   Pre-existing open question, unrelated to this spike, but it sits on the same
   critical path: we cannot verify it until we can complete a real sign-in.

---

## 10. Actions we must take manually

Ordered by urgency. Items 1–3 are on the critical path and should happen this
week — the review is manual and the clock starts when we submit.

1. **Read the `aka.ms/AppRegInfo` article in a real browser** and record its
   current text here. It is the only first-party statement of the rules and I
   could not read it.
2. **Register the Entra application** with exactly the §2 configuration.
   Free, no subscription. Record client ID and tenant ID.
3. **Submit `aka.ms/mce-reviewappid`.** Submit **once** — one source says
   duplicate submissions are unwelcome. Keep a copy of what we submitted.
4. **Record the submission date** and treat approval as an external dependency
   with an unknown, multi-week horizon.
5. **On approval:** verify the compiled-in Mojang entitlement public key against
   a real signed response (open question 8), and run the full chain end to end.
6. **Do not chase it.** No duplicate submissions, no second application.

### Explicitly not options

Listed so nobody rediscovers them under schedule pressure:

- Using another launcher's client ID. Prism Launcher's own fork guidance
  requires forks to replace its API keys with their own or blank them.
- Impersonating the official Minecraft launcher.
- Proxying our users' authentication through somebody else's approved application.
- Offline/cracked authentication, or any bypass of the entitlement check.

None of these is a shortcut; each is a different way of making the project
something we said it would not be.

---

## 11. Is production auth implementable today?

**Not by us, today — because we do not hold an approved client ID.** The code is
largely written and the chain is understood; the missing input is a credential
only Microsoft/Mojang can issue.

But the constraint is *administrative and pending*, not *architectural or
closed*. There is nothing to redesign.

### The verdict

> ### UNCLEAR / APPROVAL-DEPENDENT

**Why not YES.** Approval is a manual, discretionary review with no published
eligibility criteria, no published SLA, no documented appeal, and no
self-service route. We have one dated success and no failures, which is
encouraging but is not a guarantee. Anyone claiming certainty here is
overreading the evidence. We also have not read the one first-party document
that states the rules (§4.1), so there may be criteria we do not know about.

**Why not NO.** A legitimate path unambiguously exists and is live *today*:

- the official error message points at a first-party Minecraft help article;
- the official approval form returns HTTP 200 right now;
- it is free and needs no company, no subscription and no payment method;
- and it demonstrably produced a working approval for an individual developer's
  launcher in June 2026.

"There is no legitimate path" would be false, and would be the kind of false
that pushes a team toward the §10 "explicitly not options" list.

**What it means for the schedule.** Submit immediately (§10), then build
everything else. Per §8, approval blocks only sign-in, entitlement verification
and online-mode play — roughly the last mile. The install pipeline, the
interface, profiles, mods and all of the 1.8.9 performance work are unblocked
and testable today, and §12 keeps them that way.

---

## 12. Development strategy while approval is pending

Design the launcher so that *not having a client ID* is a normal, well-typed
state rather than a wall.

### 12.1 The abstraction

Four seams, so no other module knows how identity was obtained:

| Piece | Responsibility |
|---|---|
| `MinecraftIdentity` | Who is playing: UUID, username, access token, and **how it was obtained** |
| `AuthProvider` | Produces a `MinecraftIdentity`. Microsoft device-code in production. |
| `EntitlementVerifier` | Decides whether an identity may launch. Signature-verified in production. |
| `CredentialStore` | Persists the one long-lived secret. Already implemented. |

### 12.2 The development provider, and why it is safe

**Status: implemented and wired into the interface.**
`crates/launcher-core/src/auth/provider.rs`,
`crates/launcher-core/src/auth/development.rs`, behind `--features dev-auth`.

A development provider returns an obviously fake local identity so the interface,
profiles, mods and install pipeline can be exercised end to end.

Four independent barriers, strongest first:

1. **It does not exist in a release binary.** Gated behind a non-default Cargo
   feature, and a `compile_error!` fires if that feature is ever combined with a
   release profile. This is a **build failure**, not a runtime check — the code
   is not in the artifact to be enabled.
2. **It is not on by default even in debug.** It requires an explicit opt-in.
3. **It cannot authenticate to multiplayer** — not because we block it, but
   because it holds no valid token. Mojang's session server rejects it. The
   protection is physics, not policy.
4. **It is never presented as real.** Its identity is tagged
   `IdentityKind::Development`, its username is visibly fake, and the interface
   carries a persistent banner.

The entitlement gate is unchanged in release builds: the signature-verified
Mojang path is the only implementation compiled in.

Verified behaviour:

| Configuration | Result |
|---|---|
| default build | `providers 1`, Microsoft only — the module does not exist |
| default build, opt-in variable set | still `providers 1`; the variable cannot conjure code that was not compiled |
| `--features dev-auth`, no variable | `providers 2`; development one reports `NOT REAL` and refuses to activate |
| `--features dev-auth` + variable | development identity available, warning logged on every use |
| `--release --features dev-auth` | **compile error** |

### 12.3 What this unblocks

Everything in §8. When the client ID arrives, the production provider slots into
the same seam and the mock stops being built.
