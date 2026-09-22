# BudgetGuard brand kit — outline & timeline (D-022)

**Owner:** Priya (Product, UX & Localisation — branding)  
**Review:** Gugu (claims / UX) → Neo (implement) / Jules (social)  
**Date:** 2026-09-17  
**Status:** Outline v0 — kit assets follow  

---

## 1. Goal

A **full brand kit** usable on the **Android app and web companion**, not messaging-only. Must reinforce First trustworthy week product law:

- **Safe to spend** ≠ cash  
- SMS = bank-alert signal (not reconciled statement truth)  
- No invoice / payment oversell (Limited mode / forbidden claims with Jules)  
- Android primary; web companion  

No paid fonts/tools/stock without Edward via Gugu.

---

## 2. Kit contents (checklist)

| # | Deliverable | Notes |
| --- | --- | --- |
| 1 | Brand purpose / positioning one-pager | With Jules; respect forbidden claims / Limited mode |
| 2 | Logo system | Primary, mono, app icon mark + clear space / don’ts |
| 3 | Colour palette | Light (+ dark if needed); accessible contrast notes |
| 4 | Typography | Web + Android-friendly; free/open licenses default |
| 5 | Spacing / radius / elevation tokens | Shared scale |
| 6 | Icon style guidance | Line weight, corner, do/don’t |
| 7 | Voice & tone (en-ZA) | Synced with Jules |
| 8 | Example applications | Home (Safe to spend), web header, Jules avatar/cover specs |
| 9 | Handoff pack | CSS variables + Android theme tokens for Neo |

---

## 3. Timeline vs Must M1–M6

Must M1–M6 remains the eng bar for the First trustworthy week. Branding must **not** block STS honesty, entity isolation, or invoice review.

### Phase A — Before / parallel to Must (this week)

**Brand scaffold that unblocks honest UI copy and theming without redesigning flows.**

| Item | Why now | Owner |
| --- | --- | --- |
| A1. Positioning + claims one-pager (draft) | Jules + Home copy must not drift | Priya + Jules → Gugu |
| A2. Voice & tone en-ZA (short) | Locks “Safe to spend”, “Plan leftover (not cash)”, “workspace” | Priya + Jules |
| A3. Colour + type **token draft** (CSS + Android names) | Neo can theme M1 Home without inventing palette | Priya → Neo |
| A4. App icon mark **v0** (simple geometric) | Store / launcher placeholder | Priya |
| A5. Example: Home Safe-to-spend frame (wire + tokens) | Proves trust hierarchy visually | Priya |

**Out of Phase A:** full logo lockup polish, marketing site, social campaign templates beyond basic avatar/cover specs.

### Phase B — After Must M1–M6 land on `dev` (or parallel if eng idle)

| Item | Why later | Owner |
| --- | --- | --- |
| B1. Full logo system + don’ts | Needs A settled; not blocking STS | Priya → Gugu → Neo |
| B2. Dark theme (if required) | After light tokens proven on Home | Priya + Neo |
| B3. Icon set guidance + sample icons | After navigation IA stable | Priya |
| B4. Web companion header / marketing page application | Companion secondary | Priya + Jules |
| B5. Jules social pack (avatar, cover, post templates) | After claims one-pager approved | Jules from kit |
| B6. Kit v1 handoff zip (Figma-free: SVG + `tokens.json` + markdown) | Implementation pack | Priya → Neo |

### Explicit non-goals (until Edward via Gugu)

- Paid font licenses, paid icon packs, agency tools  
- Rebrand that changes “Safe to spend” product semantics  
- Payment / “we pay suppliers” creative  

---

## 4. Working process

1. **Priya** drafts kit sections in `docs/brand/`  
2. **Jules** co-owns positioning + voice (claims)  
3. **Gugu** claims/UX review (approve before Neo paints production UI)  
4. **Neo** implements tokens on Android (+ web if in scope)  
5. **Jules** applies approved kit to social  

Blockers escalate to **Gugu only** (D-015).

---

## 5. Immediate next steps (kit v0)

1. Sync Jules on positioning constraints (forbidden claims / Limited mode)  
2. Draft colour + type tokens (open fonts: e.g. Inter or Roboto Flex — confirm license)  
3. Draft app icon mark v0 + Home STS visual hierarchy  
4. Publish `docs/brand/kit-v0.md` + `tokens.css` / `tokens-android.md` for Gugu review  

**Target:** kit v0 outline assets within 24–48h wall-clock while Must eng continues on `dev`.

---

## 6. Open questions for Gugu (not Edward)

1. Working product name lock: **BudgetGuard** vs other?  
2. Dark mode required for v0 or Phase B?  
3. Any existing founder mark / colour preference to honour?  
