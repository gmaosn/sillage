@echo off
rem Starts @GAME@ with the Java runtime that came with it (no console window; add --help for the options).
set "HERE=%~dp0"
set "AOT="
if exist "%HERE%lib\@GAME@.aot" set "AOT=-XX:AOTCache=%HERE%lib\@GAME@.aot"
if "%~1"=="" (
  start "" "%HERE%runtime\bin\javaw.exe" --enable-native-access=ALL-UNNAMED %AOT% -cp "%HERE%lib\@GAME@.jar;%HERE%lib\kotlin-stdlib.jar" @MAIN@
) else (
  "%HERE%runtime\bin\java.exe" --enable-native-access=ALL-UNNAMED %AOT% -cp "%HERE%lib\@GAME@.jar;%HERE%lib\kotlin-stdlib.jar" @MAIN@ %*
)
