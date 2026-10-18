# Consume-effect wire check

`gradlew :consumeIntegrationJar` builds an opt-in test mod in `build/integration`. It is **not** part
of the release jar. Run only on a throwaway Fabric 26.2 server with Polymer Patcher++, Polymer,
Fabric API, and the installed Farmer's Delight jar. It automatically shuts that server down.

The test round-trips actual installed Farmer's Delight default stacks through `PolymerItemEntry.CODEC`
with a receiver registry containing only vanilla consume-effect types. It checks the milk bottle's
fallback is exercised, all bytes are consumed, and its actual custom server-side effect is untouched.
Success is `CONSUME_WIRE_INTEGRATION_PASS`; inspect the marker rather than just the process exit code.

The normal `check` suite also runs `verifyConsumeEffectWire`, which reproduces the exact bogus effect
ID 102 without external mods and checks whole-value fallback, shared/native preservation, and vanilla effects.
