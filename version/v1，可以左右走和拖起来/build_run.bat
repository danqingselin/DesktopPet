@echo off
setlocal
cd /d %~dp0
if not exist bin mkdir bin
echo [编译] javac -d bin DesktopPet.java PetControlPanel.java
javac -d bin DesktopPet.java PetControlPanel.java
if errorlevel 1 (
  echo.
  echo *** 编译失败（请检查上面的错误信息）***
  pause
  exit /b 1
)
echo [运行] java -cp bin PetControlPanel
java -cp bin PetControlPanel
endlocal
