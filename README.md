# JC Meta Translate Helper

Android proof of concept for Meta AI live translation on James's Galaxy Z Fold 7.

## Goal

When Meta AI shows the translation of **James's own speech**, this helper reads that
outgoing translated text and speaks it through the **phone speaker**. It intentionally
does not speak the other person's incoming translation.

## First phone test

1. Open this project in Android Studio.
2. Let Gradle sync and run the app on the Fold 7.
3. Tap **Enable Accessibility Service**.
4. Select **JC Meta Translate Helper → Meta Translation Speaker** and enable it.
5. Return to the helper and tap **Test Phone Speaker (Spanish)**.
6. Open Meta AI live translation and speak a short English sentence.

## Important test status

Version 0.2.0 is a diagnostic field-test build. Meta does not provide a public
live-translation API,
so the helper uses Android Accessibility and currently identifies James's speech as
the newest text on the right side of the Meta conversation screen. The first Fold 7
test will confirm:

- Meta View's package is `com.facebook.stella`
- translated message text is exposed to Accessibility
- James's message is right-aligned in the accessibility node tree
- Android keeps TTS on the built-in speaker while the glasses are connected

No microphone recording, network upload, account credential, or conversation history
is included.

If automatic speech does not trigger, return to the helper, tap **Copy Translation
Diagnostics**, and share the copied text. It contains only the visible accessibility
labels and screen positions reported by Meta View; it lets the detector be matched to
the Fold 7's actual Meta layout instead of guessing.
