# Changelog

## 4.2.1

### 4.2.1 final hardening
### Final audit hardening
- Fixed the `BackupCodecTest` Kotlin string-literal syntax error.
- Removed the unnecessary KGP-specific `JvmTarget` reference from the AGP 9 built-in Kotlin setup.
- Made active-session field population session-aware so refreshes do not overwrite user input.
- Removed main-thread executor waiting during camera shutdown.
- Hardened Excel Jalali rendering against malformed imported date strings.
- Split release/debug publication paths so a release dispatch does not wait for emulator tests.
- Added focused repository instrumentation tests for active-session exclusivity, idempotent resume, session-date ownership, and packet-length validation.
- Cleaned and completed `.gitignore` security/artifact coverage.

- Revalidated AGP/Gradle compatibility against the September 2026 Android Gradle plugin 9.4.0 release and retained the compatible 9.6.0 wrapper.
- Fixed SQLite/SharedPreferences backup transaction ordering.
- Fixed record dates to follow the owning session date.
- Removed silent active-session closing from the low-level database layer.
- Added release R8 rules, isolated instrumentation-test cleanup, date-input validation, and Excel row reuse.
- Updated CI publication to reuse the already-built APK and added download-artifact verification.

- All settings consolidated on the main screen; settings navigation is in-page only.
- Removed the insecure GitHub keystore-generation workflow and added local-only generation tooling.
- Fixed stale/orphan active-session handling across days by surfacing one active session regardless of date and preventing multiple active sessions.
- Fixed BOTH recognition pair-window deadlock.
- Improved camera viewport readiness with retry instead of crash.
- Improved OCR element fallback and result model clarity.
- Hardened settings import, date-column normalization, Excel column generation, and release shrinking.
- Added focused unit/instrumented test coverage plus CI emulator, dependency submission, and Dependabot configuration.

## 4.2.0
- همه تنظیمات به صفحه اصلی منتقل شد و صفحه جداگانه تنظیمات حذف شد.
- تنظیمات دوربین از صفحه دوربین جمع شد؛ دوربین فقط برای اسکن و بررسی نتیجه نگه داشته شد.
- تغییرات تنظیمات بلافاصله در SharedPreferences ذخیره می‌شوند.
- دکمه تنظیمات صفحه اصلی فقط به بخش تنظیمات همان صفحه اسکرول می‌کند.
- حالت تشخیص هوشمند ارقام برای نصب جدید به‌صورت پیش‌فرض فعال است تا گروه‌های جداشده بهتر یکپارچه شوند.

## 4.1.2

- Fixed missing runtime string resources that blocked Android resource compilation.
- Fixed duplicate XML declaration in XLSX workbook relationships.
- Excel metadata now follows the actual app version.
- Improved safe replacement of generated Excel/backup files.
- GitHub Actions now uses current artifact upload action and stronger APK validation.

## 4.1.1

- Added SIMPLE / SMART number scan modes.
- SMART mode joins irregular digit groups and recognizes supported spaces, commas and separators.
- SMART results can display the recognized separators while saving canonical digits for validation and storage.
- Preserved `applicationId` and database schema version for in-place upgrades.
- 4.2.1 deliberately retains AGP 9.4.0 + Gradle 9.6.0 for the final release; this is a toolchain pin, not an accidental downgrade from the 4.1.1 development baseline.
- Updated GitHub Actions to current checkout/setup-java/setup-gradle actions.
- Release workflow now requires explicit production signing secrets and never silently substitutes Debug for Release.
- Flat APK artifact output with a single APK file.
