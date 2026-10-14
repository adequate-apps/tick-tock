# Gradle wrapper provenance

Gradle 9.6.1, binary distribution, generated with `gradle wrapper --gradle-version 9.6.1 --distribution-type bin`
on JDK 21.

Both halves of the wrapper are pinned so that a tampered download or a swapped jar fails the build
instead of running:

| File | sha256 |
|---|---|
| `gradle-wrapper.jar` | `497c8c2a7e5031f6aa847f88104aa80a93532ec32ee17bdb8d1d2f67a194a9c7` |
| `gradle-9.6.1-bin.zip` (enforced by `distributionSha256Sum`) | `9c0f7faeeb306cb14e4279a3e084ca6b596894089a0638e68a07c945a32c9e14` |

The distribution hash comes from the Gradle release checksums page. CI recomputes the jar hash and
compares it with the table above; `validateDistributionUrl=true` and `retries=0` keep the wrapper
from silently falling back to another source.

To move to a new Gradle version: regenerate with the `gradle wrapper` task, update both hashes here,
and treat the change as a reviewed dependency change. Never hand-edit the jar.
