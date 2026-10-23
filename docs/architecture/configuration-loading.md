# Configuration loading

Settings can be loaded from a local file (`FileSettingsSource`) or from an ES index
(`IndexSettingsSource`). Dynamic reloading is triggered via `POST /_readonlyrest/admin/refreshconfig`.
Test-mode config injection is available via `/_readonlyrest/admin/config/test`.
