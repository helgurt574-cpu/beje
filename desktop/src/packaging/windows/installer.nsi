; Bêje Windows kurulum sihirbazı (NSIS). Gradle "buildWindowsInstaller" görevi @...@ alanlarını doldurur.
; Kullanıcı başına kurulum: yönetici izni istemez; Başlat menüsü ve masaüstü kısayolu ekler.
Unicode true
!include "MUI2.nsh"

!define APPNAME "Bêje"
!define VERSION "@VERSION@"
!define UNINSTKEY "Software\Microsoft\Windows\CurrentVersion\Uninstall\Beje"

Name "${APPNAME} ${VERSION}"
OutFile "@OUTFILE@"
InstallDir "$LOCALAPPDATA\Programs\Beje"
InstallDirRegKey HKCU "Software\Beje" "InstallDir"
RequestExecutionLevel user
SetCompressor /SOLID lzma
SetCompressorDictSize 64
BrandingText "${APPNAME} ${VERSION}"

!define MUI_ICON "@ICON@"
!define MUI_UNICON "@ICON@"
!define MUI_ABORTWARNING
!define MUI_FINISHPAGE_RUN "$INSTDIR\Beje.exe"

!insertmacro MUI_PAGE_WELCOME
!insertmacro MUI_PAGE_DIRECTORY
!insertmacro MUI_PAGE_INSTFILES
!insertmacro MUI_PAGE_FINISH
!insertmacro MUI_UNPAGE_CONFIRM
!insertmacro MUI_UNPAGE_INSTFILES

!insertmacro MUI_LANGUAGE "Turkish"
!insertmacro MUI_LANGUAGE "English"
!insertmacro MUI_LANGUAGE "Arabic"
!insertmacro MUI_LANGUAGE "Farsi"
!insertmacro MUI_LANGUAGE "Kurdish"

; Açık olan Bêje'yi kapatır; aksi halde dosyaları kilitli kalır ve üzerine yazılamaz.
!macro CloseBeje
  IfFileExists "$INSTDIR\Beje.exe" 0 +3
    ExecWait '"$INSTDIR\Beje.exe" --quit'
    Sleep 2500
  nsExec::Exec `powershell -NoProfile -ExecutionPolicy Bypass -Command "Get-Process | Where-Object { $$_.Path -like '$INSTDIR\*' } | Stop-Process -Force"`
  Pop $0
  Sleep 800
!macroend

Section "Bêje"
  !insertmacro CloseBeje
  SetOutPath "$INSTDIR"
  RMDir /r "$INSTDIR\app"
  RMDir /r "$INSTDIR\runtime"
  File /r "@SRCDIR@/*"
  WriteUninstaller "$INSTDIR\Uninstall.exe"

  CreateShortCut "$SMPROGRAMS\${APPNAME}.lnk" "$INSTDIR\Beje.exe" "" "$INSTDIR\Beje.exe" 0
  CreateShortCut "$DESKTOP\${APPNAME}.lnk" "$INSTDIR\Beje.exe" "" "$INSTDIR\Beje.exe" 0

  WriteRegStr HKCU "Software\Beje" "InstallDir" "$INSTDIR"
  WriteRegStr HKCU "${UNINSTKEY}" "DisplayName" "${APPNAME}"
  WriteRegStr HKCU "${UNINSTKEY}" "DisplayVersion" "${VERSION}"
  WriteRegStr HKCU "${UNINSTKEY}" "Publisher" "Bêje"
  WriteRegStr HKCU "${UNINSTKEY}" "DisplayIcon" "$INSTDIR\Beje.exe"
  WriteRegStr HKCU "${UNINSTKEY}" "InstallLocation" "$INSTDIR"
  WriteRegStr HKCU "${UNINSTKEY}" "UninstallString" '"$INSTDIR\Uninstall.exe"'
  WriteRegDWORD HKCU "${UNINSTKEY}" "NoModify" 1
  WriteRegDWORD HKCU "${UNINSTKEY}" "NoRepair" 1
  WriteRegDWORD HKCU "${UNINSTKEY}" "EstimatedSize" @SIZEKB@
SectionEnd

Section "Uninstall"
  !insertmacro CloseBeje
  Delete "$SMPROGRAMS\${APPNAME}.lnk"
  Delete "$DESKTOP\${APPNAME}.lnk"
  DeleteRegValue HKCU "Software\Microsoft\Windows\CurrentVersion\Run" "Beje"
  DeleteRegKey HKCU "${UNINSTKEY}"
  DeleteRegKey HKCU "Software\Beje"
  RMDir /r "$INSTDIR"
SectionEnd
