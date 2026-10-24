# Attachment command regression check

Run `gradlew attachmentIntegrationJar` and install its test jar on a **throwaway** Fabric server
with Polymer Patcher and Fabric API. NEVER install on the live server: it exits during initialization.
Read `ATTACHMENT_COMMAND_INTEGRATION_PASS/FAIL`, not only the exit status.

Exercises Fabric's actual mixed-in DataAccessorHandler with first-time attachment allocation during
the data-load callback (the original assertion), persistent replacement/removal through the normal
API, nonpersistent preservation, no-op commands, and rejection of existing-map corruption.
The production patch changes only the null-to-first-map snapshot; Fabric still applies and syncs data.
