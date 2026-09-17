# Android Brand Token Mapping (Kit v0)

**Date:** 2026-09-17  
**Status:** Implemented (thin wire)

## Token Mapping: CSS → Android

| Kit v0 Token (CSS) | Android Palette | Hex Value | Use |
|--------------------|-----------------|-----------|-----|
| `--bg` | `Palette.bg` | `#F7F4EF` | App/page background (warm paper) |
| `--surface` | `Palette.surface` | `#FFFFFF` | Cards |
| `--ink` | `Palette.ink` | `#1C1917` | Primary text |
| `--ink-muted` | `Palette.inkMuted` | `#57534E` | Secondary text |
| `--brand` | `Palette.brand` | `#0F766E` | Primary actions, key numbers (teal) |
| `--brand-pressed` | `Palette.brandPressed` | `#115E59` | Pressed primary button state |
| `--accent` | `Palette.accent` | `#C4A35A` | Gold highlight (sparse, NOT for STS) |
| `--danger` | `Palette.danger` | `#B91C1C` | Errors, negative STS |
| `--warning` | `Palette.warning` | `#B45309` | Caution/not reconciled |
| `--border` | `Palette.border` | `#E7E5E4` | Dividers |

## XML Resources

Kit v0 tokens are also defined in `app/src/main/res/values/colors.xml` for use in XML layouts.

## Legacy Aliases

For backward compatibility, the following legacy aliases map to kit-v0 tokens:

- `Palette.paper` → `Palette.bg`
- `Palette.canvas` → `Palette.surface`
- `Palette.muted` → `Palette.inkMuted`
- `Palette.moss` → `Palette.brand`
- `Palette.coral` → `Palette.danger`
- `Palette.line` → `Palette.border`

Other legacy colors (`inkSoft`, `inkRaised`, `mint`, `sage`, `peach`) remain unchanged for gradual migration.

## Safe to Spend Color Rule

**CRITICAL:** The "Safe to spend" hero value and label must NEVER use `Palette.accent` (gold).

Current implementation uses `Color.WHITE` on a dark `Palette.ink` card background, which complies with this rule.

Future refinement may use `Palette.brand` (teal) on a `Palette.surface` (white) card per the kit-v0 visual spec, but this is outside the scope of this thin token wire.

## Locked UI Copy (from STS #7)

- Safe to spend
- Plan leftover (not cash)
- Help: Use Safe to spend before you buy something.
- Entity label: workspace (code may say "entity")

## Dark Mode

Phase B — not implemented in kit v0.
