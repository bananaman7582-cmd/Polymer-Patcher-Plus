# Wither Storm cluster wire regression

`gradlew :witherStormIntegrationJar` produces an opt-in test mod in `build/integration`.
It is not included in the release jar or the ordinary check suite, which does not install Wither Storm.

Run it only on a throwaway Fabric 26.2 server with the patcher, Polymer, Fabric API, and the installed
Wither Storm port and its dependencies. **The test shuts that server down automatically. Never put it
on the live server.**

The test invokes the real `BLOCK_STATE_POS_MAP` codec after Mixin transformation, with socketless
native and carrier-client connections and the accepted-pack block mapper. For every borrowed vanilla
leaf state it verifies the canonical leaf's client wire ID, map layout, absence of trailing bytes,
and preservation of the cluster's original state. Local serialization must retain the server raw ID.

Success is logged as `WITHERSTORM_WIRE_CHECK_PASS`; failure as `WITHERSTORM_WIRE_CHECK_FAIL` with a
stack trace. Inspect the marker, not just the process exit code. This is a codec integration check,
not an in-game visual test or a test of the native distant renderer.
