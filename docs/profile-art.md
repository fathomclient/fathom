# Profile artwork hook

A profile can carry a user-supplied Minecraft screenshot. It is shown on the
Profiles screen, at the top of the selected profile's settings. Artwork is
optional and nothing depends on it.

This used to sit behind a "launch stage" panel on Home. Home is now a full-window
canvas owned by the background system (see `backgrounds.md`), and layering a
second image into it would fight the footage rather than complement it — so the
picture moved to the profile it belongs to.

Place one image in the launcher's local data directory:

```text
profile-art/<profile-id>.png
profile-art/<profile-id>.jpg
profile-art/<profile-id>.jpeg
```

The profile id is the filename used for that profile under `profiles/`. PNG and
JPEG are supported. The image is cropped to cover and decoded once when the
profile is selected. Re-select the profile or restart the launcher after
replacing its artwork.

Artwork is presentation-only. It is not copied into exports, sent over the
network, or used by Minecraft.
