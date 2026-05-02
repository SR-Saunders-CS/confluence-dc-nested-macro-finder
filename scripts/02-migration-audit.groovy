import com.atlassian.confluence.pages.PageManager
import com.atlassian.confluence.setup.settings.SettingsManager
import com.atlassian.confluence.spaces.SpaceManager
import com.atlassian.spring.container.ContainerManager

// ── CONFIGURATION ─────────────────────────────────────────────────────────────
// Edit these values before running. Everything else is automatic.
//
// SPACE_KEY        — A single space key like "DEV", or null to scan all spaces.
// OUTER_MACRO_FILTER — A macro name like "expand" to narrow results, or null.
// MAX_PAGES        — Stop after this many pages/posts (useful for a quick
//                    sample on a large instance). null means no limit.
// ─────────────────────────────────────────────────────────────────────────────
def SPACE_KEY          = null
def OUTER_MACRO_FILTER = null
def MAX_PAGES          = null

// ── KNOWN ATLASSIAN MACROS ────────────────────────────────────────────────────
// Used to decide whether an outer macro is third-party. A macro is flagged
// as third-party if its name contains a hyphen AND it is not in this list.
// Hyphens in macro names are a strong signal of a marketplace app.
// ─────────────────────────────────────────────────────────────────────────────
def KNOWN_ATLASSIAN_MACROS = [
    'panel', 'info', 'note', 'warning', 'expand', 'code',
    'section', 'column', 'tip', 'details', 'excerpt', 'status',
    'jira', 'layout', 'noformat', 'html', 'toc', 'children',
    'recently-updated', 'include', 'excerpt-include', 'anchor',
    'color', 'table-plus', 'layout-section', 'layout-cell'
] as Set

// ── GET CONFLUENCE SERVICES ───────────────────────────────────────────────────
// ContainerManager is the correct way to get Spring beans in Confluence DC.
// ─────────────────────────────────────────────────────────────────────────────
def pageManager    = ContainerManager.getComponent('pageManager')    as PageManager
def spaceManager   = ContainerManager.getComponent('spaceManager')   as SpaceManager
def settingsManager = ContainerManager.getComponent('settingsManager') as SettingsManager

// ── RESOLVE THE BASE URL ──────────────────────────────────────────────────────
// We get the base URL from Confluence's own settings rather than hardcoding
// a context path like "/wiki". The context path varies by installation —
// some instances are deployed at /wiki, others at the root /.
// getBaseUrl() always returns the correct value as configured by the admin.
// We strip any trailing slash so we can safely append urlPath (which starts
// with /) without creating a double-slash.
// ─────────────────────────────────────────────────────────────────────────────
def baseUrl = settingsManager.getGlobalSettings().getBaseUrl()
if (baseUrl.endsWith('/')) {
    baseUrl = baseUrl[0..-2]
}

// ── DECIDE WHICH SPACES TO SCAN ──────────────────────────────────────────────
def spacesToScan = SPACE_KEY
    ? [spaceManager.getSpace(SPACE_KEY)].findAll { it != null }
    : spaceManager.getAllSpaces()

if (!spacesToScan) {
    return "⚠️  No spaces found. Check your SPACE_KEY value."
}

// ── COLLECT ALL CONTENT TO SCAN ───────────────────────────────────────────────
// We scan both pages and blog posts. Blog posts use the same storage format
// as pages and can contain the same nested macros, but getPages() silently
// skips them — they must be fetched separately.
//
// We do not scan templates, comments, or historical versions. Those are
// noted in the report header so the customer knows the scope.
// ─────────────────────────────────────────────────────────────────────────────
def allContentToScan = []

spacesToScan.each { space ->
    log.info("Collecting content from space: ${space.key} — ${space.name}")
    allContentToScan.addAll(pageManager.getPages(space, true))
    allContentToScan.addAll(
        pageManager.getBlogPosts(space, true)
    )
}

if (MAX_PAGES != null) {
    allContentToScan = allContentToScan.take(MAX_PAGES)
}

