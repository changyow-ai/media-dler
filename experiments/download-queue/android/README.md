# Android SQLite priority restart probe

`PrivatePriorityRestartTest.kt` is an opt-in instrumentation fixture, outside the
normal test source set. It uses the production `DownloadQueue` and
`SqliteDownloadQueueStore` with a UUID-specific database and scratch directory.
It checks the actual SQLite connection path before mutating the fixture. It does
not access the live AppContainer queue, start a service, or download media.

Add this directory as an extra `androidTest` source directory using a temporary
Gradle init script and build the release test APK with the existing release
signing configuration. Run only
`com.changyow.mediadler.qa.PrivatePriorityRestartTest#productionSqlitePriorityAcrossProcesses`.
Both invocations require `qaPrivateRestart=true` and the same fresh UUID in
`qaPrivateSession`:

1. Run with `qaPrivateStage=prepare` on the leased, idle test device.
2. Terminate and recreate the target App process while no real writer is active.
3. Run with `qaPrivateStage=verify`. The test requires a different target PID.

The second phase verifies pending priority order, cached source and checkpoint
bytes, preserved progress, claim order, and an unrelated manual pause. It closes
its SQLite connection and removes only its private fixture directory. Exported
`prepare.json` and `verify.json` are under the target App's external files folder,
in `qa_private_priority_<UUID>`; preserve those reports before removing that exact
test directory.

This tests persistence and queue recovery. The separate native media and UI
evidence in `../results-20261020.json` verifies actual pause/resume, safe writer
handoff, and completed media integrity.
