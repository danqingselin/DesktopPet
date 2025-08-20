@echo off
setlocal

REM ���� Ϊ�����������루����̨�� JVM ���� UTF-8������
chcp 65001 >nul
set "JAVA_TOOL_OPTIONS=-Dfile.encoding=UTF-8"

REM ���� �е��ű�����Ŀ¼ ���� 
cd /d "%~dp0"

REM ���� ���Ŀ¼ ���� 
if not exist "bin" mkdir "bin"

echo [����] javac -encoding UTF-8 -d bin PetRecorder.java DesktopPet.java PetControlPanel.java
javac -encoding UTF-8 -d bin PetRecorder.java DesktopPet.java PetControlPanel.java
if errorlevel 1 (
  echo.
  echo *** ����ʧ�ܣ���������Ĵ�����Ϣ��***
  pause
  exit /b 1
)

echo [����] java -cp bin PetControlPanel
java -cp bin PetControlPanel

echo.
echo �������˳�����������رմ���...
pause >nul
endlocal
