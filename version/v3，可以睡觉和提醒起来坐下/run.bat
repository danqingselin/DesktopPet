@echo off
setlocal
cd /d %~dp0

if not exist bin mkdir bin
if not exist lib mkdir lib
echo 请把 jna.jar 和 jna-platform.jar 放到 lib\ 目录下

echo [编译] javac -encoding UTF-8 -cp lib\jna.jar;lib\jna-platform.jar -d bin PetRecorder.java DesktopPet.java PetControlPanel.java
javac -encoding UTF-8 -cp lib\jna.jar;lib\jna-platform.jar -d bin PetRecorder.java DesktopPet.java PetControlPanel.java
if errorlevel 1 (
  echo.
  echo *** 编译失败（请检查上面的错误信息）***
  pause
  exit /b 1
)

echo [运行] java -cp bin;lib\jna.jar;lib\jna-platform.jar PetControlPanel
java -cp bin;lib\jna.jar;lib\jna-platform.jar PetControlPanel
endlocal
