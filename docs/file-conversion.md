# File conversion

Select one or more files, open **Options**, and choose **Convert file** or
**Convert N files**. The conversion sheet offers formats shared by the selected
files. **Review selected files** can exclude incompatible inputs from a batch.

- **Convert here** saves results in the folder currently open in the Files tab.
- **Add task** queues the conversion. Navigate to the destination folder, open
  **Task**, and run the pending conversion there.

Conversion creates new files and retains originals. Existing names receive a
number, for example `photo (1).jpg`. Files run sequentially in one background
task. A failed input does not stop the rest of the batch. Retrying processes
only unsuccessful items. Completed tasks provide a per-file result summary.
Pending tasks follow the same in-memory lifecycle as existing copy tasks;
executed tasks use the operation journal for interrupted-operation reporting.

## Formats

| Input | Output |
| --- | --- |
| JPEG, PNG, WebP, GIF, BMP; HEIC/HEIF on Android 9+; AVIF on Android 12+ | JPEG, PNG, WebP |
| UTF-8/UTF-16 TXT, Markdown, CSV, LOG; HTML; DOCX; ODT | PDF, TXT, HTML, DOCX |
| PDF with embedded text | TXT |
| Device-supported MP4, M4V, MOV, MKV, WebM, 3GP, TS/MTS/M2TS | MP4 (H.264/AAC), M4A (AAC), WAV (16-bit PCM) |
| Device-supported MP3, M4A, AAC, WAV, FLAC, OGG, Opus, AMR | M4A (AAC), WAV (16-bit PCM) |

Same-format outputs are available so mixed batches can share one target. They
still create new files; document outputs use the text-focused conversion below.

Office and HTML conversion keeps readable text and paragraphs, not document
layout, images, formulas, or complex styling. Markdown is treated as source
text. Legacy DOC/XLS/PPT and spreadsheet/presentation conversion are not
implemented. PDF extraction does not perform OCR and rejects encrypted PDFs.

Animated images use the first frame. JPEG replaces transparency with white.
JPEG and WebP expose a quality control. Image metadata is removed; images keep
their orientation and full dimensions, subject to available memory. WebP uses
lossy compression. Media conversion uses Android's installed codecs, omits
subtitles/metadata, and tone-maps HDR video to SDR. No files are uploaded.

Document limits: 64 MiB source, 12 MiB decoded text/XML, 2 million characters,
500 PDF pages; unusually dense PDF pages are rejected. Media exports time out
after two hours. WAV output is limited by its 4 GiB RIFF container. Cancelled
or failed writes discard their staging file before publishing an output.

## Verification

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest --offline
adb -s DEVICE install -r -t app/build/outputs/apk/debug/app-debug.apk
adb -s DEVICE install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s DEVICE shell am instrument -w -e phase conversion com.nixplorer.debug.test/com.nixplorer.safety.OperationSafetyInstrumentation
```

The `conversion` instrumentation phase creates disposable app-private inputs
and verifies image encoding and transparency, document pagination and Unicode,
batch collisions/partial failures/retries, cancellation, deferred destinations,
AAC/WAV audio, MP4 output, and extracted audio. Its media assets are generated
test patterns and sine tones, not user media.
