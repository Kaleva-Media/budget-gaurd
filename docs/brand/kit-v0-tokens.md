# BudgetGuard brand kit v0 — tokens (draft)

**Status:** Draft for Gugu claims/UX review — not production-locked  
**Date:** 2026-09-17  
**Licenses:** Prefer free/open; no paid fonts without Edward via Gugu  

---

## Positioning (placeholder — Jules to confirm)

**Product name (locked):** BudgetGuard  

**One-liner (Jules, Limited):** Android budgeting for South Africans who keep personal and business money in separate spaces — what your plan says you can still spend, not cash in the bank.

**Trust pillars (product law):**
1. **Safe to spend** is the number to use before you buy — not the same as bank cash alone, and not plan surplus.
2. SMS balances are **bank-alert signals**, not a full statement reconcile.
3. Invoices never change the plan until you approve; no payment promises.

---

## Colour (light, v0)

| Token | Hex | Use |
| --- | --- | --- |
| `--bg` | `#F7F4EF` | App / page background (warm paper) |
| `--surface` | `#FFFFFF` | Cards |
| `--ink` | `#1C1917` | Primary text |
| `--ink-muted` | `#57534E` | Secondary text |
| `--brand` | `#0F766E` | Primary actions, key numbers (teal) |
| `--brand-pressed` | `#115E59` | Pressed primary |
| `--accent` | `#C4A35A` | Sparse highlight (SA gold-warm) — not for STS |
| `--danger` | `#B91C1C` | Errors, negative STS |
| `--warning` | `#B45309` | Caution / not reconciled |
| `--border` | `#E7E5E4` | Dividers |

### Contrast notes (v0)

- `ink` on `bg` / `surface`: aim WCAG AA for body  
- `brand` on `surface` for buttons: verify AA for text buttons  
- **Never** use `accent` gold for Safe to spend (reserved for non-cash decoration)  
- Negative STS: `danger` on `surface`, not red fill behind large currency alone without label  

### Dark mode

**Phase B** — not in v0 unless Gugu requires it.

---

## Typography (v0)

| Role | Web | Android | Notes |
| --- | --- | --- | --- |
| UI / body | **Inter** (OFL) | **Inter** or system `sans-serif` if bundling deferred | Numbers tabular if possible |
| Display / brand wordmark | Inter SemiBold | same | Avoid playful scripts |
| Mono (debug only) | system mono | system mono | Not customer-facing |

**Locked UI strings (product):**
- `Safe to spend`
- `Plan leftover (not cash)` (default secondary)
- Help first line: `Use Safe to spend before you buy something.`
- UI word: **workspace** (code may say entity)

---

## Spacing / radius / elevation

| Token | Value |
| --- | --- |
| space-1…6 | 4, 8, 12, 16, 24, 32 px |
| radius-sm | 8 px |
| radius-md | 12 px |
| radius-lg | 16 px |
| elevation-1 | 0 1px 2px rgba(28,25,23,.08) |
| elevation-2 | 0 4px 12px rgba(28,25,23,.10) |

---

## Logo / mark (v0 direction)

**App icon mark:** Simple shield-or-ledger mark in `--brand` on `--bg` or white — geometric, readable at 48dp. Avoid rand signs as sole mark (clutter at small sizes).  

**Wordmark:** “BudgetGuard” in Inter SemiBold, `--ink`; optional teal underline bar under “Guard”.  

**Don’ts:** Drop shadows on wordmark; gradient STS numbers; “cash” or “available” as synonym for plan leftover.

*(SVG assets in kit v0.1 — next file.)*

---

## Home hierarchy (Safe to spend)

1. Workspace name (muted)  
2. Period range (muted)  
3. **Safe to spend** — largest, `--brand` or `--ink`  
4. Optional **Plan leftover (not cash)** — smaller, muted  
5. Footnote / help: not reconciled; use Safe to spend before you buy  

---

## CSS variables (handoff sketch)

```css
:root {
  --bg: #F7F4EF;
  --surface: #FFFFFF;
  --ink: #1C1917;
  --ink-muted: #57534E;
  --brand: #0F766E;
  --brand-pressed: #115E59;
  --accent: #C4A35A;
  --danger: #B91C1C;
  --warning: #B45309;
  --border: #E7E5E4;
  --radius-md: 12px;
  --space-4: 16px;
}
```

## Android theme names (sketch)

`colorBg`, `colorSurface`, `colorInk`, `colorInkMuted`, `colorBrand`, `colorDanger`, `colorWarning`, `colorBorder` — map 1:1 to CSS.

---

## Decisions (Gugu 2026-09-17)

- Name: **BudgetGuard** locked  
- Dark mode: **Phase B** (light-only in v0)  
- No founder mark/colour to honour  
- Jules claims sync: see `01-purpose-positioning.md` + `02-voice-and-tone.md`  
