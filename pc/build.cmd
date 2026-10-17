@echo off
rem Builds AngryBot.exe with the C# compiler that ships with Windows. Nothing to install.
setlocal
cd /d "%~dp0"
set CSC=%WINDIR%\Microsoft.NET\Framework64\v4.0.30319\csc.exe
if not exist "%CSC%" set CSC=%WINDIR%\Microsoft.NET\Framework\v4.0.30319\csc.exe
if not exist "%CSC%" (
  echo Can't find the .NET Framework 4 C# compiler.
  exit /b 1
)
set REFS=/codepage:65001 /r:System.Windows.Forms.dll /r:System.Drawing.dll

"%CSC%" /nologo /optimize+ /target:winexe %REFS% /out:AngryBot.exe src\Common.cs src\Recorder.cs || goto :fail
"%CSC%" /nologo /optimize+ /target:exe %REFS% /main:AngryBot.SelfTest /out:tests\SelfTest.exe src\Common.cs src\Recorder.cs tests\SelfTest.cs || goto :fail
echo Built AngryBot.exe
exit /b 0

:fail
echo Build failed.
exit /b 1
