fastlane documentation
----

# Installation

Make sure you have the latest version of the Xcode command line tools installed:

```sh
xcode-select --install
```

For _fastlane_ installation instructions, see [Installing _fastlane_](https://docs.fastlane.tools/#installing-fastlane)

# Available Actions

## Android

### android internal

```sh
[bundle exec] fastlane android internal
```

Deploy to Google Play internal testing track

### android alpha

```sh
[bundle exec] fastlane android alpha
```

Deploy to Google Play closed testing (alpha) track

### android beta

```sh
[bundle exec] fastlane android beta
```

Deploy to Google Play open testing track

### android production

```sh
[bundle exec] fastlane android production
```

Promote internal to production

### android production_rollout

```sh
[bundle exec] fastlane android production_rollout
```

Upload AAB directly to production (full rollout)

### android metadata

```sh
[bundle exec] fastlane android metadata
```

Update store listing metadata only

----

This README.md is auto-generated and will be re-generated every time [_fastlane_](https://fastlane.tools) is run.

More information about _fastlane_ can be found on [fastlane.tools](https://fastlane.tools).

The documentation of _fastlane_ can be found on [docs.fastlane.tools](https://docs.fastlane.tools).
