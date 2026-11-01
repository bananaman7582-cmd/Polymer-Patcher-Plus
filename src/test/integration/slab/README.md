# Concrete slab regression check

Build `slabIntegrationJar` and run only on a throwaway server with the actual FallDropBackport and its
dependencies. Never install this test jar on a live world: it places test blocks, then stops the server.
Check SLAB_INTEGRATION_PASS/FAIL. Tests all 16 colors and six states each, actual diamond-pickaxe speed,
carrier emission, vanilla double-block reuse, vanilla regressions, and real BlockItem placement in both orders.
