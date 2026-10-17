; Included at the top level of Tauri's NSIS template (bundle.windows.nsis.installerHooks).
;
; Tauri's template writes the setup executable's ProductName, FileVersion and
; LegalCopyright but not CompanyName, so Windows' Properties > Details and some
; antivirus prompts show the installer as coming from nobody. The launcher
; itself gets its CompanyName from bundle.publisher in tauri.conf.json; keep
; this the same. (The template's ${MANUFACTURER} is defined after this file is
; included, so it cannot be used here.)
VIAddVersionKey "CompanyName" "Fathom"
