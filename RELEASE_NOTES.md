# Zeidex Convert 1.3.0 — أي مستند إلى PDF

## الجديد: تحويل كل مستند إلى PDF على الجهاز

النسخة 1.2.0 كانت ترفض `.xlsx` و`.docx`等人的 رسالة «حوّله على تطبيق سطح المكتب». الآن كل مستند يُقرأ داخل الجهاز ويُكتب PDF — بلا إنترنت وبلا مكتبة خارجية.

### ما يُقرأ وما يُكتب

| المُدخل | المُخرج |
|---|---|
| PDF | PNG، JPG، DOCX، TXT |
| DOCX | PDF، DOCX، TXT، Markdown |
| XLSX، XLSM، ODS، CSV، TSV | PDF (بجداول مرسومة)، DOCX، TXT |
| PPTX | PDF (شريحة لكل عنوان)، DOCX، TXT |
| ODT، RTF، HTML، EPUB، Markdown، TXT | PDF، DOCX، TXT |

- PDF بنص **قابل للتحديد والبحث**، وليس صورة: ترقيم صفحات تلقائي، رأس جدول مظلّل، شبكة خلايا، وأوراق عريضة (أكثر من 6 أعمدة) أفقية تلقائيًا.
- العربية والكتابة من اليمين إلى اليسار تُشكَّل بخطوط النظام، لا بخط لاتيني.
- جداول البيانات تُقرأ **بقيمها**: الأرقام والتواريخ والعملات من `xl/styles.xml`، فما تراه في Excel هو ما يظهر في PDF.
- EPUB وRTF وODT وHTML تُقرأ فقراتها وعناوينها وجداولها.

### إصلاحات في الطريق

- PDF ممسوح ضوئيًا بلا طبقة نصية كان يُنتج ملف TXT فارغًا؛ الآن يُبلَّغ عنه برسالة OCR واضحة.
- HTML إلى TXT كان يكتب وسوم HTML الخام؛ الآن يُخرج النص وحده.
- استخراج الصوت من الفيديو يرفض المسارات غير المدعومة (غير AAC/AMR) برسالة مفهومة قبل الوصول إلى المُجمِّع الأصلي.

### حدود مقصودة

- Office 97-2003 الثنائي (`.doc`، `.xls`، `.ppt`) لا يُقرأ على الجهاز — الرسالة تطلب حفظه بصيغة حديثة أو تحويله على سطح المكتب.
- ملفات iWork (`.pages`، `.numbers`، `.key`) تحتاج تصديرًا مسبقًا إلى PDF أو DOCX.
- PDF ممسوح ضوئيًا يحتاج OCR في تطبيق سطح المكتب.

### التثبيت

- APK: أندرويد 8.0 فأحدث، موقّع بالمفتاح الرسمي `d24bf9df…`، قابل للتثبيت فوق 1.2.0.
- AAB: لمتجر Google Play والتوزيع.
- التحقق: `SHA256SUMS.txt` مرفق مع كل ملف.

---

# Zeidex Convert 1.3.0 — any document to PDF

## New: any document becomes a PDF on the device

1.2.0 refused `.xlsx`, `.docx` and friends with "convert it on the desktop app". Every document is now read on the phone and written as a PDF — offline, with no external library.

### Inputs and outputs

| Input | Output |
|---|---|
| PDF | PNG, JPG, DOCX, TXT |
| DOCX | PDF, DOCX, TXT, Markdown |
| XLSX, XLSM, ODS, CSV, TSV | PDF (drawn tables), DOCX, TXT |
| PPTX | PDF (one slide per heading), DOCX, TXT |
| ODT, RTF, HTML, EPUB, Markdown, TXT | PDF, DOCX, TXT |

- The PDF carries **selectable, searchable text**, not an image: automatic pagination, a shaded header row, a cell grid, and landscape pages for sheets wider than six columns.
- Arabic and right-to-left scripts are shaped by the system fonts, not a Latin-only Type 1 face.
- Spreadsheets keep their **values**: numbers, dates and currencies come from `xl/styles.xml`, so what Excel shows is what the PDF shows.
- EPUB, RTF, ODT and HTML keep their paragraphs, headings and tables.

### Fixed along the way

- A scanned PDF with no text layer produced an empty TXT; it now fails with a clear OCR message.
- HTML to TXT wrote raw markup; it now emits the text only.
- Audio extraction rejects tracks it cannot mux (anything but AAC/AMR) with a readable message instead of reaching the native muxer.

### Deliberate limits

- Binary Office 97-2003 (`.doc`, `.xls`, `.ppt`) is not parsed on the device; the message asks for a modern format or a desktop conversion.
- iWork (`.pages`, `.numbers`, `.key`) needs a prior export to PDF or DOCX.
- Scanned PDFs still need OCR on the desktop app.

### Install

- APK: Android 8.0+, signed with the official `d24bf9df…` key, installs over 1.2.0.
- AAB: for Google Play and distribution.
- Verify against `SHA256SUMS.txt`, attached with every asset.
