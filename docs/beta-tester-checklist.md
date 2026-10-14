# Fathom public beta — tester checklist

Thank you for testing Fathom. This list takes about 50 minutes. Work through it
in order, tick what works, and for anything that does not, note the step number
and what you saw. The **Report a problem** section at the end says what to send.

You need: Windows 10 or 11 (64-bit), a Microsoft account that owns Minecraft:
Java Edition, and about 2 GB of free disk space.

Please do **not** send screenshots of the sign-in code page, your Microsoft
account e-mail, or anything from `%LOCALAPPDATA%\Fathom\data\accounts`.

---

## 1. Install

1. Download `Fathom Client_<version>_x64-setup.exe` from the link you were sent.
   Check its SHA-256 matches the one published beside it:
   `Get-FileHash "Fathom Client_<version>_x64-setup.exe"` in PowerShell.
2. Run it. The beta build is **not code-signed yet**, so Windows SmartScreen
   says "Windows protected your PC". Choose **More info → Run anyway**. Tell us
   if your antivirus blocks or quarantines it.
3. The installer should not ask for administrator rights. It installs to
   `%LOCALAPPDATA%\Fathom Client` and adds **Fathom** to the Start menu.
4. ☐ Fathom opens from the Start menu, with the Fathom icon in the taskbar.
5. ☐ On a PC that has never had Fathom: the first screen offers sign-in, nothing
   is broken or blank, and no console window appears.

## 2. Sign in and accounts

6. ☐ Press **Sign in**. A code and a Microsoft page appear; enter the code,
   approve, and come back. Fathom shows your Minecraft name and head.
7. ☐ Close Fathom completely and open it again: you are still signed in,
   without signing in again.
8. ☐ If you have a second Minecraft account: add it, switch between the two,
   and check the name shown changes.
9. ☐ **Sign out**, close Fathom, open it again: you are signed out.
10. ☐ Sign back in for the rest of the list.

## 3. First launch

11. ☐ Press **Play**. The first launch downloads Minecraft, Java and the loader
    (a few minutes on a slow connection) with visible progress, and the game
    starts.
12. ☐ The game window shows **Fathom's** title screen, not Minecraft's, and the
    version line says 1.8.9 · Legacy Fabric.
13. ☐ Close the game, press Play again: it starts quickly, without
    re-downloading.

## 4. Playing

14. ☐ Create a singleplayer world, walk around for a minute, leave, and rejoin.
15. ☐ Join a multiplayer server you normally play on. Note which server.
16. ☐ Use **Disconnect**, join a *different* server, then come back. Nothing
    from the first server (scoreboard, ping) should appear on the second.
17. ☐ Over ten minutes of normal play: no crash, no freeze, no flicker. If it
    feels slower than you are used to, note your FPS before and after (F3).
17a. ☐ While it runs, with **Keep Fathom open while playing** on: Home shows
    "Playing for …", the frame rate, p95, p99, memory and a frame-time
    timeline, updating every few seconds. On another launcher page, the
    header shows a "Playing · …" capsule that goes back to Home.
17b. ☐ In game, Right Shift → **Performance**: the instrument at the top shows
    the frame rate, p95, p99 and memory over a two-minute timeline. Change a
    setting on that page: a tick appears on the timeline. Hover a column: it
    reads out that second.
17c. ☐ Close the game. Home says how long you played, with the session's
    average, p95, p99 and hitches; the Performance page shows the same as
    **Last session**. Note anything that looks wrong (a frame rate you know is
    not right, a timeline that stays empty after a minute in a world).

## 5. Mods and the HUD

18. ☐ Press **Right Shift**. The Fathom Hub opens over the game.
19. ☐ In **Mods**, switch a few modules on and off (Keystrokes, CPS, Armour HUD,
    Potion HUD, Direction, Clock). Each one appears and disappears at once.
20. ☐ Open a module's page and change a setting (a colour, a size). The change
    shows immediately. Press **Reset to defaults**: the setting goes back and
    the module stays on.
21. ☐ Press **Edit HUD**. Drag two widgets to new places, resize one from its
    corner, and press **Done**. Restart the game: they are where you left them.
