@echo off
setlocal

REM —— 为避免中文乱码（控制台与 JVM 都用 UTF-8）——
chcp 65001 >nul
set "JAVA_TOOL_OPTIONS=-Dfile.encoding=UTF-8"

REM —— 切到脚本所在目录 —— 
cd /d "%~dp0"

REM —— 输出目录 —— 
if not exist "bin" mkdir "bin"

echo [编译] javac -encoding UTF-8 -d bin PetRecorder.java DesktopPet.java PetControlPanel.java
javac -encoding UTF-8 -d bin PetRecorder.java DesktopPet.java PetControlPanel.java
if errorlevel 1 (
  echo.
  echo *** 编译失败（请检查上面的错误信息）***
  pause
  exit /b 1
)

echo [运行] java -cp bin PetControlPanel
java -cp bin PetControlPanel

echo.
echo 程序已退出，按任意键关闭窗口...
pause >nul
endlocal
