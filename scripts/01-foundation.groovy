import com.atlassian.confluence.pages.PageManager
import com.atlassian.confluence.spaces.SpaceManager
import com.atlassian.spring.container.ContainerManager

// ── WHAT THIS SCRIPT DOES ─────────────────────────────────────────────────────
//
// Confluence stores page content as XML. Every macro on a page is an
// <ac:structured-macro> element. When one macro contains another inside
// its <ac:rich-text-body>, that is "nesting" — and nested macros are one
// of the most common causes of pages rendering incorrectly after a
// migration to Confluence Cloud.
//
// This script scans a space, finds every page where a macro contains
// another macro inside its body, and prints a plain report.
//
// This is the foundation script. It is intentionally simple — no HTML,
// no badges, no remediation hints. Read it, understand it, then look at
// 02-migration-audit.groovy to see how the same idea can be extended.
//
// ── PROOF OF CONCEPT ──────────────────────────────────────────────────────────
//
// This script is a starting point, not a finished tool. It will find the
// most common nesting patterns but it does not cover every edge case.
// Treat its output as a list of pages worth investigating — not a
// definitive audit.
//
// ─────────────────────────────────────────────────────────────────────────────

// ── CONFIGURATION ─────────────────────────────────────────────────────────────
// Set SPACE_KEY to scan a single space, or null to scan all spaces. In the below example the space key is "NES", change to your own.
// ─────────────────────────────────────────────────────────────────────────────
def SPACE_KEY = "NES"

// ── GET THE CONFLUENCE SERVICES WE NEED ──────────────────────────────────────
// ContainerManager gives us access to Confluence's internal services.
// PageManager lets us fetch pages. SpaceManager lets us fetch spaces.
// ─────────────────────────────────────────────────────────────────────────────
def pageManager  = ContainerManager.getComponent('pageManager')  as PageManager
def spaceManager = ContainerManager.getComponent('spaceManager') as SpaceManager

// ── DECIDE WHICH SPACES TO SCAN ──────────────────────────────────────────────
def spacesToScan = SPACE_KEY
    ? [spaceManager.getSpace(SPACE_KEY)].findAll { it != null }
    : spaceManager.getAllSpaces()

if (!spacesToScan) {
    return "No spaces found. Check your SPACE_KEY value."
}

// ── SCAN EACH PAGE ────────────────────────────────────────────────────────────
// For each page, we fetch its body as a raw XML string, parse it, and
// look for any <ac:structured-macro> element that contains another
// <ac:structured-macro> inside its <ac:rich-text-body>.
//
// The crucial rule: the inner macro must be inside the outer macro's
// <ac:rich-text-body> specifically — not just anywhere below it in the
// XML tree. Without this rule, the script produces false positives.
// ─────────────────────────────────────────────────────────────────────────────
def findings = []

spacesToScan.each { space ->
    log.info("Scanning: ${space.key}")

    pageManager.getPages(space, true).each { page ->
        def rawBody = page.getBodyContent()?.getBody()
        if (!rawBody) return

        // Wrap the page body in a root element so XmlParser can read it.
        // Confluence page bodies are XML fragments — they have no single
        // root element, which XmlParser requires.
        def wrappedXml = """<root
            xmlns:ac="http://www.atlassian.com/schema/confluence/4/ac/"
            xmlns:ri="http://www.atlassian.com/schema/confluence/4/ri/">
            ${rawBody}
        </root>"""

        def parsedBody
        try {
            parsedBody = new XmlParser(false, false).parseText(wrappedXml)
        } catch (Exception e) {
            log.warn("Could not parse '${page.title}': ${e.message}")
            return
        }

        // Find every macro element in the page.
        // The instanceof check is needed because XmlParser's ** operator
        // returns text nodes (plain Strings) as well as element nodes.
        def allMacros = parsedBody.'**'.findAll {
            it instanceof groovy.util.Node &&
            it.name() == 'ac:structured-macro'
        }

        allMacros.each { outerMacro ->
            def outerName = outerMacro.attribute('ac:name') ?: 'unknown'

            // Get the rich-text-body of this macro — this is the only
            // place where nested macros can legitimately live.
            def richTextBody = outerMacro.children().find {
                it instanceof groovy.util.Node &&
                it.name() == 'ac:rich-text-body'
            }
            if (!richTextBody) return

            // Look for any macros inside this rich-text-body.
            def innerMacros = richTextBody.'**'.findAll {
                it instanceof groovy.util.Node &&
                it.name() == 'ac:structured-macro'
            }
            if (!innerMacros) return

            def innerNames = innerMacros
                .collect { it.attribute('ac:name') ?: 'unknown' }
                .unique()
                .sort()

            findings << [
                space    : space.key,
                pageTitle: page.title,
                pageId   : page.id,
                outer    : outerName,
                inner    : innerNames
            ]
        }
    }
}

// ── PRINT THE REPORT ──────────────────────────────────────────────────────────
if (!findings) {
    return "No nested macros found."
}

def report = new StringBuilder()
report << "=== Nested Macro Findings (${findings.size()}) ===\n\n"

findings.groupBy { it.space }.each { spaceKey, spaceFindings ->
    report << "Space: ${spaceKey}\n"
    spaceFindings.each { f ->
        report << "  Page : ${f.pageTitle} (id=${f.pageId})\n"
        report << "  Outer: [${f.outer}]  →  Inner: ${f.inner.join(', ')}\n\n"
    }
}

return report.toString()