22. ☐ In the HUD editor, drag a widget onto another. It should move clear on
    its own; if it cannot, it is outlined in orange.
23. ☐ Change Minecraft's **GUI Scale** (Options → Video Settings). Widgets stay
    in their corners and on screen.
24. ☐ Try **Zoom** (C), **Toggle sprint**, the **Crosshair** presets,
    **Fullbright** and **Time Changer**. Turn each off again and check the game
    looks normal.
25. ☐ If you have a server with a sidebar scoreboard: switch on **Scoreboard**
    and check every line matches what the server shows with it off.
26. ☐ Chat: turn on **Timestamps** and **Fold repeats**. Clickable chat
    messages (party invites, links) still work when clicked.

## 5a. Finding mods

The Browse tab on the Mods page searches Modrinth. It is off until you allow it,
because searching sends what you type to Modrinth.

- ☐ Mods → **Browse** → **Allow mod repositories**. Results appear, each marked
  **Compatible**, **Unverified** or **Incompatible** after a moment.
- ☐ Search for a mod by name; results update as you type, without freezing the
  window. Scroll down: more results load.
- ☐ Open a **Compatible** mod, press **Install**, check the list of files, and
  install it. It appears in **Installed**; the game starts with it.
- ☐ Open an **Unverified** mod and press **Review**: Install stays greyed out
  until you tick "Install anyway". (Only install it if you are happy to test
  whether it loads.)
- ☐ Remove the mod you installed from its page. Tell us any mod marked
  Compatible that did not load, or Incompatible that you know works.

## 5b. Managing mods

- ☐ Open an installed mod from **Installed**. It shows where it came from, whether it fits, what it
  needs, and **Show file** opens its folder.
- ☐ If a mod has an update, use **Check for update** on its page. After updating, **Earlier builds**
  lists the old one; **Put back** restores it (no download needed).
- ☐ Switch a library a mod needs (for example Legacy Fabric API) off, then press **Play**. Fathom
  should refuse and name both mods instead of starting a game that will crash. Switch it back on.
- ☐ If a launch ever crashes: on Home, **What changed?** opens the Issues tab, which lists what
  changed since the last launch that worked and what the game's log says. Try **Launch without
  external mods**: the game should start with only Fathom, and your mods should all be back for the
  next normal launch. Tell us if the log lines it shows point at the wrong mod.

## 6. Appearance

27. ☐ In the Hub's **Appearance** page, choose a different menu background.
    Close and reopen the Hub, then restart the game: your choice is kept.
28. ☐ Switch the Right-Shift background between **Gameplay glass** and
    **Fathom background**. Both look right over the game.
29. ☐ Change **Fathom UI scale** in Quick. The Hub resizes; your HUD widgets do
    not.

## 7. OptiFine (only if you use it)

30. ☐ Add OptiFine HD U M5 and OptiFabric through the launcher's instructions
    (Fathom does not download OptiFine for you). The game starts, OptiFine's
    options appear, and steps 18–24 still work.

## 8. Your files are safe

31. ☐ Your worlds, screenshots and resource packs are in
    `%LOCALAPPDATA%\Fathom\data\profiles\<profile>\minecraft\`.
32. ☐ Uninstall Fathom (Settings → Apps). Then check the folder in step 31 is
    still there with everything in it. Reinstall, sign in, and your worlds and
    settings come back.

## 9. Updating

The beta does not update itself. When a new build is published:

33. ☐ Close Fathom and run the new installer over the old one. Fathom opens on
    the new version, still signed in, with your profiles, worlds and settings.

---

## Report a problem

In the launcher, open **Recovery** and choose **Save a diagnostics report**.
The file is written into `%LOCALAPPDATA%\Fathom\data` without your Windows
username, tokens or account details. Send it with:

- the checklist step number and what you expected;
- what happened instead, and whether it happens every time;
- your Windows version, GPU, and whether OptiFine was installed;
- the game log if the game was involved:
  `%LOCALAPPDATA%\Fathom\data\profiles\<profile>\minecraft\logs\latest.log`.
  Look through it before sending: it contains your Minecraft name.
