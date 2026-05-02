# How it works

This document explains the technical approach behind both scripts — the XML structure they parse, the rule they enforce, and why each design decision was made. You do not need to read this to run the scripts, but it will help you understand, trust, and extend them.

---

## Confluence storage format

Confluence stores every page's content as an XML dialect called **storage format**. When you look at a page in the editor, what Confluence actually stores looks like this:

```xml
<p>Some introductory text.</p>

<ac:structured-macro ac:name="expand" ac:schema-version="1">
  <ac:parameter ac:name="title">Click to expand</ac:parameter>
  <ac:rich-text-body>
    <p>Content inside the expand macro.</p>
  </ac:rich-text-body>
</ac:structured-macro>
```

Every macro — whether it is a built-in Confluence macro or a marketplace app — is represented as an `<ac:structured-macro>` element with an `ac:name` attribute identifying which macro it is.

### The three body types

A macro can have one of three body types:

| Body type | XML element | Can contain nested macros? |
|---|---|---|
| Rich text | `<ac:rich-text-body>` | ✅ Yes — this is where nesting happens |
| Plain text | `<ac:plain-text-body>` | ❌ No — CDATA content, not parsed as XML |
| No body | *(absent)* | ❌ N/A |

The scripts only look inside `<ac:rich-text-body>` elements. Plain text bodies (used by `code`, `noformat`, etc.) are ignored entirely.

---

## What nesting looks like in XML

A nested macro is an `<ac:structured-macro>` that appears inside another macro's `<ac:rich-text-body>`:

```xml
<ac:structured-macro ac:name="expand">        ← outer macro
  <ac:rich-text-body>
    <ac:structured-macro ac:name="info">      ← inner macro (nested)
      <ac:rich-text-body>
        <p>This is the problem.</p>
      </ac:rich-text-body>
    </ac:structured-macro>
  </ac:rich-text-body>
</ac:structured-macro>
```

This is what breaks in Confluence Cloud's modern editor.

---

## The crucial constraint

The scripts enforce one rule that prevents false positives:

> **An inner macro only counts as nested if it descends from the outer macro's `<ac:rich-text-body>` — not just anywhere below the outer macro in the XML tree.**

Without this rule, a script that simply looks for "any macro below another macro in the tree" would produce floods of false positives. For example, two macros sitting side by side on a page are both "below" the root element, but neither is nested inside the other.

The scripts implement this by walking up the parent chain from each inner macro candidate. If the path from the inner macro to the outer macro's `<ac:rich-text-body>` passes through another `<ac:structured-macro>`, the inner macro is not an immediate child in the nesting hierarchy — it belongs to a deeper level and will be reported as part of that deeper level's chain instead.

---

## Why XmlParser, not XmlSlurper

Both scripts use `new XmlParser(false, false)` to parse page bodies. Groovy also has `XmlSlurper`, which is more commonly seen in examples. We use XmlParser for two specific reasons:

1. **Correct subtree traversal.** XmlParser builds a proper DOM tree. When you call `node.'**'` on an extracted node, it traverses only that node's subtree. XmlSlurper uses lazy `GPathResult` objects where `'**'` on an extracted node can silently traverse the full document — causing intermediate nesting levels to be missed.

2. **Reliable node identity.** The crucial constraint requires comparing nodes (`current != richTextBodyNode`). XmlParser nodes are proper Java objects with reference identity. XmlSlurper's GPathResult objects do not support reliable identity comparison.

The two `false` arguments mean:
- `false` — do not validate against a DTD (Confluence storage format has none)
- `false` — do not treat `ac:` as a namespace prefix; keep it as part of the element name

With namespace-aware parsing disabled, `node.name()` returns `"ac:structured-macro"` (with the prefix), and attributes are accessed via `node.attribute("ac:name")`.

---

## Why we wrap the body in a `<root>` element

Confluence page bodies are XML *fragments* — they have no single root element. A page might start with a `<p>` tag, or directly with a macro. XmlParser requires a single root element to parse correctly, so both scripts wrap the raw body:

```groovy
def wrappedXml = """<root
    xmlns:ac="http://www.atlassian.com/schema/confluence/4/ac/"
    xmlns:ri="http://www.atlassian.com/schema/confluence/4/ri/">
    ${rawBody}
</root>"""
```

The namespace declarations prevent parse errors on storage format fragments that reference `ac:` or `ri:` prefixed elements.

---

## Why one entry per outermost macro

The audit script (`02`) reports one finding per outermost macro, with the full nesting chain beneath it. It does not report every parent-child pair separately.

For example, `expand → panel → info` produces one finding:

```
Chain: expand → panel → info  (depth=3)
```

Not three findings:
```
expand contains panel
panel contains info        ← this would be noise
expand contains info       ← this would be a false impression
```

The single-entry approach is more useful to a content editor: "fix this one expand macro and two levels of nesting go away." It also avoids inflating the finding count with entries that all point to the same root cause.

---

## The `viaTable` flag

Some macros contain a table in their body, and a macro appears inside a table cell:

```xml
<ac:structured-macro ac:name="panel">
  <ac:rich-text-body>
    <table><tbody><tr><td>
      <ac:structured-macro ac:name="jira">...</ac:structured-macro>
    </td></tr></tbody></table>
  </ac:rich-text-body>
</ac:structured-macro>
```

This is still nesting — the inner macro is inside the outer macro's rich-text-body — but the remediation is different. You cannot simply "remove the outer macro wrapper." You need to extract the table from the macro body first, then deal with the inner macro. The `viaTable` flag surfaces this distinction so the customer knows the fix is more involved.

---

## The `isThirdParty` heuristic

The audit script flags a macro as third-party if its name contains a hyphen **and** it is not in a small list of known Atlassian macros (e.g. `table-plus`, `recently-updated`).

This is a heuristic, not a registry lookup. It will:
- **Miss** some marketplace macros whose names do not contain hyphens
- **Flag** some custom in-house macros whose names happen to contain hyphens

Treat third-party flags as "worth checking" rather than "definitely a marketplace macro."

---

## ContainerManager vs ComponentAccessor

Confluence DC uses `com.atlassian.spring.container.ContainerManager` to access Spring beans. This is the Confluence equivalent of Jira's `ComponentAccessor`. Do not use `ComponentAccessor` in Confluence scripts — it is Jira-specific and will not resolve.

```groovy
// ✅ Correct for Confluence DC
def pageManager = ContainerManager.getComponent('pageManager') as PageManager

// ❌ Wrong — Jira only
def pageManager = ComponentAccessor.getComponent(PageManager)
```

---

## Static type checker warnings

The Script Console's static type checker will underline `com.atlassian.confluence.*` imports in red and report "unable to resolve class." These are **false positives**. The classes exist at runtime — the checker simply does not have Confluence's classpath available at edit time. The scripts run correctly despite these warnings.
