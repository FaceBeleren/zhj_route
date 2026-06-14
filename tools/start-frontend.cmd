@echo off
cd /d "%~dp0.."
"D:\worklog\nodejs\node.exe" ".\frontend\node_modules\vite\bin\vite.js" ".\frontend" --host 0.0.0.0 --port 5175 --strictPort
