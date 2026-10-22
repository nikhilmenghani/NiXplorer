# Local PDF editing

Open a PDF and choose **Edit PDF** in its viewer, or select a PDF in Files and choose **Edit PDF**. The editor uses the existing app palette, scoped Material 3 Expressive components, a page preview, grouped tool cards, and undo/redo. Every command is applied to a private working copy. **Save a copy** uses Android's document picker; the original remains unchanged.

## Implemented tools

- Add selectable text, images, freehand drawing and drawn signatures.
- Draw rectangles, ellipses, lines and arrows; highlight, underline and strike out regions.
- Add web/email links, paint whiteout, or remove and replace a region.
- Fill standard AcroForm fields and create text fields and checkboxes.
- Reorder, extract, delete and duplicate pages; rotate, insert blank pages, append PDFs and crop.
- Add text watermarks, page numbers/Bates labels, headers and footers.
- Change title, author, subject and keywords; add/remove bookmarks.
- Flatten forms, remove annotations, mirror pages, resize and arrange multiple pages per sheet.
- Save AES-256 password protection with separate open and owner passwords and printing/copy permissions; remove protection using the owner password.
- Reduce resolution/quality and optionally convert to grayscale.
- Export page PNG/JPEG images, embedded images, individual-page PDFs and UTF-8 text. Split into top-level bookmark sections or visible page halves. Multiple outputs are packaged in ZIP files.

## Important behavior

**Whiteout is cosmetic.** It covers the page visually and retains underlying content. **Remove/replace region** rasterizes the affected page at 144 DPI, paints out the chosen region, removes the original page streams, and can add replacement text. The saved PDF is a full rewrite with unreachable objects discarded. The affected page loses searchable text and vector detail; forms are flattened and document metadata/bookmarks are removed. Originals still contain the original content.

Drawn signatures are visual marks. They do not create a cryptographic signature or establish identity. Saving any change can invalidate existing digital signatures; the editor warns when signatures are present.

Page organization, merging, mirroring, resizing and N-up use flattened visible annotations where necessary to avoid invalid field/link references. Compression is an image rewrite: it removes searchable text, interactive fields, links and vector detail. It can increase the size of a mostly text PDF. Cropping changes the visible page boundary and does not erase hidden content.

The text tool embeds a supported font and rejects unavailable glyphs instead of dropping them. This is not arbitrary editing of existing PDF font runs or document reflow. The region replacement tool is the supported way to replace visible content when that rasterization tradeoff is acceptable.

Encrypted PDFs require the owner password for editing. Before saving edits to an encrypted source, explicitly choose Remove password or Password protect; protection is never removed implicitly. Saving with no changes copies the original bytes, retaining existing protection and signatures. Viewing and text/image export respect the original extraction restrictions. XFA forms are not supported. Newly added form fields must have unique names. Interactive form values and appearance streams are both saved; flattening is an explicit command.

The editor accepts PDFs up to 64 MiB, 200 pages and 500 commands per session. Rendered images are bounded to avoid exhausting device memory. Exported output is capped at 256 MiB. Work is serialized off the main thread; cancellation is checked during staging, commands, extraction and output copying. Generated engine staging files are removed after completion or failure. A cancelled or interrupted provider write can leave an incomplete output document; the source is retained.

## Sejda coverage

The implementation was compared with [Sejda's public tools](https://www.sejda.com/) on 2026-10-22. It is a local Android editing suite, not complete Sejda parity. Remaining work includes OCR/searchable scans, automatic deskew, layout-preserving PDF/Word/Excel/PowerPoint conversion, arbitrary editing/find-replace of existing text runs, all advanced split modes, repair of structurally damaged documents, advanced form types, image watermarks, document comparison and reusable batch PDF workflows. These require additional engines and dedicated fidelity testing. Existing NiXplorer text/document and media conversion remains available separately.

## Verification

`PdfEditorFixtureChecks` runs through the project's custom instrumentation runner with `-e phase pdfEditor`. Its generated three-page sample includes embedded images, a bookmark outline, a text field and a checkbox. Checks reopen saved PDFs to validate canonical field values and widget appearances, scan serialized streams after redaction, test independent duplicated pages, enforce source identity and password permissions, and decode exported images/PDFs. Pure JVM tests cover page selection syntax and expansion limits. Device testing is performed on POCO only.

Verified on 2026-10-22: debug app/test APK builds succeeded, all 90 JVM tests passed, and all 14 PDF editor device fixture groups passed on POCO. Manual checks covered viewer entry, text placement, undo/redo, saving through Android's document picker, independently rendering/extracting text from the saved result, zoom, portrait and landscape with 1.6x font scale. The generated source's SHA-256 was unchanged after the UI save. Display settings were restored after testing.
