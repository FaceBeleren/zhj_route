@echo off
cd /d "%~dp0..\backend"
set HTTP_PROXY=
set HTTPS_PROXY=
set ALL_PROXY=
set http_proxy=
set https_proxy=
set all_proxy=
java -jar target\zhj-route-backend-0.0.1-SNAPSHOT.jar
