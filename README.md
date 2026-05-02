# Confluence DC — Nested Macro Finder

> ℹ️ **Always test in staging first.** Before running these scripts against production, run them on a staging instance that mirrors prod as closely as possible — same Confluence version, same ScriptRunner version, same apps installed, and ideally a recent copy of production data. The scripts are read-only, but the Script Console is a powerful tool and a staging dry run is the right habit for *anything* you run there.
 

## What is this for?

If you run **Confluence Data Center** and you're planning to move to **Confluence Cloud**, some of your pages will break in ways that aren't obvious until after you've migrated. This repository helps you find those pages *before* you migrate, so you can fix them in advance.

## Who is this for?

You'll get the most out of this if you're a **Confluence administrator** or a **migration consultant**. If you're neither, the relevant person on your team is probably whoever manages your Confluence instance — send them this link.

## A 30-second primer (skip if you're a Confluence admin)

- **A macro** is a content block you drop into a Confluence page. Common examples: an info banner, an expand/collapse, a table of contents, a status lozenge.
- **Nesting** means putting one macro inside another — for example, an info banner *inside* an expand block.
- **Why this matters for migration:** Confluence Cloud's modern editor doesn't support arbitrary macro nesting. When a nested macro hits Cloud, it either renders incorrectly or gets locked into a **Legacy Content Macro** — a read-only fallback that Cloud uses when it can't render old content properly. It works today, but Atlassian is deprecating it in 2026.

That's the whole problem in one paragraph. The rest of this README explains how the scripts find those pages.

---

> **Nested macros are one of the most common silent causes of page degradation after a Confluence Cloud migration.** Pages migrate successfully, but render incorrectly or get quarantined inside a Legacy Content Macro wrapper when Cloud's editor tries to open them. The legacy editor that tolerates the old structure is being deprecated by Atlassian in 2026 — so this is a time-bound problem, not optional housekeeping.
>
> There is no native Confluence feature that surfaces this. This repository gives you two **ScriptRunner** scripts that do. (ScriptRunner is an Adaptavist app for Confluence that lets administrators run custom Groovy scripts against the instance.)

---

## ⚠️ This is a proof of concept

These scripts are **starting points for investigation, not production-grade audit tools**. They will find the most common nesting patterns reliably, but they do not cover every edge case, every macro type, or every way content can be structured. Treat their output as a list of pages worth a closer look — not a definitive report you hand to a migration team without review.

The suggestions in the output are based on macro type and nesting pattern. They are informed guesses, not instructions. Only you know what the page author intended.

---

## Why two scripts?

| Script | What it is | When to use it |
|---|---|---|
| `01-foundation.groovy` | ~100 lines. The core idea, nothing more. Plain text output. | Start here. Read it. Understand how the detection works before running anything on a real instance. |
| `02-migration-audit.groovy` | ~700 lines. The same idea, extended with HTML output, remediation hints, blog post scanning, third-party macro detection, and more. | Use this for real migration prep once you understand the foundation. |

These two scripts are the same concept at two levels of detail. The foundation teaches you the approach. The audit script shows you what that approach looks like when built out and extended (and this can be taken further with ScriptRunner).

---

## The problem, in detail

