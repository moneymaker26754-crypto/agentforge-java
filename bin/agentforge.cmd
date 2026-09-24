@echo off
setlocal
java --enable-native-access=ALL-UNNAMED "-Dagentforge.state.dir=%~dp0..\.agentforge" -jar "%~dp0..\agentforge-cli\target\agentforge-cli-0.1.0-SNAPSHOT.jar" %*
exit /b %ERRORLEVEL%
