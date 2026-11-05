#!/bin/sh
# 环境层自检：确认 gradlew 语法、wrapper jar 魔数、gradlew shebang 都正常。
sh -n /d/coding/apk_project/app-progress/gradlew && echo SYNTAX_OK
echo "--- jar head (expect P K) ---"
head -c 4 /d/coding/apk_project/app-progress/gradle/wrapper/gradle-wrapper.jar | od -An -c
echo "--- gradlew first 3 chars (expect # ! /) ---"
head -c 3 /d/coding/apk_project/app-progress/gradlew | od -An -c