// ── SCAN EACH PAGE AND BLOG POST ──────────────────────────────────────────────
// For each piece of content, parse its body XML and look for macros that
// contain other macros inside their rich-text bodies. We track how many
// pages we scanned and how many failed to parse, so the report header
// gives the customer an honest picture of coverage.
// ─────────────────────────────────────────────────────────────────────────────
def allFindings   = []
def pagesScanned  = 0
def parseFailures = 0

allContentToScan.each { content ->
    pagesScanned++

    def rawBody = content.getBodyContent()?.getBody()
    if (!rawBody) return  // Empty page — nothing to scan.

    def parsedBody = parseStorageFormatXml(rawBody, content.title)
    if (parsedBody == null) {
        parseFailures++
        return  // Parse failed — logged inside the function. Move on.
    }

    def pageFindings = findNestedMacrosInPage(
        parsedBody, content, baseUrl, OUTER_MACRO_FILTER, KNOWN_ATLASSIAN_MACROS
    )
    allFindings.addAll(pageFindings)
}

log.info("Scan complete. Pages: ${pagesScanned}, " +
         "Findings: ${allFindings.size()}, " +
         "Parse failures: ${parseFailures}")

// ── FORMAT AND RETURN THE REPORT ──────────────────────────────────────────────
return formatGroupedReport(allFindings, pagesScanned, parseFailures)


// ═══════════════════════════════════════════════════════════════════════════════
// HELPER FUNCTIONS
// Defined below the main script body so the logic above reads like a
// plain-English description of what happens, top to bottom.
// ═══════════════════════════════════════════════════════════════════════════════

// Wraps a raw storage-format XML fragment in a root element and parses it.
//
// Why a wrapper? Confluence page bodies are XML fragments with no single
// root element. XmlParser requires one, so we add a temporary <root>.
//
// Why XmlParser(false, false) rather than XmlSlurper?
//   XmlParser builds a proper DOM tree where node.parent() works correctly
//   and node identity comparison (==) is reliable. Both are essential for
//   the crucial constraint: an inner macro only counts as nested if it
//   descends from the outer macro's ac:rich-text-body specifically — not
//   just anywhere below it in the XML tree. XmlSlurper's lazy GPathResult
//   objects do not support reliable parent traversal or identity comparison,
//   which would cause false positives and missed intermediate nesting levels.
//
// With namespaceAware = false, element names retain their prefix, so
// node.name() returns "ac:structured-macro" and attributes are accessed
// via node.attribute("ac:name").
//
// Returns null if the XML cannot be parsed, so the caller can skip the page.
def parseStorageFormatXml(String rawBody, String pageTitle) {
    def wrappedXml = """<root
        xmlns:ac="http://www.atlassian.com/schema/confluence/4/ac/"
        xmlns:ri="http://www.atlassian.com/schema/confluence/4/ri/">
        ${rawBody}
    </root>"""

    try {
        return new XmlParser(false, false).parseText(wrappedXml)
    } catch (Exception parseError) {
        log.warn("Could not parse '${pageTitle}': ${parseError.message}")
        return null
    }
}

