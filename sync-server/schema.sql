CREATE TABLE IF NOT EXISTS profiles (
  id TEXT PRIMARY KEY,      -- SHA-256 of the profile name (derived on the device)
  auth TEXT NOT NULL,       -- SHA-256 of the access token; the token itself is never stored
  version INTEGER NOT NULL, -- bumped on every write, for compare-and-swap
  data TEXT NOT NULL,       -- AES-256-GCM encrypted profile, base64
  updated INTEGER NOT NULL
);
