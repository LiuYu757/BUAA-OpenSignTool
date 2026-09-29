# Product

<!-- impeccable:product-schema 1 -->

## Platform

android

## Stack

Native Android client using Kotlin and Jetpack Compose; approved by the user.

## Users

Students at Beihang University who use iClass to review their course schedule and record attendance from a phone.

## Product Purpose

Provide the existing BUAA Sign Tool's login, weekly schedule, and user-initiated course attendance flows as an Android client. Success means a student can authenticate, find the correct class week, understand each course's attendance state, and submit attendance when eligible.

## Positioning

The client uses the university SSO identity resolved through iClass/WebVPN and operates on the student's real iClass schedule and attendance state.

## Operating Context

Students may connect from campus or through BUAA WebVPN. They select a semester base date to calculate the current week and navigate the weekly schedule. Attendance is only eligible during the ten minutes before a class starts, per the user's instruction.

## Capabilities and Constraints

- Existing desktop capabilities include direct campus login, WebVPN login, week navigation, weekly schedule lookup, and single-course or weekly attendance actions.
- The Android client should retain those user-facing flows using native Android conventions.
- The semester base date must be persisted on the device and restored on later launches.
- Authentication is SSO followed by resolving the iClass `loginName`; a student number is not assumed to be the iClass identity.
- Do not log or commit credentials. The Android client may remember one login profile only when the user has logged in; the password is encrypted with Android Keystore and can be cleared from the login screen.
- The separate Linux systemd automatic signer is outside the Android client scope.

## Evidence on Hand

- Existing desktop UI and network implementation: `app.py`, `web/index.html`, `web/app.js`, `web/style.css`, and `iclass_client.py`.
- Existing headless Linux service: `auto_sign.py`.
- No Android app, emulator, or Android SDK was present when work began.
- Inference: the Android client should preserve the desktop's course list and attendance actions while replacing its desktop-specific interaction model with native Android UI.

## Product Principles

- Keep schedule state and attendance results grounded in iClass responses.
- Make the selected semester base date visible and easy to change.
- Keep attendance actions explicit and time-bounded.
- Keep SSO credentials on-device and out of logs.
