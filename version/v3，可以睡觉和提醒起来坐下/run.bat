@echo off
setlocal
cd /d %~dp0

if not exist bin mkdir bin
if not exist lib mkdir lib
echo ��� jna.jar �� jna-platform.jar �ŵ� lib\ Ŀ¼��

echo [����] javac -encoding UTF-8 -cp lib\jna.jar;lib\jna-platform.jar -d bin PetRecorder.java DesktopPet.java PetControlPanel.java
javac -encoding UTF-8 -cp lib\jna.jar;lib\jna-platform.jar -d bin PetRecorder.java DesktopPet.java PetControlPanel.java
if errorlevel 1 (
  echo.
  echo *** ����ʧ�ܣ���������Ĵ�����Ϣ��***
  pause
  exit /b 1
)

echo [����] java -cp bin;lib\jna.jar;lib\jna-platform.jar PetControlPanel
java -cp bin;lib\jna.jar;lib\jna-platform.jar PetControlPanel
endlocal