// Scans a single parsed page and returns a list of findings — one per
// outermost macro that has at least one nested macro inside its body.
//
// "Outermost" means the macro is not itself inside another macro's
// ac:rich-text-body. Reporting one entry per outermost macro (with the
// full chain beneath it) is more useful to the customer than reporting
// every parent-child pair separately: it shows "fix this one panel and
// three nested macros go away" rather than three separate findings for
// the same root cause.
def findNestedMacrosInPage(parsedBody, content, baseUrl, outerMacroFilter, knownMacros) {
    def findings = []

    def allMacroNodes = parsedBody.'**'.findAll {
        it instanceof groovy.util.Node &&
        it.name() == 'ac:structured-macro'
    }

    allMacroNodes.each { macroNode ->
        // Only report outermost macros — skip those already inside
        // another macro's rich-text-body.
        if (!isOutermostMacro(macroNode)) return

        def macroName = macroNode.attribute('ac:name') ?: 'unknown'

        if (outerMacroFilter && macroName != outerMacroFilter) return

        // Check whether this macro has any immediately nested macros.
        // "Immediately nested" means inside this macro's ac:rich-text-body
        // but not inside a further nested macro's body.
        def immediatelyNested = findImmediatelyNestedMacros(macroNode)
        if (!immediatelyNested) return

        // Build the full chain from this outermost macro downward,
        // following the deepest path through the nesting tree.
        def chainResult = buildNestingChain(macroNode)
        def nestingChain = chainResult.chain
        def viaTable     = chainResult.viaTable

        // depth = number of actual macros in the chain (not "table" markers).
        def depth = nestingChain.count { it != 'table' }

        findings << [
            spaceKey    : content.space?.key ?: 'unknown',
            spaceName   : content.space?.name ?: 'unknown',
            pageId        : content.id,
            pageTitle     : content.title,
            pageUrl       : "${baseUrl}${content.urlPath}",
            pageEditUrl   : "${baseUrl}/pages/editpage.action?pageId=${content.id}",
            outerMacro    : macroName,
            outerMacroTitle: getMacroTitle(macroNode),
            nestingChain: nestingChain,
            depth       : depth,
            viaTable    : viaTable,
            isThirdParty: isThirdPartyMacro(macroName, knownMacros)
        ]
    }

    return findings
}

// Returns true if this macro is NOT inside another macro's ac:rich-text-body.
// We walk up the ancestor chain looking for the pattern:
//   ac:rich-text-body whose parent is ac:structured-macro.
// If we find that pattern, this macro is nested inside another — not outermost.
def isOutermostMacro(macroNode) {
    def current = macroNode.parent()
    while (current != null) {
        // Text nodes are Strings — skip them, only check element nodes.
        if (current instanceof groovy.util.Node &&
            current.name() == 'ac:rich-text-body') {
            def richTextBodyParent = current.parent()
            if (richTextBodyParent instanceof groovy.util.Node &&
                richTextBodyParent.name() == 'ac:structured-macro') {
                return false
            }
        }
        current = current.parent()
    }
    return true
}

// Returns a list of maps — one per macro that is "immediately nested" inside
// the given macro's ac:rich-text-body. "Immediately nested" means the path
// from the rich-text-body down to the inner macro does not pass through
// another ac:structured-macro (which would make it a deeper-level nest).
//
// Each map contains:
//   node     — the inner macro Node
//   viaTable — true if the path from the rich-text-body to this macro
//              passes through a <table> element
def findImmediatelyNestedMacros(macroNode) {
    def richTextBodies = macroNode.children().findAll {
        it instanceof groovy.util.Node &&
        it.name() == 'ac:rich-text-body'
    }

    def immediatelyNested = []

    richTextBodies.each { richTextBody ->
        def allMacrosInBody = richTextBody.'**'.findAll {
            it instanceof groovy.util.Node &&
            it.name() == 'ac:structured-macro'
        }

        allMacrosInBody.each { innerMacro ->
            def pathInfo = getPathFromRichTextBody(innerMacro, richTextBody)
            if (pathInfo != null) {
                immediatelyNested << [
                    node    : innerMacro,
                    viaTable: pathInfo.viaTable
                ]
            }
        }
    }

    return immediatelyNested
}

// Walks up from innerMacro to richTextBodyNode, checking two things:
//   1. Is there another ac:structured-macro between them?
//      If yes, innerMacro is not immediately nested — return null.
//   2. Does the path pass through a <table> element?
//      If yes, set viaTable = true in the returned map.
//
// Returns null if innerMacro is not immediately nested in richTextBodyNode.
// Returns [viaTable: boolean] if it is immediately nested.
def getPathFromRichTextBody(innerMacro, richTextBodyNode) {
    def viaTable = false
    def current  = innerMacro.parent()

    while (current != null && current != richTextBodyNode) {
        // Text nodes are Strings — only inspect element nodes.
        if (current instanceof groovy.util.Node) {
            if (current.name() == 'ac:structured-macro') {
                return null  // Another macro sits between innerMacro and the body.
            }
            if (current.name() == 'table') {
                viaTable = true
            }
        }
        current = current.parent()
    }

    // If current is null we never reached richTextBodyNode — shouldn't happen.
    if (current == null) return null

    return [viaTable: viaTable]
}

