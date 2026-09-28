rem xek builder — cmd.exe delegates to PowerShell, which reads this same file.
set "xek_ps=%TEMP%\xek_%RANDOM%%RANDOM%.ps1"
copy /y "%~f0" "%xek_ps%" >nul
powershell -NoProfile -ExecutionPolicy Bypass -File "%xek_ps%" %*
set "xek_rc=%errorlevel%"
del "%xek_ps%" >nul 2>&1
exit /b %xek_rc%
