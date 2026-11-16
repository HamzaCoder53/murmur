You are taking over the Skein desktop build (Windows + macOS) in this repository.

1. Read docs/desktop-build-plan.md (goal, stages, rules, status) and AGENTS.md. Follow the rules
   exactly: work only on desktop/* branches, never merge into murmur and never publish a release.
2. Check where things stand: `git branch -a | grep desktop`, `git log --oneline -5 <branch>` for
   each desktop branch, and the Status section of the plan.
3. Continue with the first unfinished stage. Do one branch at a time:
   - `git switch <branch>` (create it from desktop/foundation if missing).
   - Implement that stage as described in the plan and docs/desktop-architecture.md.
   - Run `./gradlew :desktop:test` (and `:app:testDebugUnitTest :wear:testDebugUnitTest` if you
     touched app/ or wear/) with JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home.
   - Commit, then update the Status section in docs/desktop-build-plan.md on that branch.
4. After each branch, review your own diff critically (security, Bitchat compatibility, privacy)
   and fix what you find before moving on.
5. Stop and tell the developer when: a stage needs a decision, a test cannot be made to pass,
   or all feature branches are done (they will then be reviewed and merged).