// Recursively builds the nesting chain starting from macroNode, following
// the deepest path through the nesting tree.
//
// The chain is a list of strings. Macro names are listed in nesting order.
// The special string "table" is inserted between two macros when the path
// between them passes through a <table> element — this matches the expected
// output format and signals a different remediation path to the customer.
//
// Returns [chain: List<String>, viaTable: boolean].
def buildNestingChain(macroNode) {
    def macroName      = macroNode.attribute('ac:name') ?: 'unknown'
    def immediateNested = findImmediatelyNestedMacros(macroNode)

    if (!immediateNested) {
        return [chain: [macroName], viaTable: false]
    }

    // Among all immediately nested macros, find the one that leads to
    // the deepest sub-chain. We report the worst-case path so the customer
    // sees the most severe nesting first.
    def deepestSubChain = []
    def deepestViaTable = false

    immediateNested.each { nestedInfo ->
        def subResult = buildNestingChain(nestedInfo.node)

        // Insert a "table" marker in the chain if this transition goes
        // via a table element, so the display shows "panel → table → jira".
        def candidateSubChain = nestedInfo.viaTable
            ? (['table'] + subResult.chain)
            : subResult.chain

        if (candidateSubChain.size() > deepestSubChain.size()) {
            deepestSubChain = candidateSubChain
            deepestViaTable = nestedInfo.viaTable || subResult.viaTable
        }
    }

    return [
        chain   : [macroName] + deepestSubChain,
        viaTable: deepestViaTable
    ]
}

// Returns true if the macro name looks like a third-party (marketplace) macro.
// The signal: the name contains a hyphen AND is not in the known-Atlassian list.
// Atlassian's own hyphenated macros (e.g. "table-plus", "recently-updated")
// are in the known list and will not be flagged.
def isThirdPartyMacro(String macroName, Set knownMacros) {
    return macroName.contains('-') && !knownMacros.contains(macroName)
}

// Looks for a human-readable title or label on a macro node.
// Most bodied macros (expand, panel, info, note, etc.) accept a "title"
// parameter that the page author fills in. Returning this gives the
// customer a text anchor they can Ctrl+F for on the page, rather than
// hunting through every macro of that type.
// Returns null if the macro has no title parameter set.
def getMacroTitle(macroNode) {
    def titleParam = macroNode.children().find {
        it instanceof groovy.util.Node &&
        it.name() == 'ac:parameter' &&
        it.attribute('ac:name') in ['title', 'label']
    }
    def titleText = titleParam?.text()?.trim()
    return (titleText) ? titleText : null
}

// Returns a plain-English suggestion for how to fix this specific finding.
// The hint is chosen based on the outer macro type, the viaTable flag,
// and the isThirdParty flag — in that priority order.
// The goal is to give a content editor a concrete first step, not a
// generic "fix the nesting" instruction.
def getRemediationHint(finding) {
    if (finding.viaTable) {
        return "Via-table nesting needs structural rework. " +
               "Extract the table from the macro body first, " +
               "then move the inner macro outside — don't just unnest."
    }
    if (finding.isThirdParty) {
        return "Check the vendor's Cloud migration guide for " +
               "'${finding.outerMacro}'. The Cloud equivalent " +
               "may handle body content differently."
    }

    def outerMacro = finding.outerMacro

    if (outerMacro == 'expand') {
        return "Remove the expand wrapper and leave its contents " +
               "(including the inner macro) in place as standalone content."
    }
    if (outerMacro == 'panel') {
        return "Move the inner macro outside the panel, or remove " +
               "the panel wrapper and keep its contents in place."
    }
    if (outerMacro in ['info', 'note', 'warning', 'tip']) {
        return "Move the inner macro outside this panel macro, or " +
               "remove the inner macro's wrapper and keep its text."
    }
    if (outerMacro in ['section', 'column']) {
        return "Migrate this page to Confluence's new page layout " +
               "system. The old section/column layout is not " +
               "supported in the Cloud editor."
    }
    if (finding.depth >= 3) {
        return "Deep nesting (${finding.depth} levels) will almost " +
               "certainly be quarantined in Cloud's editor. " +
               "Manually restructure this content before migrating."
    }
    return "Manually move or remove the inner macro from the " +
           "outer macro's body before migrating to Cloud."
}

