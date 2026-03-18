# TimeWarp Camera — Workspace Instructions

## Project
- Android native app (Kotlin), package `com.timewarpscan.nativecamera`
- compileSdk 36, minSdk 26, Kotlin 2.1.20, AGP 8.9.1

## Code Style
- ViewBinding for all Activities (no findViewById in new code)
- Use `bindingAdapterPosition` (not `adapterPosition`)
- Fix deprecation warnings immediately — don't leave them
- Keep Activities in `ui/` subfolder by feature
- Drawables flat in `res/drawable/`, raw media in `res/raw/`

## Design
- Dark theme (#1A1A1A), yellow accent (#FFC107)
- Back button: 44dp, bg_circle_button, ic_back
- Mode toggles: yellow active bg + dark text, transparent inactive + muted white text
- Bottom nav needs clipChildren=false, high elevation+translationZ

## Build
- `.\gradlew.bat assembleDebug` to verify
- Clean build (`clean assembleDebug`) when stale R class errors occur
- Always verify build succeeds before reporting done

## Git
- Remote: `git@github.com:huuanh/timewarpkotlin.git`, branch `main`
- Commit messages: English, concise, descriptive
- Commit flow: `git add -A; git commit -m "..."; git push`

## Communication
- User communicates in Vietnamese
- Respond with Vietnamese explanations + English code/commits
