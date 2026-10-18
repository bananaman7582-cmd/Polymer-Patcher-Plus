# Full modpack recipe wire check

`gradlew :recipeIntegrationJar` builds an opt-in test mod for a **throwaway** Fabric server with the
server's content mods and Polymer Patcher++. Never install it on the live server: it shuts down after testing.

It enumerates every loaded recipe display, applies the same per-recipient display capability check and
server craftability answer used by the patcher, and round-trips `ClientboundRecipeBookAddPacket` both
one display at a time and as a combined book. The simulated recipient lacks modded display/slot/category
registries. Unsupported display types are reported separately from packet failures. It also checks a
custom display passes unchanged when an integration genuinely supplies its own codec to the client.

Inspect `RECIPE_WIRE_INTEGRATION_PASS` or `RECIPE_WIRE_INTEGRATION_FAIL`, not just the process exit code.
The baseline reproduced the exact `No value with id 1533` failure on Farmer's Delight cooking-pot displays.
Filtering is recipe-book-only: it does not remove recipes, alter recipe matching, or change machine menus.
