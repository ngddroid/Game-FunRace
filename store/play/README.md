# Google Play store listing

Paste-ready text, in the folder layout fastlane `supply` uses:

| Play Console field | File | Limit |
|---|---|---|
| App name | `en-US/title.txt` | 30 |
| Short description | `en-US/short_description.txt` | 80 |
| Full description | `en-US/full_description.txt` | 4,000 |
| Release notes (versionCode 1) | `en-US/changelogs/1.txt` | 500 |

Check after every edit (limits, policy words, keyword placement and density):

```
python3 tools/check_listing.py
```

## Category and tags
- **App or game:** Game
- **Category:** Racing. The title, short description and gameplay are all about racing. Among mostly car games, a bright obstacle racer stands out.
- **Tags (max 5):** Racing, Runner, Arcade, Casual, Platformer. Check the exact names in Play Console → Store presence → Store listing → Categorization → Manage tags. There is no "Obstacle course" or "Party" tag.

## Why this name
**Splash Dash: Obstacle Race 3D** (29/30)
- The title is the field Google Play weighs most for search. It carries the two main search words, "obstacle" and "race", plus "3D", which is common in this genre's searches.
- "Splash Dash" stays the brand. No Google Play app is titled exactly "Splash Dash" (checked 2026-11-25). The launcher label stays "Splash Dash".
- The title has no "best", "#1", "free", "new", emoji or ALL CAPS, which Play's metadata policy forbids in titles.

Alternatives: "Splash Dash: Obstacle Party" (27, the old working title, but "party" is searched less), "Splash Dash: Obstacle Run 3D" (28). The privacy policy, the terms, the in-game logo ribbon and the feature graphic all use the chosen name.

## What else moves the ranking
Text only gets the game found for the right words. Where it ranks then depends on how players respond:
- Installs from the listing (conversion): icon, the first 3 screenshots, the feature graphic and a short gameplay video. Test variants with Store listing experiments.
- Ratings and reviews: ask for a rating after a win, never after a loss (Play In-App Review API).
- Retention and uninstalls: daily rewards and reminders help.
- Android vitals: crash and ANR rates (now visible in Crashlytics) must stay under Play's bad-behaviour thresholds.
- Translated listings (for example `hi-IN`) reach players who search in their own language.
- Never use other games' names as keywords, and never stuff keywords; Play penalises both.

If ads or in-app purchases are added later, keep the description accurate. Play shows "Contains ads" and "In-app purchases" itself.