// Escapes characters that have special meaning in HTML so that page titles
// and macro names containing &, <, >, or " cannot break the report layout
// or create unintended markup.
def escapeHtml(String text) {
    if (!text) return ''
    return text
        .replace('&', '&amp;')
        .replace('<', '&lt;')
        .replace('>', '&gt;')
        .replace('"', '&quot;')
}

// Builds an HTML report from all findings, rendered as a styled table.
// The Script Console displays HTML return values directly, which is far
// more readable than a plain-text string for a customer-facing report.
// Findings are grouped by space and sorted by depth descending so the
// worst offenders surface first.
def formatGroupedReport(List findings, int pagesScanned, int parseFailures) {

    def html = new StringBuilder()

    // ── Inline styles ────────────────────────────────────────────────────────
    // We inline all styles because the Script Console does not load
    // external stylesheets. We reuse Confluence's own AUI colour tokens
    // where possible so the report feels native.
    // ─────────────────────────────────────────────────────────────────────────
    html << """
    <style>
        .nmr-wrap        { font-family: -apple-system, BlinkMacSystemFont,
                           'Segoe UI', sans-serif; font-size: 13px;
                           color: #172B4D; max-width: 960px; }
        .nmr-header      { background: #0052CC; color: #fff;
                           padding: 14px 18px; border-radius: 6px 6px 0 0; }
        .nmr-header h2   { margin: 0 0 4px; font-size: 16px; }
        .nmr-header p    { margin: 0; font-size: 11px; opacity: .85; }
        .nmr-stats       { display: flex; gap: 24px;
                           background: #F4F5F7; padding: 10px 18px;
                           border: 1px solid #DFE1E6;
                           border-top: none; }
        .nmr-stat        { text-align: center; }
        .nmr-stat strong { display: block; font-size: 22px; color: #0052CC; }
        .nmr-stat span   { font-size: 11px; color: #6B778C; }
        .nmr-scope       { font-size: 11px; color: #6B778C;
                           background: #FFFAE6; border: 1px solid #FFE380;
                           border-top: none; padding: 6px 18px; }
        .nmr-space-head  { background: #DEEBFF; color: #0747A6;
                           font-weight: 600; font-size: 13px;
                           padding: 8px 12px; margin-top: 16px;
                           border-radius: 4px; }
        table.nmr        { width: 100%; border-collapse: collapse;
                           margin-top: 4px; }
        table.nmr th     { background: #F4F5F7; color: #6B778C;
                           font-size: 11px; font-weight: 600;
                           text-transform: uppercase; letter-spacing: .05em;
                           padding: 6px 10px; border: 1px solid #DFE1E6;
                           text-align: left; }
        table.nmr td     { padding: 8px 10px; border: 1px solid #DFE1E6;
                           vertical-align: top; }
        table.nmr tr:nth-child(even) td { background: #FAFBFC; }
        .nmr-page-link   { font-weight: 600; color: #0052CC;
                           text-decoration: none; }
        .nmr-page-link:hover { text-decoration: underline; }
        .nmr-chain       { font-family: monospace; font-size: 12px;
                           background: #F4F5F7; padding: 3px 7px;
                           border-radius: 3px; white-space: nowrap; }
        .nmr-arrow       { color: #6B778C; }
        .nmr-macro       { color: #0052CC; font-weight: 600; }
        .nmr-table-node  { color: #FF8B00; font-weight: 600; }
        .badge           { display: inline-block; border-radius: 3px;
                           font-size: 11px; font-weight: 600;
                           padding: 2px 6px; white-space: nowrap; }
        .badge-deep      { background: #FFEBE6; color: #BF2600; }
        .badge-via-table { background: #FFFAE6; color: #974F0C; }
        .badge-3p        { background: #EAE6FF; color: #403294; }
        .badge-ok        { background: #E3FCEF; color: #006644; }
        .nmr-empty       { padding: 24px; text-align: center;
                           color: #6B778C; font-size: 14px; }
        .nmr-poc         { background: #FFFAE6; border: 2px solid #FFE380;
                           border-radius: 6px; padding: 12px 16px;
                           margin-bottom: 14px; font-size: 12px;
                           color: #172B4D; line-height: 1.6; }
        .nmr-poc strong  { color: #974F0C; font-size: 13px; }
        .nmr-poc ul      { margin: 6px 0 0 18px; padding: 0; }
        .nmr-poc li      { margin-bottom: 2px; }
    </style>
    <div class="nmr-wrap">
    """

    // ── PoC banner ────────────────────────────────────────────────────────────
    html << """
    <div class="nmr-poc">
        <strong>⚠️ Proof of Concept — Please Read Before Sharing</strong>
        <ul>
            <li>This script was built with ScriptRunner for Confluence Data
                Center as a <strong>starting point</strong>, not a finished
                product. It is intentionally open and editable.</li>
            <li>Results are based on current published page bodies and blog
                posts only. Templates, comments, drafts, and page history
                are out of scope.</li>
            <li>The third-party macro detection uses a heuristic (hyphen in
                the macro name). It may miss some marketplace macros and
                flag custom in-house macros. Review flagged items manually.
            </li>
            <li>Nesting chains show the <em>deepest</em> path only. A macro
                with multiple nested branches will show one chain — the
                worst case. Open the page to see the full picture.</li>
            <li>Feel free to extend this script — add CSV export, filter by
                macro name, scope to a single space, or schedule it as a
                recurring job in ScriptRunner.</li>
        </ul>
    </div>
    """

    // ── Header ───────────────────────────────────────────────────────────────
    html << """
    <div class="nmr-header">
        <h2>🔍 Nested Macro Report</h2>
        <p>ScriptRunner for Confluence DC — Migration Readiness Scan</p>
    </div>
    """

    // ── Stats bar ────────────────────────────────────────────────────────────
    def deepCount      = findings.count { it.depth >= 3 }
    def viaTableCount  = findings.count { it.viaTable }
    def thirdPartyCount = findings.count { it.isThirdParty }

    html << """
    <div class="nmr-stats">
        <div class="nmr-stat">
            <strong>${String.format('%,d', pagesScanned)}</strong>
            <span>Pages scanned</span>
        </div>
        <div class="nmr-stat">
            <strong style="color:${findings ? '#BF2600' : '#006644'}">
                ${findings.size()}
            </strong>
            <span>Findings</span>
        </div>
        <div class="nmr-stat">
            <strong style="color:${deepCount ? '#BF2600' : '#172B4D'}">
                ${deepCount}
            </strong>
            <span>Deep nests (depth ≥ 3)</span>
        </div>
        <div class="nmr-stat">
            <strong style="color:${viaTableCount ? '#974F0C' : '#172B4D'}">
                ${viaTableCount}
            </strong>
            <span>Via table</span>
        </div>
        <div class="nmr-stat">
            <strong style="color:${thirdPartyCount ? '#403294' : '#172B4D'}">
                ${thirdPartyCount}
            </strong>
            <span>Third-party macros</span>
        </div>
        <div class="nmr-stat">
            <strong style="color:${parseFailures ? '#BF2600' : '#172B4D'}">
                ${parseFailures}
            </strong>
            <span>Parse failures</span>
        </div>
    </div>
    """

    // ── Scope note ───────────────────────────────────────────────────────────
    html << """
    <div class="nmr-scope">
        ⚠️ <strong>Scope:</strong> current page bodies and blog posts only.
        Page templates, comments, drafts, and historical versions are
        not scanned. Re-run after major template or content cleanup.
    </div>
    """

    // ── No findings ──────────────────────────────────────────────────────────
    if (!findings) {
        html << """
        <div class="nmr-empty">
            ✅ No nested macros found in the scanned content.
        </div></div>
        """
        return html.toString()
    }

    // ── Findings table, grouped by space ─────────────────────────────────────
    def findingsBySpace = findings.groupBy { it.spaceKey }

    findingsBySpace.each { spaceKey, spaceFindings ->
        def spaceName    = spaceFindings.first().spaceName
        def sortedFindings = spaceFindings.sort { a, b -> b.depth <=> a.depth }

        html << """
        <div class="nmr-space-head">
            📁 ${escapeHtml(spaceKey)} — ${escapeHtml(spaceName)}
            &nbsp;<span style="font-weight:400;font-size:11px;color:#0747A6">
                (${sortedFindings.size()} finding${sortedFindings.size() == 1 ? '' : 's'})
            </span>
        </div>
        <table class="nmr">
            <thead>
                <tr>
                    <th>Page</th>
                    <th>Nesting chain</th>
                    <th>Depth</th>
                    <th>Flags</th>
                    <th>What to do</th>
                </tr>
            </thead>
            <tbody>
        """

        sortedFindings.each { finding ->
            // Build the coloured chain display.
            // Macro names are escaped so special characters in third-party
            // macro names cannot break the HTML output.
            def chainHtml = finding.nestingChain.collect { segment ->
                if (segment == 'table') {
                    "<span class='nmr-table-node'>table</span>"
                } else {
                    "<span class='nmr-macro'>${escapeHtml(segment)}</span>"
                }
            }.join(" <span class='nmr-arrow'>→</span> ")

            // Build the flags cell.
            def flags = []
            if (finding.depth >= 3) {
                flags << "<span class='badge badge-deep'>⚠️ deep nest</span>"
            }
            if (finding.viaTable) {
                flags << "<span class='badge badge-via-table'>via table</span>"
            }
            if (finding.isThirdParty) {
                flags << "<span class='badge badge-3p'>3rd party</span>"
            }
            if (!flags) {
                flags << "<span class='badge badge-ok'>standard</span>"
            }

            def pageUrl      = finding.pageUrl
            def editUrl      = finding.pageEditUrl
            def safeTitle    = escapeHtml(finding.pageTitle)
            def remediationHint = escapeHtml(getRemediationHint(finding))

            // Show the macro's title parameter (e.g. the label an author
            // gave their expand macro) so the customer can Ctrl+F for it
            // on the page rather than hunting through every macro visually.
            def macroTitleHtml = finding.outerMacroTitle
                ? """<br><span style='font-size:11px;color:#6B778C'>
                       📌 &ldquo;${escapeHtml(finding.outerMacroTitle)}&rdquo;
                     </span>"""
                : ""

            html << """
            <tr>
                <td>
                    <a class="nmr-page-link" href="${pageUrl}" target="_blank">
                        ${safeTitle}
                    </a>
                    <br>
                    <a href="${editUrl}" target="_blank"
                       style="font-size:11px;color:#0052CC;">
                        ✏️ Edit page
                    </a>
                    <span style="font-size:11px;color:#6B778C">
                        &nbsp;·&nbsp;id=${finding.pageId}
                    </span>
                </td>
                <td>
                    <span class="nmr-chain">${chainHtml}</span>
                    ${macroTitleHtml}
                </td>
                <td style="text-align:center;font-weight:600">
                    ${finding.depth}
                </td>
                <td>${flags.join(' ')}</td>
                <td style="font-size:12px;line-height:1.5">
                    ${remediationHint}
                </td>
            </tr>
            """
        }

        html << "</tbody></table>"
    }

    html << "</div>"
    return html.toString()
}