Confluence stores page content as **XML** (a structured text format). Every macro on a page is an `<ac:structured-macro>` element. When one macro contains another inside its `<ac:rich-text-body>` (the macro's body content), that is nesting:

```xml
<ac:structured-macro ac:name="expand">
  <ac:rich-text-body>
    <ac:structured-macro ac:name="info">   ← nested macro
      <ac:rich-text-body>
        <p>This will break in Cloud.</p>
      </ac:rich-text-body>
    </ac:structured-macro>
  </ac:rich-text-body>
</ac:structured-macro>
```

Confluence Cloud's modern editor does not support arbitrary macro nesting. When it encounters this pattern, it either renders the page incorrectly or wraps the content in a Legacy Content Macro — a read-only fallback that will stop working when the legacy editor is deprecated.

**Why existing tools don't solve this:**

- **CQL** (Confluence Query Language, Confluence's built-in search syntax) can find pages that *use* a macro — e.g. `macro = "expand"` — but cannot detect whether macros are nested inside each other.
- **Admin → Macro Usage report** shows instance-wide counts of each macro, but again, no nesting information.

That is the gap these scripts fill.

---

## Quick start

### What you need
- Confluence **Data Center** (the self-hosted enterprise version) with **ScriptRunner** installed
- Confluence **administrator** access (you can't use the Script Console without it)
- A space key to test against (start small — don't scan your whole instance first)

> A **space key** is the short identifier for a Confluence space, e.g. `DEV` or `HR`. You can see it in the URL when you're inside a space.

### Running a script

1. Go to **Confluence Admin → ScriptRunner → Script Console**
2. Paste the contents of the script you want to run
3. Edit the configuration constants at the top of the script (at minimum, set `SPACE_KEY`)
4. Click **Run**
5. Results appear in the **Result** panel below the editor

### Configuration

Both scripts share the same top-of-file constants:

```groovy
def SPACE_KEY = "DEV"   // A space key, or null to scan all spaces
```

The audit script (`02`) has two additional constants:

```groovy
def OUTER_MACRO_FILTER = null  // e.g. "expand" to narrow results, or null for all
def MAX_PAGES          = null  // e.g. 50 for a quick sample, or null for no limit
```

**Start with a single space key.** On large instances, scanning all spaces can take several minutes. Scope to one space first, verify the results look right, then broaden.

---

## Example output

### Foundation script (`01`)

```
=== Nested Macro Findings (3) ===

Space: DEV
  Page : Architecture Overview (id=12345)
  Outer: [expand]  →  Inner: info

  Page : Release Notes (id=67890)
  Outer: [section]  →  Inner: column

  Page : Onboarding Guide (id=11111)
  Outer: [panel]  →  Inner: info, note
```

### Audit script (`02`)

The audit script returns a styled HTML table rendered directly in the Script Console result panel:

- **Stats bar** — pages scanned, total findings, deep nests, via-table cases, third-party macros, parse failures
- **Per-space tables** — sorted worst-first (deepest nesting at the top)
- **Clickable links** — view the page or jump straight into edit mode
- **Macro title** — if the author labelled their macro (e.g. an expand titled "Deployment steps"), that label is shown so you can Ctrl+F for it on the page
- **Guidance column** — a suggested first step for each finding, based on macro type

See [`docs/how-it-works.md`](docs/how-it-works.md) for a full explanation of what each column means and how the detection works.

---

## Scope and limitations

Both scripts scan **current published page bodies** only.

| Content type | Scanned? | Notes |
|---|---|---|
| Published pages | ✅ Yes | Current version only |
| Blog posts | ✅ Yes (audit script only) | Same storage format as pages |
| Page templates | ❌ No | Templates can contain nested macros that stamp into every new page — worth a separate investigation |
| Comments | ❌ No | Rarely contain complex macro nesting |
| Page history / drafts | ❌ No | Only the current published version is scanned |
| `ac:layout` cells | ⚠️ Partial | Macros *inside* layout cells are scanned. The layout cell itself is not treated as an outer macro. |

### What the scripts detect

- Any `<ac:structured-macro>` whose `<ac:rich-text-body>` contains another `<ac:structured-macro>`, at any depth
- Old layout macros (`section`, `column`) — always nested by design, always a migration concern
- Third-party / marketplace macros — detected by name pattern (hyphen in name, not in known-Atlassian list)
- Macros nested via a table inside a macro body (`panel → table → jira`)

### What the scripts do not detect

- Macros inside `<ac:plain-text-body>` (e.g. code blocks) — impossible by definition, the content inside isn't parsed as XML
- Macros inside top-level table cells where the table is not inside a macro body — not nesting
- Every possible marketplace macro — the third-party heuristic uses a naming convention, not a registry

---

## What this is not

**This is not a fix tool.** Neither script modifies any page content. They are read-only.

**This is not a guarantee.** Whether a specific nested macro breaks in Cloud depends on the macro version, the Cloud app equivalent, and your instance configuration. These scripts surface candidates for review — a human needs to make the final call on each one.

**This is not a complete migration audit.** Nested macros are one migration risk among many. This tool addresses one specific problem. It does not replace a full migration assessment.

**This is not a substitute for testing.** Always test migrated pages in a Cloud sandbox before committing to production migration.

---

## Repository structure

```
confluence-dc-nested-macro-finder/
├── LICENSE
├── README.md
├── docs/
│   └── how-it-works.md        ← How the XML detection works
└── scripts/
    ├── 01-foundation.groovy   ← Simple, readable, ~100 lines
    └── 02-migration-audit.groovy  ← Full-featured, HTML output
```

---

## Where to go next

**If you want to understand the code** — read [`docs/how-it-works.md`](docs/how-it-works.md). It explains the XML structure, the crucial constraint, and why each design decision was made.

**If you want to extend the scripts** — both scripts are intentionally open. Common extensions:
- Export results to CSV
- Scope to a specific macro type with `OUTER_MACRO_FILTER`
- Schedule the audit script as a recurring ScriptRunner job
- Add a fix script that removes outer macro wrappers (with dry-run mode)

**If you want to fix the problems** — ScriptRunner can do that too. A follow-up script can remove outer macro wrappers in bulk, with a dry-run mode and full version history as a safety net. The complex cases (layout macros, via-table nesting, third-party macros) will always need manual review.

**If you want help** — [Adaptavist](https://www.adaptavist.com) built ScriptRunner and supports customers through Confluence Cloud migrations. If you need a deeper assessment or hands-on help, [get in touch](https://www.adaptavist.com/contact).

---

## Requirements

- Confluence Data Center (tested on 7.x and 8.x)
- ScriptRunner for Confluence (any recent version)
- Confluence administrator permissions to access the Script Console

---

*Built with ScriptRunner for Confluence Data Center. Shared as a proof of concept by Adaptavist.*
